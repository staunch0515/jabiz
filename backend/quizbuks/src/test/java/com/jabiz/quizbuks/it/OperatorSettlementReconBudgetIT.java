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
 * sends, with a history (50 operators, each with a settlement a month of 5 fees and the credit used on one of them, and a
 * posting on 5140 for each settlement, so the books of the credit taken back are read too),
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
     * with no statistics; 50 operators, 60 months, 3,000 settlements, 18,000 lines, 15,000 fees, 100,000 cash payments.
     * Before the lookups of the lines were written as probes of the unique index the case without statistics took 2.2 s
     * here and 4.3 s on CI (the planner took the lines for a handful and compared every fee with all of them): the limit
     * of 1 s is what catches that coming back, with the room CI needs (about twice the time here).
     */
    static final Duration NO_STATISTICS = Duration.ofMillis(1_000);
    /**
     * With the parts paid with granted credit added (#701 review M3, re-review M-1 and L-1): 100,000 on one fee each and
     * 23,520 on monthly invoices of five fees, each part with its GIFT lines. Measured here at 1.25 s with no statistics
     * (1.1 s the window); a limit of its own, so that CI's slower machines have their room.
     */
    static final Duration CREDIT_PARTS = Duration.ofMillis(2_000);
    /**
     * Analyzed, over all of that: 1.1 s here, with PostgreSQL's JIT off on the application's connections (application.yml).
     * With JIT on, the same plan ran in 1.2 s and its compiling took 10 s more (the estimated cost passed the JIT's inline
     * and optimize thresholds), past the query timeout: the time over the cases without statistics is the larger
     * history and, before, the compiling; not a change of plan (re-review L-2: the plans were compared).
     */
    static final Duration ANALYZED = Duration.ofMillis(2_000);

    private static final List<String> OPERATOR_IDS = new ArrayList<>();
    private static String ledgerTransaction;
    private static String ledgerEntry;
    private static String giftAccount;

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
        // Rows of the real ledger to clone: a transaction and an entry (the postings of the seeded settlements), and 5140.
        ledgerTransaction = String.valueOf(query("SELECT transaction_id FROM ledger_transaction_version LIMIT 1")
            .getFirst().get("transaction_id"));
        ledgerEntry = String.valueOf(query("SELECT entry_id FROM ledger_entry_version LIMIT 1").getFirst().get("entry_id"));
        giftAccount = String.valueOf(query("SELECT account_id FROM ledger_account_version WHERE account_code = '5140'")
            .getFirst().get("account_id"));
        // The settlements are of the operators' months from 2030; the real one is left alone.
        execute("UPDATE qb_operator SET created_at = '2030-01-01T00:00:00Z'");
        // Every table the check reads that the history fills or the setup wrote to: no statistics until ANALYZE below.
        for (String table : List.of("qb_fee_charge_version", "qb_fee_payment_version",
            "qb_operator_settlement_line_version", "qb_operator_settlement", "qb_operator", "qb_sponsor_operator",
            "qb_fee_invoice", "qb_fee_credit_note_version", "ledger_transaction_version", "ledger_entry_version",
            "entity_registry")) {
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
        // #701 review M3: as many parts paid with granted credit, each with its GIFT line (settled, as nearly all are),
        // read from the earliest switch point on whatever the window.
        giftPayments(100_000, payment, giftLine, realOperator);
        // Re-review M-1: parts paid on the monthly invoices, which may pay several fees each (left out only fee by fee).
        invoiceGiftPayments(8, payment, giftLine, realOperator);
        Duration giftsNoStatistics = measure("60 months, 124,000 credit parts, no statistics", null, CREDIT_PARTS);
        Duration giftsWindow = measure("60 months, 124,000 credit parts, the window of 3 months, no statistics",
            Instant.parse("2034-10-01T00:00:00Z"), CREDIT_PARTS);
        // #701 review M1: the waiting list over the same history, every operator waiting for its month after the last.
        Duration waitingNoStatistics = measureWaiting("no statistics", NO_STATISTICS);
        // With statistics, as autovacuum leaves a table in use: all of the history is within the timeout too.
        for (String table : List.of("qb_fee_charge_version", "qb_fee_payment_version",
            "qb_operator_settlement_line_version", "qb_operator_settlement", "ledger_transaction_version",
            "ledger_entry_version", "entity_registry")) {
            execute("ANALYZE " + table);
        }
        Duration all60Analyzed = measure("60 months, all history, analyzed", null, ANALYZED);
        Duration waitingAnalyzed = measureWaiting("analyzed", NO_STATISTICS);
        System.out.println("[recon-budget] 60 months analyzed " + all60Analyzed.toMillis() + " ms; with 124,000 "
            + "credit parts, no statistics " + giftsNoStatistics.toMillis() + " ms (window " + giftsWindow.toMillis()
            + "); waiting list " + waitingNoStatistics.toMillis() + " ms, analyzed " + waitingAnalyzed.toMillis() + " ms");
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

    /**
     * {@code count} parts paid with granted credit on the seeded operators' fees, each with the GIFT line that counted it
     * in the fee's settlement. Eight to a fee, of 1 and -1 by turns (a part is never 0; a voided invoice's is negative):
     * each settlement's lines add up to 0 more, so its columns and the ledger stay as seeded.
     */
    private static void giftPayments(int count, String payment, String giftLine, String realOperator) {
        execute("CREATE TABLE it_gift AS SELECT gen_random_uuid() AS pid, gen_random_uuid() AS lid, l.settlement_id, "
            + "l.operator_id, l.month, l.charge_id, l.occurred_at + k.k * interval '1 second' AS paid, "
            + "CASE WHEN k.k % 2 = 0 THEN -1 ELSE 1 END AS amount "
            + "FROM (SELECT * FROM qb_operator_settlement_line_version WHERE line_kind = 'CHARGE' AND operator_id <> ? "
            + "LIMIT CAST(? AS int) / 8) l CROSS JOIN generate_series(1, 8) AS k(k)", realOperator, count);
        execute("INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) SELECT n.pid, r.entity_type, "
            + "r.created_seq_id FROM it_gift n, entity_registry r WHERE r.entity_id = ?::uuid", payment);
        execute("INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) SELECT n.lid, r.entity_type, "
            + "r.created_seq_id FROM it_gift n, entity_registry r WHERE r.entity_id = ?::uuid", giftLine);
        Map<String, String> paymentSet = new HashMap<>();
        paymentSet.put("payment_id", "n.pid");
        paymentSet.put("target_entity", "'QbFeeCharge'");
        paymentSet.put("target_id", "n.charge_id");
        paymentSet.put("paid_at", "n.paid");
        paymentSet.put("amount", "n.amount");
        clone("qb_fee_payment_version", "payment_id", payment, "it_gift n", paymentSet);
        Map<String, String> lineSet = new HashMap<>();
        lineSet.put("line_id", "n.lid");
        lineSet.put("settlement_id", "n.settlement_id");
        lineSet.put("operator_id", "n.operator_id");
        lineSet.put("month", "n.month");
        lineSet.put("source_id", "n.pid::text");
        lineSet.put("charge_id", "n.charge_id");
        lineSet.put("period", "n.month");
        lineSet.put("occurred_at", "n.paid");
        lineSet.put("amount", "n.amount");
        lineSet.put("recover_gross", "n.amount");
        clone("qb_operator_settlement_line_version", "line_id", giftLine, "it_gift n", lineSet);
        execute("DROP TABLE it_gift");
    }

    /**
     * Re-review M-1: a monthly invoice of each seeded settlement's five fees (open, of the fees' Sponsor and month), and
     * {@code perInvoice} parts paid on it with granted credit, of 1 and -1 by turns; each part with a GIFT line for each
     * of the five fees in that settlement, so that the settlement's columns add up to what they did.
     */
    private static void invoiceGiftPayments(int perInvoice, String payment, String giftLine, String realOperator) {
        execute("CREATE TABLE it_inv AS SELECT gen_random_uuid()::text AS iid, c.sponsor_id, c.operator_id, c.period, "
            + "max(c.charged_at) + interval '1 day' AS issued, row_number() OVER () AS n "
            + "FROM qb_fee_charge_version c WHERE c.operator_id <> ? AND c.billing_mode = 'POSTPAID' "
            + "AND c.sponsor_id LIKE 'bud-%' GROUP BY c.sponsor_id, c.operator_id, c.period", realOperator);
        execute("INSERT INTO qb_fee_invoice (invoice_id, invoice_no, sponsor_id, operator_id, billing_mode, period, "
            + "charge_id, amount, tax, total, status, issued_at, issued_on, due_on) SELECT i.iid, 'BUD-' || i.n, "
            + "i.sponsor_id, i.operator_id, 'POSTPAID', i.period, NULL, 0, 0, 0, 'OPEN', i.issued, CAST(i.issued AS date), "
            + "CAST(i.issued AS date) + 30 "
            + "FROM it_inv i");
        execute("CREATE TABLE it_ipart AS SELECT gen_random_uuid() AS pid, i.iid, i.issued + k.k * interval '1 minute' "
            + "AS paid, CASE WHEN k.k % 2 = 0 THEN -1 ELSE 1 END AS amount FROM it_inv i "
            + "CROSS JOIN generate_series(1, CAST(? AS int)) AS k(k)", perInvoice);
        execute("INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) SELECT n.pid, r.entity_type, "
            + "r.created_seq_id FROM it_ipart n, entity_registry r WHERE r.entity_id = ?::uuid", payment);
        Map<String, String> paymentSet = new HashMap<>();
        paymentSet.put("payment_id", "n.pid");
        paymentSet.put("target_entity", "'QbFeeInvoice'");
        paymentSet.put("target_id", "n.iid");
        paymentSet.put("paid_at", "n.paid");
        paymentSet.put("amount", "n.amount");
        clone("qb_fee_payment_version", "payment_id", payment, "it_ipart n", paymentSet);
        execute("CREATE TABLE it_iline AS SELECT gen_random_uuid() AS lid, p.pid, p.paid, p.amount, l.settlement_id, "
            + "l.operator_id, l.month, l.charge_id FROM it_ipart p JOIN it_inv i ON i.iid = p.iid "
            + "JOIN qb_fee_charge_version c ON c.sponsor_id = i.sponsor_id AND c.period = i.period "
            + "JOIN qb_operator_settlement_line_version l ON l.line_kind = 'CHARGE' "
            + "AND l.charge_id = CAST(c.charge_id AS text)");
        execute("INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) SELECT n.lid, r.entity_type, "
            + "r.created_seq_id FROM it_iline n, entity_registry r WHERE r.entity_id = ?::uuid", giftLine);
        Map<String, String> lineSet = new HashMap<>();
        lineSet.put("line_id", "n.lid");
        lineSet.put("settlement_id", "n.settlement_id");
        lineSet.put("operator_id", "n.operator_id");
        lineSet.put("month", "n.month");
        lineSet.put("source_id", "n.pid::text");
        lineSet.put("charge_id", "n.charge_id");
        lineSet.put("period", "n.month");
        lineSet.put("occurred_at", "n.paid");
        lineSet.put("amount", "n.amount");
        lineSet.put("recover_gross", "n.amount");
        clone("qb_operator_settlement_line_version", "line_id", giftLine, "it_iline n", lineSet);
        System.out.println("[recon-budget] invoice parts: " + query("SELECT count(*) AS n FROM it_ipart").getFirst()
            .get("n") + " on " + query("SELECT count(*) AS n FROM it_inv").getFirst().get("n") + " invoices, "
            + query("SELECT count(*) AS n FROM it_iline").getFirst().get("n") + " GIFT lines");
        execute("DROP TABLE it_iline");
        execute("DROP TABLE it_ipart");
        execute("DROP TABLE it_inv");
    }

    /**
     * The waiting list's template (#701 review M1) at the clock's month: each seeded operator's month after its last
     * settlement waits, so every branch is asked for every operator.
     */
    private Duration measureWaiting(String label, Duration limit) {
        long start = System.nanoTime();
        List<Map<String, Object>> rows = items(OperatorSettlements.WAITING_TEMPLATE, TestTokens.bearer(tokens,
            "fm12c-budget-waiting", QbPermissions.OPERATOR_SETTLEMENT_SYSTEM), Map.of("params", Map.of("current",
            "2035-03", "zone", "Asia/Tokyo"), "limit", 1000));
        Duration took = Duration.ofNanos(System.nanoTime() - start);
        System.out.println("[recon-budget] waiting list, " + label + ": " + took.toMillis() + " ms, " + rows.size()
            + " rows");
        assertThat(rows.stream().filter(row -> OPERATOR_IDS.contains(String.valueOf(row.get("operatorId")))))
            .as("waiting list, " + label).hasSizeGreaterThanOrEqualTo(OPERATORS - 1);
        assertThat(took).as("waiting list, " + label + ": within " + limit.toMillis() + " ms").isLessThan(limit);
        return took;
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
            + "gen_random_uuid()::text AS l4, gen_random_uuid()::text AS l5, gen_random_uuid()::text AS g1, "
            + "gen_random_uuid()::text AS t1, gen_random_uuid()::text AS e1, gen_random_uuid()::text AS e2 "
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
            // Postpaid, each operator's fees of one Sponsor of its own: the monthly invoices of the parts paid on them
            // (invoiceGiftPayments) cover the five fees of a month.
            feeSet.put("sponsor_id", "'bud-' || s.operator_id");
            feeSet.put("billing_mode", "'POSTPAID'");
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
        // A posting for each settlement on 5140 (the account the take-back is read from): a credit and a debit of 1, so the
        // books net to what the seeded settlements say they took back (nothing) while recovered_books reads all of them.
        registry("t1", ledgerTransaction);
        registry("e1", ledgerEntry);
        registry("e2", ledgerEntry);
        Map<String, String> postingSet = new HashMap<>();
        postingSet.put("transaction_id", "s.t1::uuid");
        postingSet.put("booking_time", "s.issued");
        postingSet.put("created_time", "s.issued");
        postingSet.put("reference", "NULL");
        postingSet.put("reverses_transaction_id", "NULL");
        postingSet.put("source_entity", "'QbOperatorSettlement'");
        postingSet.put("source_id", "s.sid");
        clone("ledger_transaction_version", "transaction_id", ledgerTransaction, "it_seed s", postingSet);
        // Both entries in one statement: the balance of a transaction is checked when the statement ends.
        List<String> entryColumns = columns("ledger_entry_version");
        Map<String, String> entrySet = new HashMap<>();
        entrySet.put("entry_id", "CASE k.k WHEN 1 THEN s.e1::uuid ELSE s.e2::uuid END");
        entrySet.put("transaction_id", "s.t1::uuid");
        entrySet.put("account_id", "'" + giftAccount + "'::uuid");
        entrySet.put("line_no", "k.k");
        entrySet.put("direction", "CASE k.k WHEN 1 THEN 'CREDIT' ELSE 'DEBIT' END");
        entrySet.put("amount", "1");
        entrySet.put("created_time", "s.issued");
        for (String column : List.of("memo", "dimension_1", "dimension_2", "dimension_3", "dimension_4", "currency",
            "transaction_amount", "exchange_rate")) {
            entrySet.put(column, "NULL");
        }
        execute("INSERT INTO ledger_entry_version (" + String.join(", ", entryColumns) + ") SELECT "
            + String.join(", ", entryColumns.stream().map(c -> entrySet.getOrDefault(c, "t." + c)).toList())
            + " FROM it_seed s CROSS JOIN generate_series(1, 2) AS k(k) CROSS JOIN (SELECT * FROM ledger_entry_version "
            + "WHERE entry_id = ?::uuid LIMIT 1) t", ledgerEntry);
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
