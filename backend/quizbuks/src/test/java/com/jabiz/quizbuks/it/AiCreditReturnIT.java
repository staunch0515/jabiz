package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.content.ai.AiCreditReturns;
import com.jabiz.quizbuks.content.ai.QbAi;
import com.jabiz.quizbuks.fee.FeeCodes;
import com.jabiz.quizbuks.fee.FeeCredits;
import com.jabiz.runtime.job.JobRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M9a-2 (docs/quizbuks/plans/impl/F-M9.md §4 items 4 and 5, H3): an AI credit given back by the sponsor (the cash
 * first, then the fee credit to its grant, at most what the open jobs do not hold, with the fee off too), and its
 * expired fee credit voided by the daily job but for what the open jobs hold; the credit's parts always add up to what
 * it holds, and nothing is voided twice whichever daily job runs first.
 */
class AiCreditReturnIT extends AiCreditItSupport {

    @Autowired
    JobDefinition<AiCreditReturns.ExpireRef> qbAiCreditExpireJob;

    @Autowired
    JobDefinition<FeeCredits.GrantRef> qbFeeCreditExpireJob;

    private Map<String, Object> giveBack(String sponsor, Long amount) {
        return run(AiCreditReturns.RETURN, sponsorToken(sponsor), amount == null
            ? body("requestKey", UUID.randomUUID().toString())
            : body("amount", amount, "requestKey", UUID.randomUUID().toString()));
    }

    /** What the credit holds by its parts (H3): its own parts, less what fees took of it. */
    private static BigDecimal heldByParts(String sponsor) {
        Object prepaymentId = aiCredit(sponsor).get("prepayment_id");
        return money(query("SELECT coalesce(sum(amount), 0) AS h FROM (SELECT amount FROM qb_fee_payment_version "
            + "WHERE target_id = ? UNION ALL SELECT -amount FROM qb_fee_payment_version WHERE prepayment_id = ? AND "
            + "amount > 0) p", String.valueOf(prepaymentId), String.valueOf(prepaymentId)).getFirst().get("h"));
    }

    private static Map<String, Object> grantRow(String grantId) {
        return query("SELECT * FROM qb_fee_credit_grant WHERE grant_id = ?", grantId).getFirst();
    }

    private static List<String> movements(String sponsor) {
        return query("SELECT kind, amount, credit, cash, balance FROM qb_ai_credit_movement_version WHERE "
            + "sponsor_id = ? ORDER BY moved_at, process_seq_id", sponsor).stream()
            .map(m -> m.get("kind") + " " + money(m.get("amount")).toPlainString() + " "
                + money(m.get("credit")).toPlainString() + "/" + money(m.get("cash")).toPlainString() + " "
                + money(m.get("balance")).toPlainString()).toList();
    }

    private JobRunner.Outcome runJob(JobDefinition<?> job) {
        // The job's lock outlives a run by real time (jabiz.jobs.lock-at-least-for): released for the next.
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        return jobRunner.run(job, clock.instant());
    }

    @Test
    void aReturnGivesTheCashBackFirstThenTheFeeCreditToItsGrant() {
        feeOn();
        String sponsor = newSponsor("ai-return-" + UUID.randomUUID());
        String grantId = grant(sponsor, 1_000);
        cash(sponsor, 2_000);
        buy(sponsor, 2_500);
        BigDecimal cashBefore = net("2160", sponsor);
        BigDecimal creditBefore = net("2190", sponsor);

        Map<String, Object> out = giveBack(sponsor, 1_600L);
        assertThat(money(out.get("amount"))).isEqualByComparingTo("1600");
        assertThat(money(out.get("cash"))).isEqualByComparingTo("1500");
        assertThat(money(out.get("credit"))).isEqualByComparingTo("100");
        assertThat(money(out.get("balance"))).isEqualByComparingTo("900");
        assertThat(money(aiCredit(sponsor).get("remaining"))).isEqualByComparingTo("900");
        assertThat(aiCredit(sponsor)).containsEntry("status", "OPEN");
        assertThat(money(grantRow(grantId).get("remaining"))).isEqualByComparingTo("100");
        // Debit 2180, credit 2160 and 2190: the account holds it again.
        assertThat(net("2160", sponsor).subtract(cashBefore)).isEqualByComparingTo("-1500");
        assertThat(net("2190", sponsor).subtract(creditBefore)).isEqualByComparingTo("-100");
        assertThat(heldByParts(sponsor)).isEqualByComparingTo("900");
        assertThat(movements(sponsor)).containsExactly("BUY 2500 1000/1500 2500", "RETURN -1600 -100/-1500 900");

        // All that is left, with the fee off (plan §4 item 6: one may give back while it is off).
        param(com.jabiz.quizbuks.setup.QbParams.AI_FEE_ENABLED, "false");
        assertThat(money(giveBack(sponsor, null).get("amount"))).isEqualByComparingTo("900");
        assertThat(money(aiCredit(sponsor).get("remaining"))).isZero();
        assertThat(money(grantRow(grantId).get("remaining"))).isEqualByComparingTo("1000");
        assertThat(heldByParts(sponsor)).isZero();
        // Nothing left: a return of all gives nothing; of an amount is refused.
        assertThat(money(giveBack(sponsor, null).get("amount"))).isZero();
        assertThat(codes(refused(AiCreditReturns.RETURN, sponsorToken(sponsor), body("amount", 1, "requestKey",
            "k-empty"), 422))).containsExactly(FeeCodes.AI_CREDIT_RETURN_ABOVE);
    }

