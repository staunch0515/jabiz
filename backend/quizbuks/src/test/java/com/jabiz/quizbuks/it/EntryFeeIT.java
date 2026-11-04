package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.play.PlayCodes;
import com.jabiz.quizbuks.publishing.PublishingCodes;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.job.JobRunner;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A quiz's entry fee (Q88b, docs/quizbuks/plans/Q88-sponsor-certifications-entry-fee.md, D-Q88-9 to D-Q88-16): set
 * on a quiz while the switch is on, paid at the start from the taker's transferable Kudos (2120 to 2170 for the
 * sponsor, the wallet's cache reduced in step), settled daily into the sponsor's cash account (2170 to 2160), never
 * refunded; a survey cannot charge one, a live quiz keeps charging when the switch goes off.
 */
class EntryFeeIT extends PlayItSupport {

    @Autowired
    @Qualifier("qbEntryFeeSettleJob")
    JobDefinition<?> settleJob;

    @Autowired
    @Qualifier("qbReconcileJob")
    JobDefinition<?> reconcileJob;

    void param(String key, String value) {
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", key, "value", value), "reason", "Q88b"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    JobRunner.JobRun settle() {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        return jobRunner.execute(settleJob, clock.instant());
    }

    /** A taker's wallet row as the start and the syncs keep it; null without one. */
    Map<String, Object> takerWallet(String userId) {
        List<Map<String, Object>> rows = query("SELECT * FROM qb_wallet WHERE owner_id = ? AND kind = 'TAKER'",
            userId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    void advance(Duration duration) {
        clock.set(clock.instant().plus(duration));
    }

    /** A template's money column as a number (the ledger's amounts come back with decimals). */
    static BigDecimal amount(Map<String, Object> row, String column) {
        return new BigDecimal(String.valueOf(row.get(column)));
    }

    BigDecimal cash(String sponsorId) {
        List<Map<String, Object>> rows = query("SELECT balance FROM qb_wallet WHERE owner_id = ? AND kind = 'CASH'",
            sponsorId);
        return rows.isEmpty() ? BigDecimal.ZERO : (BigDecimal) rows.getFirst().get("balance");
    }

    @Test
    void aQuizChargesItsFeeFromTransferableKudosAndTheJobSettlesItIntoTheSponsorsCash() {
        param(QbParams.TRANSFER_HOLD_DAYS, "7");
        String sponsor = newSponsor("s-fee-" + UUID.randomUUID());
        credit(sponsor, 10_000);
        String token = sponsorToken(sponsor);
        // Off by default: a fee is refused when the draft is saved.
        String versionId = version(token, "Fee quiz");
        java.time.Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, "Fee quiz", now, now.plus(Duration.ofDays(30)));
        save.putAll(Map.of("maxWinners", 1000, "regions", List.of("GLOBAL"), "entryFee", 30));
        assertThat(codes(refused("QB_PUBLICATION_SAVE", token, save, 422)))
            .containsExactly(PublishingCodes.ENTRY_FEE_OFF);
        assertThat(items("qb.sponsor.reward-methods", token, Map.of()).getFirst())
            .containsEntry("entryFeeEnabled", false);

        param(QbParams.ENTRY_FEE_ENABLED, "true");
        assertThat(items("qb.sponsor.reward-methods", token, Map.of()).getFirst())
            .containsEntry("entryFeeEnabled", true);
        // Beyond the highest fee: the field's range.
        Map<String, Object> tooMuch = new java.util.LinkedHashMap<>(save);
        tooMuch.put("entryFee", 10_001);
        assertThat(codes(refused("QB_PUBLICATION_SAVE", token, tooMuch, 400)))
            .contains(PublishingCodes.ENTRY_FEE_RANGE);
        String publicationId = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        assertThat(run("QB_PUBLICATION_SUBMIT", token, body("publicationId", publicationId)).get("status"))
            .isEqualTo("LIVE");
        assertThat(card(publicationId).get("entry_fee")).isEqualTo(new BigDecimal("30"));
        assertThat(items("qb.app.feed", taker(newTaker("t-fee-look-" + UUID.randomUUID(), null)),
            Map.of("params", Map.of("at", now.toString()))).stream()
            .filter(row -> publicationId.equals(String.valueOf(row.get("publicationId")))).findFirst().orElseThrow())
            .satisfies(row -> assertThat(amount(row, "entryFee")).isEqualByComparingTo("30"));

        // A taker with nothing: refused before the start lock, with what is short.
        String poor = newTaker("t-fee-poor-" + UUID.randomUUID(), null);
        Map<String, Object> refusal = refused("QB_ATTEMPT_START", taker(poor), body("publicationId", publicationId),
            422);
        assertThat(codes(refusal)).containsExactly(PlayCodes.ENTRY_FEE_SHORT);
        assertThat(String.valueOf(violations(refusal).getFirst().get("message"))).contains("30");
        assertThat(((Number) counter(publicationId).get("starts")).longValue()).isZero();

        // A reward just paid is held back from transfers, so it cannot pay the fee yet (D-Q88-10).
        String earner = newTaker("t-fee-earner-" + UUID.randomUUID(), null);
        String rewarding = livePublication(sponsor, "Fee rewarding", Map.of("regions", List.of("GLOBAL"), "endAt",
            now.plus(Duration.ofDays(60)).toString()));
        assertThat(play(earner, rewarding, true, true)).containsEntry("rewardStatus", "PAID");
        deliverEvents();
        assertThat(ledger("2120", earner)).isEqualByComparingTo("100");
        assertThat(codes(refused("QB_ATTEMPT_START", taker(earner), body("publicationId", publicationId), 422)))
            .containsExactly(PlayCodes.ENTRY_FEE_SHORT);

        // After the hold period the reward is free, but a transfer in review sets 80 of it aside (nothing posted
        // until it is approved, which sends only what is still transferable): 20 left, the fee is refused.
        advance(Duration.ofDays(8));
        String review = UUID.randomUUID().toString();
        execute("INSERT INTO qb_transfer (transfer_id, user_id, external_account_id, amount, fee, net, status, "
            + "created_at, version) VALUES (?, ?, 'acct_test', 80, 0, 80, 'REVIEW', ?, 1)", review, earner,
            java.sql.Timestamp.from(clock.instant()));
        assertThat(String.valueOf(violations(refused("QB_ATTEMPT_START", taker(earner),
            body("publicationId", publicationId), 422)).getFirst().get("message"))).contains("20 can be spent");
        execute("DELETE FROM qb_transfer WHERE transfer_id = ?", review);

        // Without it, it pays: 2120 to 2170, the cache in step, the attempt records it.
        Map<String, Object> started = run("QB_ATTEMPT_START", taker(earner), body("publicationId", publicationId));
        String attemptId = (String) started.get("attemptId");
        assertThat(attempt(attemptId).get("entry_fee")).isEqualTo(new BigDecimal("30"));
        assertThat(ledger("2120", earner)).isEqualByComparingTo("70");
        assertThat(ledger("2170", sponsor)).isEqualByComparingTo("30");
        assertThat(takerWallet(earner).get("balance")).isEqualTo(new BigDecimal("70"));
        assertThat(postingOf(attemptId)).extracting(row -> row.get("account_code") + " " + row.get("direction") + " "
            + ((BigDecimal) row.get("amount")).stripTrailingZeros().toPlainString())
            .containsExactly("2120 DEBIT 30", "2170 CREDIT 30");
        // Continuing the attempt pays nothing more.
        assertThat(run("QB_ATTEMPT_START", taker(earner), body("publicationId", publicationId)))
            .containsEntry("resumed", true);
        assertThat(ledger("2120", earner)).isEqualByComparingTo("70");
        // The taker's ledger page lists the fee.
        assertThat(items("qb.app.ledger", taker(earner), Map.of())).anySatisfy(row -> {
            assertThat(row).containsEntry("type", "ENTRY_FEE").containsEntry("status", "COMPLETED");
            assertThat(amount(row, "amount")).isEqualByComparingTo("30");
        });
        // By method: the fee is used, like a transfer, not earned less (earned less used is the balance).
        assertThat(items("qb.app.kudos", taker(earner), Map.of()).stream()
            .filter(row -> "PLATFORM".equals(row.get("method"))).findFirst().orElseThrow()).satisfies(row -> {
                assertThat(amount(row, "earned")).isEqualByComparingTo("100");
                assertThat(amount(row, "settled")).isEqualByComparingTo("30");
                assertThat(amount(row, "balance")).isEqualByComparingTo("70");
            });
        // The sponsor sees it waiting to be settled, and on the publication.
        assertThat(amount(items("qb.sponsor.wallet", sponsorToken(sponsor), Map.of()).getFirst(), "entryFeesUnsettled"))
            .isEqualByComparingTo("30");
        assertThat(items("qb.sponsor.publications", sponsorToken(sponsor), Map.of()).stream()
            .filter(row -> publicationId.equals(String.valueOf(row.get("publicationId")))).findFirst().orElseThrow())
            .satisfies(row -> assertThat(amount(row, "entryFees")).isEqualByComparingTo("30"));

        // The daily job settles it into the sponsor's cash account, once.
        assertThat(settle().items().failed()).isZero();
        assertThat(ledger("2170", sponsor)).isEqualByComparingTo("0");
        assertThat(ledger("2160", sponsor)).isEqualByComparingTo("30");
        assertThat(cash(sponsor)).isEqualByComparingTo("30");
        settle();
        assertThat(cash(sponsor)).isEqualByComparingTo("30");
        assertThat(amount(items("qb.sponsor.wallet", sponsorToken(sponsor), Map.of()).getFirst(), "entryFeesUnsettled"))
            .isEqualByComparingTo("0");

        // The switch off: a fee cannot be set, but the live quiz keeps charging (D-Q88-14).
        param(QbParams.ENTRY_FEE_ENABLED, "false");
        String another = newTaker("t-fee-another-" + UUID.randomUUID(), null);
        assertThat(play(another, rewarding, true, true)).containsEntry("rewardStatus", "PAID");
        deliverEvents();
        advance(Duration.ofDays(8));
        run("QB_ATTEMPT_START", taker(another), body("publicationId", publicationId));
        assertThat(ledger("2170", sponsor)).isEqualByComparingTo("30");

        // The daily reconciliation finds nothing wrong, and the books balance.
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        java.time.Instant at = clock.instant();
        assertThat(jobRunner.run(reconcileJob, at)).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(query("SELECT r.status FROM qb_recon_run_version r WHERE r.scheduled_time = ?",
            java.sql.Timestamp.from(at)).getFirst()).containsEntry("status", "OK");
        // The monthly report classes both postings (D-Q88-15): two fees paid, one day's settled.
        java.time.Instant month = java.time.Instant.parse("2000-01-01T00:00:00Z");
        List<Map<String, Object>> monthly = items("qb.admin.monthly", admin(), Map.of("params", Map.of("from",
            month.toString(), "to", clock.instant().plus(Duration.ofDays(1)).toString())));
        assertThat(monthly).filteredOn(row -> "ENTRY_FEE".equals(row.get("category"))).singleElement()
            .satisfies(row -> assertThat(((Number) row.get("transactionCount")).longValue()).isGreaterThanOrEqualTo(2));
        assertThat(monthly).filteredOn(row -> "ENTRY_FEE_SETTLED".equals(row.get("category"))).singleElement()
            .satisfies(row -> assertThat(((Number) row.get("transactionCount")).longValue()).isGreaterThanOrEqualTo(1));
        assertBooksBalance();
    }

    @Test
    void aSurveyChargesNoEntryFee() {
        param(QbParams.ENTRY_FEE_ENABLED, "true");
        String sponsor = newSponsor("s-fee-survey-" + UUID.randomUUID());
        String token = sponsorToken(sponsor);
        String quizId = (String) run("QB_QUIZ_SAVE", token, body("title", "Fee survey", "kind", "SURVEY",
            "timeLimitSec", 300)).get("quizId");
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "How often?", "selection", "SINGLE",
            "options", List.of(body("text", "Daily"), body("text", "Weekly"))));
        String versionId = (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
        java.time.Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, "Fee survey", now, now.plus(Duration.ofDays(7)));
        save.putAll(Map.of("passScore", 1, "regions", List.of("GLOBAL"), "entryFee", 10, "tiers",
            List.of(body("fromScore", 0, "toScore", 1, "reward", 5))));
        Map<String, Object> saved = run("QB_PUBLICATION_SAVE", token, save);
        assertThat(String.valueOf(saved.get("problems"))).contains(PublishingCodes.ENTRY_FEE_NOT_ALLOWED);
        assertThat(codes(refused("QB_PUBLICATION_SUBMIT", token, body("publicationId", saved.get("publicationId")),
            422))).contains(PublishingCodes.ENTRY_FEE_NOT_ALLOWED);
    }
}
