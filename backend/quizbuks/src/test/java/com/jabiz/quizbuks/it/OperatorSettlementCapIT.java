package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.operator.OperatorPlans;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.OperatorSettlements;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M12 b (docs/quizbuks/plans/impl/F-M12.md §3.9, option B): a settlement counts at most the cap's lines (here 4: fees,
 * voids and credit used together): the voids first, then the oldest fees that fit with their credit-used lines. What is
 * left is counted by the next settlement at the rates and settings of the month it was left in; the settlement says it
 * was capped. Each test has a year of its own (parameters change forward in time).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "quizbuks.operator-settlement.max-lines=4")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OperatorSettlementCapIT extends CapItSupport {

    // ---- over the cap, at the original rates, a backlog-only month --------------------------------------------------

    @Test
    @Order(1)
    void feesBeyondTheCapAreCountedNextMonthAtTheRatesOfTheMonthTheyWereLeftIn() {
        String[] who = start(2027);
        // Plan 3 (the platform keeps 30%) from March: February's rate is 50%.
        Map<String, Object> proposed = run(OperatorPlans.SET, hq(), body("operatorId", who[0], "plan", "3",
            "effectiveMonth", "2027-03", "reason", "Signed for plan 3"));
        run("APPROVAL_DECIDE", content(), body("requestId", proposed.get("approvalRequestId"), "decision", "APPROVE"));
        deliverEvents();
        List<String> ids = fees(who[1], 6, 100, "2027-02-10T01:00:00Z");

        Map<String, Object> february = settle(who[0], "2027-02", "2027-03-02T00:00:00Z");
        // The four oldest; the last of them is the watermark; two are left.
        assertThat(lineCharges(february.get("settlement_id"), "CHARGE")).containsExactlyElementsOf(ids.subList(0, 4));
        assertThat(february).containsEntry("capped", true).containsEntry("uncounted", 2)
            .containsEntry("watermark_charge_id", ids.get(3));
        assertThat(february.get("watermark_charged_at")).isNotNull();
        assertThat(february.get("capped_terms")).isNotNull();
        assertThat(n(february, "platform_fees")).isEqualByComparingTo("400");
        assertThat(n(february, "operator_share")).isEqualByComparingTo("200");

        // March has no fee of its own: still a settlement, which counts the two left, at February's 50%, not March's 70%.
        Map<String, Object> march = settle(who[0], "2027-03", "2027-04-02T00:00:00Z");
        assertThat(n(march, "platform_share")).isEqualByComparingTo("0.3");
        assertThat(lineCharges(march.get("settlement_id"), "CHARGE")).containsExactlyElementsOf(ids.subList(4, 6));
        assertThat(march).containsEntry("capped", false).containsEntry("uncounted", 0);
        assertThat(n(march, "platform_fees")).isEqualByComparingTo("200");
        assertThat(n(march, "operator_share")).isEqualByComparingTo("100");
        assertThat(query("SELECT share_rate, late FROM qb_operator_settlement_line_version WHERE settlement_id = ? "
            + "AND line_kind = 'CHARGE'", march.get("settlement_id"))).hasSize(2).allSatisfy(line -> {
                assertThat(n(line, "share_rate")).isEqualByComparingTo("0.5");
                assertThat(line).containsEntry("late", true);
            });
        // Counted once: April counts nothing more.
        Map<String, Object> april = settle(who[0], "2027-04", "2027-05-02T00:00:00Z");
        assertThat(lineCharges(april.get("settlement_id"), "CHARGE")).isEmpty();
        assertThat(query("SELECT 1 FROM qb_operator_settlement_line_version WHERE operator_id = ? AND line_kind = "
            + "'CHARGE'", who[0])).hasSize(6);
        // No task: capped once, the fees left are not old.
        assertThat(capTasks(OperatorSettlements.CAPPED_TASK + ":" + who[0] + ":2027-02")).isEmpty();
    }

    // ---- the credit used on a fee counted is never cut --------------------------------------------------------------

    @Test
    @Order(2)
    void theCreditUsedOnTheFeesCountedIsNotCutAndAFeeAndItsCreditUsedCountTogetherOrNotAtAll() {
        String[] who = start(2028);
        // 110 each with tax; three fees of credit: each fee brings its fee line and its credit line (2 of the cap's 4).
        grant(who[1], 330, Instant.parse("2031-01-01T00:00:00Z"));
        List<String> ids = fees(who[1], 3, 100, "2028-02-10T01:00:00Z");

        Map<String, Object> february = settle(who[0], "2028-02", "2028-03-02T00:00:00Z");
        assertThat(lineCharges(february.get("settlement_id"), "CHARGE")).containsExactlyElementsOf(ids.subList(0, 2));
        assertThat(lineCharges(february.get("settlement_id"), "GIFT")).containsExactlyElementsOf(ids.subList(0, 2));
        assertThat(february).containsEntry("capped", true).containsEntry("uncounted", 1);

        // The third with its credit used next month.
        Map<String, Object> march = settle(who[0], "2028-03", "2028-04-02T00:00:00Z");
        assertThat(lineCharges(march.get("settlement_id"), "CHARGE")).containsExactly(ids.get(2));
        assertThat(lineCharges(march.get("settlement_id"), "GIFT")).containsExactly(ids.get(2));
        assertThat(march).containsEntry("capped", false);
    }

    // ---- voids first ------------------------------------------------------------------------------------------------

    @Test
    @Order(3)
    void voidsGoFirstAndTheFeesTakeWhatIsLeftOfTheCap() {
        String[] who = start(2029);
        String voided = fees(who[1], 1, 100, "2029-02-10T01:00:00Z").getFirst();
        settle(who[0], "2029-02", "2029-03-02T00:00:00Z");
        clock.set(Instant.parse("2029-03-05T01:00:00Z"));
        voidInvoice(invoiceOf(voided).get("invoice_id"));
        List<String> ids = fees(who[1], 6, 100, "2029-03-10T01:00:00Z");

        Map<String, Object> march = settle(who[0], "2029-03", "2029-04-02T00:00:00Z");
        assertThat(lineCharges(march.get("settlement_id"), "VOID")).containsExactly(voided);
        // One line of the cap is the void: three fees.
        assertThat(lineCharges(march.get("settlement_id"), "CHARGE")).containsExactlyElementsOf(ids.subList(0, 3));
        assertThat(march).containsEntry("capped", true).containsEntry("uncounted", 3);
    }

    // ---- a fee voided before it is counted -------------------------------------------------------------------------

    @Test
    @Order(4)
    void aFeeVoidedBeforeItIsCountedNeverAppearsAndHasNoVoidLine() {
        String[] who = start(2030);
        List<String> ids = fees(who[1], 6, 100, "2030-02-10T01:00:00Z");
        Map<String, Object> february = settle(who[0], "2030-02", "2030-03-02T00:00:00Z");
        assertThat(february).containsEntry("uncounted", 2);

        // The last of the two left is voided (its invoice) before anything counts it.
        clock.set(Instant.parse("2030-03-05T01:00:00Z"));
        voidInvoice(invoiceOf(ids.get(5)).get("invoice_id"));
        Map<String, Object> march = settle(who[0], "2030-03", "2030-04-02T00:00:00Z");
        assertThat(lineCharges(march.get("settlement_id"), "CHARGE")).containsExactly(ids.get(4));
        assertThat(lineCharges(march.get("settlement_id"), "VOID")).isEmpty();
        assertThat(march).containsEntry("capped", false).containsEntry("uncounted", 0);
        Map<String, Object> april = settle(who[0], "2030-04", "2030-05-02T00:00:00Z");
        assertThat(lineCharges(april.get("settlement_id"), "VOID")).isEmpty();
        assertThat(query("SELECT 1 FROM qb_operator_settlement_line_version WHERE charge_id = ?", ids.get(5)))
            .isEmpty();
    }

    // ---- the task -----------------------------------------------------------------------------------------------------

    @Test
    @Order(5)
    void financeIsAskedWhenTwoMonthsRunningAreCappedAndNotBefore() {
        String[] who = start(2031);
        fees(who[1], 6, 100, "2031-02-10T01:00:00Z");
        Map<String, Object> february = settle(who[0], "2031-02", "2031-03-02T00:00:00Z");
        assertThat(february).containsEntry("capped", true);
        String first = OperatorSettlements.CAPPED_TASK + ":" + who[0] + ":2031-02";
        assertThat(capTasks(first)).isEmpty();

        // March: five of its own and two left: seven fees for four lines: capped again.
        fees(who[1], 5, 100, "2031-03-10T01:00:00Z");
        Map<String, Object> march = settle(who[0], "2031-03", "2031-04-02T00:00:00Z");
        assertThat(march).containsEntry("capped", true).containsEntry("uncounted", 3);
        String second = OperatorSettlements.CAPPED_TASK + ":" + who[0] + ":2031-03";
        assertThat(capTasks(second)).hasSize(1);
        assertThat(query("SELECT assignee_permission FROM sys_task_version WHERE source_key = ?", second).getFirst())
            .containsEntry("assignee_permission", QbPermissions.RECONCILE);
    }

    @Test
    @Order(6)
    void financeIsAskedWhenAFeeLeftIsOlderThanAMonth() {
        String[] who = start(2032);
        fees(who[1], 6, 100, "2032-02-10T01:00:00Z");
        // The month is settled late (nothing ran for it): the fees left are more than a month old.
        Map<String, Object> february = settle(who[0], "2032-02", "2032-03-20T00:00:00Z");
        assertThat(february).containsEntry("capped", true).containsEntry("uncounted", 2);
        assertThat(capTasks(OperatorSettlements.CAPPED_TASK + ":" + who[0] + ":2032-02")).hasSize(1);
    }

    // ---- the order --------------------------------------------------------------------------------------------------

    /** A copy of a fee under a given id (the registry row too), as if charged at the same instant. */
    static void copyFee(String template, String id) {
        execute("INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) SELECT ?::uuid, entity_type, "
            + "created_seq_id FROM entity_registry WHERE entity_id = ?::uuid", id, template);
        execute("INSERT INTO qb_fee_charge_version (charge_id, version_no, effect_start_time, created_time, "
            + "process_seq_id, sponsor_id, operator_id, billing_mode, kind, period, amount, tax_rate, tax, total, "
            + "reason, source_key, charged_at, charged_by, share_excluded) SELECT ?::uuid, version_no, "
            + "effect_start_time, created_time, process_seq_id, sponsor_id, operator_id, billing_mode, kind, period, "
            + "amount, tax_rate, tax, total, reason, NULL, charged_at, charged_by, share_excluded "
            + "FROM qb_fee_charge_version WHERE charge_id = ?::uuid", id, template);
    }

    /**
     * Review M2: fees charged at the same instant, with ids that sort in no order of their creation (hyphens, digits and
     * letters at different places), are taken in the order the database numbers them (COLLATE "C") and the code sorts them
     * (string order): the watermark is the fourth, and the two after it are the ones the template says a capped settlement
     * left (originMonth), the first four not.
     */
    @Test
    @Order(7)
    void feesChargedAtTheSameInstantAreTakenInTheOrderOfTheirIdsAndTheWatermarkSeparatesThem() {
        String[] who = start(2033);
        clock.set(Instant.parse("2033-02-10T01:00:00Z"));
        String template = String.valueOf(manualFee(who[1], 100));
        List<String> ids = new ArrayList<>(List.of(template));
        for (String id : List.of("ffffffff-0000-4000-8000-000000000001", "00000000-ffff-4000-8000-000000000002",
            "0000000a-0000-4000-8000-00000000000a", "00000009-ffff-4000-8000-000000000003",
            "a0000000-0000-4000-8000-000000000004")) {
            copyFee(template, id);
            ids.add(id);
        }
        List<String> sorted = ids.stream().sorted().toList();
        Map<String, Object> february = settle(who[0], "2033-02", "2033-03-02T00:00:00Z");
        assertThat(query("SELECT DISTINCT charged_at FROM qb_fee_charge_version WHERE sponsor_id = ? AND kind = "
            + "'MANUAL'", who[1])).hasSize(1);
        assertThat(lineCharges(february.get("settlement_id"), "CHARGE")).containsExactlyInAnyOrderElementsOf(
            sorted.subList(0, 4));
        assertThat(february).containsEntry("watermark_charge_id", sorted.get(3)).containsEntry("uncounted", 2);

        // The template (as March reads it) says which fees the February settlement left: those after its watermark.
        List<Map<String, Object>> waiting = items(OperatorSettlements.CHARGES_TEMPLATE, TestTokens.bearer(tokens,
            "fm12b-cap-system", QbPermissions.OPERATOR_SETTLEMENT_SYSTEM), Map.of("params", Map.of("operatorId",
            who[0], "coveredFrom", "1970-01-01T00:00:00Z", "month", "2033-03", "maxFees", 100), "limit", 100));
        assertThat(waiting).extracting(row -> String.valueOf(row.get("chargeId")))
            .containsExactlyInAnyOrderElementsOf(sorted.subList(4, 6));
        assertThat(waiting).allSatisfy(row -> assertThat(row).containsEntry("originMonth", "2033-02"));
        Map<String, Object> march = settle(who[0], "2033-03", "2033-04-02T00:00:00Z");
        assertThat(lineCharges(march.get("settlement_id"), "CHARGE")).containsExactlyInAnyOrderElementsOf(
            sorted.subList(4, 6));
    }

    // ---- a credit note on a fee left -------------------------------------------------------------------------------

    /**
     * Review L5, option (b): a note on a fee not counted yet is taken at the rates the fee will be counted at (the
     * capped settlement's), in the month the note was issued, once; when the fee's line arrives later nothing more is
     * deducted. It nets as if the fee had been counted in its own month: the same as a twin operator's whose fee was.
     */
    @Test
    @Order(8)
    void aCreditNoteOnAFeeLeftIsTakenAtTheRatesTheFeeIsCountedAtAndNetsAsIfCountedInItsMonth() {
        String[] capped = start(2034);
        String[] twin = another(2034);
        for (String[] who : List.of(capped, twin)) {
            Map<String, Object> proposed = run(OperatorPlans.SET, hq(), body("operatorId", who[0], "plan", "3",
                "effectiveMonth", "2034-03", "reason", "Signed for plan 3"));
            run("APPROVAL_DECIDE", content(), body("requestId", proposed.get("approvalRequestId"), "decision",
                "APPROVE"));
            deliverEvents();
        }
        List<String> ids = fees(capped[1], 5, 1_000, "2034-02-10T01:00:00Z");
        String twinFee = fees(twin[1], 1, 1_000, "2034-02-10T01:00:00Z").getFirst();
        settle(capped[0], "2034-02", "2034-03-02T00:00:00Z");
        Map<String, Object> twinFebruary = settle(twin[0], "2034-02", "2034-03-02T00:00:00Z");
        assertThat(n(twinFebruary, "operator_share")).isEqualByComparingTo("500");

        // March: a note of 400 on the fee left (the twin's fee, counted already, gets the same).
        clock.set(Instant.parse("2034-03-05T01:00:00Z"));
        creditNote(ids.get(4), 400);
        creditNote(twinFee, 400);
        Map<String, Object> cappedMarch = settle(capped[0], "2034-03", "2034-04-02T00:00:00Z");
        Map<String, Object> twinMarch = settle(twin[0], "2034-03", "2034-04-02T00:00:00Z");

        // The fee is counted at February's 50% (500), its note taken at 50% too (200), not at March's 70% (280).
        assertThat(lineCharges(cappedMarch.get("settlement_id"), "CHARGE")).containsExactly(ids.get(4));
        assertThat(n(cappedMarch, "platform_share")).isEqualByComparingTo("0.3");
        assertThat(n(cappedMarch, "operator_share")).isEqualByComparingTo(n(twinFebruary, "operator_share"));
        assertThat(n(cappedMarch, "credit_notes")).isEqualByComparingTo("400");
        assertThat(n(cappedMarch, "credit_note_deduction")).isEqualByComparingTo("200");
        assertThat(n(cappedMarch, "credit_note_deduction")).isEqualByComparingTo(n(twinMarch, "credit_note_deduction"));
        assertThat(n(cappedMarch, "operator_share").subtract(n(cappedMarch, "credit_note_deduction")))
            .isEqualByComparingTo(n(twinFebruary, "operator_share").subtract(n(twinMarch, "credit_note_deduction")));
        // Once: April deducts nothing more for it.
        Map<String, Object> april = settle(capped[0], "2034-04", "2034-05-02T00:00:00Z");
        assertThat(n(april, "credit_note_deduction")).isEqualByComparingTo("0");
    }
}
