package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.OperatorSettlements;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M12 (docs/quizbuks/plans/impl/F-M12.md): the operators' settlement no longer shares a commission but the platform
 * fees of its sponsors, does not share the part paid with granted credit the platform bears, takes back the part the
 * operator bears, and counts each fee and each part of a payment exactly once from a switch point on. Granted credit
 * is driven in with a fee by hand paid from a credit lot.
 */
class OperatorSettlementGiftIT extends CreditNoteItSupport {

    private String hq() {
        return TestTokens.bearer(tokens, "fm12-hq", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ);
    }

    private String operator(String name) {
        return newOperator(hq(), unique(name));
    }

    private void assign(String sponsor, String operatorId) {
        run(OperatorProcesses.ASSIGN, hq(), body("userId", sponsor, "operatorId", operatorId, "reason", "F-M12"));
    }

    private static Map<String, Object> settlement(String operatorId, String month) {
        return query("SELECT * FROM qb_operator_settlement WHERE operator_id = ? AND month = ?", operatorId, month)
            .getFirst();
    }

    private static List<Map<String, Object>> lines(String settlementId, String kind) {
        return query("SELECT * FROM qb_operator_settlement_line_version WHERE settlement_id = ? AND line_kind = ? "
            + "ORDER BY occurred_at", settlementId, kind);
    }

    private static List<String> postings(String settlementId, String reference) {
        return query("SELECT a.account_code, e.direction, e.amount FROM ledger_transaction_version t "
            + "JOIN ledger_entry_version e ON e.transaction_id = t.transaction_id "
            + "JOIN ledger_account_version a ON a.account_id = e.account_id "
            + "WHERE t.source_entity = 'QbOperatorSettlement' AND t.source_id = ? AND t.reference = ? "
            + "ORDER BY e.line_no", settlementId, reference).stream().map(p -> p.get("account_code") + " "
            + p.get("direction") + " " + ((BigDecimal) p.get("amount")).stripTrailingZeros().toPlainString()).toList();
    }

    private static BigDecimal money(Map<String, Object> row, String column) {
        return (BigDecimal) row.get(column);
    }

    /** F-M12: the operator's first eight, as the reference is built. */
    private static String ref(String prefix, String month, String operatorId) {
        return prefix + month + "_" + operatorId.substring(0, Math.min(8, operatorId.length()));
    }

