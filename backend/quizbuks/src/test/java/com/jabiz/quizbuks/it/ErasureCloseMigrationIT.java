package com.jabiz.quizbuks.it;

import com.jabiz.runtime.integrity.IntegrityStore;
import com.jabiz.runtime.test.PostgresTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F-M15c's migration (V131): the uses of credit notes written before it are left exactly as they were (the new column
 * empty, every other value the same, so the integrity seal's digests over the old columns still match), and from then
 * on a use names exactly one of an invoice and a posting given back by; an AI credit may be released for an erasure, its movement be
 * a close, and a wind-up wait on AI jobs. Its own schema, migrated by Flyway as the platform does, without the
 * application.
 */
class ErasureCloseMigrationIT {

    private static final PostgresTestDatabase DB = PostgresTestDatabase.get();
    private static final String MIGRATION = "131";

    private final List<String> schemas = new ArrayList<>();

    @AfterEach
    void dropSchemas() {
        schemas.forEach(DB::dropSchema);
    }

    @Test
    void theUsesBeforeAreUntouchedAndEachUseNamesOneTarget() throws Exception {
        String schema = schemaBefore();
        String invoiceUse;
        Map<String, Object> before;
        try (Connection c = DB.connect(schema)) {
            long process = process(c);
            invoiceUse = use(c, process, "'inv-1'", "NULL");
            before = rows(c, "SELECT * FROM qb_fee_credit_note_use_version WHERE use_id = '" + invoiceUse + "'")
                .getFirst();
        }

        migrate(schema, null);

        try (Connection c = DB.connect(schema)) {
            Map<String, Object> after = new LinkedHashMap<>(rows(c,
                "SELECT * FROM qb_fee_credit_note_use_version WHERE use_id = '" + invoiceUse + "'").getFirst());
            assertThat(after.remove("back_transaction_id")).isNull();
            assertThat(after).isEqualTo(before);
            long process = process(c);
            // Exactly one of the two.
            use(c, process, "NULL", "'tx-1'");
            assertThatThrownBy(() -> use(c, process, "NULL", "NULL")).isInstanceOf(SQLException.class)
                .hasMessageContaining("qb_fee_credit_note_use_target");
            assertThatThrownBy(() -> use(c, process, "'inv-2'", "'tx-2'")).isInstanceOf(SQLException.class)
                .hasMessageContaining("qb_fee_credit_note_use_target");
            // The new values the constraints allow.
            execute(c, "INSERT INTO qb_prepayment (prepayment_id, sponsor_id, purpose, mode, amount, remaining, "
                + "charge_amount, status, frozen_at, released_at, release_reason, version) VALUES (?, 's', 'AI', "
                + "'PREPAID', 100, 0, 0, 'RELEASED', now(), now(), 'ERASED', 1)", UUID.randomUUID().toString());
            execute(c, "INSERT INTO qb_sponsor_closure (closure_id, sponsor_id, status, reason_code, erased_at, "
                + "version) VALUES (?, 's', 'WAITING', 'AI_JOBS_OPEN', now(), 1)", UUID.randomUUID().toString());
            assertThat(rows(c, "SELECT pg_get_constraintdef(oid) AS d FROM pg_constraint WHERE conname = "
                + "'qb_ai_credit_movement_version_kind_check' AND connamespace = '" + schema + "'::regnamespace")
                .getFirst().get("d").toString()).contains("CLOSE");
        }
    }

