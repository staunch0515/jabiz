package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.fee.FeeProcesses;
import com.jabiz.quizbuks.operator.OperatorPlans;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.OperatorSettlements;
import com.jabiz.quizbuks.setup.QbParams;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M12 b, the rest of the cap's cases (review M1; plan §3.9 condition 7), cap 4: three months capped in a row, credit
 * given back on a fee left, an invoice paid with credit cut in the middle (two operators), that the line table gets no
 * UPDATE or DELETE, and two runs at once and a rerun across the cap.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "quizbuks.operator-settlement.max-lines=4")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OperatorSettlementCapMoreIT extends CapItSupport {

    private void plan(String operatorId, String plan, String month) {
        Map<String, Object> proposed = run(OperatorPlans.SET, hq(), body("operatorId", operatorId, "plan", plan,
            "effectiveMonth", month, "reason", "F-M12 b"));
        run("APPROVAL_DECIDE", content(), body("requestId", proposed.get("approvalRequestId"), "decision", "APPROVE"));
        deliverEvents();
    }

    @Test
    @Order(1)
    void aFeeCountedInTheThirdMonthOfCappingStillUsesTheRatesAndSettingsOfTheFirst() {
        String[] who = start(2040);
        plan(who[0], "3", "2040-03");
        plan(who[0], "5", "2040-04");
        List<String> ids = fees(who[1], 13, 100, "2040-02-10T01:00:00Z");

        Map<String, Object> february = settle(who[0], "2040-02", "2040-03-02T00:00:00Z");
        assertThat(february).containsEntry("capped", true).containsEntry("uncounted", 9);
        // From March on MANUAL fees are no part of the base: a fee counted at March's settings would be all cost.
        clock.set(Instant.parse("2040-03-03T00:00:00Z"));
        setControlledParam(QbParams.OPERATOR_SHARE_KINDS, "MONTHLY,AUDIENCE");
        Map<String, Object> march = settle(who[0], "2040-03", "2040-04-02T00:00:00Z");
        Map<String, Object> april = settle(who[0], "2040-04", "2040-05-02T00:00:00Z");
        assertThat(march).containsEntry("capped", true).containsEntry("uncounted", 5);
        assertThat(april).containsEntry("capped", true).containsEntry("uncounted", 1);
        // Left by February, March's and April's fees counted at February's: 50% share, in the base.
        assertThat(query("SELECT share_rate, excluded, amount FROM qb_operator_settlement_line_version WHERE "
            + "settlement_id = ? AND line_kind = 'CHARGE'", march.get("settlement_id"))).hasSize(4)
            .allSatisfy(line -> {
                assertThat(n(line, "share_rate")).isEqualByComparingTo("0.5");
                assertThat(n(line, "excluded")).isEqualByComparingTo("0");
            });
        // May: the last one, in the third month after, still at February's rates and settings.
        Map<String, Object> may = settle(who[0], "2040-05", "2040-06-02T00:00:00Z");
        assertThat(lineCharges(may.get("settlement_id"), "CHARGE")).containsExactly(ids.get(12));
        assertThat(query("SELECT share_rate, excluded FROM qb_operator_settlement_line_version WHERE settlement_id = ?"
            + " AND line_kind = 'CHARGE'", may.get("settlement_id"))).singleElement().satisfies(line -> {
                assertThat(n(line, "share_rate")).isEqualByComparingTo("0.5");
                assertThat(n(line, "excluded")).isEqualByComparingTo("0");
            });
        assertThat(n(may, "platform_share")).isEqualByComparingTo("0.1");
        assertThat(may).containsEntry("capped", false);
        // The line table (append-only) never saw an UPDATE or DELETE.
        DistributionIT.noUpdatesOrDeletes("qb_operator_settlement_line_version");
        assertThat(query("SELECT coalesce(n_tup_del, 0) AS d FROM pg_stat_user_tables WHERE schemaname = "
            + "current_schema() AND relname = 'qb_operator_settlement'").getFirst().get("d")).isEqualTo(0L);
    }

    @Test
    @Order(2)
    void creditGivenBackOnAFeeLeftIsCountedWithTheFeeOnceEach() {
        String[] who = start(2041);
        // Four fees of cash, then one paid with the credit (so that it is the one left), then a note giving it back.
        fees(who[1], 4, 100, "2041-02-10T01:00:00Z");
        grant(who[1], 1_000, Instant.parse("2045-01-01T00:00:00Z"));
        String left = fees(who[1], 1, 100, "2041-02-11T01:00:00Z").getFirst();
        assertThat(query("SELECT 1 FROM qb_fee_payment_version WHERE sub_account = 'FEE_CREDIT' AND target_id = ?",
            left)).isNotEmpty();
        Map<String, Object> february = settle(who[0], "2041-02", "2041-03-02T00:00:00Z");
        assertThat(february).containsEntry("uncounted", 1);
        clock.set(Instant.parse("2041-03-05T01:00:00Z"));
        creditNote(left, 100);
        Map<String, Object> march = settle(who[0], "2041-03", "2041-04-02T00:00:00Z");
        assertThat(lineCharges(march.get("settlement_id"), "CHARGE")).containsExactly(left);
        // The credit used and the credit given back are each one line of the fee, and no (payment, fee) has two.
        assertThat(query("SELECT source_id, charge_id, gift_gross FROM qb_operator_settlement_line_version WHERE "
            + "operator_id = ? AND line_kind = 'GIFT' AND charge_id = ?", who[0], left)).hasSize(2)
            .anySatisfy(line -> assertThat(n(line, "gift_gross")).isPositive())
            .anySatisfy(line -> assertThat(n(line, "gift_gross")).isNegative());
        assertThat(query("SELECT source_id, charge_id FROM qb_operator_settlement_line_version WHERE operator_id = ? "
            + "AND line_kind = 'GIFT' GROUP BY source_id, charge_id HAVING count(*) > 1", who[0])).isEmpty();
    }

    @Test
    @Order(3)
    void anInvoicePaidWithCreditOfTwoOperatorsIsCutInTheMiddleAndEveryPartCountsOnce() {
        String[] x = start(2042);
        String[] y = another(2042);
        String sponsor = x[1];
        billing(sponsor, "POSTPAID", 100_000);
        // Three fees under X, then the sponsor goes to Y and two more are charged under Y: one month, one invoice.
        List<String> feesX = fees(sponsor, 3, 100, "2042-02-10T01:00:00Z");
        run(OperatorProcesses.ASSIGN, hq(), body("userId", sponsor, "operatorId", y[0], "reason", "F-M12 b"));
        List<String> feesY = fees(sponsor, 2, 100, "2042-02-12T01:00:00Z");
        clock.set(Instant.parse("2042-03-01T05:00:00Z"));
        assertThat(((FeeProcesses.ItemOutput) systemRun(FeeProcesses.INVOICE_ITEM, body("sponsorId", sponsor,
            "period", "2042-02"))).outcome()).isEqualTo("ISSUED");
        // The sponsor then has credit and pays the invoice: the credit first, one part for the invoice.
        grant(sponsor, 10_000, Instant.parse("2046-01-01T00:00:00Z"));
        Object invoiceId = query("SELECT invoice_id FROM qb_fee_invoice WHERE sponsor_id = ? AND charge_id IS NULL",
            sponsor).getFirst().get("invoice_id");
        run(FeeProcesses.PAY, sponsorToken(sponsor), body("invoiceId", String.valueOf(invoiceId)));
        List<Map<String, Object>> payments = query("SELECT payment_id, amount FROM qb_fee_payment_version WHERE "
            + "sub_account = 'FEE_CREDIT' AND sponsor_id = ?", sponsor);
        assertThat(payments).as("the invoice was paid with credit").isNotEmpty();
        BigDecimal paid = payments.stream().map(p -> n(p, "amount")).reduce(BigDecimal.ZERO, BigDecimal::add);

        // X: three fees with a credit-used line each: 6 lines for a cap of 4: the third fee is cut off.
        Map<String, Object> xFebruary = settle(x[0], "2042-02", "2042-03-02T00:00:00Z");
        Map<String, Object> yFebruary = settle(y[0], "2042-02", "2042-03-02T01:00:00Z");
        assertThat(lineCharges(xFebruary.get("settlement_id"), "CHARGE")).hasSize(2);
        assertThat(lineCharges(xFebruary.get("settlement_id"), "GIFT")).hasSize(2);
        assertThat(xFebruary).containsEntry("capped", true).containsEntry("uncounted", 1);
        assertThat(lineCharges(yFebruary.get("settlement_id"), "CHARGE")).hasSize(2);
        Map<String, Object> xMarch = settle(x[0], "2042-03", "2042-04-02T00:00:00Z");
        assertThat(lineCharges(xMarch.get("settlement_id"), "CHARGE")).hasSize(1);

        // Every (payment, fee) has exactly one line, over both operators and both months, and the cut sums to what was paid.
        assertThat(query("SELECT source_id, charge_id FROM qb_operator_settlement_line_version WHERE line_kind = "
            + "'GIFT' GROUP BY source_id, charge_id HAVING count(*) > 1")).isEmpty();
        assertThat(query("SELECT count(*) AS n FROM qb_operator_settlement_line_version WHERE line_kind = 'GIFT' AND "
            + "charge_id IN (?, ?, ?)", feesX.get(0), feesX.get(1), feesX.get(2)).getFirst().get("n")).isEqualTo(3L * payments.size());
        BigDecimal cut = query("SELECT gift_gross FROM qb_operator_settlement_line_version WHERE line_kind = 'GIFT' "
            + "AND operator_id IN (?, ?)", x[0], y[0]).stream().map(l -> n(l, "gift_gross"))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(cut).isEqualByComparingTo(paid);
        assertThat(feesY).hasSize(2);
    }

    @Test
    @Order(4)
    void twoRunsAtOnceAndARerunAcrossTheCapCountEachFeeOnce() throws Exception {
        String[] who = start(2043);
        List<String> ids = fees(who[1], 6, 100, "2043-02-10T01:00:00Z");
        clock.set(Instant.parse("2043-03-02T00:00:00Z"));
        List<CompletableFuture<Object>> runs = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            runs.add(CompletableFuture.supplyAsync(() -> systemRun(OperatorSettlements.RUN, body("operatorId", who[0],
                "month", "2043-02"))));
        }
        for (CompletableFuture<Object> run : runs) {
            run.get();
        }
        assertThat(query("SELECT 1 FROM qb_operator_settlement WHERE operator_id = ? AND month = '2043-02'", who[0]))
            .hasSize(1);
        Map<String, Object> february = settlement(who[0], "2043-02");
        assertThat(lineCharges(february.get("settlement_id"), "CHARGE")).containsExactlyElementsOf(ids.subList(0, 4));
        // A rerun of the same month changes nothing; the next month, run twice at once, counts the two left once.
        systemRun(OperatorSettlements.RUN, body("operatorId", who[0], "month", "2043-02"));
        assertThat(query("SELECT 1 FROM qb_operator_settlement_line_version WHERE operator_id = ?", who[0]))
            .hasSize(4);
        clock.set(Instant.parse("2043-04-02T00:00:00Z"));
        List<CompletableFuture<Object>> next = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            next.add(CompletableFuture.supplyAsync(() -> systemRun(OperatorSettlements.RUN, body("operatorId", who[0],
                "month", "2043-03"))));
        }
        for (CompletableFuture<Object> run : next) {
            run.get();
        }
        assertThat(query("SELECT charge_id FROM qb_operator_settlement_line_version WHERE operator_id = ? AND "
            + "line_kind = 'CHARGE'", who[0])).hasSize(6);
        assertThat(query("SELECT 1 FROM qb_operator_settlement WHERE operator_id = ?", who[0])).hasSize(2);
    }
}
