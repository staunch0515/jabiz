package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.quizbuks.content.ai.AiFeeSettlements;
import com.jabiz.quizbuks.content.ai.QbAi;
import com.jabiz.quizbuks.fee.AiFeeRules;
import com.jabiz.quizbuks.fee.FeeRules;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M9a-2 (docs/quizbuks/plans/impl/F-M9.md §4 item 3, H2, H4): the month's AI fees charged from the sponsor's AI
 * credit by the monthly job: a group (operator, month) a charge, the month the jobs ended in, the operator frozen on
 * them or none, the cost part kept out of the operators' share, never more than the credit holds, and the credit read
 * by lot however many parts it has.
 */
class AiFeeSettleIT extends AiCreditItSupport {

    private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

    @Autowired
    JobDefinition<AiFeeSettlements.SettleRef> qbAiFeeMonthlyJob;

    /** A finished charged job of the sponsor's, by a request run to its end. */
    private Map<String, Object> finishedJob(List<String> s) {
        Object aiJobId = request(s.get(1), s.get(2), List.of(s.get(3)), 2).get("aiJobId");
        runUntilDone(aiJobId);
        Map<String, Object> done = job(aiJobId);
        assertThat(done).containsEntry("status", QbAi.READY);
        assertThat(money(done.get("fee_yen"))).isPositive();
        return done;
    }

    /** Runs the monthly job on the 1st of the month after the clock's, at 00:27 (Tokyo, the tests' fiscal zone). */
    private JobRunner.Outcome nextMonthsRun() {
        YearMonth next = YearMonth.from(clock.instant().atZone(ZONE)).plusMonths(1);
        Instant at = next.atDay(1).atTime(0, 27).atZone(ZONE).toInstant();
        clock.set(at.plusSeconds(60));
        // The job's lock outlives a run by real time (jabiz.jobs.lock-at-least-for): released for the next.
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        return jobRunner.run(qbAiFeeMonthlyJob, at);
    }

    private static List<Map<String, Object>> charges(String sponsor) {
        return query("SELECT * FROM qb_fee_charge_version WHERE sponsor_id = ? AND kind = 'AI' ORDER BY charged_at, "
            + "row_id", sponsor);
    }

    private static BigDecimal costPart(Map<String, Object> job) {
        return money(job.get("billable_usd")).multiply(money(job.get("fee_usd_jpy"))).setScale(0, RoundingMode.UP);
    }

    @Test
    void aMonthsJobsAreChargedFromTheCreditOnceAsOneFee() {
        feeOn();
        List<String> s = charged("ai-settle-");
        String sponsor = s.get(0);
        Map<String, Object> first = finishedJob(s);
        List<String> other = quizWithArticle(s.get(1), title(), "More material.");
        Map<String, Object> second = finishedJob(List.of(sponsor, s.get(1), other.get(0), other.get(1)));
        BigDecimal cashBefore = net("2160", sponsor);
        BigDecimal fee = money(first.get("fee_yen")).add(money(second.get("fee_yen")));

        assertThat(nextMonthsRun()).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        List<Map<String, Object>> charged = charges(sponsor);
        assertThat(charged).singleElement().satisfies(charge -> {
            assertThat(charge).containsEntry("period", first.get("fee_period"))
                .containsEntry("operator_id", first.get("fee_operator_id"))
                .containsEntry("billing_mode", FeeRules.PREPAID);
            assertThat(money(charge.get("amount"))).isEqualByComparingTo(fee);
            assertThat(money(charge.get("tax"))).isEqualByComparingTo(fee.multiply(TAX).setScale(0,
                RoundingMode.FLOOR));
            assertThat(String.valueOf(charge.get("source_key"))).isEqualTo("ai:" + first.get("fee_period") + ":"
                + first.get("fee_operator_id")).hasSizeLessThanOrEqualTo(100);
            // The provider's cost of both jobs, kept out of the operators' share (F-M12 §3.2).
            assertThat(money(charge.get("share_excluded"))).isEqualByComparingTo(costPart(first).add(costPart(second)));
        });
        Map<String, Object> charge = charged.getFirst();
        BigDecimal total = money(charge.get("total"));
        // Paid from the credit (2180 to 1130), never the account; the jobs are in it; the movement says so.
        assertThat(money(aiCredit(sponsor).get("remaining"))).isEqualByComparingTo(new BigDecimal("5000")
            .subtract(total));
        assertThat(net("2160", sponsor)).isEqualByComparingTo(cashBefore);
        assertThat(net("1130", sponsor)).isZero();
        assertThat(query("SELECT fee_charge_id FROM qb_ai_job WHERE sponsor_id = ?", sponsor))
            .extracting(row -> String.valueOf(row.get("fee_charge_id")))
            .containsOnly(String.valueOf(charge.get("charge_id")));
        assertThat(query("SELECT kind, amount, balance, reference FROM qb_ai_credit_movement_version WHERE "
            + "sponsor_id = ? AND kind = 'CHARGE'", sponsor)).singleElement().satisfies(m -> {
                assertThat(money(m.get("amount"))).isEqualByComparingTo(total.negate());
                assertThat(money(m.get("balance"))).isEqualByComparingTo(new BigDecimal("5000").subtract(total));
                assertThat(m.get("reference")).hasToString(String.valueOf(charge.get("charge_id")));
            });

        // Run again: nothing more is charged.
        assertThat(nextMonthsRun()).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(charges(sponsor)).hasSize(1);
    }