    /**
     * Review L2: the seal's digests. A use sealed before V131 is verified after it over the columns of its own block
     * (the platform's {@link IntegrityStore#digest}): the added column is not among them and every other value is the
     * same, so it still verifies. A use of the new shape (no invoice, a posting given back by) is sealed over all the
     * columns now, and its digest over the column list is the whole row's (what verifying a later block compares).
     */
    @Test
    void aUseSealedBeforeStillVerifiesAndOneOfTheNewShapeSealsAndVerifies() throws Exception {
        String schema = schemaBefore();
        String oldUse;
        String sealedDigest;
        String[] sealedColumns;
        try (Connection c = DB.connect(schema)) {
            oldUse = use(c, process(c), "'inv-1'", "NULL");
            sealedColumns = columns(c);
            sealedDigest = digestOf(c, oldUse, null);
        }

        migrate(schema, null);

        try (Connection c = DB.connect(schema)) {
            assertThat(columns(c)).contains("back_transaction_id").hasSize(sealedColumns.length + 1);
            assertThat(digestOf(c, oldUse, sealedColumns)).as("verified over its block's columns")
                .isEqualTo(sealedDigest);
            String newUse = use(c, process(c), "NULL", "'tx-1'");
            String sealedNow = digestOf(c, newUse, null);
            assertThat(digestOf(c, newUse, columns(c))).as("over all the columns now").isEqualTo(sealedNow);
            assertThat(digestOf(c, newUse, sealedColumns)).as("the given-back posting is digested")
                .isNotEqualTo(sealedNow);
        }
    }

    // ---- schemas -----------------------------------------------------------------------------------------------------

    private String schemaBefore() {
        String schema = "it_erclose_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        schemas.add(schema);
        PlatformScripts.migrate(DB, schema);
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
            .defaultSchema(schema).locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("0")
            .placeholders(Map.of("concurrently", ""));
        if (target != null) {
            config.target(target);
        }
        config.load().migrate();
    }

    // ---- rows --------------------------------------------------------------------------------------------------------

    private static long process(Connection c) throws SQLException {
        long seq = (Long) rows(c, "SELECT nextval('op_process_seq') AS n").getFirst().get("n");
        execute(c, "INSERT INTO op_process (process_seq_id, process_name, process_version, actor_id, op_time) "
            + "VALUES (?, 'it', 1, 'it', now())", seq);
        return seq;
    }

    /** A use of a note's credit naming {@code invoice} and {@code lot} (SQL literals); its id. */
    private static String use(Connection c, long process, String invoice, String lot) throws SQLException {
        UUID id = UUID.randomUUID();
        execute(c, "INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) VALUES (?, 'QbFeeCreditNoteUse', "
            + "?)", id, process);
        String columns = lot == null ? "" : ", back_transaction_id";
        boolean migrated = !rows(c, "SELECT 1 FROM information_schema.columns WHERE table_schema = current_schema() "
            + "AND table_name = 'qb_fee_credit_note_use_version' AND column_name = 'back_transaction_id'").isEmpty();
        execute(c, "INSERT INTO qb_fee_credit_note_use_version (use_id, version_no, effect_start_time, created_time, "
            + "process_seq_id, credit_note_id, sponsor_id, invoice_id" + (migrated ? columns : "") + ", amount, used_at) "
            + "VALUES (?, 1, now(), now(), ?, 'note-1', 's', " + invoice + (migrated ? ", " + lot : "")
            + ", 100, now())", id, process);
        return id.toString();
    }

    /** The use table's columns now, by name (what a seal now records). */
    private static String[] columns(Connection c) throws SQLException {
        return rows(c, "SELECT a.attname::text AS n FROM pg_attribute a WHERE a.attrelid = "
            + "'qb_fee_credit_note_use_version'::regclass AND a.attnum > 0 AND NOT a.attisdropped ORDER BY 1").stream()
            .map(row -> row.get("n").toString()).toArray(String[]::new);
    }

    /** The use's digest as the platform seals it (whole row) or verifies it over a block's {@code columns}. */
    private static String digestOf(Connection c, String useId, String[] columns) throws SQLException {
        String expression = columns == null
            ? "encode(pg_catalog.sha256(convert_to(to_jsonb(t.*)::text, 'UTF8')), 'hex')"
            : IntegrityStore.digest("t", "?::text[]");
        try (PreparedStatement statement = c.prepareStatement("SELECT " + expression
            + " AS d FROM qb_fee_credit_note_use_version t WHERE t.use_id = ?::uuid")) {
            int i = 1;
            if (columns != null) {
                statement.setArray(i++, c.createArrayOf("text", columns));
            }
            statement.setString(i, useId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getString("d");
            }
        }
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
