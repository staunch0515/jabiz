package com.jabiz.runtime.test;

import com.jabiz.runtime.integrity.IntegrityStore;
import com.jabiz.runtime.config.PlatformSchemaMigration;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.configuration.FluentConfiguration;

import java.sql.Connection;
import java.sql.PreparedStatement;
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
import java.util.function.Consumer;

/**
 * The migrations keep every sealed column (decision D58; docs/design/21-audit-retention.md section 2.1). A block of
 * the integrity seal records the columns of each append-only table as it was sealed and verifying digests the rows
 * over those columns again, so a column renamed, dropped or given another type (a numeric's scale included; text of
 * another length and integers of another width read the same) makes the rows sealed before report as MODIFIED, a
 * dropped table its rows as MISSING. Columns may only be added.
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
    private Consumer<FluentConfiguration> settings = configuration -> { };

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

    /**
     * The application's own Flyway settings, where it has any (placeholders, another prefix or history table): the
     * migrations run as they run deployed. The platform's location and history table are not changed.
     */
    public SealedColumnsHistory flyway(Consumer<FluentConfiguration> settings) {
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
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
        // As PlatformSchemaMigration runs them: the platform's, then the application's.
        RuntimeException failure = null;
        try {
            FluentConfiguration platform = configure(schema).createSchemas(true)
                .locations(PlatformSchemaMigration.PLATFORM_LOCATION)
                .table(PlatformSchemaMigration.PLATFORM_HISTORY_TABLE);
            if (location == null) {
                platform.callbacks(snapshot);
            }
            platform.load().migrate();
            if (location != null) {
                FluentConfiguration application = configure(schema).locations(location);
                settings.accept(application);
                application.callbacks(snapshot).load().migrate();
            }
        } catch (RuntimeException e) {
            failure = e;
        } finally {
            try {
                db.dropSchema(schema);
            } catch (RuntimeException e) {
                // The migration's own failure says what went wrong; a schema left behind does not.
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        Set<String> unknown = new TreeSet<>(neverReleased.keySet());
        unknown.removeAll(byVersion.keySet());
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("No migration has the version(s) " + unknown + " left out as never"
                + " released (versions as Flyway writes them: 29, not V29); known: " + byVersion.keySet());
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
                if (!sameDigest(type, now.get(column))) {
                    lost.add(table + "." + column + " (" + type + ") of V" + version + " is "
                        + (now.containsKey(column) ? now.get(column) : "gone"));
                }
            });
        }));
        return new Result(List.copyOf(lost), Set.copyOf(latest.keySet()));
    }

    /**
     * Whether a column's values read the same in {@code to_jsonb} after its type changed from {@code before} to
     * {@code after}: text of any length (a narrower one would have failed on longer values), and integers of any
     * width. Everything else must stay as it was: a numeric's scale or a timestamp's precision changes the value.
     */
    static boolean sameDigest(String before, String after) {
        return after != null && (before.equals(after) || family(before) != null && family(before).equals(family(after)));
    }

    private static String family(String type) {
        if (type.equals("text") || type.startsWith("character varying")) {
            return "text";
        }
        return Set.of("smallint", "integer", "bigint").contains(type) ? "integer" : null;
    }

    private FluentConfiguration configure(String schema) {
        return Flyway.configure().dataSource(db.jdbcUrl(), db.username(), db.password()).schemas(schema)
            .defaultSchema(schema).baselineOnMigrate(true).baselineVersion("0");
    }

    /**
     * Each table the seal seals (its own query: append-only with a primary key, its own tables aside), its columns
     * and their types.
     */
    private static Map<String, Map<String, String>> appendOnlyColumns(Connection connection) throws SQLException {
        Set<String> sealed = new TreeSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(IntegrityStore.TABLES)) {
            while (rs.next()) {
                String table = rs.getString("table_name");
                if (rs.getBoolean("append_only") && ((String[]) rs.getArray("key_columns").getArray()).length > 0
                    && !IntegrityStore.OWN_TABLES.contains(table)) {
                    sealed.add(table);
                }
            }
        }
        Map<String, Map<String, String>> tables = new TreeMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                 SELECT c.relname AS table_name, a.attname AS column_name,
                        format_type(a.atttypid, a.atttypmod) AS column_type
                 FROM pg_class c
                 JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = current_schema()
                 JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
                 WHERE c.relname = ANY(?)""")) {
            statement.setArray(1, connection.createArrayOf("text", sealed.toArray()));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    tables.computeIfAbsent(rs.getString("table_name"), t -> new TreeMap<>())
                        .put(rs.getString("column_name"), rs.getString("column_type"));
                }
            }
        }
        return tables;
    }
}
