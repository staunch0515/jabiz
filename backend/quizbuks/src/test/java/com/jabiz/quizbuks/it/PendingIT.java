package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.play.PlayCodes;
import com.jabiz.quizbuks.play.PlayLimits;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rewards a sponsor cannot pay (5.3-8, U-34; docs/quizbuks/plans/Q5-answering.md, D-Q5-5): pending and not posted,
 * the publication underfunded; paid in the order of submission once there is money, never skipping one; the rest in
 * another transaction; the publication back on once nothing is pending.
 */
class PendingIT extends PlayItSupport {

    /** Starts an attempt for each taker (made without a profile: GLOBAL publications); returns their attempts. */
    List<String> startAll(String publicationId, String prefix, int count) {
        List<String> attempts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            attempts.add((String) start(taker(newTaker(prefix + i, null)), publicationId).get("attemptId"));
        }
        return attempts;
    }

    String takerOf(String attemptId) {
        return taker((String) attempt(attemptId).get("user_id"));
    }

    /** Submits a second after the last submission, so that the order of submission is the order of time. */
    Map<String, Object> finish(String attemptId, boolean first, boolean second) {
        clock.advance(Duration.ofSeconds(1));
        return submit(takerOf(attemptId), attemptId, answers(first, second));
    }

    static Map<String, Object> global() {
        return Map.of("regions", List.of("GLOBAL"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void pendingRewardsArePaidInOrderOnceThereIsMoney() {
        String sponsor = newSponsor("s-pending");
        String publicationId = livePublication(sponsor, "Short of funds", global());
        credit(sponsor, 100);
        List<String> attempts = startAll(publicationId, "t-pending-", 5);

        // The first is paid; the second cannot be: pending, not posted, the publication underfunded.
        assertThat(finish(attempts.get(0), true, true)).containsEntry("rewardStatus", "PAID");
        Map<String, Object> pending = finish(attempts.get(1), true, true);
        assertThat(pending).containsEntry("rewardStatus", "PENDING");
        assertThat((String) pending.get("message")).contains("top");
        assertThat(postingOf(attempts.get(1))).isEmpty();
        assertThat(status(publicationId)).isEqualTo("FUNDS_SHORT");
        assertThat(card(publicationId)).containsEntry("status", "FUNDS_SHORT");
        assertThat(wallet(sponsor)).containsEntry("pending_count", new BigDecimal("1"))
            .containsEntry("pending_amount", new BigDecimal("100"));
        assertThat(feed(taker(u1()), "JP", Map.of())).noneMatch(item -> publicationId.equals(item.get("publicationId")));
        assertThat(codes(refused("QB_ATTEMPT_START", taker(newTaker("t-pending-late", null)),
            body("publicationId", publicationId), 422))).containsExactly(PlayCodes.NOT_OPEN);

        // 50 is not enough for the first pending one (100): nothing is paid, and the next (10), though the balance
        // covers it, waits behind it.
        credit(sponsor, 50);
        assertThat(attempt(attempts.get(1))).containsEntry("reward_status", "PENDING");
        assertThat(finish(attempts.get(2), false, true)).containsEntry("rewardStatus", "PENDING");
        assertThat(finish(attempts.get(3), true, true)).containsEntry("rewardStatus", "PENDING");
        assertThat(finish(attempts.get(4), false, true)).containsEntry("rewardStatus", "PENDING");
        // Queue: 100, 10, 100, 10. 50 + 70 = 120 pays 100 and 10, then stops at 100 although 10 could be paid.
        credit(sponsor, 70);
        assertThat(List.of(1, 2, 3, 4).stream().map(i -> attempt(attempts.get(i)).get("reward_status")))
            .containsExactly("PAID", "PAID", "PENDING", "PENDING");
        assertThat(balance(sponsor)).isEqualByComparingTo("10");
        assertThat(status(publicationId)).isEqualTo("FUNDS_SHORT");
        deliverEvents();
        assertThat(attempt(attempts.get(4))).containsEntry("reward_status", "PENDING");

        // Enough for both: all paid, the publication back on.
        credit(sponsor, 100);
        assertThat(List.of(3, 4).stream().map(i -> attempt(attempts.get(i)).get("reward_status")))
            .containsExactly("PAID", "PAID");
        assertThat(wallet(sponsor)).containsEntry("pending_count", BigDecimal.ZERO)
            .containsEntry("pending_amount", BigDecimal.ZERO);
        deliverEvents();
        assertThat(status(publicationId)).isEqualTo("LIVE");
        assertThat(card(publicationId)).containsEntry("status", "LIVE");
        for (String attemptId : attempts) {
            assertThat(postingOf(attemptId).stream().map(row -> row.get("transaction_id")).distinct())
                .as(attemptId).hasSize(1);
        }
        assertBooksBalance();
    }

    @Test
    void moreThanABatchIsPaidByTheNextRunExactlyOnce() {
        String sponsor = newSponsor("s-batch");
        String publicationId = livePublication(sponsor, "Many pending", Map.of("regions", List.of("GLOBAL"),
            "budgetCap", 100_000));
        credit(sponsor, 100);
        int pendingCount = PlayLimits.SETTLE_BATCH + 1;
        List<String> attempts = startAll(publicationId, "t-batch-", pendingCount + 1);
        for (String attemptId : attempts) {
            finish(attemptId, true, true);
        }
        assertThat(wallet(sponsor)).containsEntry("pending_count", BigDecimal.valueOf(pendingCount));

        // One credit for all: the first batch in its own transaction, the last one by the event.
        credit(sponsor, 100L * pendingCount);
        assertThat(query("SELECT count(*) AS n FROM qb_attempt WHERE sponsor_id = ? AND reward_status = 'PENDING'",
            sponsor).getFirst()).containsEntry("n", 1L);
        deliverEvents();
        assertThat(query("SELECT count(*) AS n FROM qb_attempt WHERE sponsor_id = ? AND reward_status = 'PAID'",
            sponsor).getFirst()).containsEntry("n", (long) pendingCount + 1);
        assertThat(query("SELECT count(*) AS n, count(DISTINCT t.source_id) AS attempts "
            + "FROM ledger_transaction_version t JOIN qb_attempt a ON a.attempt_id = t.source_id "
            + "WHERE a.sponsor_id = ?", sponsor).getFirst()).containsEntry("n", (long) pendingCount + 1)
            .containsEntry("attempts", (long) pendingCount + 1);
        assertThat(balance(sponsor)).isZero();
        assertThat(status(publicationId)).isEqualTo("LIVE");
        // Delivered again, nothing changes.
        deliverEvents();
        assertThat(balance(sponsor)).isZero();
        assertBooksBalance();
    }

    @Test
    void anUnderfundedPublicationPastItsEndEndsAndAnEndedOneStaysEnded() {
        String sponsor = newSponsor("s-ended");
        String shortOne = livePublication(sponsor, "Short, then over", global());
        String endedOne = livePublication(sponsor, "Ended with pending", Map.of("regions", List.of("GLOBAL"),
            "endAt", clock.instant().plus(Duration.ofDays(2)).toString()));
        credit(sponsor, 100);
        List<String> first = startAll(shortOne, "t-ended-a", 2);
        List<String> second = startAll(endedOne, "t-ended-b", 1);
        finish(first.get(0), true, true);
        finish(first.get(1), true, true);
        finish(second.get(0), true, true);
        assertThat(status(shortOne)).isEqualTo("FUNDS_SHORT");
        assertThat(status(endedOne)).isEqualTo("FUNDS_SHORT");
        // The second ends by the minute's job; the first's end passes without the job.
        clock.advance(Duration.ofDays(3));
        runTick();
        assertThat(status(endedOne)).isEqualTo("ENDED");
        assertThat(status(shortOne)).isEqualTo("FUNDS_SHORT");
        clock.advance(Duration.ofDays(5));

        credit(sponsor, 200);
        deliverEvents();
        assertThat(status(shortOne)).isEqualTo("ENDED");
        assertThat(row(shortOne)).containsEntry("end_reason", "EXPIRED");
        assertThat(status(endedOne)).isEqualTo("ENDED");
        assertThat(attempt(second.get(0))).containsEntry("reward_status", "PAID");
        assertBooksBalance();
    }

    @Test
    void anUnderfundedPublicationCanBePausedAndResumed() {
        String sponsor = newSponsor("s-pause");
        String publicationId = livePublication(sponsor, "Paused while short", global());
        credit(sponsor, 100);
        List<String> attempts = startAll(publicationId, "t-pause-", 2);
        finish(attempts.get(0), true, true);
        finish(attempts.get(1), true, true);
        assertThat(status(publicationId)).isEqualTo("FUNDS_SHORT");
        assertThat(run("QB_PUBLICATION_PAUSE", sponsorToken(sponsor), body("publicationId", publicationId)))
            .containsEntry("status", "PAUSED");
        assertThat(card(publicationId)).containsEntry("status", "PAUSED");
        // Resumed by its time; a reward it cannot pay makes it short again.
        assertThat(run("QB_PUBLICATION_RESUME", sponsorToken(sponsor), body("publicationId", publicationId)))
            .containsEntry("status", "LIVE");
        credit(sponsor, 100);
        deliverEvents();
        assertThat(status(publicationId)).isEqualTo("LIVE");
        assertThat(attempt(attempts.get(1))).containsEntry("reward_status", "PAID");
        assertBooksBalance();
    }
}
