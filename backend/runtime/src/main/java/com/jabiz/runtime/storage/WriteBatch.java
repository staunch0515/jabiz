package com.jabiz.runtime.storage;

import com.jabiz.query.SqlIdentifiers;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Renders the writes held back in a transaction into as few statements as possible (ROADMAP phase 16p, T6 and T9;
 * decision D46 rule 1): the rows of each table as one multi-row {@code INSERT … VALUES (…), (…)} in the order they were
 * registered, several tables joined by data-modifying {@code WITH} parts into one statement. Non-deferred foreign keys
 * are checked at the end of the statement, so a row may refer to a row of another table the same statement inserts;
 * deferred constraint triggers (the ledger balance) still run at {@code COMMIT}, row by row.
 *
 * <p>A statement binds at most {@link #MAX_BINDS} values (PostgreSQL takes 65,535); more rows go into further
 * statements, still in their order. Several tables are written in the order of their first rows (each a {@code WITH}
 * part the main query reads in turn), the rows of a table in their order. Values are rendered as
 * {@link StorageEngine#insert} renders one row: a null value leaves the column to its default ({@code DEFAULT}),
 * {@link StorageEngine#NULL} is an explicit {@code NULL}, everything else is bound. Table and column names come from
 * metadata and are checked by {@link SqlIdentifiers#require}.
 *
 * <p>Held for a whole transaction (T9), the writes also include updates at a known version ({@link Update}), each with
 * the rows that exist only when it updated its row (its audit record and event). An update divides the rows around it:
 * those registered before it, the update with its rows, those registered after it are parts in that order, so that
 * every table still receives its rows in the order they were registered. The main query reads every part in turn
 * (scalar sub-selects, evaluated left to right); a part runs when it is read, so the first conflict the database meets is
 * that of the earliest write, as when they were sent one by one. An update that changes no row fails the statement right
 * where it is read, before any later part runs: its term casts {@code 'jabiz_stale_<part>'} to an integer, which the
 * engine recognizes and reports as the update would have been reported ({@link Update#notApplied}).
 *
 * <p>The parts can also be <em>carried</em> by a statement that has to be sent anyway and touches none of their tables
 * (a lock, a read of other tables): {@code WITH <parts> SELECT * FROM (<statement>) jabiz_m WHERE (<terms>) >= 0}.
 * The condition depends on no row, so PostgreSQL evaluates it once, as a gate before the statement runs at all (a
 * one-time filter): the held writes happen first, as they would have, and the statement's own snapshot, taken when it
 * starts, is the same as a statement sent after them would have had for the tables it reads.
 */
final class WriteBatch {

    /** Most values one statement binds. */
    static final int MAX_BINDS = 30_000;

    /** Prefix of the term of an update that changed no row (its part number follows). */
    static final String STALE = "jabiz_stale_";

    /** A write held back: a row to insert or an update at a known version. */
    sealed interface Entry permits Row, Update {
        String table();

        /** Values the write binds. */
        int binds();

        /** Where the write was registered (process and step), for the log when it fails; null when unknown. */
        String origin();
    }

    /** A row to insert, with the mapping of a conflict it causes back to the error of the write it belongs to. */
    record Row(String table, Map<String, Object> record, StorageEngine.InsertConflicts conflicts, String origin)
        implements Entry {
        Row {
            Objects.requireNonNull(table, "table must not be null");
            // The record may hold nulls (a column left to its default), so it is copied, not Map.copyOf'd.
            record = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(record));
        }

        Row(String table, Map<String, Object> record, StorageEngine.InsertConflicts conflicts) {
            this(table, record, conflicts, null);
        }

        @Override
        public int binds() {
            int count = 0;
            for (Object value : record.values()) {
                if (value != null && value != StorageEngine.NULL) {
                    count++;
                }
            }
            return count;
        }
    }

    /**
     * An optimistic update held back (T9): {@code updates} set and the version raised where the row has
     * {@code expectedVersion}; {@code dependents} inserted only when it did. When it changes no row, the transaction
     * (or savepoint) fails and, once rolled back, {@code notApplied} gives the error to report (a read then tells a
     * deleted row from a changed one, as an update sent on its own would have been answered).
     */
    record Update(String table, String pkColumn, Object id, long expectedVersion, String versionColumn,
        Map<String, Object> updates, List<StorageEngine.Insert> dependents, Supplier<Mono<Throwable>> notApplied,
        String origin) implements Entry {
        Update {
            Objects.requireNonNull(table, "table must not be null");
            Objects.requireNonNull(pkColumn, "pkColumn must not be null");
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(versionColumn, "versionColumn must not be null");
            Objects.requireNonNull(notApplied, "notApplied must not be null");
            // An update may set a column to NULL (a null value), so it is copied, not Map.copyOf'd.
            updates = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(updates));
            dependents = List.copyOf(dependents);
        }

        @Override
        public int binds() {
            int count = 2 + dependents.size();
            for (Object value : updates.values()) {
                if (value != null) {
                    count++;
                }
            }
            return count;
        }
    }

    /** One statement: its SQL, its values by position (bound as {@code v0}, {@code v1} …) and the rows it writes. */
    record Statement(String sql, List<Object> values, List<Row> rows) {}

    /**
     * Held writes as {@code WITH} parts: {@code parts} (each {@code jabiz_w<n> AS (…)}), the {@code terms} that read
     * them in order, the values they bind by name, the rows they insert and the updates by the number of their part.
     */
    record Parts(List<String> parts, List<String> terms, Map<String, Object> values, List<Row> rows,
        Map<Integer, Update> updates) {

        /** The parts as a statement of their own. */
        String alone() {
            List<String> reads = new ArrayList<>();
            for (int i = 0; i < terms.size(); i++) {
                reads.add(terms.get(i) + " AS jabiz_n" + i);
            }
            return "WITH " + String.join(", ", parts) + " SELECT " + String.join(", ", reads);
        }

        /** The parts carried by {@code statement}, a query that reads none of their tables. */
        String carriedBy(String statement) {
            return "WITH " + String.join(", ", parts) + " SELECT * FROM (" + statement + ") jabiz_m WHERE ("
                + String.join(" + ", terms) + ") >= 0";
        }
    }

    private WriteBatch() {}

    static List<Statement> render(List<Row> rows) {
        return render(rows, MAX_BINDS);
    }

    static List<Statement> render(List<Row> rows, int maxBinds) {
        List<Statement> statements = new ArrayList<>();
        for (List<Entry> chunk : chunks(List.copyOf(rows), maxBinds)) {
            statements.add(statement(chunk.stream().map(Row.class::cast).toList()));
        }
        return statements;
    }

    /** The entries in groups of at most {@code maxBinds} values (an entry larger than that alone), in their order. */
    static List<List<Entry>> chunks(List<? extends Entry> entries, int maxBinds) {
        List<List<Entry>> chunks = new ArrayList<>();
        List<Entry> chunk = new ArrayList<>();
        int binds = 0;
        for (Entry entry : entries) {
            int needed = entry.binds();
            if (!chunk.isEmpty() && binds + needed > maxBinds) {
                chunks.add(chunk);
                chunk = new ArrayList<>();
                binds = 0;
            }
            chunk.add(entry);
            binds += needed;
        }
        if (!chunk.isEmpty()) {
            chunks.add(chunk);
        }
        return chunks;
    }

    private static Statement statement(List<Row> rows) {
        Map<String, List<Row>> byTable = new LinkedHashMap<>();
        for (Row row : rows) {
            byTable.computeIfAbsent(SqlIdentifiers.require(row.table()), table -> new ArrayList<>()).add(row);
        }
        List<Object> values = new ArrayList<>();
        List<String> inserts = new ArrayList<>();
        Map<String, Object> named = new LinkedHashMap<>();
        byTable.forEach((table, tableRows) -> inserts.add(insert(table, tableRows, "v", named)));
        values.addAll(named.values());
        StringBuilder sql = new StringBuilder();
        if (inserts.size() == 1) {
            sql.append(inserts.getFirst());
        } else {
            // Every table as a WITH part that the main query reads in turn: a part runs when it is read, so the
            // tables are written in the order their first rows were registered, and the first conflict the database
            // meets is that of the earliest write, as when the rows were sent one by one.
            sql.append("WITH ");
            List<String> reads = new ArrayList<>();
            for (int i = 0; i < inserts.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append("jabiz_w").append(i).append(" AS (").append(inserts.get(i)).append(" RETURNING 1)");
                reads.add("(SELECT count(*) FROM jabiz_w" + i + ") AS jabiz_n" + i);
            }
            sql.append(" SELECT ").append(String.join(", ", reads));
        }
        return new Statement(sql.toString(), List.copyOf(values), List.copyOf(rows));
    }

    /**
     * The entries as {@code WITH} parts whose values are named {@code prefix0}, {@code prefix1} …: the rows between two
     * updates by table (in the order of their first rows), each update with its rows after them.
     */
    static Parts parts(List<Entry> entries, String prefix) {
        List<String> parts = new ArrayList<>();
        List<String> terms = new ArrayList<>();
        Map<String, Object> values = new LinkedHashMap<>();
        List<Row> rows = new ArrayList<>();
        Map<Integer, Update> updates = new LinkedHashMap<>();
        Map<String, List<Row>> pending = new LinkedHashMap<>();
        for (Entry entry : entries) {
            switch (entry) {
                case Row row -> {
                    rows.add(row);
                    pending.computeIfAbsent(SqlIdentifiers.require(row.table()), t -> new ArrayList<>()).add(row);
                }
                case Update update -> {
                    insertParts(pending, prefix, parts, terms, values);
                    int part = parts.size();
                    parts.add("jabiz_w" + part + " AS (" + update(update, prefix, values) + " RETURNING 1)");
                    // Changed no row: the cast fails here, before any later part is read and run.
                    terms.add("CAST(CASE WHEN (SELECT count(*) FROM jabiz_w" + part + ") > 0 THEN '0'::text ELSE '"
                        + STALE + part + "'::text END AS bigint)");
                    updates.put(part, update);
                    for (StorageEngine.Insert dependent : update.dependents()) {
                        int own = parts.size();
                        parts.add("jabiz_w" + own + " AS (" + dependent("jabiz_w" + part, dependent, prefix, values)
                            + " RETURNING 1)");
                        terms.add("(SELECT count(*) FROM jabiz_w" + own + ")");
                    }
                }
            }
        }
        insertParts(pending, prefix, parts, terms, values);
        return new Parts(List.copyOf(parts), List.copyOf(terms), values, List.copyOf(rows), Map.copyOf(updates));
    }

    private static void insertParts(Map<String, List<Row>> pending, String prefix, List<String> parts,
        List<String> terms, Map<String, Object> values) {
        pending.forEach((table, rows) -> {
            int part = parts.size();
            parts.add("jabiz_w" + part + " AS (" + insert(table, rows, prefix, values) + " RETURNING 1)");
            terms.add("(SELECT count(*) FROM jabiz_w" + part + ")");
        });
        pending.clear();
    }

    /** The update at its expected version; its values are added to {@code values}. */
    private static String update(Update update, String prefix, Map<String, Object> values) {
        String versionColumn = SqlIdentifiers.require(update.versionColumn());
        List<String> assignments = new ArrayList<>();
        for (Map.Entry<String, Object> e : update.updates().entrySet()) {
            String column = SqlIdentifiers.require(e.getKey());
            if (column.equalsIgnoreCase(versionColumn)) {
                continue;
            }
            assignments.add(column + " = " + (e.getValue() == null ? "NULL" : bind(e.getValue(), prefix, values)));
        }
        assignments.add(versionColumn + " = " + versionColumn + " + 1");
        return "UPDATE " + SqlIdentifiers.require(update.table()) + " SET " + String.join(", ", assignments)
            + " WHERE " + SqlIdentifiers.require(update.pkColumn()) + " = " + bind(update.id(), prefix, values)
            + " AND " + versionColumn + " = " + bind(update.expectedVersion(), prefix, values);
    }

    /**
     * A row that exists only when the update of part {@code updated} changed its row: passed as JSON and taken as its
     * table's row type, so that the database converts each value to its column's type (as phase 16p-1 writes it).
     */
    private static String dependent(String updated, StorageEngine.Insert row, String prefix,
        Map<String, Object> values) {
        String target = SqlIdentifiers.require(row.table());
        List<String> columns = new ArrayList<>();
        row.record().forEach((column, value) -> {
            if (value != null) {
                columns.add(SqlIdentifiers.require(column));
            }
        });
        String json = bind(new JsonText(R2dbcStorageEngine.rowJson(row.record())), prefix, values);
        return "INSERT INTO " + target + " (" + String.join(", ", columns) + ") SELECT "
            + String.join(", ", columns.stream().map(c -> "r." + c).toList()) + " FROM " + updated
            + ", jsonb_populate_record(NULL::" + target + ", CAST(" + json + " AS jsonb)) r";
    }

    private static String bind(Object value, String prefix, Map<String, Object> values) {
        String name = prefix + values.size();
        values.put(name, value);
        return ":" + name;
    }

    /** The multi-row insert of one table's rows; its values are added to {@code values} as {@code prefix<n>}. */
    private static String insert(String table, List<Row> rows, String prefix, Map<String, Object> values) {
        Set<String> columns = new LinkedHashSet<>();
        for (Row row : rows) {
            row.record().forEach((column, value) -> {
                if (value != null) {
                    columns.add(SqlIdentifiers.require(column));
                }
            });
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("Insert into " + table + " has no values");
        }
        List<String> tuples = new ArrayList<>();
        for (Row row : rows) {
            Map<String, Object> byColumn = new LinkedHashMap<>();
            row.record().forEach((column, value) -> byColumn.put(column.toLowerCase(Locale.ROOT), value));
            List<String> markers = new ArrayList<>();
            for (String column : columns) {
                Object value = byColumn.get(column.toLowerCase(Locale.ROOT));
                if (value == null) {
                    // Left out of this row: the column's default, as a single-row insert that omits it.
                    markers.add("DEFAULT");
                } else if (value == StorageEngine.NULL) {
                    // An untyped literal takes the column's type, which a bound NULL would have to name.
                    markers.add("NULL");
                } else {
                    markers.add(bind(value, prefix, values));
                }
            }
            tuples.add("(" + String.join(", ", markers) + ")");
        }
        return "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES " + String.join(", ", tuples);
    }
}