    @Test
    void creditBorneByTheOperatorIsSharedTakenBackCarriedAndClearedNextMonth() {
        clock.set(Instant.parse("2027-03-10T01:00:00Z"));
        String operatorId = operator("Borne by operator");
        String sponsor = newSponsor(unique("fm12-b"));
        assign(sponsor, operatorId);
        param(QbParams.FEE_TAX_RATE, "0.1");
        param(QbParams.operatorGiftTreatment("MANUAL"), "OPERATOR");

        // 1,100 of granted credit (MANUAL source) pays a 1,000 fee (1,100 with tax) in full.
        grant(sponsor, 1_100, Instant.parse("2028-01-01T00:00:00Z"));
        Object charge = manualFee(sponsor, 1_000);
        assertThat(query("SELECT 1 FROM qb_fee_payment_version WHERE sub_account = 'FEE_CREDIT' AND target_id = ?",
            String.valueOf(charge))).isNotEmpty();

        clock.set(Instant.parse("2027-04-02T00:00:00Z"));
        systemRun(OperatorSettlements.RUN, body("operatorId", operatorId, "month", "2027-03"));
        Map<String, Object> march = settlement(operatorId, "2027-03");
        // Plan 1 keeps half: the operator's share of the 1,000 base is 500; the credit used (1,100 with tax) is taken
        // back at rate 1; 500 is taken, 600 carried; nothing is payable.
        assertThat(money(march, "revenue")).isEqualByComparingTo("0");
        assertThat(money(march, "platform_fees")).isEqualByComparingTo("1000");
        assertThat(money(march, "cost_excluded")).isEqualByComparingTo("0");
        assertThat(money(march, "gift_paid")).isEqualByComparingTo("0");
        assertThat(money(march, "operator_share")).isEqualByComparingTo("500");
        assertThat(money(march, "gift_used")).isEqualByComparingTo("1100");
        assertThat(money(march, "gift_due")).isEqualByComparingTo("1100");
        assertThat(money(march, "gift_recovered")).isEqualByComparingTo("500");
        assertThat(money(march, "gift_carried_out")).isEqualByComparingTo("600");
        assertThat(money(march, "payable")).isEqualByComparingTo("0");
        String marchId = String.valueOf(march.get("settlement_id"));
        assertThat(lines(marchId, "GIFT")).singleElement().satisfies(line -> {
            assertThat(money(line, "recover_gross")).isEqualByComparingTo("1100");
            assertThat(money(line, "in_base")).isEqualByComparingTo("1000");
            assertThat(line).containsEntry("no_share", false).containsEntry("grant_source", "MANUAL");
        });
        assertThat(postings(marchId, ref("op_", "2027-03", operatorId))).containsExactly("5130 DEBIT 500",
            "2140 CREDIT 500");
        assertThat(postings(marchId, ref("opg_", "2027-03", operatorId))).containsExactly("2140 DEBIT 500",
            "5140 CREDIT 500");

        // April: a 2,000 fee paid in cash (the credit lot is spent); the share is 1,000, and the 600 carried is taken
        // out of it. 400 is payable.
        clock.set(Instant.parse("2027-04-10T01:00:00Z"));
        cash(sponsor, 3_000);
        manualFee(sponsor, 2_000);
        clock.set(Instant.parse("2027-05-02T00:00:00Z"));
        systemRun(OperatorSettlements.RUN, body("operatorId", operatorId, "month", "2027-04"));
        Map<String, Object> april = settlement(operatorId, "2027-04");
        assertThat(money(april, "operator_share")).isEqualByComparingTo("1000");
        assertThat(money(april, "gift_carried_in")).isEqualByComparingTo("600");
        assertThat(money(april, "gift_due")).isEqualByComparingTo("0");
        assertThat(money(april, "gift_recovered")).isEqualByComparingTo("600");
        assertThat(money(april, "gift_carried_out")).isEqualByComparingTo("0");
        assertThat(money(april, "payable")).isEqualByComparingTo("400");

        // The lines and the charges are only ever inserted (append-only, temporal write-once).
        noUpdatesOrDeletes("qb_operator_settlement_line_version");
        noUpdatesOrDeletes("qb_fee_charge_version");
    }

    static void noUpdatesOrDeletes(String table) {
        assertThat(query("SELECT coalesce(n_tup_upd, 0) + coalesce(n_tup_del, 0) AS changed "
            + "FROM pg_stat_user_tables WHERE schemaname = current_schema() AND relname = ?", table))
            .as(table).singleElement()
            .satisfies(row -> assertThat(((Number) row.get("changed")).longValue()).as(table).isZero());
    }

    @Test
    void creditBorneByThePlatformIsNotSharedOrTakenBack() {
        clock.set(Instant.parse("2027-03-10T01:00:00Z"));
        String operatorId = operator("Borne by platform");
        String sponsor = newSponsor(unique("fm12-a"));
        assign(sponsor, operatorId);
        param(QbParams.FEE_TAX_RATE, "0.1");
        // MANUAL's default treatment is PLATFORM (甲): not shared, not taken back.

        grant(sponsor, 1_100, Instant.parse("2028-01-01T00:00:00Z"));
        manualFee(sponsor, 1_000);

        clock.set(Instant.parse("2027-04-02T00:00:00Z"));
        systemRun(OperatorSettlements.RUN, body("operatorId", operatorId, "month", "2027-03"));
        Map<String, Object> march = settlement(operatorId, "2027-03");
        // The 1,000 is in the base but the same-batch credit the platform bears takes it out: the net base is 0.
        assertThat(money(march, "platform_fees")).isEqualByComparingTo("1000");
        assertThat(money(march, "gift_paid")).isEqualByComparingTo("1000");
        assertThat(money(march, "operator_share")).isEqualByComparingTo("0");
        assertThat(money(march, "gift_used")).isEqualByComparingTo("0");
        assertThat(money(march, "gift_due")).isEqualByComparingTo("0");
        assertThat(money(march, "gift_recovered")).isEqualByComparingTo("0");
        assertThat(money(march, "payable")).isEqualByComparingTo("0");
        String marchId = String.valueOf(march.get("settlement_id"));
        assertThat(lines(marchId, "GIFT")).singleElement().satisfies(line -> {
            assertThat(money(line, "recover_gross")).isEqualByComparingTo("0");
            assertThat(money(line, "in_base")).isEqualByComparingTo("1000");
            assertThat(line).containsEntry("no_share", true);
        });
        // Nothing is payable and nothing taken back: no postings.
        assertThat(postings(marchId, ref("op_", "2027-03", operatorId))).isEmpty();
        assertThat(postings(marchId, ref("opg_", "2027-03", operatorId))).isEmpty();
    }

