package com.jabiz.quizbuks.it;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.FeeProcesses;
import com.jabiz.quizbuks.operator.OperatorSettlements;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.OperatorTransfers;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M12 part a, the review's findings (PR #687): an invoice's credit is cut over all its fees (H1), a credit note
 * takes back only the part of its fee that entered the base (M3), a refund reverses the treatment of the use whatever
 * the settings are now (M2), and the direct Sponsor's loop converges (§3.7). The tests that change a setting run last:
 * a setting takes effect at the clock's time, which the other tests' clocks precede.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OperatorSettlementReviewIT extends CreditNoteItSupport {

    static final String CHARGE_RELAY = "IT_QB_FEE_CHARGE_RELAY_REVIEW";

    /** A test-only root that charges a fee as its callers do (under the sponsor's wallet lock), with the cost part. */
    @TestConfiguration
    static class Processes {

        @Bean
        ProcessDefinition<FeeProcesses.ChargeInput, FeeProcesses.ChargeOutput, ProcessContext> itFeeChargeRelayReview() {
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

    private void switchOn() {
        if (query("SELECT 1 FROM sys_param_version WHERE param_key = ?", QbParams.FEE_TAX_RATE).isEmpty()) {
            param(QbParams.FEE_TAX_RATE, "0.1");
        }
    }

    private String hq() {
        return TestTokens.bearer(tokens, "fm12-review-hq", QbPermissions.OPERATORS_WRITE,
            QbPermissions.OPERATORS_READ);
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

    private Object chargeWithCostPart(String sponsor, long amount, long shareExcluded) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("sponsorId", sponsor);
        input.put("kind", "MANUAL");
        input.put("period", "2027-03");
        input.put("amount", amount);
        input.put("reason", "F-M12 cost part");
        input.put("sourceKey", "it-" + UUID.randomUUID());
        input.put("shareExcluded", shareExcluded);
        return ((FeeProcesses.ChargeOutput) systemRun(CHARGE_RELAY, input)).chargeId();
    }

    private Object monthInvoiceOf(String sponsor) {
        return query("SELECT invoice_id FROM qb_fee_invoice WHERE sponsor_id = ? AND charge_id IS NULL "
            + "ORDER BY issued_at DESC", sponsor).getFirst().get("invoice_id");
    }

    // ---- H1: an invoice's credit is cut over all its fees ----------------------------------------------------------------

    @Test
    void aMonthsInvoiceOfTwoOperatorsFeesGivesEachItsOwnShareOfTheCreditNotTheWhole() {
        switchOn();
        clock.set(Instant.parse("2027-03-10T01:00:00Z"));
        String operatorA = operator("Invoice A");
        String operatorB = operator("Invoice B");
        String sponsor = newSponsor(unique("fm12r-invoice"));
        assign(sponsor, operatorA);
        billing(sponsor, "POSTPAID", 100_000);
        // Two fees of the month on one invoice: the first under A, the sponsor then goes to B and is charged there.
        Object first = manualFee(sponsor, 1_000);
        assign(sponsor, operatorB);
        Object second = manualFee(sponsor, 1_000);
        grant(sponsor, 1_100, Instant.parse("2029-01-01T00:00:00Z"));
        cash(sponsor, 1_200);
        assertThat(invoiceTheMonth(sponsor)).isEqualTo("ISSUED");
        run(FeeProcesses.PAY, sponsorToken(sponsor), body("invoiceId", String.valueOf(monthInvoiceOf(sponsor))));

        Map<String, Object> a = settle(operatorA, "2027-03", "2027-04-02T00:00:00Z");
        Map<String, Object> b = settle(operatorB, "2027-03", "2027-04-02T00:00:00Z");
        // 1,100 of credit on 2,200 of fees: 550 on each, not 1,100 on each.
        assertThat(linesOf(a.get("settlement_id"), "GIFT")).singleElement().satisfies(line -> {
            assertThat(line).containsEntry("charge_id", String.valueOf(first));
            assertThat(n(line, "gift_gross")).isEqualByComparingTo("550");
            assertThat(n(line, "amount")).isEqualByComparingTo("500");
        });
        assertThat(linesOf(b.get("settlement_id"), "GIFT")).singleElement().satisfies(line -> {
            assertThat(line).containsEntry("charge_id", String.valueOf(second));
            assertThat(n(line, "gift_gross")).isEqualByComparingTo("550");
        });
    }

    @Test
    void anInvoiceOfFeesBeforeAndAfterTheSwitchPointCutsTheCreditOverBoth() {
        switchOn();
        clock.set(Instant.parse("2027-02-10T01:00:00Z"));
        String operatorId = operator("Switch invoice");
        String sponsor = newSponsor(unique("fm12r-switch"));
        assign(sponsor, operatorId);
        billing(sponsor, "POSTPAID", 100_000);
        manualFee(sponsor, 1_000);
        // The operator's last old settlement ends between the two fees.
        execute("INSERT INTO qb_operator_settlement (settlement_id, operator_id, month, period_start, period_end, "
            + "revenue, platform_share, operator_share, prospecting_cost, carried_in, payable, carried_out, status, "
            + "issued_at, revision, version) VALUES (gen_random_uuid()::text, ?, '2027-01', "
            + "'2027-01-01T00:00:00Z', '2027-02-15T00:00:00Z', 0, 0.5, 0, 0, 0, 0, 0, 'CREDITED', now(), 1, 0)",
            operatorId);
        clock.set(Instant.parse("2027-02-20T01:00:00Z"));
        Object after = manualFee(sponsor, 1_000);
        grant(sponsor, 1_100, Instant.parse("2029-01-01T00:00:00Z"));
        cash(sponsor, 1_200);
        clock.set(Instant.parse("2027-02-25T01:00:00Z"));
        assertThat(invoiceTheMonth(sponsor)).isEqualTo("ISSUED");
        clock.set(Instant.parse("2027-02-26T01:00:00Z"));
        run(FeeProcesses.PAY, sponsorToken(sponsor), body("invoiceId", String.valueOf(monthInvoiceOf(sponsor))));

        Map<String, Object> february = settle(operatorId, "2027-02", "2027-03-02T00:00:00Z");
        assertThat(linesOf(february.get("settlement_id"), "CHARGE")).singleElement()
            .satisfies(line -> assertThat(line).containsEntry("charge_id", String.valueOf(after)));
        // The fee counted gets its half of the credit (550), not all of it.
        assertThat(linesOf(february.get("settlement_id"), "GIFT")).singleElement().satisfies(line -> {
            assertThat(line).containsEntry("charge_id", String.valueOf(after));
            assertThat(n(line, "gift_gross")).isEqualByComparingTo("550");
        });
    }

    // ---- M3: a credit note takes back only the part that entered the base ------------------------------------------

    @Test
    void aCreditNoteOnAFeeWithACostPartTakesBackOnlyThePartInTheBaseAtTheRatesOfItsLine() {
        switchOn();
        clock.set(Instant.parse("2027-03-10T01:00:00Z"));
        String operatorId = operator("Note cost");
        String sponsor = newSponsor(unique("fm12r-notecost"));
        assign(sponsor, operatorId);
        cash(sponsor, 5_000);
        Object chargeId = chargeWithCostPart(sponsor, 1_500, 1_000);
        Map<String, Object> march = settle(operatorId, "2027-03", "2027-04-02T00:00:00Z");
        assertThat(n(march, "operator_share")).isEqualByComparingTo("250");

        clock.set(Instant.parse("2027-04-10T01:00:00Z"));
        creditNote(chargeId, 1_500);
        Map<String, Object> april = settle(operatorId, "2027-04", "2027-05-02T00:00:00Z");
        assertThat(n(april, "credit_notes")).isEqualByComparingTo("1500");
        // 500 of the note's 1,500 entered the base: half of it, 250 (not half of 1,500).
        assertThat(n(april, "credit_note_deduction")).isEqualByComparingTo("250");
    }

    // ---- §3.7: the direct Sponsor's loop ---------------------------------------------------------------------------------

    @Test
    void theOperatorsMoneyPaidBackAsItsDirectSponsorsFeesConvergesWithoutError() {
        switchOn();
        param(QbParams.OPERATOR_WITHDRAWAL_MIN, "1");
        clock.set(Instant.parse("2027-03-10T01:00:00Z"));
        NewOperator op = newOperatorWithLead(hq(), unique("Loop"), null);
        String direct = op.leadId();
        String sponsor = newSponsor(unique("fm12r-loop"));
        assign(sponsor, op.operatorId());
        cash(sponsor, 20_000);
        manualFee(sponsor, 10_000);

        BigDecimal previous = null;
        long fee = 0;
        String[] months = {"2027-03", "2027-04", "2027-05", "2027-06"};
        for (int i = 0; i < months.length; i++) {
            String settleAt = "2027-%02d-02T00:00:00Z".formatted(4 + i);
            Map<String, Object> settled = settle(op.operatorId(), months[i], settleAt);
            BigDecimal payable = n(settled, "payable");
            assertThat(settled).containsEntry("status", OperatorSettlements.CREDITED);
            if (previous != null) {
                assertThat(payable).as("the loop shrinks: " + months[i]).isLessThan(previous);
            }
            previous = payable;
            if (payable.signum() == 0) {
                break;
            }
            // The operator moves all of it to its direct Sponsor, who pays a fee with it (the cash is the platform's
            // to keep), and the fee enters the next month's base.
            clock.set(Instant.parse("2027-%02d-05T01:00:00Z".formatted(4 + i)));
            String key = UUID.randomUUID().toString();
            client.post().uri("/api/processes/" + OperatorTransfers.TRANSFER + "/latest")
                .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, memberSession(op.leadId(), op.operatorId()))
                .header("Idempotency-Key", key).bodyValue(body("amount", payable.longValueExact(), "requestKey", key))
                .exchange().expectStatus().isOk();
            fee = payable.multiply(BigDecimal.valueOf(10)).divide(BigDecimal.valueOf(11), 0,
                java.math.RoundingMode.DOWN).longValueExact();
            manualFee(direct, fee);
            assertThat(query("SELECT 1 FROM qb_fee_charge_version WHERE sponsor_id = ? AND amount = ?", direct, fee))
                .isNotEmpty();
        }
        assertThat(fee).isPositive();
        assertBooksBalance();
    }

    // ---- M2: a refund reverses the use whatever the settings are now (last: a setting changes) ---------------------------

    @Test
    @Order(Integer.MAX_VALUE - 1)
    void aRefundReversesTheUseExactlyAlthoughTheTreatmentWasChangedBetween() {
        switchOn();
        clock.set(Instant.parse("2027-03-09T00:00:00Z"));
        param(QbParams.operatorGiftTreatment("MANUAL"), "OPERATOR");
        clock.set(Instant.parse("2027-03-10T01:00:00Z"));
        String operatorId = operator("Treatment M2");
        String sponsor = newSponsor(unique("fm12r-treat"));
        assign(sponsor, operatorId);
        grant(sponsor, 1_100, Instant.parse("2029-01-01T00:00:00Z"));
        Object chargeId = manualFee(sponsor, 1_000);
        Map<String, Object> march = settle(operatorId, "2027-03", "2027-04-02T00:00:00Z");
        assertThat(linesOf(march.get("settlement_id"), "GIFT")).singleElement()
            .satisfies(line -> assertThat(line).containsEntry("no_share", false));

        // The settings change (the operator no longer bears MANUAL credit) before the credit comes back.
        clock.set(Instant.parse("2027-04-05T00:00:00Z"));
        param(QbParams.operatorGiftTreatment("MANUAL"), "PLATFORM");
        clock.set(Instant.parse("2027-04-10T01:00:00Z"));
        creditNote(chargeId, 1_000);
        Map<String, Object> april = settle(operatorId, "2027-04", "2027-05-02T00:00:00Z");
        // The refund is treated as the use was: shared, taken back (reversing exactly), not "borne by the platform".
        assertThat(linesOf(april.get("settlement_id"), "GIFT")).singleElement().satisfies(line -> {
            assertThat(line).containsEntry("no_share", false);
            assertThat(n(line, "recover_gross")).isEqualByComparingTo("-1100");
        });
        assertThat(n(april, "gift_due")).isEqualByComparingTo("-1100");
        assertThat(n(april, "gift_share_deduction")).isEqualByComparingTo("0");
    }

    // ---- M3: a fee of a kind outside the list: nothing taken back by a note (last: a setting changes) -----------------

    @Test
    @Order(Integer.MAX_VALUE)
    void aCreditNoteOnAFeeOfAKindOutsideTheListTakesBackNothing() {
        switchOn();
        clock.set(Instant.parse("2027-03-09T00:00:00Z"));
        param(QbParams.OPERATOR_SHARE_KINDS, "MONTHLY");
        clock.set(Instant.parse("2027-03-10T01:00:00Z"));
        String operatorId = operator("Note outside");
        String sponsor = newSponsor(unique("fm12r-noteout"));
        assign(sponsor, operatorId);
        cash(sponsor, 5_000);
        Object chargeId = manualFee(sponsor, 1_000);
        Map<String, Object> march = settle(operatorId, "2027-03", "2027-04-02T00:00:00Z");
        assertThat(n(march, "operator_share")).isEqualByComparingTo("0");

        clock.set(Instant.parse("2027-04-10T01:00:00Z"));
        creditNote(chargeId, 1_000);
        Map<String, Object> april = settle(operatorId, "2027-04", "2027-05-02T00:00:00Z");
        assertThat(n(april, "credit_notes")).isEqualByComparingTo("1000");
        assertThat(n(april, "credit_note_deduction")).isEqualByComparingTo("0");
        assertThat(n(april, "carried_out")).isEqualByComparingTo("0");
    }
}
