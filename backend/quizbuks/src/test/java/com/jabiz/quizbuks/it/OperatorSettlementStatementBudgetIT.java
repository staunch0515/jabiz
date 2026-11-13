package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.OperatorSettlements;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M12 b (plan §3.9, M8): how many statements the transaction of one operator's settlement sends, however many lines
 * it writes (the platform batches the writes, D46), and how long it takes on the test database. A settlement of N fees
 * each paid with granted credit writes 2N lines (a fee line and a credit-used line each). The credit-used read looks at
 * every payment with granted credit since the switch point, so another operator's thousands of them are in the schema
 * too (review M7). A change of the settlement path changes the numbers on purpose: say why in the change.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "it.sql-log.enabled=true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OperatorSettlementStatementBudgetIT extends CreditNoteItSupport {

    /** The transaction of a settlement of 2,000 lines (1,000 fees and the credit used on each). */
    static final int LINES_2000 = 14;

    private String hq() {
        return TestTokens.bearer(tokens, "fm12b-budget-hq", QbPermissions.OPERATORS_WRITE,
            QbPermissions.OPERATORS_READ);
    }

    /** An operator, its sponsor, and one real fee paid with granted credit: {charge, payment, operator, sponsor}. */
    private String[] seed(int year, String name) {
        clock.set(Instant.parse(year + "-02-01T01:00:00Z"));
        String operatorId = newOperator(hq(), unique(name));
        String sponsor = newSponsor(unique("fm12b-" + name.replaceAll("[^A-Za-z0-9]", "-")));
        run(OperatorProcesses.ASSIGN, hq(), body("userId", sponsor, "operatorId", operatorId, "reason", "F-M12 b"));
        grant(sponsor, 110, Instant.parse((year + 5) + "-01-01T00:00:00Z"));
        clock.set(Instant.parse(year + "-02-10T01:00:00Z"));
        String chargeId = String.valueOf(manualFee(sponsor, 100));
        String paymentId = String.valueOf(query("SELECT payment_id FROM qb_fee_payment_version WHERE target_id = ? "
            + "AND sub_account = 'FEE_CREDIT'", chargeId).getFirst().get("payment_id"));
        return new String[] {chargeId, paymentId, operatorId, sponsor};
    }

    /**
     * {@code count} more fees like the seeded one, each with its credit-used part, for the operator and sponsor given
     * (set straight in the tables: the point is the settlement's read and write, not 10,000 approvals).
     */
    private void clone(String[] seeded, int count, String operatorId, String sponsor) {
        String table = "it_clone_" + Math.abs(System.nanoTime() % 1_000_000);
        execute("CREATE TABLE " + table + " AS SELECT n, gen_random_uuid() AS cid, gen_random_uuid() AS pid "
            + "FROM generate_series(1, CAST(? AS int)) n", count);
        execute("INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) SELECT c.cid, r.entity_type, "
            + "r.created_seq_id FROM " + table + " c, entity_registry r WHERE r.entity_id = ?::uuid", seeded[0]);
        execute("INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) SELECT c.pid, r.entity_type, "
            + "r.created_seq_id FROM " + table + " c, entity_registry r WHERE r.entity_id = ?::uuid", seeded[1]);
        execute("INSERT INTO qb_fee_charge_version (charge_id, version_no, effect_start_time, created_time, "
            + "process_seq_id, sponsor_id, operator_id, billing_mode, kind, period, amount, tax_rate, tax, total, "
            + "reason, source_key, charged_at, charged_by, share_excluded) SELECT c.cid, 1, t.effect_start_time + "
            + "c.n * interval '1 second', t.created_time, t.process_seq_id, ?, ?, t.billing_mode, t.kind, t.period, "
            + "t.amount, t.tax_rate, t.tax, t.total, t.reason, NULL, t.charged_at + c.n * interval '1 second', "
            + "t.charged_by, t.share_excluded FROM " + table + " c JOIN qb_fee_charge_version t "
            + "ON t.charge_id = ?::uuid", sponsor, operatorId, seeded[0]);
        execute("INSERT INTO qb_fee_payment_version (payment_id, version_no, effect_start_time, created_time, "
            + "process_seq_id, sponsor_id, target_entity, target_id, sub_account, grant_id, cash_lot_id, amount, "
            + "paid_at, transaction_id, prepayment_id) SELECT c.pid, 1, t.effect_start_time, t.created_time, "
            + "t.process_seq_id, ?, t.target_entity, CAST(c.cid AS text), t.sub_account, t.grant_id, t.cash_lot_id, "
            + "t.amount, t.paid_at + c.n * interval '1 second', t.transaction_id, t.prepayment_id FROM " + table
            + " c JOIN qb_fee_payment_version t ON t.payment_id = ?::uuid", sponsor, seeded[1]);
        execute("DROP TABLE " + table);
        // As autovacuum would have by the time a month's settlement runs: without statistics the planner sees one row.
        execute("ANALYZE qb_fee_charge_version");
        execute("ANALYZE qb_fee_payment_version");
    }

    /** Each statement cut short (the batches of lines are megabytes). */
    private static void print(String what, List<String> statements) {
        System.out.println("[statement-budget] " + what + ": " + statements.size());
        for (int i = 0; i < statements.size(); i++) {
            String sql = statements.get(i).replaceAll("\\s+", " ");
            System.out.println(String.format("%3d  %s", i + 1, sql.length() > 160 ? sql.substring(0, 160) + "..." : sql));
        }
    }

    @AfterEach
    void forget() {
        SqlStatementLog.clear();
    }

    /** The statements of the transaction that wrote the settlement's lines, and how long the run took. */
    private record Measured(List<String> statements, Duration took) {}

    private Measured settle(String operatorId, String month, String at) {
        clock.set(Instant.parse(at));
        SqlStatementLog.clear();
        long start = System.nanoTime();
        systemRun(OperatorSettlements.RUN, body("operatorId", operatorId, "month", month));
        Duration took = Duration.ofNanos(System.nanoTime() - start);
        List<List<String>> writing = SqlStatementLog.transactions().stream()
            .filter(t -> t.stream().anyMatch(sql -> sql.contains("INSERT INTO qb_operator_settlement_line_version")))
            .toList();
        assertThat(writing).as("one transaction wrote the lines").hasSize(1);
        return new Measured(writing.getFirst(), took);
    }

    private void measure(int year, int fees, int others, int maxStatements, Duration maxTime, String label) {
        String[] seeded = seed(year, "Budget " + label);
        clone(seeded, fees - 1, seeded[2], seeded[3]);
        if (others > 0) {
            String[] other = seed(year + 1, "Budget other " + label);
            clone(other, others - 1, other[2], other[3]);
        }
        Measured measured = settle(seeded[2], year + "-02", year + "-03-02T00:00:00Z");
        print(label + " (" + 2 * fees + " lines, " + others + " other gift rows)", measured.statements());
        System.out.println("[statement-budget] " + label + ": " + measured.statements().size() + " statements, "
            + measured.took().toMillis() + " ms");
        assertThat(query("SELECT count(*) AS n FROM qb_operator_settlement_line_version WHERE operator_id = ?",
            seeded[2]).getFirst().get("n")).isEqualTo((long) 2 * fees);
        assertThat(measured.statements()).as(label + " statements").hasSizeLessThanOrEqualTo(maxStatements);
        assertThat(measured.took()).as(label + " duration").isLessThanOrEqualTo(maxTime);
    }

    @Test
    @Order(1)
    void aSettlementOf2000LinesSendsExactlyAsManyStatementsAsCounted() {
        param(QbParams.FEE_TAX_RATE, "0.1");
        String[] seeded = seed(2027, "Budget 2000");
        clone(seeded, 999, seeded[2], seeded[3]);
        Measured measured = settle(seeded[2], "2027-02", "2027-03-02T00:00:00Z");
        print("settlement of 2,000 lines", measured.statements());
        assertThat(measured.statements()).hasSize(LINES_2000);
    }

    @Test
    @Order(2)
    void fiveThousandLinesTakeNoMoreThan150StatementsAnd20SecondsWithAnotherOperatorsGiftRowsInTheSchema() {
        measure(2028, 2_500, 5_000, 150, Duration.ofSeconds(20), "5,000 lines");
    }

    @Test
    @Order(3)
    void twentyThousandLinesTakeNoMoreThan450StatementsAnd80Seconds() {
        measure(2030, 10_000, 10_000, 450, Duration.ofSeconds(80), "20,000 lines");
    }
}
