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

    /** Review M-2: six voids with a cap of four: written over two settlements, one VOID line each, the same total. */
    @Test
    @Order(5)
    void voidsAboveTheCapAreWrittenOverTwoSettlementsOneLineEachAndTheDeductionTotalsTheSame() {
        String[] who = start(2047);
        setControlledParam(QbParams.OPERATOR_SHARE_KINDS, QbParams.defaultOf(QbParams.OPERATOR_SHARE_KINDS));
        List<String> ids = fees(who[1], 6, 100, "2047-02-10T01:00:00Z");
        settle(who[0], "2047-02", "2047-03-02T00:00:00Z");
        settle(who[0], "2047-03", "2047-04-02T00:00:00Z");
        clock.set(Instant.parse("2047-04-05T01:00:00Z"));
        for (String id : ids) {
            voidInvoice(invoiceOf(id).get("invoice_id"));
        }
        Map<String, Object> april = settle(who[0], "2047-04", "2047-05-02T00:00:00Z");
        Map<String, Object> may = settle(who[0], "2047-05", "2047-06-02T00:00:00Z");
        assertThat(lineCharges(april.get("settlement_id"), "VOID")).containsExactlyElementsOf(ids.subList(0, 4));
        assertThat(lineCharges(may.get("settlement_id"), "VOID")).containsExactlyElementsOf(ids.subList(4, 6));
        // The voids are read one more than the cap: "at least" this many are left (the count says so).
        assertThat(april).containsEntry("capped", true).containsEntry("uncounted", 1);
        assertThat(may).containsEntry("capped", false);
        // Each fee's share (100 at 50%: 50) is deducted once: 200 and 100, 300 as if all at once.
        assertThat(n(april, "void_deduction")).isEqualByComparingTo("200");
        assertThat(n(may, "void_deduction")).isEqualByComparingTo("100");
        assertThat(query("SELECT charge_id FROM qb_operator_settlement_line_version WHERE operator_id = ? AND "
            + "line_kind = 'VOID' GROUP BY charge_id HAVING count(*) > 1", who[0])).isEmpty();
    }

    /**
     * Review M-2: voids, then the credit used on fees counted before, then new fees compete for the cap in that order.
     * Cap 4: two voids and three parts of credit and three waiting fees: the first settlement writes the two voids and two
     * parts and no fee; the next the last part and the three fees.
     */
    @Test
    @Order(6)
    void voidsTakeTheCapFirstThenCreditUsedOnFeesCountedBeforeThenNewFees() {
        String[] who = start(2048);
        setControlledParam(QbParams.OPERATOR_SHARE_KINDS, QbParams.defaultOf(QbParams.OPERATOR_SHARE_KINDS));
        // Two fees to be voided (cash), then the fee that credit is used on later (10,000; the grant pays 10,500 of it).
        List<String> voided = fees(who[1], 2, 100, "2048-02-10T01:00:00Z");
        grant(who[1], 10_500, Instant.parse("2052-01-01T00:00:00Z"));
        String paid = fees(who[1], 1, 10_000, "2048-02-11T01:00:00Z").getFirst();
        settle(who[0], "2048-02", "2048-03-02T00:00:00Z");
        String template = String.valueOf(query("SELECT payment_id FROM qb_fee_payment_version WHERE target_id = ? AND "
            + "sub_account = 'FEE_CREDIT'", paid).getFirst().get("payment_id"));
        clock.set(Instant.parse("2048-03-05T01:00:00Z"));
        for (String id : voided) {
            voidInvoice(invoiceOf(id).get("invoice_id"));
        }
        execute("CREATE TABLE it_parts3 AS SELECT n, gen_random_uuid() AS pid FROM generate_series(1, 3) n");
        execute("INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) SELECT p.pid, r.entity_type, "
            + "r.created_seq_id FROM it_parts3 p, entity_registry r WHERE r.entity_id = ?::uuid", template);
        execute("INSERT INTO qb_fee_payment_version (payment_id, version_no, effect_start_time, created_time, "
            + "process_seq_id, sponsor_id, target_entity, target_id, sub_account, grant_id, cash_lot_id, amount, "
            + "paid_at, transaction_id, prepayment_id) SELECT p.pid, 1, t.effect_start_time, t.created_time, "
            + "t.process_seq_id, t.sponsor_id, t.target_entity, t.target_id, t.sub_account, t.grant_id, t.cash_lot_id, "
            + "1, TIMESTAMPTZ '2048-03-10T01:00:00Z' + p.n * interval '1 second', t.transaction_id, t.prepayment_id "
            + "FROM it_parts3 p JOIN qb_fee_payment_version t ON t.payment_id = ?::uuid", template);
        List<String> waiting = fees(who[1], 3, 100, "2048-03-12T01:00:00Z");
        assertThat(paid).isNotNull();

        Map<String, Object> march = settle(who[0], "2048-03", "2048-04-02T00:00:00Z");
        assertThat(lineCharges(march.get("settlement_id"), "VOID")).hasSize(2);
        assertThat(lineCharges(march.get("settlement_id"), "GIFT")).hasSize(2);
        assertThat(lineCharges(march.get("settlement_id"), "CHARGE")).isEmpty();
        assertThat(march).containsEntry("capped", true).containsEntry("uncounted", 4);
        Map<String, Object> april = settle(who[0], "2048-04", "2048-05-02T00:00:00Z");
        assertThat(lineCharges(april.get("settlement_id"), "VOID")).isEmpty();
        assertThat(lineCharges(april.get("settlement_id"), "GIFT")).hasSize(1);
        assertThat(lineCharges(april.get("settlement_id"), "CHARGE")).containsExactlyElementsOf(waiting);
        assertThat(april).containsEntry("capped", false);
    }
}
