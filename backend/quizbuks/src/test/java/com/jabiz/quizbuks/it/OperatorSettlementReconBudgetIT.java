package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.OperatorSettlements;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M12 c (plan M8 style): how long the daily check of the operators' settlements takes, and how many statements it
 * sends, with a history (50 operators, each with a settlement a month of 5 fees and the credit used on one of them),
 * all of it within the 5 seconds a query may take. The history is cloned straight in the tables from one real settlement
 * (the point is the check's read, not 3,000 settlements through the processes). Run it alone; it prints the numbers
 * the PR gives as limits. No statistics, as right after a bulk load.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "it.sql-log.enabled=true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OperatorSettlementReconBudgetIT extends CreditNoteItSupport {

    static final int OPERATORS = 50;
    /**
     * What the check may take (the platform's query timeout is 5 s): measured on the test database at 0.2 s the longest
     * with no statistics and 0.75 s analyzed, of which 0.5 s is PostgreSQL compiling the plan (JIT: the estimates of a
     * table with statistics pass jit_above_cost); 50 operators, 60 months, 3,000 settlements, 18,000 lines, 15,000
     * fees, 100,000 cash payments. Before the lookups of the lines were written as probes of the unique index the
     * case without statistics took 2.2 s here and 4.3 s on CI (the planner took the lines for a handful and compared
     * every fee with all of them): the limit of 2 s is what catches that coming back, with the room CI needs.
     */
    static final Duration NO_STATISTICS = Duration.ofMillis(2_000);
    static final Duration ANALYZED = Duration.ofMillis(2_000);

    private static final List<String> OPERATOR_IDS = new ArrayList<>();

    private String hq() {
        return TestTokens.bearer(tokens, "fm12c-budget-hq", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ);
    }

    private static List<String> columns(String table) {
        return query("SELECT column_name FROM information_schema.columns WHERE table_schema = current_schema() AND "
            + "table_name = ? AND column_name <> 'row_id' ORDER BY ordinal_position", table).stream()
            .map(row -> String.valueOf(row.get("column_name"))).toList();
    }

    /** Rows like the template row {@code key = id}, one for each row of {@code from}, with some columns set. */
    private static void clone(String table, String key, String id, String from, Map<String, String> set) {
        List<String> columns = columns(table);
        String names = String.join(", ", columns);
        String values = String.join(", ", columns.stream().map(c -> set.getOrDefault(c, "t." + c)).toList());
        execute("INSERT INTO " + table + " (" + names + ") SELECT " + values + " FROM " + from + " CROSS JOIN (SELECT * "
            + "FROM " + table + " WHERE " + key + " = ?" + (key.equals("settlement_id") || key.equals("source_id")
            ? "" : "::uuid") + " LIMIT 1) t", id);
    }

    private static void registry(String idColumn, String templateId) {
        execute("INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) SELECT s." + idColumn
            + "::uuid, r.entity_type, r.created_seq_id FROM it_seed s, entity_registry r WHERE r.entity_id = ?::uuid",
            templateId);
    }

    @Test
    @Order(1)
    void theCheckOverFiftyOperatorsAndTwoYearsAndFiveYearsOfSettlementsIsWithinTheQueryTimeout() {
        clock.set(Instant.parse("2027-02-01T01:00:00Z"));
        param(com.jabiz.quizbuks.setup.QbParams.FEE_TAX_RATE, "0.1");
        param(com.jabiz.quizbuks.setup.QbParams.operatorGiftTreatment("MANUAL"), "OPERATOR");
        for (int i = 0; i < OPERATORS; i++) {
            OPERATOR_IDS.add(newOperator(hq(), unique("Budget op " + i)));
        }
        String realOperator = OPERATOR_IDS.getFirst();
        String sponsor = newSponsor(unique("fm12c-budget"));
        run(OperatorProcesses.ASSIGN, hq(), body("userId", sponsor, "operatorId", realOperator, "reason", "F-M12 c"));
        grant(sponsor, 1_100, Instant.parse("2032-01-01T00:00:00Z"));
        clock.set(Instant.parse("2027-02-10T01:00:00Z"));
        String fee = String.valueOf(manualFee(sponsor, 1_000));
        String payment = String.valueOf(query("SELECT payment_id FROM qb_fee_payment_version WHERE target_id = ? AND "
            + "sub_account = 'FEE_CREDIT'", fee).getFirst().get("payment_id"));
        clock.set(Instant.parse("2027-03-02T00:00:00Z"));
        systemRun(OperatorSettlements.RUN, body("operatorId", realOperator, "month", "2027-02"));
        Map<String, Object> settlement = query("SELECT * FROM qb_operator_settlement WHERE operator_id = ?",
            realOperator).getFirst();
        String settlementId = String.valueOf(settlement.get("settlement_id"));
        String chargeLine = String.valueOf(query("SELECT line_id FROM qb_operator_settlement_line_version WHERE "
            + "settlement_id = ? AND line_kind = 'CHARGE'", settlementId).getFirst().get("line_id"));
        String giftLine = String.valueOf(query("SELECT line_id FROM qb_operator_settlement_line_version WHERE "
            + "settlement_id = ? AND line_kind = 'GIFT'", settlementId).getFirst().get("line_id"));
        // The settlements are of the operators' months from 2030; the real one is left alone.
        execute("UPDATE qb_operator SET created_at = '2030-01-01T00:00:00Z'");
        for (String table : List.of("qb_fee_charge_version", "qb_fee_payment_version",
            "qb_operator_settlement_line_version", "entity_registry")) {
            execute("ALTER TABLE " + table + " SET (autovacuum_enabled = false)");
        }

        seed(0, 24, settlementId, chargeLine, giftLine, fee, payment);
        clock.set(Instant.parse("2032-01-02T00:00:00Z"));
        Duration all24 = measure("24 months, all history, no statistics", null, NO_STATISTICS);
        Duration window24 = measure("24 months, the window of 3 months, no statistics", Instant.parse("2031-10-01T00:00:00Z"), NO_STATISTICS);

        seed(24, 60, settlementId, chargeLine, giftLine, fee, payment);
        clock.set(Instant.parse("2035-01-02T00:00:00Z"));
        // Straight after the bulk load, with no statistics (autovacuum off).
        Duration all60 = measure("60 months, all history, no statistics", null, NO_STATISTICS);
        Duration window60 = measure("60 months, the window of 3 months, no statistics", Instant.parse("2034-10-01T00:00:00Z"),
            NO_STATISTICS);
        // Money paid in cash is most of the payments: 100,000 of them (the check reads those with granted credit only).
        cashPayments(100_000, payment);
        // With statistics, as autovacuum leaves a table in use: all of the history is within the timeout too.
        for (String table : List.of("qb_fee_charge_version", "qb_fee_payment_version",
            "qb_operator_settlement_line_version", "qb_operator_settlement", "entity_registry")) {
            execute("ANALYZE " + table);
        }
        Duration all60Analyzed = measure("60 months, all history, analyzed", null, ANALYZED);
        System.out.println("[recon-budget] 60 months analyzed " + all60Analyzed.toMillis() + " ms");
        System.out.println("[recon-budget] " + OPERATORS + " operators: 24 months " + all24.toMillis() + " ms (window "
            + window24.toMillis() + "), 60 months " + all60.toMillis() + " ms"
            + " (window " + window60.toMillis() + ")");
    }

    private static void cashPayments(int count, String payment) {
        execute("CREATE TABLE it_noise AS SELECT gen_random_uuid() AS pid FROM generate_series(1, CAST(? AS int))", count);
        execute("INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) SELECT n.pid, r.entity_type, "
            + "r.created_seq_id FROM it_noise n, entity_registry r WHERE r.entity_id = ?::uuid", payment);
        Map<String, String> set = new HashMap<>();
        set.put("payment_id", "n.pid");
        set.put("sub_account", "'CASH'");
        set.put("grant_id", "NULL");
        set.put("cash_lot_id", "gen_random_uuid()::text");
        set.put("target_id", "gen_random_uuid()::text");
        clone("qb_fee_payment_version", "payment_id", payment, "it_noise n", set);
        execute("DROP TABLE it_noise");
    }

    /** The settlements of the months {@code from} to {@code to} (exclusive) of the operators, from 2030-01, with their lines. */
    private void seed(int from, int to, String settlementId, String chargeLine, String giftLine, String fee,
        String payment) {
        execute("DROP TABLE IF EXISTS it_seed");
        execute("CREATE TABLE it_seed AS SELECT o.idx, m.m, o.operator_id, gen_random_uuid()::text AS sid, "
            + "to_char(date '2030-01-01' + (m.m * interval '1 month'), 'YYYY-MM') AS month, "
            + "(date '2030-01-01' + ((m.m + 1) * interval '1 month') + interval '1 day 9 hours') AT TIME ZONE 'UTC' AS issued, "
            + "gen_random_uuid()::text AS c1, gen_random_uuid()::text AS c2, gen_random_uuid()::text AS c3, "
            + "gen_random_uuid()::text AS c4, gen_random_uuid()::text AS c5, gen_random_uuid()::text AS p1, "
            + "gen_random_uuid()::text AS l1, gen_random_uuid()::text AS l2, gen_random_uuid()::text AS l3, "
            + "gen_random_uuid()::text AS l4, gen_random_uuid()::text AS l5, gen_random_uuid()::text AS g1 "
            + "FROM (SELECT row_number() OVER (ORDER BY operator_id) AS idx, operator_id FROM qb_operator "
            + "WHERE operator_id = ANY(?)) o CROSS JOIN generate_series(CAST(? AS int), CAST(? AS int) - 1) AS m(m)",
            OPERATOR_IDS.toArray(String[]::new), from, to);
        // The settlements: five fees of 1,000, the credit used on the first (1,100, taken back at 1, none of it taken
        // for want of a share: carried from month to month).
        Map<String, String> set = new HashMap<>();
        set.put("settlement_id", "s.sid");
        set.put("operator_id", "s.operator_id");
        set.put("month", "s.month");
        set.put("period_start", "s.issued - interval '1 day 9 hours' - interval '1 month'");
        set.put("period_end", "s.issued - interval '1 day 9 hours'");
        set.put("issued_at", "s.issued");
        set.put("covered_from", "'2000-01-01T00:00:00Z'::timestamptz");
        set.put("platform_fees", "5000");
        set.put("cost_excluded", "0");
        set.put("gift_paid", "0");
        set.put("gift_share_deduction", "0");
        set.put("void_deduction", "0");
        set.put("gift_used", "1100");
        set.put("gift_due", "1100");
        set.put("gift_recovered", "0");
        set.put("gift_carried_in", "1100 * s.m");
        set.put("gift_carried_out", "1100 * (s.m + 1)");
        set.put("capped", "false");
        set.put("uncounted", "0");
        set.put("watermark_charged_at", "NULL");
        set.put("watermark_charge_id", "NULL");
        set.put("document_run_id", "NULL");
        set.put("document_no", "NULL");
        clone("qb_operator_settlement", "settlement_id", settlementId, "it_seed s", set);

        for (String column : List.of("c1", "c2", "c3", "c4", "c5")) {
            registry(column, fee);
        }
        registry("p1", payment);
        for (String column : List.of("l1", "l2", "l3", "l4", "l5", "g1")) {
            registry(column, chargeLine);
        }
        for (int k = 1; k <= 5; k++) {
            Map<String, String> feeSet = new HashMap<>();
            feeSet.put("charge_id", "s.c" + k + "::uuid");
            feeSet.put("operator_id", "s.operator_id");
            feeSet.put("period", "s.month");
            feeSet.put("source_key", "NULL");
            feeSet.put("charged_at", "(s.month || '-05')::date + (" + k + " * interval '1 minute')");
            clone("qb_fee_charge_version", "charge_id", fee, "it_seed s", feeSet);
            Map<String, String> lineSet = new HashMap<>();
            lineSet.put("line_id", "s.l" + k + "::uuid");
            lineSet.put("settlement_id", "s.sid");
            lineSet.put("operator_id", "s.operator_id");
            lineSet.put("month", "s.month");
            lineSet.put("source_id", "s.c" + k);
            lineSet.put("charge_id", "s.c" + k);
            lineSet.put("period", "s.month");
            lineSet.put("occurred_at", "(s.month || '-05')::date + (" + k + " * interval '1 minute')");
            clone("qb_operator_settlement_line_version", "line_id", chargeLine, "it_seed s", lineSet);
        }
        Map<String, String> paymentSet = new HashMap<>();
        paymentSet.put("payment_id", "s.p1::uuid");
        paymentSet.put("target_id", "s.c1");
        paymentSet.put("paid_at", "(s.month || '-05')::date + interval '2 minutes'");
        clone("qb_fee_payment_version", "payment_id", payment, "it_seed s", paymentSet);
        Map<String, String> giftSet = new HashMap<>();
        giftSet.put("line_id", "s.g1::uuid");
        giftSet.put("settlement_id", "s.sid");
        giftSet.put("operator_id", "s.operator_id");
        giftSet.put("month", "s.month");
        giftSet.put("source_id", "s.p1");
        giftSet.put("charge_id", "s.c1");
        giftSet.put("period", "s.month");
        giftSet.put("occurred_at", "(s.month || '-05')::date + interval '2 minutes'");
        clone("qb_operator_settlement_line_version", "line_id", giftLine, "it_seed s", giftSet);
        execute("DROP TABLE it_seed");
    }

    private Duration measure(String label, Instant since, Duration limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("at", clock.instant().toString());
        params.put("zone", "Asia/Tokyo");
        params.put("waitingHours", 48);
        params.put("readinessDays", 5);
        if (since != null) {
            params.put("since", since.toString());
        }
        SqlStatementLog.clear();
        long start = System.nanoTime();
        List<Map<String, Object>> rows;
        try {
            rows = items(OperatorSettlementReconIT.TEMPLATE, TestTokens.bearer(tokens, "fm12c-budget-recon",
                QbPermissions.WALLET_SYSTEM), Map.of("params", params, "limit", 1000));
        } catch (AssertionError timedOut) {
            System.out.println("[recon-budget] " + label + ": NOT WITHIN THE QUERY TIMEOUT after "
                + Duration.ofNanos(System.nanoTime() - start).toMillis() + " ms");
            throw timedOut;
        }
        Duration took = Duration.ofNanos(System.nanoTime() - start);
        long statements = SqlStatementLog.STATEMENTS.stream().filter(sql -> sql.contains("new_settlements")).count();
        System.out.println("[recon-budget] " + label + ": " + took.toMillis() + " ms, " + statements
            + " statement(s) of the template, " + rows.size() + " rows");
        // The seeded history is consistent with itself: nothing is found in the 50 operators' months (the real
        // operator's own lines are the template's).
        assertThat(rows.stream().filter(row -> OPERATOR_IDS.contains(String.valueOf(row.get("sponsorId")))
            && !OPERATOR_IDS.getFirst().equals(row.get("sponsorId")))).as(label).isEmpty();
        assertThat(statements).as(label + ": one statement").isEqualTo(1);
        assertThat(took).as(label + ": within " + limit.toMillis() + " ms").isLessThan(limit);
        return took;
    }
}
