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

    /** The scheduled time of the last monthly run. */
    private Instant lastRun;

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
        lastRun = at;
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

        // #699 review M4: the same run requested again (JOB_RUN_REQUEST, the same scheduled time): nothing more.
        Instant scheduled = lastRun;
        requestRunAgain(scheduled);
        assertThat(charges(sponsor)).hasSize(1);

        // #699 review M1: a job of the month whose outcome was recorded after the run (its fee_period the month
        // before): its sponsor's item has a new key (the batch's earliest job), so the run requested again charges it.
        // A token of now: the one of the test's start has expired with the month.
        String token = sponsorFull(sponsor);
        List<String> late = quizWithArticle(token, title(), "Late material.");
        Map<String, Object> lateJob = finishedJob(List.of(sponsor, token, late.get(0), late.get(1)));
        execute("UPDATE qb_ai_job SET fee_period = ? WHERE ai_job_id = ?", first.get("fee_period"),
            String.valueOf(lateJob.get("ai_job_id")));
        requestRunAgain(scheduled);
        assertThat(charges(sponsor)).hasSize(2).last().satisfies(c -> {
            assertThat(c).containsEntry("period", first.get("fee_period"));
            assertThat(money(c.get("amount"))).isEqualByComparingTo(money(lateJob.get("fee_yen")));
        });
        assertThat(job(lateJob.get("ai_job_id")).get("fee_charge_id")).isNotNull();
        requestRunAgain(scheduled);
        assertThat(charges(sponsor)).hasSize(2);
    }

    /** JOB_RUN_REQUEST of the monthly job at {@code scheduledTime}, done when its run is recorded. */
    private void requestRunAgain(Instant scheduledTime) {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        Map<String, Object> accepted = client.post().uri("/api/jobs/{job}/runs", AiFeeSettlements.JOB)
            .header(org.springframework.http.HttpHeaders.AUTHORIZATION, com.jabiz.runtime.test.TestTokens.bearer(
                tokens, "ai-fee-jobs", "job.run", "job.read"))
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue(Map.of("scheduledTime",
                scheduledTime.toString(), "reason", "A late AI job")).exchange().expectStatus().isAccepted()
            .expectBody(MAP).returnResult().getResponseBody();
        long seq = ((Number) accepted.get("requestSeqId")).longValue();
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
        while (query("SELECT 1 FROM sys_job_run WHERE request_seq_id = ?", seq).isEmpty()) {
            assertThat(System.nanoTime()).as("the run of the request ended").isLessThan(deadline);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * #699 review M4 (H4): the month's charge spends the credit as the account does: the fee credit first, the lot that
     * expires first first, then the cash; the charge's parts and the movement say how much came from each.
     */
    @Test
    void theChargeSpendsTheFeeCreditThatExpiresFirstThenTheCash() {
        feeOn();
        String sponsor = newSponsor("ai-settle-lots-" + java.util.UUID.randomUUID());
        String later = grant(sponsor, 1_000);
        String sooner = grant(sponsor, 1_000);
        execute("UPDATE qb_fee_credit_grant SET expires_at = ? WHERE grant_id = ?",
            Timestamp.from(clock.instant().plus(java.time.Duration.ofDays(60))), sooner);
        cash(sponsor, 1_000);
        buy(sponsor, 2_500);
        String token = sponsorFull(sponsor);
        List<String> quiz = quizWithArticle(token, title(), "Material for the lots.");
        Map<String, Object> done = finishedJob(List.of(sponsor, token, quiz.get(0), quiz.get(1)));
        nextMonthsRun();
        Map<String, Object> charge = charges(sponsor).getFirst();
        BigDecimal total = money(charge.get("total"));
        Object creditId = aiCredit(sponsor).get("prepayment_id");
        // All of it from the grant that expires first (the fee is small), none from the later grant or the cash.
        assertThat(query("SELECT sub_account, grant_id, amount FROM qb_fee_payment_version WHERE target_id = ? AND "
            + "prepayment_id = ?", String.valueOf(charge.get("charge_id")), String.valueOf(creditId)))
            .singleElement().satisfies(part -> {
                assertThat(part).containsEntry("sub_account", "FEE_CREDIT").containsEntry("grant_id", sooner);
                assertThat(money(part.get("amount"))).isEqualByComparingTo(total);
            });
        assertThat(later).isNotEqualTo(sooner);
        assertThat(query("SELECT credit, cash FROM qb_ai_credit_movement_version WHERE sponsor_id = ? AND kind = "
            + "'CHARGE'", sponsor)).singleElement().satisfies(m -> {
                assertThat(money(m.get("credit"))).isEqualByComparingTo(total.negate());
                assertThat(money(m.get("cash"))).isZero();
            });
        assertThat(done.get("fee_charge_id")).isNull();
    }

    /**
     * #699 review M4: the month's charge item, a purchase and a job's outcome at once: each waits for the lock it needs
     * (the item AI then wallet, the purchase the wallet, the outcome the AI locks), none fails, and the credit's parts
     * add up to what it holds afterwards.
     */
    @Test
    void theChargeAPurchaseAndAnOutcomeAtOnceAllEndAndTheCreditAddsUp() throws Exception {
        feeOn();
        List<String> s = charged("ai-settle-race-");
        String sponsor = s.get(0);
        finishedJob(s);
        cash(sponsor, 2_000);
        // The next month: a job of this month waits for its charge, another of the new month for its outcome.
        YearMonth next = YearMonth.from(clock.instant().atZone(ZONE)).plusMonths(1);
        clock.set(next.atDay(1).atTime(0, 30).atZone(ZONE).toInstant());
        String token = sponsorFull(sponsor);
        List<String> other = quizWithArticle(token, title(), "More material for the race.");
        Object running = request(token, other.get(0), List.of(other.get(1)), 2).get("aiJobId");
        java.util.concurrent.CompletableFuture<Object> settle = java.util.concurrent.CompletableFuture.supplyAsync(
            () -> systemRun(AiFeeSettlements.SETTLE, body("sponsorId", sponsor, "month", next.toString())));
        java.util.concurrent.CompletableFuture<Map<String, Object>> purchase = java.util.concurrent.CompletableFuture
            .supplyAsync(() -> buy(sponsor, 1_000));
        java.util.concurrent.CompletableFuture<Void> outcome = java.util.concurrent.CompletableFuture
            .runAsync(() -> runUntilDone(running));
        java.util.concurrent.CompletableFuture.allOf(settle, purchase, outcome).get(120,
            java.util.concurrent.TimeUnit.SECONDS);

        assertThat(charges(sponsor)).hasSize(1);
        assertThat(job(running).get("finished_at")).isNotNull();
        BigDecimal remaining = money(aiCredit(sponsor).get("remaining"));
        assertThat(remaining).isEqualByComparingTo(new BigDecimal("6000").subtract(money(charges(sponsor).getFirst()
            .get("total"))));
        Object creditId = aiCredit(sponsor).get("prepayment_id");
        assertThat(money(query("SELECT coalesce(sum(amount), 0) AS h FROM (SELECT amount FROM qb_fee_payment_version "
            + "WHERE target_id = ? UNION ALL SELECT -amount FROM qb_fee_payment_version WHERE prepayment_id = ? AND "
            + "amount > 0) p", String.valueOf(creditId), String.valueOf(creditId)).getFirst().get("h")))
            .isEqualByComparingTo(remaining);
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

        // #692 re-review M1: the month's summary gives what the platform took on, before tax (never posted).
        String reader = com.jabiz.runtime.test.TestTokens.bearer(tokens, "ai-fee-monthly",
            com.jabiz.quizbuks.QbPermissions.ADMIN_FINANCE_READ);
        List<Map<String, Object>> rows = query("SELECT coalesce(sum(fee_yen), 0) AS f, coalesce(sum(absorbed_yen), 0) "
            + "AS a, count(*) AS n FROM qb_ai_job WHERE fee_mode = 'CREDIT' AND fee_period = ?", done.get("fee_period"));
        assertThat(items("qb.admin.ai-fee-monthly", reader, Map.of("params", Map.of("month", done.get("fee_period")),
            "limit", 10))).singleElement().satisfies(row -> {
                assertThat(money(row.get("absorbedYen"))).isEqualByComparingTo(money(rows.getFirst().get("a")))
                    .isGreaterThanOrEqualTo(new BigDecimal("10000").subtract(fits));
                assertThat(money(row.get("feeYen"))).isEqualByComparingTo(money(rows.getFirst().get("f")));
                assertThat(((Number) row.get("jobs")).longValue()).isEqualTo(((Number) rows.getFirst().get("n"))
                    .longValue());
            });
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
