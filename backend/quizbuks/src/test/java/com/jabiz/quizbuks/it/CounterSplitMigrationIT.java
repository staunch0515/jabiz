package com.jabiz.quizbuks.it;

import com.jabiz.runtime.test.PostgresTestDatabase;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The migration of the lock split (V10, docs/quizbuks/plans/Q10-lock-split.md 3, 6.4): counters written before it
 * (V9) are carried over to the two sides with {@code reservedTotal - released} the old {@code reserved} exactly; a row
 * that does not hold the old invariants fails the migration as a whole, the database staying at V9. Its own schemas,
 * migrated by Flyway as the platform does (its scripts, then the application's), without the application.
 */
class CounterSplitMigrationIT {

    private static final PostgresTestDatabase DB = PostgresTestDatabase.get();

    private final List<String> schemas = new ArrayList<>();

    @AfterEach
    void dropSchemas() {
        schemas.forEach(DB::dropSchema);
    }

    /** A counters row as V9 has it and its card's highest reward. */
    record Old(long starts, long completions, long passes, long winners, long reserved, long spent, long maxReward) {}

    @Test
    void theCountersOfV9AreSplitWithTheReserveKept() throws Exception {
        String schema = schemaAtV9();
        Map<UUID, Old> rows = new LinkedHashMap<>();
        // Never started; in progress; ended by its winners; ended by its budget with lower rewards; another reward.
        rows.put(UUID.randomUUID(), new Old(0, 0, 0, 0, 0, 0, 100));
        rows.put(UUID.randomUUID(), new Old(5, 2, 2, 1, 300, 100, 100));
        rows.put(UUID.randomUUID(), new Old(10, 10, 10, 10, 0, 1000, 100));
        rows.put(UUID.randomUUID(), new Old(4, 4, 3, 3, 0, 250, 100));
        rows.put(UUID.randomUUID(), new Old(7, 3, 1, 1, 4 * 40, 40, 40));
        try (Connection connection = DB.connect(schema)) {
            rows.forEach((id, old) -> insert(connection, id, old));
        }

        migrate(schema, null);

        try (Connection connection = DB.connect(schema)) {
            assertThat(columns(connection, "qb_publication_counter")).containsExactlyInAnyOrder("publication_id",
                "completions", "passes", "winners", "released", "spent", "version");
            assertThat(columns(connection, "qb_publication_start_counter")).containsExactlyInAnyOrder(
                "publication_id", "starts", "reserved_total", "version");
            for (Map.Entry<UUID, Old> row : rows.entrySet()) {
                Old old = row.getValue();
                Map<String, Object> split = select(connection, "SELECT s.starts, s.reserved_total, n.completions, "
                    + "n.passes, n.winners, n.released, n.spent, s.reserved_total - n.released AS reserved "
                    + "FROM qb_publication_counter n JOIN qb_publication_start_counter s "
                    + "ON s.publication_id = n.publication_id WHERE n.publication_id = ?", row.getKey());
                assertThat(split).as(String.valueOf(old))
                    .containsEntry("starts", d(old.starts())).containsEntry("completions", d(old.completions()))
                    .containsEntry("passes", d(old.passes())).containsEntry("winners", d(old.winners()))
                    .containsEntry("spent", d(old.spent()))
                    .containsEntry("reserved_total", d(old.starts() * old.maxReward()))
                    .containsEntry("released", d(old.completions() * old.maxReward()))
                    .containsEntry("reserved", d(old.reserved()));
            }
            assertThat(select(connection, "SELECT count(*) AS n FROM qb_publication_start_counter", null))
                .containsEntry("n", (long) rows.size());
            // Without a default from now on: a new row names what it released.
            assertThat(select(connection, "SELECT column_default FROM information_schema.columns WHERE table_schema "
                + "= current_schema() AND table_name = 'qb_publication_counter' AND column_name = 'released'", null))
                .containsEntry("column_default", null);
        }
    }

    @Test
    void aRowThatBreaksTheOldInvariantsFailsTheMigrationAsAWhole() throws Exception {
        // Reserved other than (starts - completions) x the highest reward; more completions than starts.
        for (Old bad : List.of(new Old(3, 1, 1, 1, 100, 100, 100), new Old(1, 2, 2, 2, 0, 200, 100))) {
            String schema = schemaAtV9();
            try (Connection connection = DB.connect(schema)) {
                insert(connection, UUID.randomUUID(), new Old(2, 1, 1, 1, 100, 100, 100));
                insert(connection, UUID.randomUUID(), bad);
            }
            assertThatThrownBy(() -> migrate(schema, null)).isInstanceOf(FlywayException.class)
                .hasMessageContaining("does not hold its invariants");
            assertStillAtV9(schema);
        }
        // A counters row without its card.
        String schema = schemaAtV9();
        try (Connection connection = DB.connect(schema)) {
            UUID id = UUID.randomUUID();
            registry(connection, id);
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO qb_publication_counter "
                + "(publication_id, starts, completions, passes, winners, reserved, spent, version) "
                + "VALUES (?, 0, 0, 0, 0, 0, 0, 1)")) {
                statement.setObject(1, id);
                statement.execute();
            }
        }
        assertThatThrownBy(() -> migrate(schema, null)).isInstanceOf(FlywayException.class)
            .hasMessageContaining("does not hold its invariants");
        assertStillAtV9(schema);
    }

    private static void assertStillAtV9(String schema) throws SQLException {
        try (Connection connection = DB.connect(schema)) {
            assertThat(select(connection, "SELECT max(version::int) AS v FROM flyway_schema_history WHERE success",
                null)).containsEntry("v", 9);
            assertThat(select(connection, "SELECT to_regclass('qb_publication_start_counter') IS NULL AS absent",
                null)).containsEntry("absent", true);
            assertThat(columns(connection, "qb_publication_counter")).contains("starts", "reserved")
                .doesNotContain("released");
        }
    }

    // ---- schemas -----------------------------------------------------------------------------------------------------

    /** A new schema with the platform's scripts and the application's up to V9. */
    private String schemaAtV9() {
        String schema = "it_split_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        schemas.add(schema);
        Flyway.configure().dataSource(DB.jdbcUrl(), DB.username(), DB.password()).schemas(schema)
            .defaultSchema(schema).createSchemas(true).locations("classpath:db/jabiz").table("jabiz_schema_history")
            .baselineOnMigrate(true).baselineVersion("0").load().migrate();
        migrate(schema, "9");
        return schema;
    }

    /** The application's scripts up to {@code target} (all when null), as the platform runs them. */
    private static void migrate(String schema, String target) {
        var config = Flyway.configure().dataSource(DB.jdbcUrl(), DB.username(), DB.password()).schemas(schema)
            .defaultSchema(schema).locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("0");
        if (target != null) {
            config.target(target);
        }
        config.load().migrate();
    }

    // ---- rows --------------------------------------------------------------------------------------------------------

    private static void insert(Connection connection, UUID id, Old old) {
        try {
            registry(connection, id);
            try (PreparedStatement card = connection.prepareStatement("INSERT INTO qb_publication_card "
                + "(publication_id, sponsor_id, status, regions, start_at, end_at, name, quiz_title, version_id, "
                + "max_reward, full_score, pass_score, time_limit_sec, question_count, tiers, went_on_at, version) "
                + "VALUES (?, 'sponsor', 'LIVE', 'GLOBAL', now(), now() + interval '7 days', 'Old', 'Old', ?, ?, 5, 3, "
                + "300, 2, '[]', now(), 1)")) {
                card.setObject(1, id);
                card.setObject(2, UUID.randomUUID());
                card.setBigDecimal(3, d(old.maxReward()));
                card.execute();
            }
            try (PreparedStatement counter = connection.prepareStatement("INSERT INTO qb_publication_counter "
                + "(publication_id, starts, completions, passes, winners, reserved, spent, version) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, 3)")) {
                counter.setObject(1, id);
                counter.setBigDecimal(2, d(old.starts()));
                counter.setBigDecimal(3, d(old.completions()));
                counter.setBigDecimal(4, d(old.passes()));
                counter.setBigDecimal(5, d(old.winners()));
                counter.setBigDecimal(6, d(old.reserved()));
                counter.setBigDecimal(7, d(old.spent()));
                counter.execute();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The publication's entry in the platform's registry (the counters and the card refer to it). */
    private static void registry(Connection connection, UUID id) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO op_process (process_seq_id, process_name, process_version, actor_id, "
                + "op_time) VALUES (nextval('op_process_seq'), 'IT', 1, 'it', now())");
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO entity_registry (entity_id, "
            + "entity_type, created_seq_id) VALUES (?, 'QbPublication', currval('op_process_seq'))")) {
            statement.setObject(1, id);
            statement.execute();
        }
    }

    private static List<String> columns(Connection connection, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT column_name FROM "
            + "information_schema.columns WHERE table_schema = current_schema() AND table_name = ?")) {
            statement.setString(1, table);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getString(1));
                }
            }
        }
        return columns;
    }

    private static Map<String, Object> select(Connection connection, String sql, Object parameter)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (parameter != null) {
                statement.setObject(1, parameter);
            }
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).as(sql).isTrue();
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                }
                return row;
            }
        }
    }

    private static BigDecimal d(long value) {
        return BigDecimal.valueOf(value);
    }
}