    /** M1 of the review: a job asked before the onboarding has no operator, and its fee is no operator's. */
    @Test
    void aJobWithoutAnOperatorIsChargedAsNoOperatorsAfterOneIsAssigned() {
        feeOn();
        List<String> s = charged("ai-settle-none-");
        Map<String, Object> done = finishedJob(s);
        assertThat(done.get("fee_operator_id")).isNotNull();
        // As if it was asked before the onboarding: the sponsor has an operator now, the job had none.
        execute("UPDATE qb_ai_job SET fee_operator_id = NULL WHERE ai_job_id = ?", String.valueOf(done.get("ai_job_id")));
        nextMonthsRun();
        assertThat(charges(s.get(0))).singleElement().satisfies(charge -> {
            assertThat(charge.get("operator_id")).isNull();
            assertThat(String.valueOf(charge.get("source_key"))).isEqualTo("ai:" + done.get("fee_period") + ":-");
        });
    }

    /**
     * H2 (b) of the review: a month whose fees with tax are above what the credit holds is lowered to what it holds,
     * the rest the platform's, and nothing is taken from the account or left owed.
     */
    @Test
    void aMonthAboveWhatTheCreditHoldsIsChargedWhatItHoldsAndNeverTheAccount() {
        feeOn();
        List<String> s = charged("ai-settle-short-");
        String sponsor = s.get(0);
        Map<String, Object> done = finishedJob(s);
        // As if the tax rate had gone up between the job's end and the charge: its fee with tax is above the credit.
        execute("UPDATE qb_ai_job SET fee_yen = 10000 WHERE ai_job_id = ?", String.valueOf(done.get("ai_job_id")));
        BigDecimal cashBefore = net("2160", sponsor);
        nextMonthsRun();
        // The largest amount whose total with 10% tax is within 5,000: 4,546 + 454.
        BigDecimal fits = new BigDecimal("4546");
        assertThat(AiFeeRules.withTax(fits, TAX)).isEqualByComparingTo("5000");
        assertThat(charges(sponsor)).singleElement().satisfies(charge ->
            assertThat(money(charge.get("amount"))).isEqualByComparingTo(fits));
        Map<String, Object> after = job(done.get("ai_job_id"));
        assertThat(money(after.get("fee_yen"))).isEqualByComparingTo(fits);
        assertThat(money(after.get("absorbed_yen"))).isEqualByComparingTo(money(done.get("absorbed_yen"))
            .add(new BigDecimal("10000").subtract(fits)));
        assertThat(money(aiCredit(sponsor).get("remaining"))).isZero();
        assertThat(net("2160", sponsor)).isEqualByComparingTo(cashBefore);
        assertThat(net("1130", sponsor)).isZero();
        assertThat(query("SELECT 1 FROM qb_fee_invoice WHERE sponsor_id = ? AND status <> 'PAID'", sponsor)).isEmpty();
    }