    @Test
    void aReturnLeavesWhatTheOpenJobsHoldAndRefusesARepeatedKey() {
        feeOn();
        List<String> s = charged("ai-return-held-");
        String sponsor = s.get(0);
        Object aiJobId = request(s.get(1), s.get(2), List.of(s.get(3)), 2).get("aiJobId");
        Map<String, Object> queued = job(aiJobId);
        BigDecimal reserved = money(queued.get("reserved_yen"));
        assertThat(reserved).isPositive();

        // While it runs, its reservation stays.
        BigDecimal returnable = new BigDecimal("5000").subtract(reserved);
        assertThat(codes(refused(AiCreditReturns.RETURN, sponsorToken(sponsor), body("amount",
            returnable.add(BigDecimal.ONE), "requestKey", "k-above"), 422)))
            .containsExactly(FeeCodes.AI_CREDIT_RETURN_ABOVE);
        String key = UUID.randomUUID().toString();
        run(AiCreditReturns.RETURN, sponsorToken(sponsor), body("amount", 100, "requestKey", key));
        assertThat(codes(refused(AiCreditReturns.RETURN, sponsorToken(sponsor), body("amount", 100, "requestKey",
            key), 422))).containsExactly(FeeCodes.AI_CREDIT_REPEATED);

        // Once it ended, its fee with tax stays until the month's charge.
        runUntilDone(aiJobId);
        Map<String, Object> done = job(aiJobId);
        assertThat(done).containsEntry("status", QbAi.READY);
        BigDecimal fee = money(done.get("fee_yen"));
        BigDecimal withTax = fee.add(fee.multiply(TAX).setScale(0, java.math.RoundingMode.FLOOR));
        assertThat(money(giveBack(sponsor, null).get("amount"))).isEqualByComparingTo(
            new BigDecimal("4900").subtract(withTax));
        assertThat(money(aiCredit(sponsor).get("remaining"))).isEqualByComparingTo(withTax);
        assertThat(heldByParts(sponsor)).isEqualByComparingTo(withTax);
    }

    @Autowired
    JobDefinition<com.jabiz.quizbuks.content.ai.AiFeeSettlements.SettleRef> qbAiFeeMonthlyJob;

