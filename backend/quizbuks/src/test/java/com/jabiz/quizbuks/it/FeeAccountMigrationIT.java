package com.jabiz.quizbuks.it;

import com.jabiz.runtime.test.PostgresTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The migration of the account that pays the platform's fees (F-M1, docs/quizbuks/plans/impl/F-M.md §M1): each
 * Sponsor's cash as the ledger has it (2160, credit less debit) becomes one lot of its own ({@code OTHER}, the record
 * {@code MIGRATION}); a Sponsor whose cash is 0 gets none, nor does an account other than 2160; an invoice paid
 * before has its total as its paid amount, an open one 0. Its own schemas, migrated by Flyway as the platform does,
 * without the application.
 */
class FeeAccountMigrationIT {

    private static final PostgresTestDatabase DB = PostgresTestDatabase.get();
    private static final String MIGRATION = "103";

    private final List<String> schemas = new ArrayList<>();
    private long seq;

    @AfterEach
    void dropSchemas() {
        schemas.forEach(DB::dropSchema);
    }

    @Test
    void eachSponsorsCashBecomesOneLotAndPaidInvoicesTheirPaidAmount() throws Exception {
        String schema = schemaBefore();
        String withCash = "sponsor-cash-" + UUID.randomUUID();
        String spentAll = "sponsor-spent-" + UUID.randomUUID();
        String kudosOnly = "sponsor-kudos-" + UUID.randomUUID();
        try (Connection c = DB.connect(schema)) {
            long process = process(c);
            String cash = account(c, process, "2160", "LIABILITY");
            String stripe = account(c, process, "1110", "ASSET");
            String kudos = account(c, process, "2110", "LIABILITY");
            // 1000 in, 300 out: 700 left. 500 in and out: nothing. Kudos are not cash.
            posting(c, process, cash, "CREDIT", stripe, 1000, withCash);
            posting(c, process, cash, "DEBIT", stripe, 300, withCash);
            posting(c, process, cash, "CREDIT", stripe, 500, spentAll);
            posting(c, process, cash, "DEBIT", stripe, 500, spentAll);
            posting(c, process, kudos, "CREDIT", stripe, 2000, kudosOnly);
            invoice(c, withCash, "QBF-2026-90001", "2026-09", "PAID", true);
            invoice(c, withCash, "QBF-2026-90002", "2026-10", "OPEN", false);
        }

        migrate(schema, null);

        try (Connection c = DB.connect(schema)) {
            List<Map<String, Object>> lots = rows(c, "SELECT sponsor_id, source, amount, remaining, source_entity, "
                + "source_id, arrived_at FROM qb_cash_lot ORDER BY sponsor_id");
            assertThat(lots).singleElement().satisfies(lot -> {
                assertThat(lot).containsEntry("sponsor_id", withCash).containsEntry("source", "OTHER")
                    .containsEntry("source_entity", "MIGRATION").containsEntry("source_id", null);
                assertThat((BigDecimal) lot.get("amount")).isEqualByComparingTo("700");
                assertThat((BigDecimal) lot.get("remaining")).isEqualByComparingTo("700");
                assertThat(lot.get("arrived_at")).isNotNull();
            });
            assertThat(rows(c, "SELECT invoice_no, paid_amount FROM qb_fee_invoice ORDER BY invoice_no"))
                .extracting(row -> row.get("invoice_no") + ":" + ((BigDecimal) row.get("paid_amount")).toPlainString())
                .containsExactly("QBF-2026-90001:1100", "QBF-2026-90002:0");
            // The new kind of wallet and the invoices' new status are allowed.
            execute(c, "INSERT INTO qb_wallet (wallet_id, owner_id, kind, balance, pending_count, pending_amount, "
                + "pending_out, version) VALUES (?, ?, 'FEE_CREDIT', 0, 0, 0, 0, 1)", UUID.randomUUID().toString(),
                withCash);
            execute(c, "UPDATE qb_fee_invoice SET status = 'PARTIAL', paid_amount = 100 WHERE invoice_no = ?",
                "QBF-2026-90002");
        }
    }

    // ---- schemas -----------------------------------------------------------------------------------------------------

    private String schemaBefore() {
        String schema = "it_feeacct_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        schemas.add(schema);
        Flyway.configure().dataSource(DB.jdbcUrl(), DB.username(), DB.password()).schemas(schema)
            .defaultSchema(schema).createSchemas(true).locations("classpath:db/jabiz").table("jabiz_schema_history")
            .baselineOnMigrate(true).baselineVersion("0").load().migrate();
        migrate(schema, before(schema));
        return schema;
    }

