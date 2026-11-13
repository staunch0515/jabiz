package com.jabiz.quizbuks.it;

import org.junit.jupiter.api.Test;
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
class OperatorSettlementCapLimitsIT extends CapItSupport {

    @Test
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
}