    /**
     * F-M14 G13 for the AI credit (plan §5): one of each of its postings through the processes, each in its own
     * category of the monthly report (bought, charged, paid from the credit, given back, expired), none in OTHER, and
     * the report closing over this class's ledger.
     */
    @Test
    void eachAiCreditPostingIsInItsMonthlyCategory() {
        feeOn();
        List<String> s = charged("ai-monthly-");
        String sponsor = s.get(0);
        String grantId = grant(sponsor, 200);
        buy(sponsor, 200);
        Object aiJobId = request(s.get(1), s.get(2), List.of(s.get(3)), 2).get("aiJobId");
        runUntilDone(aiJobId);
        giveBack(sponsor, 100L);
        // The month's charge, then the grant expires (its 200 bought into the credit, spent last of the fee credit).
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Tokyo");
        Instant monthly = java.time.YearMonth.from(clock.instant().atZone(zone)).plusMonths(1).atDay(1).atTime(0, 27)
            .atZone(zone).toInstant();
        clock.set(monthly.plusSeconds(60));
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        assertThat(jobRunner.run(qbAiFeeMonthlyJob, monthly)).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        execute("UPDATE qb_fee_credit_grant SET expires_at = ? WHERE grant_id = ?",
            Timestamp.from(clock.instant().plusSeconds(60)), grantId);
        advance(Duration.ofMinutes(5));
        assertThat(runJob(qbAiCreditExpireJob)).isEqualTo(JobRunner.Outcome.SUCCEEDED);

        String reader = com.jabiz.runtime.test.TestTokens.bearer(tokens, "ai-monthly",
            com.jabiz.quizbuks.QbPermissions.ADMIN_FINANCE_READ);
        List<Map<String, Object>> report = items("qb.admin.monthly", reader, Map.of("params",
            Map.of("from", "2000-01-01T00:00:00Z", "to", "2100-01-01T00:00:00Z"), "limit", 100));
        MonthlyKinds.assertGuard(report, List.of("AI_CREDIT_BOUGHT", "AI_CHARGED", "AI_CREDIT_USED",
            "AI_CREDIT_RETURNED", "AI_CREDIT_VOIDED"), query(MonthlyKinds.SOURCES_SQL),
            ((Number) query(MonthlyKinds.NO_SOURCE_SQL).getFirst().get("n")).longValue(),
            query(MonthlyKinds.TOTALS_SQL).getFirst());
    }

    @Test
    void expiredFeeCreditIsVoidedButForWhatTheOpenJobsHold() {
        feeOn();
        String sponsor = newSponsor("ai-expire-" + UUID.randomUUID());
        String grantId = grant(sponsor, 3_000);
        cash(sponsor, 1_000);
        buy(sponsor, 3_000);
        String token = sponsorFull(sponsor);
        List<String> quiz = quizWithArticle(token, title(), "Material for the expiry");
        Object aiJobId = request(token, quiz.get(0), List.of(quiz.get(1)), 2).get("aiJobId");
        runUntilDone(aiJobId);
        BigDecimal fee = money(job(aiJobId).get("fee_yen"));
        BigDecimal withTax = fee.add(fee.multiply(TAX).setScale(0, java.math.RoundingMode.FLOOR));
        assertThat(withTax).isPositive();

        // The grant expires; the expired credit is not given back, only what is not expired (none here).
        execute("UPDATE qb_fee_credit_grant SET expires_at = ? WHERE grant_id = ?",
            Timestamp.from(clock.instant().plusSeconds(60)), grantId);
        advance(Duration.ofMinutes(5));
        assertThat(codes(refused(AiCreditReturns.RETURN, sponsorToken(sponsor), body("amount", 1, "requestKey",
            "k-expired"), 422))).containsExactly(FeeCodes.AI_CREDIT_RETURN_ABOVE);
        BigDecimal promotionBefore = net("5140", sponsor);

        // F-M2's daily job first: it voids what the grant itself still holds (nothing: all in the credit).
        assertThat(runJob(qbFeeCreditExpireJob)).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(runJob(qbAiCreditExpireJob)).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        BigDecimal voided = new BigDecimal("3000").subtract(withTax);
        assertThat(money(aiCredit(sponsor).get("remaining"))).isEqualByComparingTo(withTax);
        assertThat(heldByParts(sponsor)).isEqualByComparingTo(withTax);
        Map<String, Object> grant = grantRow(grantId);
        assertThat(money(grant.get("remaining"))).isZero();
        assertThat(money(grant.get("voided_amount"))).isEqualByComparingTo(voided);
        assertThat(grant.get("voided_at")).isNotNull();
        // 2180 to 5140, the grant the source of the void (promotion_ledger reads it so).
        assertThat(promotionBefore.subtract(net("5140", sponsor))).isEqualByComparingTo(voided);
        assertThat(movements(sponsor)).last().isEqualTo("EXPIRE -" + voided.toPlainString() + " -"
            + voided.toPlainString() + "/0 " + withTax.toPlainString());

        // Both jobs again, either order: nothing more is voided.
        assertThat(runJob(qbAiCreditExpireJob)).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(runJob(qbFeeCreditExpireJob)).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(money(grantRow(grantId).get("voided_amount"))).isEqualByComparingTo(voided);
        assertThat(movements(sponsor).stream().filter(m -> m.startsWith("EXPIRE"))).hasSize(1);
        assertThat(heldByParts(sponsor)).isEqualByComparingTo(withTax);
    }
}
