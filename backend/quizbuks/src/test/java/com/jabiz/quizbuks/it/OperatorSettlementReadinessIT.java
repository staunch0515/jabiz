package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.DistributionPrices;
import com.jabiz.quizbuks.fee.DistributionSettlements;
import com.jabiz.quizbuks.fee.FeeProcesses;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.OperatorSettlements;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M12 b (docs/quizbuks/plans/impl/F-M12.md §3.1, H5): the settlement job runs every day and takes each operator's
 * earliest month without a settlement; the steps before it (the distribution fees and the monthly invoices of the sponsors
 * whose fees the operator is frozen on) are waited for until {@code qb.operator.settlement.readiness-max-days} after the
 * month's end, then the month is settled and what was not there is counted next time as late.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OperatorSettlementReadinessIT extends CreditNoteItSupport {

    @Autowired
    @Qualifier("qbOperatorSettlementJob")
    JobDefinition<OperatorSettlements.RunInput> settlementJob;

    private String hq() {
        return TestTokens.bearer(tokens, "fm12b-hq", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ);
    }

    private String operator(String name) {
        return newOperator(hq(), unique(name));
    }

    private void assign(String sponsor, String operatorId) {
        run(OperatorProcesses.ASSIGN, hq(), body("userId", sponsor, "operatorId", operatorId, "reason", "F-M12 b"));
    }

    /** Tokyo's {@code date} at {@code hour}:13. */
    private static Instant at(String date, int hour) {
        return LocalDate.parse(date).atTime(hour, 13).atZone(TOKYO).toInstant();
    }

    /** The job's run at that time (each time is its own run: the items' keys carry it). */
    private JobRunner.JobRun runAt(String date, int hour) {
        Instant at = at(date, hour);
        clock.set(at);
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        return jobRunner.execute(settlementJob, at);
    }

    private JobRunner.JobRun runDay(String date) {
        return runAt(date, 2);
    }

    /** The items the job would take for a run in {@code current}'s month, of this operator. */
    private List<Map<String, Object>> due(String operatorId, String current) {
        return items(OperatorSettlements.DUE_TEMPLATE, TestTokens.bearer(tokens, "fm12b-system",
            QbPermissions.OPERATOR_SETTLEMENT_SYSTEM), Map.of("params", Map.of("current", current, "zone",
            TOKYO.getId()), "limit", 1000)).stream().filter(row -> operatorId.equals(row.get("operatorId"))).toList();
    }

    private static List<Map<String, Object>> settlements(String operatorId) {
        return query("SELECT * FROM qb_operator_settlement WHERE operator_id = ? ORDER BY month", operatorId);
    }

    private static List<Object> months(String operatorId) {
        return settlements(operatorId).stream().map(row -> row.get("month")).toList();
    }

    private static List<Map<String, Object>> chargeLines(Object settlementId) {
        return query("SELECT * FROM qb_operator_settlement_line_version WHERE settlement_id = ? AND line_kind = "
            + "'CHARGE' ORDER BY occurred_at", settlementId);
    }

    /** A postpaid sponsor of the operator with this month's monthly fee charged and not invoiced. */
    private String postpaidSponsor(String name, String operatorId) {
        String sponsor = newSponsor(unique(name));
        assign(sponsor, operatorId);
        billing(sponsor, "POSTPAID", 20_000);
        assertThat(monthly(sponsor)).isEqualTo("CHARGED");
        return sponsor;
    }

    private void invoice(String sponsor, String period) {
        assertThat(((FeeProcesses.ItemOutput) systemRun(FeeProcesses.INVOICE_ITEM, body("sponsorId", sponsor,
            "period", period))).outcome()).isEqualTo("ISSUED");
    }

    // ---- the items --------------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    void aMonthThatIsStuckStaysTheOneTakenUntilItIsSettledThenTheNextIsTaken() {
        clock.set(Instant.parse("2027-01-10T01:00:00Z"));
        param(QbParams.FEE_MONTHLY, "1000");
        // It waits as long as it may: 31 days after January's end, Tokyo's March 4th at midnight.
        param(QbParams.OPERATOR_SETTLEMENT_READINESS_MAX_DAYS, "31");
        String operatorId = operator("Stuck");
        String sponsor = postpaidSponsor("fm12b-stuck", operatorId);

        // March 2nd and 3rd: January is the month taken, not February, and it waits for its invoice.
        assertThat(due(operatorId, "2027-03")).singleElement().satisfies(item -> assertThat(item)
            .containsEntry("month", "2027-01"));
        assertThat(runDay("2027-03-02").items().succeeded()).isGreaterThanOrEqualTo(1);
        assertThat(runDay("2027-03-03").items().succeeded()).isGreaterThanOrEqualTo(1);
        assertThat(settlements(operatorId)).isEmpty();
        assertThat(due(operatorId, "2027-03")).singleElement().satisfies(item -> assertThat(item)
            .containsEntry("month", "2027-01"));

        // The invoice is issued: January is settled first, February the next day (it has no fee and waits for nothing).
        invoice(sponsor, "2027-01");
        runAt("2027-03-03", 12);
        assertThat(months(operatorId)).containsExactly("2027-01");
        assertThat(due(operatorId, "2027-03")).singleElement().satisfies(item -> assertThat(item)
            .containsEntry("month", "2027-02"));
        runDay("2027-03-04");
        assertThat(months(operatorId)).containsExactly("2027-01", "2027-02");
        assertThat(due(operatorId, "2027-03")).isEmpty();
        runDay("2027-03-05");
        assertThat(months(operatorId)).containsExactly("2027-01", "2027-02");
        param(QbParams.OPERATOR_SETTLEMENT_READINESS_MAX_DAYS, "5");
    }

    @Test
    @Order(2)
    void anOperatorNotReadyIsSkippedAndTakenAgainTheNextDayUntilItIs() {
        clock.set(Instant.parse("2028-03-10T01:00:00Z"));
        param(QbParams.FEE_MONTHLY, "1000");
        String operatorId = operator("Not ready");
        String sponsor = postpaidSponsor("fm12b-wait", operatorId);

        runDay("2028-04-02");
        runDay("2028-04-03");
        assertThat(settlements(operatorId)).isEmpty();
        // The invoice is issued: the next day settles the month.
        invoice(sponsor, "2028-03");
        runDay("2028-04-04");
        assertThat(months(operatorId)).containsExactly("2028-03");
        assertThat(chargeLines(settlements(operatorId).getFirst().get("settlement_id"))).hasSize(1);
        // Settled: not taken again.
        runDay("2028-04-05");
        assertThat(settlements(operatorId)).hasSize(1);
    }

    @Test
    @Order(3)
    void anOperatorCreatedAfterAMonthHasNoItemForIt() {
        clock.set(Instant.parse("2029-02-10T01:00:00Z"));
        String operatorId = operator("Created in February");
        // Its months start with the one it was created in.
        assertThat(due(operatorId, "2029-02")).isEmpty();
        assertThat(due(operatorId, "2029-03")).singleElement().satisfies(item -> assertThat(item)
            .containsEntry("month", "2029-02"));
        runDay("2029-03-02");
        assertThat(months(operatorId)).containsExactly("2029-02");
        assertThat(due(operatorId, "2029-04")).singleElement().satisfies(item -> assertThat(item)
            .containsEntry("month", "2029-03"));
        // Not January, though it is earlier than every month it has.
        assertThat(due(operatorId, "2029-03")).isEmpty();
    }

    @Test
    @Order(4)
    void theMonthBeforeMustBeSettledSoTheEarliestMonthIsTakenAndNotALaterOne() {
        clock.set(Instant.parse("2030-01-10T01:00:00Z"));
        String operatorId = operator("Gap");
        assertThat(due(operatorId, "2030-04")).singleElement().satisfies(item -> assertThat(item)
            .containsEntry("month", "2030-01"));
        // January first; February is taken only once January has its row.
        runDay("2030-04-02");
        assertThat(months(operatorId)).containsExactly("2030-01");
        assertThat(due(operatorId, "2030-04")).singleElement().satisfies(item -> assertThat(item)
            .containsEntry("month", "2030-02"));
    }

    // ---- the deadline -----------------------------------------------------------------------------------------------

    @Test
    @Order(5)
    void theSponsorsOfAnOperatorAreThoseWhoseFeesItIsFrozenOnEvenAfterTheyAreReassigned() {
        clock.set(Instant.parse("2031-03-10T01:00:00Z"));
        param(QbParams.FEE_MONTHLY, "1000");
        String operatorA = operator("Frozen A");
        String operatorB = operator("Frozen B");
        String sponsor = postpaidSponsor("fm12b-frozen", operatorA);
        // The fee was charged with A frozen on it; the sponsor now belongs to B.
        assign(sponsor, operatorB);
        runDay("2031-04-02");
        // A waits for the invoice of its sponsor's fee; B, whose sponsor has no fee under it, does not.
        assertThat(settlements(operatorA)).isEmpty();
        assertThat(months(operatorB)).containsExactly("2031-03");
        invoice(sponsor, "2031-03");
        runDay("2031-04-03");
        assertThat(months(operatorA)).containsExactly("2031-03");
    }

    @Test
    @Order(6)
    void afterTheDeadlineItSettlesAnywayAndTheFeeNotThereYetIsCountedNextTimeAsLateAndOnce() {
        autoApprove(true);
        clock.set(Instant.parse("2032-03-10T01:00:00Z"));
        param(QbParams.DISTRIBUTION_BILLING_ENABLED, "true");
        param(QbParams.FEE_TAX_RATE, "0.1");
        String sponsor = pricedSponsor("fm12b-late");
        String operatorId = String.valueOf(query("SELECT operator_id FROM qb_sponsor_operator WHERE user_id = ?",
            sponsor).getFirst().get("operator_id"));
        run(DistributionPrices.SET, TestTokens.bearer(tokens, "fm12b-prices", QbPermissions.FEE_MANAGE),
            body("operatorId", operatorId, "rate", "0.05", "unitFloor", "1", "unitCeiling", "20", "minimumDeposit",
                500, "reason", "F-M12 b"));
        credit(sponsor, 5_000);
        cash(sponsor, 3_000);
        String publicationId = livePublication(sponsor, "FM12b late", Map.of("maxWinners", 30, "regions",
            List.of("GLOBAL")));
        for (int i = 0; i < 3; i++) {
            start(taker(newTaker(unique("fm12b-late-taker-" + i), null)), publicationId);
        }
        manualFee(sponsor, 1_000);

        // March's distribution fee is due and not settled: April 2nd to 5th wait (the deadline is the 6th, midnight).
        runDay("2032-04-02");
        runDay("2032-04-05");
        assertThat(settlements(operatorId)).isEmpty();
        // The 6th: settled anyway, with the fee of the month only.
        runDay("2032-04-06");
        assertThat(months(operatorId)).containsExactly("2032-03");
        Map<String, Object> march = settlements(operatorId).getFirst();
        assertThat(chargeLines(march.get("settlement_id"))).singleElement().satisfies(line -> assertThat(line)
            .containsEntry("fee_kind", "MANUAL").containsEntry("late", false));

        // The distribution fee is settled a day later: its period is March, charged in April.
        clock.set(at("2032-04-07", 1));
        systemRun(DistributionSettlements.SETTLE, body("publicationId", publicationId, "sponsorId", sponsor,
            "period", "2032-03", "last", false));
        Map<String, Object> fee = query("SELECT * FROM qb_fee_charge_version WHERE sponsor_id = ? AND kind = "
            + "'DISTRIBUTION'", sponsor).getFirst();
        assertThat(fee).containsEntry("period", "2032-03");

        // April's and May's distribution fees (the publication is still on, with no start): settled, so that they do not
        // hold the next settlements back.
        clock.set(at("2032-05-01", 1));
        systemRun(DistributionSettlements.SETTLE, body("publicationId", publicationId, "sponsorId", sponsor,
            "period", "2032-04", "last", false));
        // April's settlement counts it, as late; May's does not count it again.
        runDay("2032-05-02");
        assertThat(months(operatorId)).containsExactly("2032-03", "2032-04");
        Map<String, Object> april = settlements(operatorId).get(1);
        assertThat(chargeLines(april.get("settlement_id"))).singleElement().satisfies(line -> assertThat(line)
            .containsEntry("fee_kind", "DISTRIBUTION").containsEntry("late", true)
            .containsEntry("charge_id", String.valueOf(fee.get("charge_id"))));
        clock.set(at("2032-06-01", 1));
        systemRun(DistributionSettlements.SETTLE, body("publicationId", publicationId, "sponsorId", sponsor,
            "period", "2032-05", "last", false));
        runDay("2032-06-02");
        assertThat(months(operatorId)).containsExactly("2032-03", "2032-04", "2032-05");
        assertThat(chargeLines(settlements(operatorId).get(2).get("settlement_id"))).isEmpty();
        assertThat(query("SELECT 1 FROM qb_operator_settlement_line_version WHERE operator_id = ? AND charge_id = ? "
            + "AND line_kind = 'CHARGE'", operatorId, String.valueOf(fee.get("charge_id")))).hasSize(1);
        assertThat((BigDecimal) march.get("platform_fees")).isEqualByComparingTo("1000");
    }

    @Test
    @Order(7)
    void aRunRequestedByHandDoesNotBypassTheWaitAndZeroDaysSettlesTheSameDay() {
        clock.set(Instant.parse("2033-03-10T01:00:00Z"));
        param(QbParams.FEE_MONTHLY, "1000");
        String operatorId = operator("Requested");
        postpaidSponsor("fm12b-request", operatorId);
        clock.set(at("2033-04-03", 3));

        awaitRequest(at("2033-04-03", 2));
        assertThat(settlements(operatorId)).isEmpty();

        // Zero days: it does not wait at all; a run the same day (asked for the next day's time) settles it.
        param(QbParams.OPERATOR_SETTLEMENT_READINESS_MAX_DAYS, "0");
        clock.set(at("2033-04-04", 3));
        awaitRequest(at("2033-04-04", 2));
        assertThat(months(operatorId)).containsExactly("2033-03");
        param(QbParams.OPERATOR_SETTLEMENT_READINESS_MAX_DAYS, "5");
    }

    /** JOB_RUN_REQUEST for the settlement job at {@code scheduledTime}, done when its run is recorded. */
    private void awaitRequest(Instant scheduledTime) {
        Instant now = clock.instant();
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        Map<String, Object> accepted = client.post().uri("/api/jobs/{job}/runs", OperatorSettlements.JOB)
            .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(tokens, "fm12b-jobs", "job.run", "job.read"))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("scheduledTime", scheduledTime.toString(),
                "reason", "The month's settlement was missed")).exchange().expectStatus().isAccepted()
            .expectBody(MAP).returnResult().getResponseBody();
        long seq = ((Number) accepted.get("requestSeqId")).longValue();
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (query("SELECT 1 FROM sys_job_run WHERE request_seq_id = ?", seq).isEmpty()) {
            assertThat(System.nanoTime()).as("the run of the request ended").isLessThan(deadline);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        clock.set(now);
    }
}
