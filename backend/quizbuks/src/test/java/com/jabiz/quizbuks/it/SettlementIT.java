package com.jabiz.quizbuks.it;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Settling a reward in the ledger (5.3-7, 5.3-9, 5.3-13; docs/quizbuks/plans/Q5-answering.md, D-Q5-4): one balanced
 * transaction per paid attempt, in the submission's transaction, with the attempt as its source, the parties and the
 * publication; the commission as the controlled rate says at the time; nothing for a reward of 0.
 */
class SettlementIT extends PlayItSupport {

    static Instant instant(Object value) {
        return value instanceof OffsetDateTime time ? time.toInstant() : ((java.sql.Timestamp) value).toInstant();
    }

    @Test
    void aRewardMovesFromTheSponsorsWalletToTheTakersInOneTransaction() {
        String sponsor = newSponsor("s-settle");
        String publicationId = livePublication(sponsor, "Settled");
        credit(sponsor, 1_000);
        String taker = u1();
        Map<String, Object> result = play(taker, publicationId, true, true);
        String attemptId = (String) result.get("attemptId");
        assertThat(result).containsEntry("rewardStatus", "PAID");

        List<Map<String, Object>> lines = postingOf(attemptId);
        assertThat(lines).extracting(row -> row.get("account_code"), row -> row.get("direction"),
                row -> ((BigDecimal) row.get("amount")).intValue(), row -> row.get("dimension_1"),
                row -> row.get("dimension_2"))
            .containsExactly(tuple("2110", "DEBIT", 100, sponsor, publicationId),
                tuple("2120", "CREDIT", 100, taker, publicationId));
        Map<String, Object> first = lines.getFirst();
        assertThat(first).containsEntry("source_entity", "QbAttempt").containsEntry("source_id", attemptId)
            .containsEntry("reference", attemptId).containsEntry("description", "Quiz reward");
        Map<String, Object> attempt = attempt(attemptId);
        assertThat(instant(first.get("booking_time"))).isEqualTo(instant(attempt.get("submitted_at")));
        assertThat(attempt).containsEntry("ledger_transaction_id", String.valueOf(first.get("transaction_id")))
            .containsEntry("reward_status", "PAID");
        assertThat((BigDecimal) attempt.get("fee")).isZero();
        assertThat(balance(sponsor)).isEqualByComparingTo("900");
        assertThat(ledger("2120", taker)).isEqualByComparingTo(sumOfNetRewards(taker));
        assertBooksBalance();
    }

    @Test
    void aRewardOfZeroIsNotPosted() {
        String sponsor = newSponsor("s-zero");
        String publicationId = livePublication(sponsor, "Nothing won");
        credit(sponsor, 1_000);
        Map<String, Object> result = play(u1(), publicationId, false, false);
        assertThat(result).containsEntry("score", 0).containsEntry("passed", false)
            .containsEntry("rewardStatus", "NONE");
        assertThat(((Number) result.get("reward")).intValue()).isZero();
        assertThat(postingOf((String) result.get("attemptId"))).isEmpty();
        assertThat(balance(sponsor)).isEqualByComparingTo("1000");
        // A reward below the pass mark is still paid when its tier has one (5.3-7: failing may earn).
        Map<String, Object> failing = play(u2Global(), livePublication(sponsor, "Fail but earn", Map.of("regions",
            List.of("GLOBAL"), "passScore", 4)), false, true);
        assertThat(failing).containsEntry("score", 3).containsEntry("passed", false)
            .containsEntry("rewardStatus", "PAID");
        assertThat(((Number) failing.get("reward")).intValue()).isEqualTo(10);
        assertBooksBalance();
    }

    @Test
    void theCommissionIsTheRateInEffectRoundedDown() {
        String sponsor = newSponsor("s-fee");
        // Rewards of 15 (three or four points) and 100 (five).
        String publicationId = livePublication(sponsor, "With commission", Map.of("tiers", List.of(
            body("fromScore", 0, "toScore", 2, "reward", 0), body("fromScore", 3, "toScore", 4, "reward", 15),
            body("fromScore", 5, "toScore", 5, "reward", 100))));
        credit(sponsor, 1_000);
        feeRate("0.1");
        try {
            String partial = (String) play(u1(), publicationId, false, true).get("attemptId");
            List<Map<String, Object>> lines = postingOf(partial);
            assertThat(lines).extracting(row -> row.get("account_code"), row -> row.get("direction"),
                    row -> ((BigDecimal) row.get("amount")).intValue(), row -> row.get("dimension_1"),
                    row -> row.get("dimension_2"))
                .containsExactly(tuple("2110", "DEBIT", 15, sponsor, publicationId),
                    tuple("2120", "CREDIT", 14, u1(), publicationId),
                    tuple("4110", "CREDIT", 1, null, publicationId));
            assertThat((BigDecimal) attempt(partial).get("fee")).isEqualByComparingTo("1");
            String taker = newTaker("t-fee", "JP");
            String full = (String) play(taker, publicationId, true, true).get("attemptId");
            assertThat(postingOf(full)).extracting(row -> ((BigDecimal) row.get("amount")).intValue())
                .containsExactly(100, 90, 10);
            assertThat(ledger("4110", null)).isEqualByComparingTo("11");
        } finally {
            feeRate("0");
        }
        assertThat(balance(sponsor)).isEqualByComparingTo("885");
        assertBooksBalance();
    }

    @Test
    void theWalletsAgreeWithTheLedgerAndNothingIsRewritten() {
        String sponsor = newSponsor("s-books");
        String publicationId = livePublication(sponsor, "Books", Map.of("regions", List.of("GLOBAL")));
        credit(sponsor, 500);
        for (int i = 0; i < 4; i++) {
            play(newTaker("t-books-" + i, null), publicationId, i % 2 == 0, true);
        }
        // 100 + 10 + 100 + 10.
        assertThat(balance(sponsor)).isEqualByComparingTo("280");
        assertThat(ledger("2110", sponsor)).isEqualByComparingTo("280");
        assertThat(ledger("2120", null)).isEqualByComparingTo(sumOfNetRewards(null));
        assertBooksBalance();
        assertNothingUpdated();
    }

    String u2Global() {
        return newTaker("t-global", null);
    }

    /** What a taker (every taker: null) was paid, net of commission, by the attempts' own records. */
    static BigDecimal sumOfNetRewards(String userId) {
        return (BigDecimal) query("SELECT coalesce(sum(reward - coalesce(fee, 0)), 0) AS s FROM qb_attempt "
            + "WHERE reward_status = 'PAID' AND (CAST(? AS text) IS NULL OR user_id = ?)", userId, userId)
            .getFirst().get("s");
    }
}
