package com.jabiz.runtime.config;

import com.jabiz.runtime.test.PostgresTestDatabase;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V66 builds its index CONCURRENTLY, outside a transaction (ROADMAP 17zz18), and repairs what an earlier failed run
 * left: an INVALID index of its name is dropped and built again, a valid one is kept; a run that failed (here on the
 * platform's lock timeout) is recorded as failed, stops the next start until {@code repair}, then completes. Each test
 * migrates a schema of its own with the production setting of the placeholder.
 */
class ConcurrentIndexMigrationTest {

    private static final PostgresTestDatabase DB = PostgresTestDatabase.get();
    private static final String INDEX = "ledger_entry_version_account_dimension_1_idx";

    private final String schema = "cim_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

    @AfterEach
    void dropSchema() {
        DB.dropSchema(schema);
    }

    private FluentConfiguration application() {
        return Flyway.configure()
            .dataSource(DB.jdbcUrl(), DB.username(), DB.password())
            .schemas(schema)
            .defaultSchema(schema)
            .createSchemas(true)
            .locations("classpath:db/legacyapp");
    }

    /** The platform's scripts as PlatformSchemaMigration runs them, for the steps it does not take (target, repair). */
    private FluentConfiguration platform() {
        return Flyway.configure().configuration(application())
            .locations(PlatformSchemaMigration.PLATFORM_LOCATION)
            .table(PlatformSchemaMigration.PLATFORM_HISTORY_TABLE)
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .configuration(PlatformSchemaMigration.NON_TRANSACTIONAL_LOCK)
            .placeholders(Map.of(PlatformSchemaMigration.CONCURRENTLY_PLACEHOLDER, "CONCURRENTLY"));
    }

    @Test
    void anInvalidIndexOfTheSameNameIsBuiltAgain() throws SQLException {
        platform().target("65").load().migrate();
        // What a concurrent build that failed half way leaves: the name taken by an INVALID index (of another
        // definition here, so that the rebuilt one is told apart).
        leaveInvalidIndex("ledger_entry_version (account_id)");
        assertThat(index()).containsEntry("valid", false);

        new PlatformSchemaMigration().migrate(application().load());

        assertThat(index()).containsEntry("valid", true).satisfies(index -> assertThat((String) index.get("def"))
            .contains("(account_id, dimension_1) WHERE (dimension_1 IS NOT NULL)"));
    }

    @Test
    void aValidIndexOfTheSameNameIsKept() throws SQLException {
        platform().target("65").load().migrate();
        execute("CREATE INDEX " + INDEX + " ON ledger_entry_version (account_id, dimension_1)"
            + " WHERE dimension_1 IS NOT NULL");
        Object oid = index().get("oid");

        new PlatformSchemaMigration().migrate(application().load());

        assertThat(index()).containsEntry("valid", true).containsEntry("oid", oid);
    }

    @Test
    void aFailedRunStopsTheNextStartUntilRepairedThenCompletes() throws SQLException {
        platform().target("65").load().migrate();
        // A transaction that wrote the table and stays open: the concurrent build waits for it, past the lock timeout.
        try (Connection writer = DB.connect(schema); Statement statement = writer.createStatement()) {
            writer.setAutoCommit(false);
            statement.execute("LOCK TABLE ledger_entry_version IN ROW EXCLUSIVE MODE");
            assertThatThrownBy(() -> new PlatformSchemaMigration(Duration.ofMillis(300))
                .migrate(application().load()))
                .hasStackTraceContaining("lock timeout");
            writer.rollback();
        }
        assertThat(single("SELECT success FROM jabiz_schema_history WHERE version = '66'")).isEqualTo(false);
        assertThat(index()).containsEntry("valid", false);

        // The next start stops at validation: the failed run must be looked at.
        assertThatThrownBy(() -> new PlatformSchemaMigration().migrate(application().load()))
            .hasStackTraceContaining("failed migration");

        platform().load().repair();
        new PlatformSchemaMigration().migrate(application().load());

        assertThat(single("SELECT success FROM jabiz_schema_history WHERE version = '66'")).isEqualTo(true);
        assertThat(index()).containsEntry("valid", true).satisfies(index -> assertThat((String) index.get("def"))
            .contains("(account_id, dimension_1) WHERE (dimension_1 IS NOT NULL)"));
    }

    @Test
    void theLockTimeoutFollowsTheApplicationsInitSql() {
        assertThat(PlatformSchemaMigration.initSql(null, Duration.ZERO)).isNull();
        assertThat(PlatformSchemaMigration.initSql("SET work_mem = '8MB'", Duration.ZERO))
            .isEqualTo("SET work_mem = '8MB'");
        assertThat(PlatformSchemaMigration.initSql(null, Duration.ofMinutes(2))).isEqualTo("SET lock_timeout = 120000");
        assertThat(PlatformSchemaMigration.initSql(" SET work_mem = '8MB' ", Duration.ofMillis(1)))
            .isEqualTo("SET work_mem = '8MB';\nSET lock_timeout = 1");
        assertThatThrownBy(() -> new PlatformSchemaMigration(Duration.ofSeconds(-1)))
            .hasMessageContaining(PlatformSchemaMigration.LOCK_TIMEOUT_PROPERTY);
    }

    /**
     * A CREATE INDEX CONCURRENTLY of the index's name that fails after its catalog entry committed: it waits for a
     * transaction holding a lock on the table and gives up on its lock timeout.
     */
    private void leaveInvalidIndex(String on) throws SQLException {
        try (Connection writer = DB.connect(schema); Statement hold = writer.createStatement();
             Connection builder = DB.connect(schema); Statement build = builder.createStatement()) {
            writer.setAutoCommit(false);
            hold.execute("LOCK TABLE ledger_entry_version IN ROW EXCLUSIVE MODE");
            build.execute("SET lock_timeout = 300");
            assertThatThrownBy(() -> build.execute("CREATE INDEX CONCURRENTLY " + INDEX + " ON " + on))
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("55P03"));
            writer.rollback();
        }
    }

    /** The index of V66 in this schema: oid, valid, def. */
    private Map<String, Object> index() throws SQLException {
        try (Connection connection = DB.connect(schema); PreparedStatement statement = connection.prepareStatement(
            "SELECT x.indexrelid::bigint AS oid, x.indisvalid, pg_get_indexdef(x.indexrelid) FROM pg_index x"
                + " JOIN pg_class i ON i.oid = x.indexrelid WHERE i.relname = ? AND i.relnamespace = ?::regnamespace")) {
            statement.setString(1, INDEX);
            statement.setString(2, schema);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).as("index " + INDEX + " exists").isTrue();
                Map<String, Object> index = new LinkedHashMap<>();
                index.put("oid", rs.getLong(1));
                index.put("valid", rs.getBoolean(2));
                index.put("def", rs.getString(3));
                return index;
            }
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = DB.connect(schema); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private Object single(String sql) throws SQLException {
        try (Connection connection = DB.connect(schema); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getObject(1);
        }
    }
}
