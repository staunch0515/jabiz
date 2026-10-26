package com.jabiz.runtime.test;

import com.jabiz.runtime.integrity.IntegrityStore;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.configuration.FluentConfiguration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The migrations keep every sealed column (decision D58; docs/design/21-audit-retention.md section 2.1). A block of
 * the integrity seal records the columns of each append-only table as it was sealed and verifying digests the rows
 * over those columns again, so a column renamed, dropped or given another type (a numeric's scale included) makes the
 * rows sealed before report as MODIFIED, a dropped table its rows as MISSING. Columns may only be added.
 *
 * <p>This runs a set of migrations version by version in a schema of its own and records, after each, the columns and
 * types of every append-only table, found as the seal finds them; whatever version an installation was sealed at,
 * each of those must still be there once every migration ran. The platform checks its own {@code db/jabiz}
 * ({@link #platform}); an application checks its {@code db/migration} after the platform's ({@link #application}):
 * <pre>{@code
 * assertThat(SealedColumnsHistory.application(PostgresTestDatabase.get(), "classpath:db/migration").check().lost())
 *     .isEmpty();
 * }</pre>
 * A version no installation ever stopped at (unreleased, undone by the next migration of the same release) may be
 * left out with {@link #neverReleased}, giving the reason.
 */
public final class SealedColumnsHistory {

    private static final String PLATFORM = "classpath:db/jabiz";
    private static final String PLATFORM_HISTORY = "jabiz_schema_history";

    /**
     * What the run found.
     *
     * @param lost   each sealed column (or table) the latest version no longer has as it was, described
     * @param tables the append-only tables of the latest version, so that a test can see it checked what it meant to
     */
    public record Result(List<String> lost, Set<String> tables) {}

    private final PostgresTestDatabase db;
    private final String location;
    private final Map<String, String> neverReleased = new LinkedHashMap<>();

    private SealedColumnsHistory(PostgresTestDatabase db, String location) {
        this.db = Objects.requireNonNull(db, "db must not be null");
        this.location = location;
    }

    /** The platform's own migrations. */
    public static SealedColumnsHistory platform(PostgresTestDatabase db) {
        return new SealedColumnsHistory(db, null);
    }

    /** An application's migrations at {@code location} ({@code classpath:db/migration}), after the platform's. */
    public static SealedColumnsHistory application(PostgresTestDatabase db, String location) {
        return new SealedColumnsHistory(db, Objects.requireNonNull(location, "location must not be null"));
    }

    /** Leaves a version out: no installation ever stopped at it, so nothing was sealed there. */
    public SealedColumnsHistory neverReleased(String version, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Say why version " + version + " was never released");
        }
        neverReleased.put(version, reason);
        return this;
    }

    /** Runs the migrations in a new schema, dropped afterwards. */
    public Result check() {
        String schema = "it_sealed_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Map<String, Map<String, Map<String, String>>> byVersion = new LinkedHashMap<>();
        Callback snapshot = new Callback() {
            @Override
            public boolean supports(Event event, Context context) {
                return event == Event.AFTER_EACH_MIGRATE;
            }

            @Override
            public boolean canHandleInTransaction(Event event, Context context) {
                return true;
            }

            @Override
            public void handle(Event event, Context context) {
                if (context.getMigrationInfo() == null || context.getMigrationInfo().getVersion() == null) {
                    return; // a repeatable migration: no version an installation stops at
                }
                try {
                    byVersion.put(context.getMigrationInfo().getVersion().getVersion(),
                        appendOnlyColumns(context.getConnection()));
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public String getCallbackName() {
                return "sealed-columns-history";
            }
        };
        try {
            FluentConfiguration platform = configure(schema).createSchemas(true).locations(PLATFORM)
                .table(PLATFORM_HISTORY);
            if (location == null) {
                platform.callbacks(snapshot);
            }
            platform.load().migrate();
            if (location != null) {
                configure(schema).locations(location).callbacks(snapshot).load().migrate();
            }
        } finally {
            db.dropSchema(schema);
        }
        if (byVersion.isEmpty()) {
            return new Result(List.of(), Set.of());
        }
        Map<String, Map<String, String>> latest = byVersion.values().stream().reduce((a, b) -> b).orElseThrow();
        byVersion.keySet().removeAll(neverReleased.keySet());
        Set<String> lost = new TreeSet<>();
        byVersion.forEach((version, tables) -> tables.forEach((table, columns) -> {
            Map<String, String> now = latest.get(table);
            if (now == null) {
                lost.add(table + " of V" + version + " is gone (or no longer append-only)");
                return;
            }
            columns.forEach((column, type) -> {
                if (!type.equals(now.get(column))) {
                    lost.add(table + "." + column + " (" + type + ") of V" + version + " is "
                        + (now.containsKey(column) ? now.get(column) : "gone"));
                }
            });
        }));
        return new Result(List.copyOf(lost), Set.copyOf(latest.keySet()));
    }

    private FluentConfiguration configure(String schema) {
        return Flyway.configure().dataSource(db.jdbcUrl(), db.username(), db.password()).schemas(schema)
            .defaultSchema(schema).baselineOnMigrate(true).baselineVersion("0");
    }

    /** Each append-only table (as the seal finds them, its own tables aside), its columns and their types. */
    private static Map<String, Map<String, String>> appendOnlyColumns(Connection connection) throws SQLException {
        Map<String, Map<String, String>> tables = new TreeMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("""
                 SELECT c.relname AS table_name, a.attname AS column_name,
                        format_type(a.atttypid, a.atttypmod) AS column_type
                 FROM pg_class c
                 JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = current_schema()
                 JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
                 WHERE c.relkind IN ('r', 'p')
                   AND EXISTS (SELECT 1 FROM pg_trigger t JOIN pg_proc p ON p.oid = t.tgfoid
                               WHERE t.tgrelid = c.oid AND NOT t.tgisinternal
                                 AND p.proname = 'jabiz_reject_mutation' AND (t.tgtype & 1) <> 0)""")) {
            while (rs.next()) {
                String table = rs.getString("table_name");
                if (!IntegrityStore.OWN_TABLES.contains(table)) {
                    tables.computeIfAbsent(table, t -> new TreeMap<>())
                        .put(rs.getString("column_name"), rs.getString("column_type"));
                }
            }
        }
        return tables;
    }
}