    /** The latest migration before this one that the branch has (whichever of the earlier ones are merged). */
    private static String before(String schema) {
        return java.util.Arrays.stream(Flyway.configure().dataSource(DB.jdbcUrl(), DB.username(), DB.password())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().info().all())
            .map(org.flywaydb.core.api.MigrationInfo::getVersion)
            .filter(version -> version.isNewerThan("0") && !version.isAtLeast(MIGRATION))
            .max(org.flywaydb.core.api.MigrationVersion::compareTo).orElseThrow().getVersion();
    }

    private static void migrate(String schema, String target) {
        var config = Flyway.configure().dataSource(DB.jdbcUrl(), DB.username(), DB.password()).schemas(schema)
            .defaultSchema(schema).locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("0");
        if (target != null) {
            config.target(target);
        }
        config.load().migrate();
    }

    // ---- rows --------------------------------------------------------------------------------------------------------

    private long process(Connection c) throws SQLException {
        seq = (Long) rows(c, "SELECT nextval('op_process_seq') AS n").getFirst().get("n");
        execute(c, "INSERT INTO op_process (process_seq_id, process_name, process_version, actor_id, op_time) "
            + "VALUES (?, 'it', 1, 'it', now())", seq);
        return seq;
    }

    private static UUID registered(Connection c, long process, String type) throws SQLException {
        UUID id = UUID.randomUUID();
        execute(c, "INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) VALUES (?, ?, ?)", id, type,
            process);
        return id;
    }

    private static String account(Connection c, long process, String code, String kind) throws SQLException {
        UUID id = registered(c, process, "LedgerAccount");
        execute(c, "INSERT INTO ledger_account_version (account_id, version_no, effect_start_time, created_time, "
            + "process_seq_id, account_code, account_name, account_type) VALUES (?, 1, now(), now(), ?, ?, ?, ?)",
            id, process, code, code, kind);
        return id.toString();
    }

    /** One balanced transaction: {@code account} in {@code direction} for the party, the other side on {@code other}. */
    private static void posting(Connection c, long process, String account, String direction, String other,
        long amount, String party) throws SQLException {
        UUID tx = registered(c, process, "LedgerTransaction");
        execute(c, "INSERT INTO ledger_transaction_version (transaction_id, version_no, effect_start_time, "
            + "created_time, process_seq_id, booking_time, description) VALUES (?, 1, now(), now(), ?, now(), 'it')",
            tx, process);
        UUID first = registered(c, process, "LedgerEntry");
        UUID second = registered(c, process, "LedgerEntry");
        // Both lines in one statement: the balance is checked when it ends.
        execute(c, "INSERT INTO ledger_entry_version (entry_id, version_no, effect_start_time, created_time, "
            + "process_seq_id, transaction_id, account_id, line_no, direction, amount, dimension_1) VALUES "
            + "(?, 1, now(), now(), ?, ?, ?::uuid, 1, ?, ?, ?), (?, 1, now(), now(), ?, ?, ?::uuid, 2, ?, ?, NULL)",
            first, process, tx, account, direction, amount, party,
            second, process, tx, other, "CREDIT".equals(direction) ? "DEBIT" : "CREDIT", amount);
    }

    private static void invoice(Connection c, String sponsor, String no, String period, String status, boolean paid)
        throws SQLException {
        execute(c, "INSERT INTO qb_fee_invoice (invoice_id, invoice_no, sponsor_id, billing_mode, period, amount, tax, "
            + "total, status, issued_at, due_on, paid_at, payment_transaction_id, version) VALUES (?, ?, ?, 'PREPAID', "
            + "?, 1000, 100, 1100, ?, now(), current_date, ?, ?, 1)", UUID.randomUUID().toString(), no,
            sponsor, period, status, paid ? java.sql.Timestamp.valueOf("2026-10-02 00:00:00") : null,
            paid ? UUID.randomUUID().toString() : null);
    }

    private static void execute(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setObject(i + 1, values[i]);
            }
            statement.execute();
        }
    }

    private static List<Map<String, Object>> rows(Connection c, String sql) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql); ResultSet result = statement.executeQuery()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            while (result.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) {
                    row.put(result.getMetaData().getColumnLabel(i), result.getObject(i));
                }
                rows.add(row);
            }
            return rows;
        }
    }
}
