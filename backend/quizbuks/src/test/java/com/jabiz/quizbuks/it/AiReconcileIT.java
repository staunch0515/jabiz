package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.content.ai.AiCreditReturns;
import com.jabiz.quizbuks.content.ai.AiFeeSettlements;
import com.jabiz.quizbuks.content.ai.QbAi;
import com.jabiz.runtime.job.JobRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M9a-2b (docs/quizbuks/plans/impl/F-M9.md §8): the daily reconciliation of the AI credit and the AI fee. A sponsor's
 * whole round (bought with granted credit and cash, a request run to its end, the month's charge, a return, the grant
 * expired and voided) leaves no AI difference, nor one of the fee credit or the prepayments; each check then finds
 * what it is for: AI_CREDIT, AI_FEE_MONTH (#699 review L3: the charge against its jobs as charged), AI_FEE_UNBILLED,
 * AI_FEE_RESERVED and AI_CREDIT_EXPIRED_HELD (not before its hours).
 */
class AiReconcileIT extends AiCreditItSupport {

    private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

    @Autowired
    @Qualifier("qbReconcileJob")
    JobDefinition<?> reconcileJob;

    @Autowired
    JobDefinition<AiFeeSettlements.SettleRef> qbAiFeeMonthlyJob;

    @Autowired
    JobDefinition<AiCreditReturns.ExpireRef> qbAiCreditExpireJob;

    /** The items of a reconciliation run now, of the sponsor's. */
    private List<String> reconcile(String sponsor) {
        // One run a scheduled time (the same time again is a replay).
        advance(Duration.ofSeconds(1));
        Instant at = clock.instant();
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        assertThat(jobRunner.run(reconcileJob, at)).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        Object runId = query("SELECT run_id FROM qb_recon_run_version WHERE scheduled_time = ?", Timestamp.from(at))
            .getFirst().get("run_id");
        return query("SELECT check_code, reference, expected, actual FROM qb_recon_item_version WHERE run_id = ?::uuid "
            + "AND party_id = ? ORDER BY check_code, reference", String.valueOf(runId), sponsor).stream()
            .map(row -> row.get("check_code") + " " + row.get("reference") + " " + money(row.get("expected"))
                .toPlainString() + "/" + money(row.get("actual")).toPlainString()).toList();
    }

    private JobRunner.Outcome run(JobDefinition<?> job, Instant at) {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        return jobRunner.run(job, at);
    }

    /** A finished charged job of the sponsor's, by a request run to its end. */
    private Map<String, Object> finishedJob(String token) {
        List<String> quiz = quizWithArticle(token, title(), "Material for the reconciliation.");
        Object aiJobId = request(token, quiz.get(0), List.of(quiz.get(1)), 2).get("aiJobId");
        runUntilDone(aiJobId);
        Map<String, Object> done = job(aiJobId);
        assertThat(done).containsEntry("status", QbAi.READY);
        return done;
    }

    @Test
    void aWholeRoundLeavesNoDifferenceAndEachCheckFindsWhatItIsFor() {
        feeOn();
        String sponsor = newSponsor("ai-recon-" + UUID.randomUUID());
        String grantId = grant(sponsor, 1_000);
        cash(sponsor, 5_000);
        buy(sponsor, 4_000);
        String token = sponsorFull(sponsor);
        Map<String, Object> charged = finishedJob(token);
        Map<String, Object> waiting = finishedJob(token);

        // The month's charge takes both; a return; the grant expires and the daily job voids what is left of it.
        YearMonth next = YearMonth.from(clock.instant().atZone(ZONE)).plusMonths(1);
        Instant monthly = next.atDay(1).atTime(0, 27).atZone(ZONE).toInstant();
        clock.set(monthly.plusSeconds(60));
        assertThat(run(qbAiFeeMonthlyJob, monthly)).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(job(charged.get("ai_job_id")).get("fee_charge_id")).isNotNull();
        token = sponsorFull(sponsor);
        run(AiCreditReturns.RETURN, token, body("amount", 500, "requestKey", UUID.randomUUID().toString()));
        execute("UPDATE qb_fee_credit_grant SET expires_at = ? WHERE grant_id = ?",
            Timestamp.from(clock.instant().plusSeconds(60)), grantId);
        advance(Duration.ofMinutes(5));
        assertThat(run(qbAiCreditExpireJob, clock.instant())).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        advance(Duration.ofDays(3));
        assertThat(reconcile(sponsor)).as("a whole round").isEmpty();

        // AI_FEE_MONTH (#699 review L3): a job of the charge changed after it.
        String chargeId = String.valueOf(job(charged.get("ai_job_id")).get("fee_charge_id"));
        execute("UPDATE qb_ai_job SET fee_yen = fee_yen + 1 WHERE ai_job_id = ?",
            String.valueOf(charged.get("ai_job_id")));
        assertThat(reconcile(sponsor)).singleElement().asString().startsWith("AI_FEE_MONTH " + chargeId + " ");
        execute("UPDATE qb_ai_job SET fee_yen = fee_yen - 1 WHERE ai_job_id = ?",
            String.valueOf(charged.get("ai_job_id")));

        // AI_FEE_RESERVED: a finished job still holding a reservation; AI_FEE_UNBILLED: one of a month long gone, out
        // of its charge (which AI_FEE_MONTH finds short by its fee).
        String other = String.valueOf(waiting.get("ai_job_id"));
        Map<String, Object> before = job(other);
        java.math.BigDecimal amount = money(query("SELECT amount FROM qb_fee_charge_version WHERE charge_id = ?::uuid",
            chargeId).getFirst().get("amount"));
        java.math.BigDecimal fee = money(before.get("fee_yen"));
        execute("UPDATE qb_ai_job SET fee_charge_id = NULL, reserved_yen = 7, fee_period = '2000-01' WHERE "
            + "ai_job_id = ?", other);
        assertThat(reconcile(sponsor)).containsExactly(
            "AI_FEE_MONTH " + chargeId + " " + amount.toPlainString() + "/" + amount.subtract(fee).toPlainString(),
            "AI_FEE_RESERVED reserved 0/7", "AI_FEE_UNBILLED 2000-01 0/" + fee.toPlainString());
        execute("UPDATE qb_ai_job SET fee_charge_id = ?, reserved_yen = 0, fee_period = ? WHERE ai_job_id = ?",
            before.get("fee_charge_id"), before.get("fee_period"), other);
        assertThat(reconcile(sponsor)).isEmpty();

        // AI_CREDIT: the credit's remaining changed by hand (ledger and movements both disagree).
        execute("UPDATE qb_prepayment SET remaining = remaining + 3 WHERE sponsor_id = ? AND purpose = 'AI'", sponsor);
        assertThat(reconcile(sponsor)).filteredOn(item -> item.startsWith("AI_")).containsExactly(
            "AI_CREDIT ledger " + money(aiCredit(sponsor).get("remaining")).toPlainString() + "/"
                + money(aiCredit(sponsor).get("remaining")).subtract(java.math.BigDecimal.valueOf(3)).toPlainString(),
            "AI_CREDIT movements " + money(aiCredit(sponsor).get("remaining")).toPlainString() + "/"
                + money(aiCredit(sponsor).get("remaining")).subtract(java.math.BigDecimal.valueOf(3)).toPlainString());
        execute("UPDATE qb_prepayment SET remaining = remaining - 3 WHERE sponsor_id = ? AND purpose = 'AI'", sponsor);
    }

    /**
     * #702 review M4: AI_FEE_MONTH looks at the charges of the last 40 days, and at those the latest run reported: a
     * difference found stays (day 41 too) until it is resolved; once resolved, the charge is out of the window.
     */
    @Test
    void aChargesDifferenceIsStillReportedAfterItsFortyDays() {
        feeOn();
        String sponsor = newSponsor("ai-recon-old-" + UUID.randomUUID());
        cash(sponsor, 5_000);
        buy(sponsor, 4_000);
        Map<String, Object> charged = finishedJob(sponsorFull(sponsor));
        YearMonth next = YearMonth.from(clock.instant().atZone(ZONE)).plusMonths(1);
        Instant monthly = next.atDay(1).atTime(0, 27).atZone(ZONE).toInstant();
        clock.set(monthly.plusSeconds(60));
        assertThat(run(qbAiFeeMonthlyJob, monthly)).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        String jobId = String.valueOf(charged.get("ai_job_id"));
        String chargeId = String.valueOf(job(jobId).get("fee_charge_id"));
        execute("UPDATE qb_ai_job SET fee_yen = fee_yen + 1 WHERE ai_job_id = ?", jobId);
        assertThat(monthItems(sponsor)).as("day 1").singleElement().asString().startsWith("AI_FEE_MONTH " + chargeId);

        advance(Duration.ofDays(41));
        assertThat(monthItems(sponsor)).as("day 41, carried from the run before").singleElement().asString()
            .startsWith("AI_FEE_MONTH " + chargeId);

        execute("UPDATE qb_ai_job SET fee_yen = fee_yen - 1 WHERE ai_job_id = ?", jobId);
        assertThat(monthItems(sponsor)).as("resolved").isEmpty();
        // Out of the window and not reported by the run before: a change now is not seen (the known limit, §7.55).
        execute("UPDATE qb_ai_job SET fee_yen = fee_yen + 1 WHERE ai_job_id = ?", jobId);
        assertThat(monthItems(sponsor)).as("old and not reported before").isEmpty();
        execute("UPDATE qb_ai_job SET fee_yen = fee_yen - 1 WHERE ai_job_id = ?", jobId);
    }

    /**
     * #702 review L2: a finished charged job with a fee and no month is reported at once (no charge would take it in).
     */
    @Test
    void aJobWithAFeeAndNoMonthIsReported() {
        feeOn();
        String sponsor = newSponsor("ai-recon-period-" + UUID.randomUUID());
        cash(sponsor, 5_000);
        buy(sponsor, 4_000);
        Map<String, Object> done = finishedJob(sponsorFull(sponsor));
        String jobId = String.valueOf(done.get("ai_job_id"));
        BigDecimal fee = money(done.get("fee_yen"));
        assertThat(fee).isPositive();
        execute("UPDATE qb_ai_job SET fee_period = NULL WHERE ai_job_id = ?", jobId);
        assertThat(reconcile(sponsor)).filteredOn(item -> item.startsWith("AI_FEE_UNBILLED"))
            .containsExactly("AI_FEE_UNBILLED none 0/" + fee.toPlainString());
        execute("UPDATE qb_ai_job SET fee_period = ? WHERE ai_job_id = ?", done.get("fee_period"), jobId);
        assertThat(reconcile(sponsor)).filteredOn(item -> item.startsWith("AI_FEE_UNBILLED")).isEmpty();
    }

    private List<String> monthItems(String sponsor) {
        return reconcile(sponsor).stream().filter(item -> item.startsWith("AI_FEE_MONTH")).toList();
    }

    /**
     * AI_CREDIT_EXPIRED_HELD: expired fee credit the daily expiry did not void is found once
     * qb.ai.credit.expired-held-after-hours (48) have passed, not before; once voided, not again.
     */
    @Test
    void expiredFeeCreditLeftInTheCreditIsFoundAfterItsHours() {
        feeOn();
        String sponsor = newSponsor("ai-recon-expired-" + UUID.randomUUID());
        String grantId = grant(sponsor, 800);
        buy(sponsor, 800);
        execute("UPDATE qb_fee_credit_grant SET expires_at = ? WHERE grant_id = ?",
            Timestamp.from(clock.instant().plusSeconds(60)), grantId);
        advance(Duration.ofHours(47));
        assertThat(reconcile(sponsor)).as("within the hours").isEmpty();
        advance(Duration.ofHours(2));
        assertThat(reconcile(sponsor)).containsExactly("AI_CREDIT_EXPIRED_HELD expired 0/800");
        assertThat(run(qbAiCreditExpireJob, clock.instant())).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(reconcile(sponsor)).isEmpty();
    }
}