    @Test
    void theSwitchPointLeavesOldFeesAloneAndCountsTheFirstNewFeeOnce() {
        clock.set(Instant.parse("2027-06-10T01:00:00Z"));
        String operatorId = operator("Switch");
        String sponsor = newSponsor(unique("fm12-switch"));
        assign(sponsor, operatorId);
        param(QbParams.FEE_TAX_RATE, "0.1");

        // An old fee of June, before the switch point, and the operator's last old settlement (no covered_from).
        Object oldCharge = manualFee(sponsor, 1_000);
        execute("INSERT INTO qb_operator_settlement (settlement_id, operator_id, month, period_start, period_end, "
            + "revenue, platform_share, operator_share, prospecting_cost, carried_in, payable, carried_out, status, "
            + "issued_at, revision, version) VALUES (gen_random_uuid()::text, ?, '2027-06', "
            + "'2027-05-31T15:00:00Z', '2027-06-30T15:00:00Z', 0, 0.5, 0, 0, 0, 0, 0, 'CREDITED', now(), 1, 0)",
            operatorId);

        // A new fee of July, after the switch point.
        clock.set(Instant.parse("2027-07-10T01:00:00Z"));
        Object newCharge = manualFee(sponsor, 2_000);

        clock.set(Instant.parse("2027-08-02T00:00:00Z"));
        systemRun(OperatorSettlements.RUN, body("operatorId", operatorId, "month", "2027-07"));
        Map<String, Object> july = settlement(operatorId, "2027-07");
        // The switch point is the end of the old June settlement; only the July fee is counted.
        assertThat(july.get("covered_from")).isNotNull();
        assertThat(money(july, "platform_fees")).isEqualByComparingTo("2000");
        String julyId = String.valueOf(july.get("settlement_id"));
        assertThat(lines(julyId, "CHARGE")).singleElement()
            .satisfies(line -> assertThat(line).containsEntry("charge_id", String.valueOf(newCharge)));
        // The old June fee has no line in the new settlement.
        assertThat(query("SELECT 1 FROM qb_operator_settlement_line_version WHERE charge_id = ?",
            String.valueOf(oldCharge))).isEmpty();
    }

    @Test
    void anOperatorCannotReadAnotherOperatorsLines() {
        clock.set(Instant.parse("2027-09-10T01:00:00Z"));
        param(QbParams.FEE_TAX_RATE, "0.1");
        String operatorA = operator("Scope A");
        String operatorB = operator("Scope B");
        String sponsorA = newSponsor(unique("fm12-scope-a"));
        String sponsorB = newSponsor(unique("fm12-scope-b"));
        assign(sponsorA, operatorA);
        assign(sponsorB, operatorB);
        cash(sponsorA, 3_000);
        cash(sponsorB, 3_000);
        manualFee(sponsorA, 1_000);
        manualFee(sponsorB, 1_000);
        clock.set(Instant.parse("2027-10-02T00:00:00Z"));
        systemRun(OperatorSettlements.RUN, body("operatorId", operatorA, "month", "2027-09"));
        systemRun(OperatorSettlements.RUN, body("operatorId", operatorB, "month", "2027-09"));
        String settlementA = String.valueOf(settlement(operatorA, "2027-09").get("settlement_id"));
        String settlementB = String.valueOf(settlement(operatorB, "2027-09").get("settlement_id"));

        String memberA = verifiedUser(unique("fm12-member-a"));
        run(OperatorProcesses.MEMBER_ADD, hq(), body("operatorId", operatorA, "userId", memberA, "lead", false));
        List<Map<String, Object>> seen = items("qb.operator.settlement-lines", memberSession(memberA, operatorA),
            Map.of("limit", 100));
        assertThat(seen).isNotEmpty();
        assertThat(seen).allSatisfy(row -> assertThat(row).containsEntry("settlementId", settlementA));
        assertThat(seen).noneSatisfy(row -> assertThat(row).containsEntry("settlementId", settlementB));
    }
}