    /**
     * H4 of the review: a credit with more parts than a prepayment is read with (bought, charged, given back over the
     * years) is charged as any other: it is read by lot, the lots with something left.
     */
    @Test
    void aCreditWithMorePartsThanAPrepaymentIsReadWithIsChargedByLot() {
        feeOn();
        List<String> s = charged("ai-settle-parts-");
        String sponsor = s.get(0);
        Map<String, Object> credit = aiCredit(sponsor);
        Map<String, Object> part = query("SELECT * FROM qb_fee_payment_version WHERE target_id = ?",
            String.valueOf(credit.get("prepayment_id"))).getFirst();
        // 1,100 parts set aside and then 1,100 given back on another cash lot, which sorts first and holds nothing in
        // the end: read part by part (at most 1,001), it would look like it held 1,000 and pay the fee from it.
        String empty = "00000000-0000-4000-8000-000000000001";
        Object seq = query("SELECT max(process_seq_id) AS s FROM op_process").getFirst().get("s");
        Timestamp at = Timestamp.from(clock.instant());
        // The parts set aside have the lowest ids (read first by key), those given back the highest.
        execute("WITH pairs AS (SELECT CAST(CASE WHEN n <= 1100 THEN '00' || substr(md5(n::text), 3) "
                + "ELSE 'ff' || substr(md5(n::text), 3) END AS uuid) AS id, n, "
                + "CASE WHEN n <= 1100 THEN 1 ELSE -1 END AS amount FROM generate_series(1, 2200) n), "
                + "registered AS (INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) "
                + "SELECT id, 'QbFeePayment', ? FROM pairs ORDER BY n RETURNING entity_id) "
                + "INSERT INTO qb_fee_payment_version (payment_id, version_no, effect_start_time, created_time, "
                + "process_seq_id, sponsor_id, target_entity, target_id, sub_account, grant_id, cash_lot_id, amount, "
                + "paid_at, transaction_id) "
                + "SELECT p.id, 1, ?, ?, ?, ?, ?, ?, 'CASH', NULL, ?, p.amount, ?, 'parts' FROM pairs p ORDER BY p.n",
            seq, at, at, seq, sponsor, part.get("target_entity"), part.get("target_id"), empty, at);
        assertThat(query("SELECT count(*) AS n FROM qb_fee_payment_version WHERE target_id = ?",
            String.valueOf(credit.get("prepayment_id"))).getFirst().get("n")).hasToString("2201");
        Map<String, Object> done = finishedJob(s);

        assertThat(nextMonthsRun()).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        Map<String, Object> charge = charges(sponsor).getFirst();
        BigDecimal total = money(charge.get("total"));
        assertThat(money(aiCredit(sponsor).get("remaining"))).isEqualByComparingTo(new BigDecimal("5000")
            .subtract(total));
        // The fee took its total from the lot that holds it, none from the one that holds nothing.
        List<Map<String, Object>> paid = query("SELECT cash_lot_id, amount FROM qb_fee_payment_version WHERE "
            + "prepayment_id = ? AND target_id = ?", String.valueOf(credit.get("prepayment_id")),
            String.valueOf(charge.get("charge_id")));
        assertThat(paid).extracting(row -> String.valueOf(row.get("cash_lot_id")))
            .containsOnly(String.valueOf(part.get("cash_lot_id")));
        assertThat(paid.stream().map(row -> money(row.get("amount"))).reduce(BigDecimal.ZERO, BigDecimal::add))
            .isEqualByComparingTo(total);
        assertThat(job(done.get("ai_job_id")).get("fee_charge_id")).hasToString(String.valueOf(charge.get("charge_id")));
    }
}
