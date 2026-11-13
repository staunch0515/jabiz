package com.jabiz.quizbuks.it;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import com.jabiz.quizbuks.setup.QbParams;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M12 b, review H1: the lines no cap cuts (the credit used on fees counted before) are bounded by the cap too. Many
 * parts of payments on a fee counted before (more than the write limit of the line dataset, 100) do not make the settlement
 * fail for ever: the oldest fit in what the cap leaves and the rest are counted by the next settlements, each once.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "quizbuks.operator-settlement.max-lines=40")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OperatorSettlementCapLimitsIT extends CapItSupport {

    @Test
    @Order(1)
    void manyNewPartsOfCreditUsedOnAFeeCountedBeforeAreTakenWithinTheCapAMonthAtATime() {
        String[] who = start(2044);
        grant(who[1], 10_500, Instant.parse("2048-01-01T00:00:00Z"));
        // A fee of 10,000 (11,000 with tax) paid 10,500 with credit; counted in February with its credit used.
        String fee = fees(who[1], 1, 10_000, "2044-02-10T01:00:00Z").getFirst();
        Map<String, Object> february = settle(who[0], "2044-02", "2044-03-02T00:00:00Z");
        assertThat(lineCharges(february.get("settlement_id"), "GIFT")).hasSize(1);

        // 129 more parts of 1 yen of credit on it, paid in March (after the settlement): more than the write limit of 100.
        String template = String.valueOf(query("SELECT payment_id FROM qb_fee_payment_version WHERE target_id = ? AND "
            + "sub_account = 'FEE_CREDIT'", fee).getFirst().get("payment_id"));
        execute("CREATE TABLE it_parts AS SELECT n, gen_random_uuid() AS pid FROM generate_series(1, 129) n");
        execute("INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) SELECT p.pid, r.entity_type, "
            + "r.created_seq_id FROM it_parts p, entity_registry r WHERE r.entity_id = ?::uuid", template);
        execute("INSERT INTO qb_fee_payment_version (payment_id, version_no, effect_start_time, created_time, "
            + "process_seq_id, sponsor_id, target_entity, target_id, sub_account, grant_id, cash_lot_id, amount, "
            + "paid_at, transaction_id, prepayment_id) SELECT p.pid, 1, t.effect_start_time, t.created_time, "
            + "t.process_seq_id, t.sponsor_id, t.target_entity, t.target_id, t.sub_account, t.grant_id, t.cash_lot_id, "
            + "1, TIMESTAMPTZ '2044-03-10T01:00:00Z' + p.n * interval '1 second', t.transaction_id, t.prepayment_id "
            + "FROM it_parts p JOIN qb_fee_payment_version t ON t.payment_id = ?::uuid", template);

        // Each month takes what the cap (40) lets it: 40, 40, 40, 9.
        int[] expected = {40, 40, 40, 9};
        int total = 0;
        for (int i = 0; i < expected.length; i++) {
            String month = "2044-0" + (3 + i);
            Map<String, Object> settled = settle(who[0], month, "2044-0" + (4 + i) + "-02T00:00:00Z");
            List<Object> gifts = lineCharges(settled.get("settlement_id"), "GIFT");
            assertThat(gifts).as(month).hasSize(expected[i]);
            assertThat(settled).containsEntry("capped", i < 3);
            total += gifts.size();
        }
        assertThat(total).isEqualTo(129);
        // Each part once.
        assertThat(query("SELECT source_id, charge_id FROM qb_operator_settlement_line_version WHERE line_kind = "
            + "'GIFT' GROUP BY source_id, charge_id HAVING count(*) > 1")).isEmpty();
        assertThat(query("SELECT 1 FROM qb_operator_settlement_line_version WHERE line_kind = 'GIFT'")).hasSize(130);
    }

    /**
     * Review M-1: the parts of a payment that pays a fee counted before AND a waiting fee are bounded by the cap too (they
     * used to be written whatever their number, over the write limit of the line dataset). An invoice of a counted fee and
     * a waiting fee is paid by 130 parts of credit: each part is a line of each fee. The settlement does not fail, writes
     * at most the cap's lines, and the counted fee's lines are written 40 a month; the waiting fee, whose own credit-used
     * lines are more than the cap, waits (the head of the line, said in the plan) and finance is asked.
     */
    @Test
    @Order(2)
    void anInvoiceOfACountedAndAWaitingFeePaidInManyPartsDoesNotMakeTheSettlementFail() {
        String[] who = start(2046);
        // The month's invoice is issued after the first settlement here, so the settlement must not wait for it.
        param(QbParams.OPERATOR_SETTLEMENT_READINESS_MAX_DAYS, "0");
        billing(who[1], "POSTPAID", 100_000);
        String counted = fees(who[1], 1, 10_000, "2046-02-10T01:00:00Z").getFirst();
        settle(who[0], "2046-02", "2046-03-02T00:00:00Z");
        // A second fee of the same month and sponsor, charged after the settlement; the month's invoice covers both.
        String waiting = "0f000000-0000-4000-8000-000000000001";
        OperatorSettlementCapIT.copyFee(counted, waiting);
        clock.set(Instant.parse("2046-03-06T01:00:00Z"));
        systemRun(com.jabiz.quizbuks.fee.FeeProcesses.INVOICE_ITEM, body("sponsorId", who[1], "period", "2046-02"));
        grant(who[1], 5_000, Instant.parse("2050-01-01T00:00:00Z"));
        Object invoiceId = query("SELECT invoice_id FROM qb_fee_invoice WHERE sponsor_id = ? AND charge_id IS NULL",
            who[1]).getFirst().get("invoice_id");
        run(com.jabiz.quizbuks.fee.FeeProcesses.PAY, sponsorToken(who[1]), body("invoiceId", String.valueOf(invoiceId)));
        String template = String.valueOf(query("SELECT payment_id FROM qb_fee_payment_version WHERE target_id = ? AND "
            + "sub_account = 'FEE_CREDIT'", String.valueOf(invoiceId)).getFirst().get("payment_id"));
        execute("CREATE TABLE it_parts2 AS SELECT n, gen_random_uuid() AS pid FROM generate_series(1, 129) n");
        execute("INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) SELECT p.pid, r.entity_type, "
            + "r.created_seq_id FROM it_parts2 p, entity_registry r WHERE r.entity_id = ?::uuid", template);
        execute("INSERT INTO qb_fee_payment_version (payment_id, version_no, effect_start_time, created_time, "
            + "process_seq_id, sponsor_id, target_entity, target_id, sub_account, grant_id, cash_lot_id, amount, "
            + "paid_at, transaction_id, prepayment_id) SELECT p.pid, 1, t.effect_start_time, t.created_time, "
            + "t.process_seq_id, t.sponsor_id, t.target_entity, t.target_id, t.sub_account, t.grant_id, t.cash_lot_id, "
            + "1, t.paid_at + p.n * interval '1 second', t.transaction_id, t.prepayment_id "
            + "FROM it_parts2 p JOIN qb_fee_payment_version t ON t.payment_id = ?::uuid", template);

        int counted130 = 0;
        for (int i = 0; i < 4; i++) {
            Map<String, Object> settled = settle(who[0], "2046-0" + (3 + i), "2046-0" + (4 + i) + "-02T00:00:00Z");
            long lines = ((Number) query("SELECT count(*) AS n FROM qb_operator_settlement_line_version WHERE "
                + "settlement_id = ?", settled.get("settlement_id")).getFirst().get("n")).longValue();
            assertThat(lines).as("lines of month " + (3 + i)).isLessThanOrEqualTo(40);
            assertThat(settled).containsEntry("capped", true);
            counted130 += lineCharges(settled.get("settlement_id"), "GIFT").size();
            assertThat(lineCharges(settled.get("settlement_id"), "CHARGE")).isEmpty();
        }
        // The counted fee's 130 lines (one per part) were written 40, 40, 40, 10; the waiting fee is still waiting.
        assertThat(counted130).isEqualTo(130);
        assertThat(query("SELECT 1 FROM qb_operator_settlement_line_version WHERE charge_id = ? AND line_kind = 'CHARGE'",
            waiting)).isEmpty();
        assertThat(query("SELECT source_id, charge_id FROM qb_operator_settlement_line_version WHERE line_kind = "
            + "'GIFT' GROUP BY source_id, charge_id HAVING count(*) > 1")).isEmpty();
        assertThat(counted).isNotNull();
    }
}
