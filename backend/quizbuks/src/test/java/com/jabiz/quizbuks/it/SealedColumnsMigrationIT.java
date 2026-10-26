package com.jabiz.quizbuks.it;

import com.jabiz.runtime.test.PostgresTestDatabase;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application's migrations keep every sealed column (docs/design/21-audit-retention.md section 2.1): a block of
 * the platform's integrity seal records the columns of each append-only table at the time it was sealed, and
 * verifying digests those columns again, so a column renamed, dropped or changed in type afterwards makes every row
 * sealed before report as MODIFIED. Columns may only be added. So whatever version an installation was at when its
 * rows were sealed (1.2 was at V28), each column an append-only table had then must still be there, of the same type,
 * once every migration ran.
 */
class SealedColumnsMigrationIT {

    private static final PostgresTestDatabase DB = PostgresTestDatabase.get();

    /**
     * Versions no installation ever stopped at, so nothing was sealed there. V29 renamed the sealed sponsor_id and V30
     * renamed it back; both ship in the same release (1.3) and run in one upgrade with the application stopped. Add a
     * version here only when it is unreleased and the next migration of the same release undoes it.
     */
    private static final Set<String> NEVER_RELEASED = Set.of("29");

    private final List<String> schemas = new ArrayList<>();

    @AfterEach
    void dropSchemas() {
        schemas.forEach(DB::dropSchema);
    }

    @Test
    void noMigrationRenamesDropsOrRetypesAColumnOfAnAppendOnlyTable() throws SQLException {
        String schema = "it_sealed_cols_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        schemas.add(schema);
        Flyway.configure().dataSource(DB.jdbcUrl(), DB.username(), DB.password()).schemas(schema)
            .defaultSchema(schema).createSchemas(true).locations("classpath:db/jabiz").table("jabiz_schema_history")
            .baselineOnMigrate(true).baselineVersion("0").load().migrate();

        // The columns each append-only table had after each of the application's versions.
        Map<String, Map<String, Map<String, String>>> byVersion = new LinkedHashMap<>();
        for (MigrationInfo migration : application(schema).load().info().pending()) {
            String version = migration.getVersion().getVersion();
            application(schema).target(version).load().migrate();
            byVersion.put(version, appendOnlyColumns(schema));
        }
        Map<String, Map<String, String>> latest = byVersion.values().stream().reduce((a, b) -> b).orElseThrow();

        // Types with their modifiers: a numeric's scale is part of the value to_jsonb digests.
        List<String> lost = new ArrayList<>();
        byVersion.keySet().removeAll(NEVER_RELEASED);
        byVersion.forEach((version, tables) -> tables.forEach((table, columns) -> columns.forEach((column, type) -> {
            Map<String, String> now = latest.get(table);
            if (now == null) {
                // Dropped, renamed or no longer append-only: its sealed rows would be reported missing.
                lost.add(table + " of V" + version + " is gone");
            } else if (!type.equals(now.get(column))) {
                lost.add(table + "." + column + " (" + type + ") of V" + version + " is "
                    + (now.containsKey(column) ? now.get(column) : "gone"));
            }
        })));
        assertThat(new TreeSet<>(lost)).as("sealed columns that verifying would no longer find").isEmpty();
        // The check saw the tables it is about.
        assertThat(latest).containsKeys("qb_publication_version", "qb_reward_tier_version", "qb_cash_reward_version",
            "qb_redemption_version", "qb_digital_prize_version");
    }

    /** The application's scripts, run as the platform runs them (its own history table, after the platform's). */
    private static FluentConfiguration application(String schema) {
        return Flyway.configure().dataSource(DB.jdbcUrl(), DB.username(), DB.password()).schemas(schema)
            .defaultSchema(schema).locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("0");
    }

    /** Each table guarded by the platform's append-only trigger (as the seal finds them), its columns and types. */
    private static Map<String, Map<String, String>> appendOnlyColumns(String schema) throws SQLException {
        Map<String, Map<String, String>> tables = new TreeMap<>();
        try (Connection connection = DB.connect(schema); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("""
                 SELECT c.relname AS table_name, a.attname AS column_name,
                        format_type(a.atttypid, a.atttypmod) AS column_type
                   FROM pg_class c
                   JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = current_schema()
                   JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
                  WHERE c.relkind = 'r'
                    AND EXISTS (SELECT 1 FROM pg_trigger t JOIN pg_proc p ON p.oid = t.tgfoid
                                 WHERE t.tgrelid = c.oid AND p.proname = 'jabiz_reject_mutation')""")) {
            while (rs.next()) {
                tables.computeIfAbsent(rs.getString("table_name"), t -> new TreeMap<>())
                    .put(rs.getString("column_name"), rs.getString("column_type"));
            }
        }
        return tables;
    }
}
