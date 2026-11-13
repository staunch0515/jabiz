package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.FeeProcesses;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.OperatorSettlements;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.ReconChecks;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M12 c (docs/quizbuks/plans/impl/F-M12.md §6): the daily check of the operators' settlements, template
 * {@code qb.system.recon-operator-settlement}. Each check is shown with a difference made and with none; the year of each
 * test is its own (the parameters change forward in time).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OperatorSettlementReconIT extends CapItSupport {

    static final String TEMPLATE = "qb.system.recon-operator-settlement";

    private String reconToken() {
        return TestTokens.bearer(tokens, "fm12c-recon", QbPermissions.WALLET_SYSTEM);
    }

    /** The template's rows as the daily run asks for them, at the clock's time (the hours: null is "not published"). */
    private List<Map<String, Object>> found(Object waitingHours) {
        Map<String, Object> params = new HashMap<>();
        params.put("at", clock.instant().toString());
        params.put("zone", TOKYO.getId());
        // The settlement waits 5 days for its steps (qb.operator.settlement.readiness-max-days, as published).
        params.put("readinessDays", 5);
        if (waitingHours != null) {
            params.put("waitingHours", waitingHours);
        }
        return items(TEMPLATE, reconToken(), Map.of("params", params, "limit", 1000));
    }

    /** What the check finds about one operator (or sponsor), by check code (none: all), with the hours at 48. */
    private List<Map<String, Object>> of(String party, String... codes) {
        List<String> wanted = List.of(codes);
        return found(48).stream().filter(row -> party.equals(row.get("sponsorId"))
            && (wanted.isEmpty() || wanted.contains(String.valueOf(row.get("checkCode"))))).toList();
    }

    private static void assertRow(Map<String, Object> row, String check, String reference, String expected,
        String actual) {
        assertThat(row).containsEntry("checkCode", check).containsEntry("reference", reference);
        assertThat(new BigDecimal(String.valueOf(row.get("expected")))).as("expected").isEqualByComparingTo(expected);
        assertThat(new BigDecimal(String.valueOf(row.get("actual")))).as("actual").isEqualByComparingTo(actual);
    }

    private void assign(String sponsor, String operatorId) {
        run(OperatorProcesses.ASSIGN, hq(), body("userId", sponsor, "operatorId", operatorId, "reason", "F-M12 c"));
    }

    private void invoice(String sponsor, String period) {
        assertThat(((FeeProcesses.ItemOutput) systemRun(FeeProcesses.INVOICE_ITEM, body("sponsorId", sponsor,
            "period", period))).outcome()).isEqualTo("ISSUED");
    }

    private static void tamper(String column, String value, String operatorId, String month) {
        execute("UPDATE qb_operator_settlement SET " + column + " = " + value + " WHERE operator_id = ? AND month = ?",
            operatorId, month);
    }

    /**
     * A fee of no amount like the template, charged as it was: corrupt data, which the table's check (amount above 0)
     * refuses, so the check is dropped in this test's schema first. A payment on it cannot be cut over its fees.
     */
    private static void zeroFee(String template, String id) {
        execute("ALTER TABLE qb_fee_charge_version DROP CONSTRAINT IF EXISTS qb_fee_charge_version_amount_check");
        execute("INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) SELECT ?::uuid, entity_type, "
            + "created_seq_id FROM entity_registry WHERE entity_id = ?::uuid", id, template);
        execute("INSERT INTO qb_fee_charge_version (charge_id, version_no, effect_start_time, created_time, "
            + "process_seq_id, sponsor_id, operator_id, billing_mode, kind, period, amount, tax_rate, tax, total, "
            + "reason, source_key, charged_at, charged_by, share_excluded) SELECT ?::uuid, version_no, "
            + "effect_start_time, created_time, process_seq_id, sponsor_id, operator_id, billing_mode, kind, period, "
            + "0, tax_rate, 0, 0, reason, NULL, charged_at, charged_by, 0 "
            + "FROM qb_fee_charge_version WHERE charge_id = ?::uuid", id, template);
    }

    /** A fee like the template with no operator (as the fees of before F-M11 are). */
    private static void looseFee(String template, String id) {
        execute("INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) SELECT ?::uuid, entity_type, "
            + "created_seq_id FROM entity_registry WHERE entity_id = ?::uuid", id, template);
        execute("INSERT INTO qb_fee_charge_version (charge_id, version_no, effect_start_time, created_time, "
            + "process_seq_id, sponsor_id, operator_id, billing_mode, kind, period, amount, tax_rate, tax, total, "
            + "reason, source_key, charged_at, charged_by, share_excluded) SELECT ?::uuid, version_no, "
            + "effect_start_time, created_time, process_seq_id, sponsor_id, NULL, billing_mode, kind, period, "
            + "amount, tax_rate, tax, total, reason, NULL, charged_at, charged_by, share_excluded "
            + "FROM qb_fee_charge_version WHERE charge_id = ?::uuid", id, template);
    }

    /** A part of a payment made with granted credit like the template's, aimed at the fee {@code target}. */
    private static void paymentOn(String templatePayment, String id, String target, long amount) {
        execute("INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) SELECT ?::uuid, entity_type, "
            + "created_seq_id FROM entity_registry WHERE entity_id = ?::uuid", id, templatePayment);
        execute("INSERT INTO qb_fee_payment_version (payment_id, version_no, effect_start_time, created_time, "
            + "process_seq_id, sponsor_id, target_entity, target_id, sub_account, grant_id, cash_lot_id, amount, "
            + "paid_at, transaction_id, prepayment_id) SELECT ?::uuid, 1, effect_start_time, created_time, "
            + "process_seq_id, sponsor_id, 'QbFeeCharge', ?, sub_account, grant_id, cash_lot_id, ?, paid_at, "
            + "transaction_id, prepayment_id FROM qb_fee_payment_version WHERE payment_id = ?::uuid", id, target, amount,
            templatePayment);
    }

    private static String paymentOf(String chargeId) {
        return String.valueOf(query("SELECT payment_id FROM qb_fee_payment_version WHERE target_id = ? "
            + "AND sub_account = 'FEE_CREDIT'", chargeId).getFirst().get("payment_id"));
    }

    /** The existing checks the credit taken back could disturb: the grants' promotion expense and the operator's account. */
    private void assertPromotionAndAccountAgree(String operatorId) {
        String token = reconToken();
        assertThat(items("qb.system.recon-fee-credit", token, Map.of("limit", 1000)).stream()
            .filter(row -> "FEE_CREDIT_PROMOTION".equals(row.get("checkCode")))).isEmpty();
        assertThat(items("qb.system.recon-operator-account", token, Map.of("limit", 1000)).stream()
            .filter(row -> operatorId.equals(row.get("sponsorId")))).isEmpty();
    }

    // ---- OPERATOR_SETTLEMENT_WAITING --------------------------------------------------------------------------------

    @Test
    @Order(1)
    void anOperatorStillWithoutItsMonthIsReportedAfterTheHoursWithTheStepItWaitsFor() {
        clock.set(Instant.parse("2027-01-10T01:00:00Z"));
        param(QbParams.FEE_MONTHLY, "1000");
        String operatorId = newOperator(hq(), unique("Waiting"));
        String sponsor = newSponsor(unique("fm12c-waiting"));
        assign(sponsor, operatorId);
        billing(sponsor, "POSTPAID", 20_000);
        assertThat(monthly(sponsor)).isEqualTo("CHARGED");

        // January ended at Tokyo's midnight (Jan 31, 15:00 UTC). The month waits for its invoice, which the settlement
        // may wait for 5 days (to Feb 5, 15:00 UTC): inside that wait, and 48 hours after it, nothing is reported (a).
        clock.set(Instant.parse("2027-02-04T00:00:00Z"));
        assertThat(of(operatorId)).isEmpty();
        clock.set(Instant.parse("2027-02-07T00:00:00Z"));
        assertThat(of(operatorId)).isEmpty();
        // 57 hours after the deadline: it is reported, with the step it waits for (b).
        clock.set(Instant.parse("2027-02-08T00:00:00Z"));
        assertThat(of(operatorId)).singleElement().satisfies(row -> assertRow(row,
            ReconChecks.OPERATOR_SETTLEMENT_WAITING, "2027-01:INVOICE", "0", "1"));
        // Not checked without the hours (the parameter not published).
        assertThat(found(null).stream().filter(row -> operatorId.equals(row.get("sponsorId")))).isEmpty();

        // Two months behind: the earliest is the one named (the head of the line), and how many are behind counted.
        clock.set(Instant.parse("2027-03-10T00:00:00Z"));
        assertThat(of(operatorId)).singleElement().satisfies(row -> assertRow(row,
            ReconChecks.OPERATOR_SETTLEMENT_WAITING, "2027-01:INVOICE", "0", "2"));

        // The invoice is issued: it waits for no step; the month is named alone.
        invoice(sponsor, "2027-01");
        assertThat(of(operatorId)).singleElement().satisfies(row -> assertRow(row,
            ReconChecks.OPERATOR_SETTLEMENT_WAITING, "2027-01", "0", "2"));
        systemRun(OperatorSettlements.RUN, body("operatorId", operatorId, "month", "2027-01"));
        assertThat(of(operatorId)).singleElement().satisfies(row -> assertRow(row,
            ReconChecks.OPERATOR_SETTLEMENT_WAITING, "2027-02", "0", "1"));
        systemRun(OperatorSettlements.RUN, body("operatorId", operatorId, "month", "2027-02"));
        assertThat(of(operatorId)).isEmpty();
    }

    @Test
    @Order(9)
    void aMonthReadyButNotSettledBecauseTheJobDidNotRunIsReportedTheHoursAfterItBecameReady() {
        clock.set(Instant.parse("2036-01-10T01:00:00Z"));
        String operatorId = newOperator(hq(), unique("Ready"));
        String sponsor = newSponsor(unique("fm12c-ready"));
        assign(sponsor, operatorId);
        billing(sponsor, "POSTPAID", 20_000);
        assertThat(monthly(sponsor)).isEqualTo("CHARGED");
        // The invoice is issued 30 hours after the month's end (Feb 1, 21:00 UTC): the month is ready from then, and the
        // settlement job does not run. Reported 48 hours after that, which is inside the 5 days' wait: start is the
        // moment it was ready, not the deadline and not the month's end.
        clock.set(Instant.parse("2036-02-01T21:00:00Z"));
        invoice(sponsor, "2036-01");
        clock.set(Instant.parse("2036-02-03T20:00:00Z"));
        assertThat(of(operatorId)).isEmpty();
        clock.set(Instant.parse("2036-02-03T22:00:00Z"));
        assertThat(of(operatorId)).singleElement().satisfies(row -> assertRow(row,
            ReconChecks.OPERATOR_SETTLEMENT_WAITING, "2036-01", "0", "1"));
    }

    // ---- OPERATOR_SETTLEMENT_COVERAGE: the columns, GIFT_RECOVERY ---------------------------------------------------

    @Test
    @Order(2)
    void aSettlementThatDisagreesWithItsLinesOrTheLedgerIsReportedAndAnAgreeingOneIsNot() {
        String[] who = start(2028);
        param(QbParams.operatorGiftTreatment("MANUAL"), "OPERATOR");
        // 1,100 of granted credit (borne by the operator) pays a 1,000 fee: the share is 500, 1,100 is due, 500 taken,
        // 600 carried.
        grant(who[1], 1_100, Instant.parse("2031-01-01T00:00:00Z"));
        manualFee(who[1], 1_000);
        Map<String, Object> february = settle(who[0], "2028-02", "2028-03-02T00:00:00Z");
        assertThat(n(february, "gift_due")).isEqualByComparingTo("1100");
        assertThat(n(february, "gift_recovered")).isEqualByComparingTo("500");
        assertThat(n(february, "gift_carried_out")).isEqualByComparingTo("600");
        // Clean: every column is its lines' sum, and the 500 is the credit to 5140.
        assertThat(of(who[0])).isEmpty();

        tamper("platform_fees", "1005", who[0], "2028-02");
        assertThat(of(who[0])).singleElement().satisfies(row -> assertRow(row,
            ReconChecks.OPERATOR_SETTLEMENT_COVERAGE, "2028-02:platformFees", "1000", "1005"));
        tamper("platform_fees", "1000", who[0], "2028-02");
        assertThat(of(who[0])).isEmpty();

        // The credit due: the column against the lines, and the month's flow (due = taken + carried out - carried in).
        tamper("gift_due", "1000", who[0], "2028-02");
        assertThat(of(who[0])).hasSize(2).anySatisfy(row -> assertRow(row, ReconChecks.OPERATOR_SETTLEMENT_COVERAGE,
            "2028-02:giftDue", "1100", "1000")).anySatisfy(row -> assertRow(row, ReconChecks.GIFT_RECOVERY,
            "2028-02", "1000", "1100"));
        tamper("gift_due", "1100", who[0], "2028-02");
        assertThat(of(who[0])).isEmpty();

        // What was taken against the postings of 5140.
        tamper("gift_recovered", "501", who[0], "2028-02");
        assertThat(of(who[0], ReconChecks.GIFT_RECOVERY)).anySatisfy(row -> assertRow(row,
            ReconChecks.GIFT_RECOVERY, "recovered", "501", "500"));
        tamper("gift_recovered", "500", who[0], "2028-02");
        assertThat(of(who[0])).isEmpty();

        // The part not shared: a column nothing of the lines gives.
        tamper("gift_paid", "7", who[0], "2028-02");
        assertThat(of(who[0])).singleElement().satisfies(row -> assertRow(row,
            ReconChecks.OPERATOR_SETTLEMENT_COVERAGE, "2028-02:giftPaid", "0", "7"));
        tamper("gift_paid", "0", who[0], "2028-02");
        assertThat(of(who[0])).isEmpty();
    }

    // ---- OPERATOR_SETTLEMENT_COVERAGE: fees not counted -------------------------------------------------------------

    @Test
    @Order(3)
    void aFeeNoSettlementCountedIsReportedOnlyAfterTwoSettlementsThatCouldHaveCountedIt() {
        String[] who = start(2029);
        List<String> ids = fees(who[1], 1, 100, "2029-02-10T01:00:00Z");
        settle(who[0], "2029-02", "2029-03-02T00:00:00Z");

        // A fee as of February, found after one settlement: the next counts it (one cycle is allowed).
        String late = UUID.randomUUID().toString();
        OperatorSettlementCapIT.copyFee(ids.getFirst(), late);
        assertThat(of(who[0])).isEmpty();
        Map<String, Object> march = settle(who[0], "2029-03", "2029-04-02T00:00:00Z");
        assertThat(lineCharges(march.get("settlement_id"), "CHARGE")).containsExactly(late);
        assertThat(of(who[0])).isEmpty();

        // Another, after both: two settlements that could have counted it did not.
        String missed = UUID.randomUUID().toString();
        OperatorSettlementCapIT.copyFee(ids.getFirst(), missed);
        assertThat(of(who[0])).singleElement().satisfies(row -> assertRow(row,
            ReconChecks.OPERATOR_SETTLEMENT_COVERAGE, "fee:2029-02", "100", "0"));
        // The next settlement counts it, and the check is clean again.
        Map<String, Object> april = settle(who[0], "2029-04", "2029-05-02T00:00:00Z");
        assertThat(lineCharges(april.get("settlement_id"), "CHARGE")).containsExactly(missed);
        assertThat(of(who[0])).isEmpty();
    }

    @Test
    @Order(4)
    void aFeeOfAVoidedInvoiceIsNeverMissedAndAFeeOfALaterMonthIsNotDueYet() {
        String[] who = start(2030);
        fees(who[1], 1, 100, "2030-02-10T01:00:00Z");
        settle(who[0], "2030-02", "2030-03-02T00:00:00Z");
        settle(who[0], "2030-03", "2030-04-02T00:00:00Z");
        // A fee of April, charged after the settlements of February and March: not of their months.
        clock.set(Instant.parse("2030-04-10T01:00:00Z"));
        manualFee(who[1], 100);
        assertThat(of(who[0])).isEmpty();
        settle(who[0], "2030-04", "2030-05-02T00:00:00Z");
        // Voided before it was counted: no settlement counts it, and the check does not ask for it.
        clock.set(Instant.parse("2030-05-10T01:00:00Z"));
        String voided = String.valueOf(manualFee(who[1], 100));
        voidInvoice(invoiceOf(voided).get("invoice_id"));
        settle(who[0], "2030-05", "2030-06-02T00:00:00Z");
        settle(who[0], "2030-06", "2030-07-02T00:00:00Z");
        assertThat(query("SELECT 1 FROM qb_operator_settlement_line_version WHERE charge_id = ? AND line_kind = "
            + "'CHARGE'", voided)).isEmpty();
        assertThat(of(who[0])).isEmpty();
    }

    // ---- OPERATOR_SETTLEMENT_COVERAGE: parts of payments not counted ------------------------------------------------

    @Test
    @Order(5)
    void aPartOfAPaymentOnFeesOfNoPositiveTotalIsNeverCountedAndShowsAfterTwoSettlements() {
        String[] who = start(2031);
        param(QbParams.operatorGiftTreatment("MANUAL"), "OPERATOR");
        grant(who[1], 110, Instant.parse("2035-01-01T00:00:00Z"));
        clock.set(Instant.parse("2031-02-10T01:00:00Z"));
        String fee = String.valueOf(manualFee(who[1], 100));
        // The same fee with no amount, and a part of a payment on it (a payment cannot be cut over fees whose totals
        // add up to nothing: the settlement leaves it out rather than fail every day).
        String zero = UUID.randomUUID().toString();
        zeroFee(fee, zero);
        String part = UUID.randomUUID().toString();
        paymentOn(paymentOf(fee), part, zero, 50);

        Map<String, Object> february = settle(who[0], "2031-02", "2031-03-02T00:00:00Z");
        assertThat(lineCharges(february.get("settlement_id"), "CHARGE")).contains(fee, zero);
        assertThat(query("SELECT 1 FROM qb_operator_settlement_line_version WHERE line_kind = 'GIFT' AND "
            + "source_id = ?", part)).isEmpty();
        // The fee's own payment was counted. One settlement: one cycle is allowed.
        assertThat(query("SELECT 1 FROM qb_operator_settlement_line_version WHERE line_kind = 'GIFT' AND "
            + "charge_id = ?", fee)).hasSize(1);
        assertThat(of(who[0])).isEmpty();
        settle(who[0], "2031-03", "2031-04-02T00:00:00Z");
        assertThat(of(who[0])).singleElement().satisfies(row -> assertRow(row,
            ReconChecks.OPERATOR_SETTLEMENT_COVERAGE, "gift:2031-02", "50", "0"));
    }

    // ---- OPERATOR_CHARGE_NO_OPERATOR, OPERATOR_GIFT_CARRY_OPEN ------------------------------------------------------

    @Test
    @Order(6)
    void aFeeWithoutAnOperatorIsReportedByItsSponsor() {
        String[] who = start(2032);
        clock.set(Instant.parse("2032-02-10T01:00:00Z"));
        String fee = String.valueOf(manualFee(who[1], 100));
        // A fee with no operator (as the fees of before F-M11 are): no settlement would count it. It is reported by its
        // sponsor and period, with its amount.
        looseFee(fee, UUID.randomUUID().toString());
        assertThat(of(who[1])).singleElement().satisfies(row -> assertRow(row,
            ReconChecks.OPERATOR_CHARGE_NO_OPERATOR, "2032-02", "0", "100"));
        // A sponsor whose fees all have their operator's is not reported.
        String other = newSponsor(unique("fm12c-other"));
        assign(other, who[0]);
        cash(other, 5_000);
        manualFee(other, 100);
        assertThat(of(other)).isEmpty();
    }

    @Test
    @Order(7)
    void aSuspendedOperatorThatStillCarriesCreditIsANoteNotADifference() {
        String[] who = start(2033);
        param(QbParams.operatorGiftTreatment("MANUAL"), "OPERATOR");
        grant(who[1], 1_100, Instant.parse("2036-01-01T00:00:00Z"));
        manualFee(who[1], 1_000);
        Map<String, Object> february = settle(who[0], "2033-02", "2033-03-02T00:00:00Z");
        assertThat(n(february, "gift_carried_out")).isEqualByComparingTo("600");
        // Active: nothing to say.
        assertThat(of(who[0])).isEmpty();
        execute("UPDATE qb_operator SET status = 'SUSPENDED' WHERE operator_id = ?", who[0]);
        assertThat(of(who[0])).singleElement().satisfies(row -> assertRow(row,
            ReconChecks.OPERATOR_GIFT_CARRY_OPEN, null, "0", "600"));
        // A note: the run stays OK.
        assertThat(ReconChecks.result(ReconChecks.coded(of(who[0])), 10).status()).isEqualTo(ReconChecks.OK);
        // Nothing carried any more: nothing to say (the next month takes the 600 out of its share).
        execute("UPDATE qb_operator_settlement SET gift_carried_out = 0, gift_recovered = gift_recovered + 600 "
            + "WHERE operator_id = ? AND month = '2033-02'", who[0]);
        assertThat(of(who[0], ReconChecks.OPERATOR_GIFT_CARRY_OPEN)).isEmpty();
    }

    // ---- GIFT_RECOVERY at a rate of one half, with a refund that paid the cost carried ------------------------------

    @Test
    @Order(8)
    void theCreditTakenBackAtAHalfAndGivenBackAgainStaysConsistentThoughTheRefundPaidCostNotTheAccount() {
        String[] who = start(2034);
        param(QbParams.operatorGiftTreatment("MANUAL"), "OPERATOR");
        param(QbParams.OPERATOR_CREDIT_RECOVERY_RATE, "0.5");
        grant(who[1], 1_100, Instant.parse("2037-01-01T00:00:00Z"));
        String charge = String.valueOf(manualFee(who[1], 1_000));

        // February: share 500; 1,100 used, half of it due (550); 500 taken and 50 carried.
        Map<String, Object> february = settle(who[0], "2034-02", "2034-03-02T00:00:00Z");
        assertThat(n(february, "gift_used")).isEqualByComparingTo("1100");
        assertThat(n(february, "gift_due")).isEqualByComparingTo("550");
        assertThat(n(february, "gift_recovered")).isEqualByComparingTo("500");
        assertThat(n(february, "gift_carried_out")).isEqualByComparingTo("50");
        assertThat(of(who[0])).isEmpty();
        // The 500 credited to 5140 for the settlement is not a grant's: the promotion expense of the grants (5140 by the
        // operator, source the grants) does not read it, and the operator's account (2140) reads both postings of it.
        assertPromotionAndAccountAgree(who[0]);

        // March: the invoice is voided and the credit comes back: -550 due and 50 carried in is -500 given back, which
        // pays the 500 of cost carried (the voided fee's share), so 5140 gives back 500 and 2140 none of it (4110 has it).
        clock.set(Instant.parse("2034-03-10T01:00:00Z"));
        voidInvoice(invoiceOf(charge).get("invoice_id"));
        Map<String, Object> march = settle(who[0], "2034-03", "2034-04-02T00:00:00Z");
        assertThat(n(march, "gift_due")).isEqualByComparingTo("-550");
        assertThat(n(march, "gift_carried_in")).isEqualByComparingTo("50");
        assertThat(n(march, "gift_recovered")).isEqualByComparingTo("-500");
        assertThat(n(march, "cost_offset")).isEqualByComparingTo("500");
        // The two months took back and gave back the same: 5140 nets to 0 for the settlements; 2140 does not (its 500 from
        // February was not given back there: the refund paid cost, which is 4110's).
        assertThat(n(february, "gift_recovered").add(n(march, "gift_recovered"))).isEqualByComparingTo("0");
        BigDecimal net2140 = (BigDecimal) query("SELECT coalesce(sum(CASE WHEN e.direction = 'CREDIT' THEN e.amount "
            + "ELSE -e.amount END), 0) AS b FROM ledger_transaction_version t JOIN ledger_entry_version e ON "
            + "e.transaction_id = t.transaction_id JOIN ledger_account_version a ON a.account_id = e.account_id "
            + "WHERE t.source_entity = 'QbOperatorSettlement' AND a.account_code = '2140' AND t.reference LIKE 'opg_%' "
            + "AND t.source_id IN (SELECT CAST(settlement_id AS text) FROM qb_operator_settlement WHERE operator_id = ?)",
            who[0]).getFirst().get("b");
        assertThat(net2140).isEqualByComparingTo("-500");
        // The check compares 5140 (0) with the settlements' 0, and the months' flows: no difference.
        assertThat(of(who[0])).isEmpty();

        // The promotion expense of the grants counts only the grants' postings, and the operator's account both postings
        // of the settlements: neither is disturbed by the credit taken back.
        assertPromotionAndAccountAgree(who[0]);
    }
}
