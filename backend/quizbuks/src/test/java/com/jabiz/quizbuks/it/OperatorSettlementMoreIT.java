package com.jabiz.quizbuks.it;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.FeeCodes;
import com.jabiz.quizbuks.fee.FeeProcesses;
import com.jabiz.quizbuks.fee.FeeRequests;
import com.jabiz.quizbuks.fee.QbFees;
import com.jabiz.quizbuks.operator.OperatorPlans;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.OperatorSettlements;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F-M12 part a, the cases of docs/quizbuks/plans/impl/F-M12.md section 7 that need a month's run each: a voided invoice
 * (M1), a plan changed between the fee and its correction (M2), a credit note giving back credit used (M3), two runs
 * at once (M4), a sponsor reassigned (§3.6), a refund larger than the use, an inactive operator (§3.8), the cost part
 * of a fee kept out of the base (H3) and a fee counted a month late (§3.2). Granted credit is the MANUAL source, borne
 * by the operator here (B): its fee is shared, and the credit used is taken back from the share.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OperatorSettlementMoreIT extends CreditNoteItSupport {

    static final String CHARGE_RELAY = "IT_QB_FEE_CHARGE_RELAY";

    /** A test-only root that charges a fee as its callers do (under the sponsor's wallet lock), with the cost part. */
    @TestConfiguration
    static class Processes {

        @Bean
        ProcessDefinition<FeeProcesses.ChargeInput, FeeProcesses.ChargeOutput, ProcessContext> itFeeChargeRelay() {
            return ProcessDefinition.define(CHARGE_RELAY, 1, FeeProcesses.ChargeInput.class,
                FeeProcesses.ChargeOutput.class, ProcessContext.class, pb -> pb
                    .permissions(QbPermissions.FEE_SYSTEM)
                    .internal()
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put("in", input);
                        return ctx;
                    })
                    .outputMapper(ctx -> ctx.get("out", FeeProcesses.ChargeOutput.class))
                    .step("Lock the wallet", HoldLock.<ProcessContext>exclusive(ctx -> QbWallets.lockName(
                        QbWallets.SPONSOR, ctx.get("in", FeeProcesses.ChargeInput.class).sponsorId())))
                    .step("Charge", CallProcess.<ProcessContext>of(FeeProcesses.CHARGE, 1, ctx -> ctx.get("in"),
                        "out")));
        }
    }

    private static boolean switched;

    /** The tax rate, and the MANUAL credit borne by the operator (B); once for the class (its schema is its own). */
    private void switchOn() {
        if (!switched) {
            param(QbParams.FEE_TAX_RATE, "0.1");
            param(QbParams.operatorGiftTreatment("MANUAL"), "OPERATOR");
            switched = true;
        }
    }

    private String hq() {
        return TestTokens.bearer(tokens, "fm12-more-hq", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ);
    }

    private String operator(String name) {
        return newOperator(hq(), unique(name));
    }

    private void assign(String sponsor, String operatorId) {
        run(OperatorProcesses.ASSIGN, hq(), body("userId", sponsor, "operatorId", operatorId, "reason", "F-M12"));
    }

    private Map<String, Object> settle(String operatorId, String month, String at) {
        clock.set(Instant.parse(at));
        systemRun(OperatorSettlements.RUN, body("operatorId", operatorId, "month", month));
        return settlement(operatorId, month);
    }

    private static Map<String, Object> settlement(String operatorId, String month) {
        return query("SELECT * FROM qb_operator_settlement WHERE operator_id = ? AND month = ?", operatorId, month)
            .getFirst();
    }

    private static List<Map<String, Object>> linesOf(Object settlementId, String kind) {
        return query("SELECT * FROM qb_operator_settlement_line_version WHERE settlement_id = ? AND line_kind = ? "
            + "ORDER BY occurred_at, source_id", String.valueOf(settlementId), kind);
    }

    private static BigDecimal n(Map<String, Object> row, String column) {
        return (BigDecimal) row.get(column);
    }

    private static List<String> postings(Object settlementId, String prefix, String month, String operatorId) {
        String reference = prefix + month + "_" + operatorId.substring(0, 8);
        return query("SELECT a.account_code, e.direction, e.amount FROM ledger_transaction_version t "
            + "JOIN ledger_entry_version e ON e.transaction_id = t.transaction_id "
            + "JOIN ledger_account_version a ON a.account_id = e.account_id "
            + "WHERE t.source_entity = 'QbOperatorSettlement' AND t.source_id = ? AND t.reference = ? "
            + "ORDER BY e.line_no", String.valueOf(settlementId), reference).stream()
            .map(p -> p.get("account_code") + " " + p.get("direction") + " "
                + ((BigDecimal) p.get("amount")).stripTrailingZeros().toPlainString()).toList();
    }

    private static BigDecimal sum(List<Map<String, Object>> rows, String column) {
        return rows.stream().map(r -> (BigDecimal) r.get(column)).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** A sponsor of a new operator, a fee of 1,000 (1,100 with tax) paid in full by granted credit in March 2027. */
    private record Paid(String operatorId, String sponsor, Object chargeId) {}

    private Paid feePaidByCredit(String name) {
        switchOn();
        clock.set(Instant.parse("2027-03-10T01:00:00Z"));
        String operatorId = operator(name);
        String sponsor = newSponsor(unique("fm12m-" + name.toLowerCase().replace(' ', '-')));
        assign(sponsor, operatorId);
        grant(sponsor, 1_100, Instant.parse("2029-01-01T00:00:00Z"));
        Object chargeId = manualFee(sponsor, 1_000);
        assertThat(query("SELECT 1 FROM qb_fee_payment_version WHERE sub_account = 'FEE_CREDIT' AND target_id = ?",
            String.valueOf(chargeId))).isNotEmpty();
        return new Paid(operatorId, sponsor, chargeId);
    }

    // ---- M1: a voided invoice -------------------------------------------------------------------------------------

    @Test
    void aFeeCountedThenVoidedGetsAVoidLineACostThatIsCarriedAndTheCreditGivenBackReversesTheTakeBack() {
        Paid paid = feePaidByCredit("Void M1");
        Map<String, Object> march = settle(paid.operatorId(), "2027-03", "2027-04-02T00:00:00Z");
        assertThat(n(march, "operator_share")).isEqualByComparingTo("500");
        assertThat(n(march, "gift_carried_out")).isEqualByComparingTo("600");

        // April: finance voids the invoice (four eyes); the credit it was paid with is given back.
        clock.set(Instant.parse("2027-04-10T01:00:00Z"));
        voidInvoice(invoiceOf(paid.chargeId()).get("invoice_id"));
        Map<String, Object> april = settle(paid.operatorId(), "2027-04", "2027-05-02T00:00:00Z");
        String aprilId = String.valueOf(april.get("settlement_id"));

        // A VOID line at the rates the fee was counted at: a signed cost, not a negative base.
        assertThat(linesOf(aprilId, "VOID")).singleElement().satisfies(line -> {
            assertThat(line).containsEntry("charge_id", String.valueOf(paid.chargeId()));
            assertThat(n(line, "amount")).isEqualByComparingTo("-1000");
            assertThat(n(line, "share_rate")).isEqualByComparingTo("0.5");
        });
        assertThat(n(april, "platform_fees")).isEqualByComparingTo("0");
        assertThat(n(april, "operator_share")).isEqualByComparingTo("0");
        assertThat(n(april, "void_deduction")).isEqualByComparingTo("500");
        // Nothing earned in April: the cost is carried, and the credit given back pays it (net within the month).
        // The credit given back reverses the take-back: -1,100 due, 600 carried in, 500 paid back.
        assertThat(sum(linesOf(aprilId, "GIFT"), "recover_gross")).isEqualByComparingTo("-1100");
        assertThat(n(april, "gift_due")).isEqualByComparingTo("-1100");
        assertThat(n(april, "gift_recovered")).isEqualByComparingTo("-500");
        assertThat(n(april, "gift_carried_out")).isEqualByComparingTo("0");
        // The 500 given back first pays the 500 of cost carried: nothing is payable and nothing is carried on.
        assertThat(n(april, "carried_out")).isEqualByComparingTo("0");
        assertThat(n(april, "payable")).isEqualByComparingTo("0");
        // L3: the 500 that paid the cost carried is kept on the settlement (it is the 4110 line below); March had none.
        assertThat(n(april, "cost_offset")).isEqualByComparingTo("500");
        assertThat(n(march, "cost_offset")).isEqualByComparingTo("0");
        // Over both months the operator was taken 500 + 0 and given back 500: the fee never happened.
        assertThat(n(march, "gift_recovered").add(n(april, "gift_recovered"))).isEqualByComparingTo("0");
        // 5140 returned 500; none of it is paid out (it paid the cost): recovered as 4110.
        assertThat(postings(aprilId, "opg_", "2027-04", paid.operatorId())).containsExactly("5140 DEBIT 500",
            "4110 CREDIT 500");
        assertBooksBalance();

        // Counted once: a later run (May) writes no second VOID line.
        Map<String, Object> may = settle(paid.operatorId(), "2027-05", "2027-06-02T00:00:00Z");
        assertThat(linesOf(String.valueOf(may.get("settlement_id")), "VOID")).isEmpty();
    }

    // ---- M2: a plan changed between the fee and its correction ------------------------------------------------------

    @Test
    void aVoidInALaterMonthIsTakenAtTheRateTheFeeWasCountedAtNotThePlanThatMonth() {
        switchOn();
        clock.set(Instant.parse("2027-02-10T01:00:00Z"));
        String operatorId = operator("Plan M2");
        String sponsor = newSponsor(unique("fm12m-plan"));
        assign(sponsor, operatorId);
        // Plan 3 (the platform keeps 30%) from March with four eyes.
        Map<String, Object> proposed = run(OperatorPlans.SET, hq(), body("operatorId", operatorId, "plan", "3",
            "effectiveMonth", "2027-03", "reason", "Signed for plan 3"));
        run("APPROVAL_DECIDE", content(), body("requestId", proposed.get("approvalRequestId"), "decision", "APPROVE"));
        deliverEvents();
        cash(sponsor, 5_000);
        Object chargeId = manualFee(sponsor, 1_000);

        // February at 50%: 500 to the operator.
        Map<String, Object> february = settle(operatorId, "2027-02", "2027-03-02T00:00:00Z");
        assertThat(n(february, "platform_share")).isEqualByComparingTo("0.5");
        assertThat(n(february, "operator_share")).isEqualByComparingTo("500");

        // March at 30%: the February fee's invoice is voided; the 500 comes back at February's rate (not 700).
        clock.set(Instant.parse("2027-03-10T01:00:00Z"));
        voidInvoice(invoiceOf(chargeId).get("invoice_id"));
        Map<String, Object> march = settle(operatorId, "2027-03", "2027-04-02T00:00:00Z");
        assertThat(n(march, "platform_share")).isEqualByComparingTo("0.3");
        assertThat(linesOf(march.get("settlement_id"), "VOID")).singleElement().satisfies(line -> {
            assertThat(n(line, "share_rate")).isEqualByComparingTo("0.5");
            assertThat(n(line, "amount")).isEqualByComparingTo("-1000");
        });
        assertThat(n(march, "void_deduction")).isEqualByComparingTo("500");
    }

    // ---- M3: a credit note giving the credit back ---------------------------------------------------------------------

    @Test
    void aCreditNoteGivingCreditBackNetsToZeroAndAnInvoiceWithANoteIsNotVoided() {
        Paid paid = feePaidByCredit("Note M3");
        Map<String, Object> march = settle(paid.operatorId(), "2027-03", "2027-04-02T00:00:00Z");
        assertThat(n(march, "gift_carried_out")).isEqualByComparingTo("600");

        // April: a note of 1,000 returns the credit (a negative FEE_CREDIT part aimed at the note).
        clock.set(Instant.parse("2027-04-10T01:00:00Z"));
        creditNote(paid.chargeId(), 1_000);
        Object noteId = notes(paid.sponsor()).getFirst().get("credit_note_id");
        assertThat(query("SELECT amount FROM qb_fee_payment_version WHERE target_entity = 'QbFeeCreditNote' AND "
            + "target_id = ? AND sub_account = 'FEE_CREDIT'", String.valueOf(noteId))).singleElement()
            .satisfies(part -> assertThat(n(part, "amount")).isEqualByComparingTo("-1100"));
        Map<String, Object> april = settle(paid.operatorId(), "2027-04", "2027-05-02T00:00:00Z");
        String aprilId = String.valueOf(april.get("settlement_id"));

        // The part reaches the fee through the note's charge: a GIFT line of the fee, negative.
        assertThat(linesOf(aprilId, "GIFT")).singleElement().satisfies(line -> {
            assertThat(line).containsEntry("charge_id", String.valueOf(paid.chargeId()));
            assertThat(n(line, "gift_gross")).isEqualByComparingTo("-1100");
            assertThat(n(line, "recover_gross")).isEqualByComparingTo("-1100");
        });
        // Three items net to zero: the note's deduction (500), the credit given back (-1,100 less the 600 carried in)
        // paid back (500) and nothing else.
        assertThat(n(april, "credit_note_deduction")).isEqualByComparingTo("500");
        assertThat(n(april, "gift_recovered")).isEqualByComparingTo("-500");
        // The refund pays the note's cost first: nothing payable, nothing carried.
        assertThat(n(april, "payable")).isEqualByComparingTo("0");
        assertThat(n(april, "carried_out")).isEqualByComparingTo("0");
        assertThat(n(march, "gift_recovered").add(n(april, "gift_recovered"))).isEqualByComparingTo("0");
        assertThat(linesOf(aprilId, "VOID")).isEmpty();

        // An invoice with a note is not voided, and no VOID line is ever written for it.
        assertThat(codes(refused(FeeRequests.RECORD, finance(), body("kind", QbFees.REQUEST_VOID, "invoiceId",
            String.valueOf(invoiceOf(paid.chargeId()).get("invoice_id")), "reason", "Charged by mistake"), 422)))
            .containsExactly(FeeCodes.INVOICE_HAS_CREDIT_NOTE);
        Map<String, Object> may = settle(paid.operatorId(), "2027-05", "2027-06-02T00:00:00Z");
        assertThat(linesOf(may.get("settlement_id"), "VOID")).isEmpty();
    }

    // ---- M4: two runs at once ----------------------------------------------------------------------------------------

    @Test
    void twoRunsOfTheSameMonthAtOnceMakeOneSettlementAndTheIndexRejectsAFeeCountedTwice() {
        switchOn();
        clock.set(Instant.parse("2027-03-10T01:00:00Z"));
        String operatorId = operator("Race M4");
        String sponsor = newSponsor(unique("fm12m-race"));
        assign(sponsor, operatorId);
        cash(sponsor, 3_000);
        manualFee(sponsor, 1_000);
        clock.set(Instant.parse("2027-04-02T00:00:00Z"));

        CompletableFuture<Object> first = CompletableFuture.supplyAsync(() -> systemRun(OperatorSettlements.RUN,
            body("operatorId", operatorId, "month", "2027-03")));
        CompletableFuture<Object> second = CompletableFuture.supplyAsync(() -> systemRun(OperatorSettlements.RUN,
            body("operatorId", operatorId, "month", "2027-03")));
        first.join();
        second.join();
        assertThat(query("SELECT 1 FROM qb_operator_settlement WHERE operator_id = ? AND month = '2027-03'",
            operatorId)).hasSize(1);
        Map<String, Object> settled = settlement(operatorId, "2027-03");
        assertThat(linesOf(settled.get("settlement_id"), "CHARGE")).hasSize(1);

        // A rerun is a no-op.
        systemRun(OperatorSettlements.RUN, body("operatorId", operatorId, "month", "2027-03"));
        assertThat(query("SELECT 1 FROM qb_operator_settlement_line_version WHERE operator_id = ?", operatorId))
            .hasSize(1);

        // The index itself: a second CHARGE line for the same fee (a new line, so only (kind, source, charge) clashes).
        Object lineId = linesOf(settled.get("settlement_id"), "CHARGE").getFirst().get("line_id");
        String copy = UUID.randomUUID().toString();
        execute("INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) SELECT ?::uuid, entity_type, "
            + "created_seq_id FROM entity_registry WHERE entity_id = ?::uuid", copy, String.valueOf(lineId));
        String columns = "version_no, effect_start_time, created_time, process_seq_id, settlement_id, operator_id, "
            + "sponsor_id, month, line_kind, source_id, charge_id, fee_kind, period, late, occurred_at, amount, "
            + "excluded, share_rate, recovery_rate";
        assertThatThrownBy(() -> execute("INSERT INTO qb_operator_settlement_line_version (line_id, " + columns
            + ") SELECT ?::uuid, " + columns + " FROM qb_operator_settlement_line_version WHERE line_id = ?::uuid",
            copy, String.valueOf(lineId))).hasStackTraceContaining("qb_operator_settlement_line_version_source_idx");
    }

    // ---- more lines than one write batch --------------------------------------------------------------------------

    /** A settlement writes one line per fee: more than the platform's default batch of 100 is one settlement still. */
    @Test
    void aMonthOfMoreFeesThanTheDefaultWriteBatchIsSettledInOne() {
        switchOn();
        clock.set(Instant.parse("2027-03-10T01:00:00Z"));
        String operatorId = operator("Many lines");
        String sponsor = newSponsor(unique("fm12m-many"));
        assign(sponsor, operatorId);
        cash(sponsor, 200_000);
        for (int i = 0; i < 120; i++) {
            manualFee(sponsor, 100);
        }
        Map<String, Object> march = settle(operatorId, "2027-03", "2027-04-02T00:00:00Z");
        assertThat(linesOf(march.get("settlement_id"), "CHARGE")).hasSize(120);
        assertThat(new java.math.BigDecimal(String.valueOf(march.get("platform_fees")))).isEqualByComparingTo("12000");
    }

    // ---- §3.6: a sponsor reassigned ------------------------------------------------------------------------------------

    @Test
    void aSponsorReassignedKeepsTheFeeAndItsCreditTakeBackWithTheOperatorItWasChargedUnder() {
        Paid paid = feePaidByCredit("Reassign A");
        String operatorB = operator("Reassign B");
        // The fee and its credit were charged under A; the sponsor then goes to B and pays another fee there.
        assign(paid.sponsor(), operatorB);
        cash(paid.sponsor(), 2_000);
        Object chargeB = manualFee(paid.sponsor(), 500);

        Map<String, Object> a = settle(paid.operatorId(), "2027-03", "2027-04-02T00:00:00Z");
        Map<String, Object> b = settle(operatorB, "2027-03", "2027-04-02T00:00:00Z");
        assertThat(linesOf(a.get("settlement_id"), "CHARGE")).singleElement()
            .satisfies(line -> assertThat(line).containsEntry("charge_id", String.valueOf(paid.chargeId())));
        assertThat(linesOf(a.get("settlement_id"), "GIFT")).hasSize(1);
        assertThat(n(a, "gift_used")).isEqualByComparingTo("1100");
        assertThat(linesOf(b.get("settlement_id"), "CHARGE")).singleElement()
            .satisfies(line -> assertThat(line).containsEntry("charge_id", String.valueOf(chargeB)));
        assertThat(linesOf(b.get("settlement_id"), "GIFT")).isEmpty();
        assertThat(n(b, "gift_used")).isEqualByComparingTo("0");
        assertThat(n(b, "platform_fees")).isEqualByComparingTo("500");
    }

    // ---- §3.8: a refund larger than the use, an inactive operator ------------------------------------------------------

    @Test
    void aRefundLargerThanTheMonthsUseIsReturnedToTheOperatorWithItsPostingEvenWhenTheShareIsZeroAndInactive() {
        Paid paid = feePaidByCredit("Refund zero");
        settle(paid.operatorId(), "2027-03", "2027-04-02T00:00:00Z");
        clock.set(Instant.parse("2027-04-10T01:00:00Z"));
        creditNote(paid.chargeId(), 1_000);
        // The operator's contract rests: it is still settled.
        execute("UPDATE qb_operator SET status = 'SUSPENDED' WHERE operator_id = ?", paid.operatorId());
        Map<String, Object> april = settle(paid.operatorId(), "2027-04", "2027-05-02T00:00:00Z");
        String id = String.valueOf(april.get("settlement_id"));
        assertThat(n(april, "operator_share")).isEqualByComparingTo("0");
        assertThat(n(april, "gift_used")).isEqualByComparingTo("-1100");
        assertThat(n(april, "gift_recovered")).isNegative();
        assertThat(n(april, "gift_carried_out")).isEqualByComparingTo("0");
        // Returned to the operator's account: more than the month's use, and posted although the share is 0.
        assertThat(postings(id, "opg_", "2027-04", paid.operatorId())).containsExactly("5140 DEBIT 500",
            "4110 CREDIT 500");
        assertThat(postings(id, "op_", "2027-04", paid.operatorId())).isEmpty();
        assertThat(n(april, "payable")).isEqualByComparingTo("0");
        assertThat(n(april, "carried_out")).isEqualByComparingTo("0");
        assertThat(n(april, "fixed_fee")).isEqualByComparingTo("0");
    }

    // ---- H3: the cost part, through the real charge ----------------------------------------------------------------------

    private Object chargeWithCostPart(String sponsor, long amount, long shareExcluded) {
        Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("sponsorId", sponsor);
        input.put("kind", "MANUAL");
        input.put("period", "2027-03");
        input.put("amount", amount);
        input.put("reason", "F-M12 cost part");
        input.put("sourceKey", "it-" + UUID.randomUUID());
        input.put("shareExcluded", shareExcluded);
        return ((FeeProcesses.ChargeOutput) systemRun(CHARGE_RELAY, input)).chargeId();
    }

    @Test
    void theCostPartOfAFeeStaysOutOfTheBaseAndAKindOutsideTheListIsAllExcluded() {
        switchOn();
        clock.set(Instant.parse("2027-03-10T01:00:00Z"));
        String operatorId = operator("Cost H3");
        String sponsor = newSponsor(unique("fm12m-cost"));
        assign(sponsor, operatorId);
        cash(sponsor, 5_000);
        Object chargeId = chargeWithCostPart(sponsor, 1_500, 1_000);
        assertThat(query("SELECT share_excluded FROM qb_fee_charge_version WHERE charge_id = ?::uuid",
            String.valueOf(chargeId))).singleElement()
            .satisfies(row -> assertThat(n(row, "share_excluded")).isEqualByComparingTo("1000"));

        // MANUAL is in the list: the base is 1,500 less the cost part 1,000; half of 500 is 250.
        Map<String, Object> settled = settle(operatorId, "2027-03", "2027-04-02T00:00:00Z");
        assertThat(n(settled, "platform_fees")).isEqualByComparingTo("1500");
        assertThat(n(settled, "cost_excluded")).isEqualByComparingTo("1000");
        assertThat(n(settled, "operator_share")).isEqualByComparingTo("250");
        assertThat(linesOf(settled.get("settlement_id"), "CHARGE")).singleElement()
            .satisfies(line -> assertThat(n(line, "excluded")).isEqualByComparingTo("1000"));
    }

    /**
     * With MANUAL out of the list (as an AI fee is out of the default one): the line is written, all of it excluded.
     * Last in the class: the parameter takes effect at the clock's time, which the other tests' clocks precede.
     */
    @Test
    @Order(Integer.MAX_VALUE)
    void aKindOutsideTheListIsWrittenWithAllOfItExcluded() {
        switchOn();
        clock.set(Instant.parse("2027-03-10T01:00:00Z"));
        String other = operator("Cost H3 outside");
        String sponsor2 = newSponsor(unique("fm12m-cost2"));
        assign(sponsor2, other);
        cash(sponsor2, 5_000);
        param(QbParams.OPERATOR_SHARE_KINDS, "MONTHLY");
        clock.set(Instant.parse("2027-03-11T01:00:00Z"));
        Object outside = chargeWithCostPart(sponsor2, 1_500, 1_000);
        Map<String, Object> out = settle(other, "2027-03", "2027-04-02T00:00:00Z");
        assertThat(linesOf(out.get("settlement_id"), "CHARGE")).singleElement().satisfies(line -> {
            assertThat(line).containsEntry("charge_id", String.valueOf(outside));
            assertThat(n(line, "excluded")).isEqualByComparingTo("1500");
        });
        assertThat(n(out, "cost_excluded")).isEqualByComparingTo("1500");
        assertThat(n(out, "operator_share")).isEqualByComparingTo("0");
    }

    // ---- §3.2: a fee counted a month late ----------------------------------------------------------------------------------

    @Test
    void aFeeOfAnEarlierMonthChargedAfterTheSettlementIsCountedOnceMarkedLateAndNotRepeated() {
        switchOn();
        clock.set(Instant.parse("2027-02-10T01:00:00Z"));
        String operatorId = operator("Late");
        String sponsor = newSponsor(unique("fm12m-late"));
        assign(sponsor, operatorId);
        cash(sponsor, 5_000);
        manualFee(sponsor, 1_000);
        settle(operatorId, "2027-02", "2027-03-02T00:00:00Z");

        // March 5th: a fee of February is charged (as a final distribution fee is).
        clock.set(Instant.parse("2027-03-05T01:00:00Z"));
        Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("sponsorId", sponsor);
        input.put("kind", "MANUAL");
        input.put("period", "2027-02");
        input.put("amount", 400);
        input.put("reason", "Late fee");
        input.put("sourceKey", "it-late-" + UUID.randomUUID());
        Object lateCharge = ((FeeProcesses.ChargeOutput) systemRun(CHARGE_RELAY, input)).chargeId();

        Map<String, Object> march = settle(operatorId, "2027-03", "2027-04-02T00:00:00Z");
        assertThat(linesOf(march.get("settlement_id"), "CHARGE")).singleElement().satisfies(line -> {
            assertThat(line).containsEntry("charge_id", String.valueOf(lateCharge)).containsEntry("period", "2027-02")
                .containsEntry("late", true);
            assertThat(n(line, "share_rate")).isEqualByComparingTo("0.5");
        });
        assertThat(n(march, "platform_fees")).isEqualByComparingTo("400");
        assertThat(n(march, "operator_share")).isEqualByComparingTo("200");

        Map<String, Object> april = settle(operatorId, "2027-04", "2027-05-02T00:00:00Z");
        assertThat(linesOf(april.get("settlement_id"), "CHARGE")).isEmpty();
        assertThat(n(april, "platform_fees")).isEqualByComparingTo("0");
        assertThat(query("SELECT 1 FROM qb_operator_settlement_line_version WHERE charge_id = ?",
            String.valueOf(lateCharge))).hasSize(1);
    }
}
