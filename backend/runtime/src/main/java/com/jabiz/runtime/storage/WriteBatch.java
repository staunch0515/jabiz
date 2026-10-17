package com.jabiz.runtime.storage;

import com.jabiz.query.SqlIdentifiers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Renders inserts collected within one commit into as few statements as possible (ROADMAP phase 16p, T6; decision
 * D46 rule 1): the rows of each table as one multi-row {@code INSERT … VALUES (…), (…)} in the order they were
 * registered, several tables joined by data-modifying {@code WITH} parts into one statement. Non-deferred foreign keys
 * are checked at the end of the statement, so a row may refer to a row of another table the same statement inserts;
 * deferred constraint triggers (the ledger balance) still run at {@code COMMIT}, row by row.
 *
 * <p>A statement binds at most {@link #MAX_BINDS} values (PostgreSQL takes 65,535); more rows go into further
 * statements, still in their order. Several tables are written in the order of their first rows (each a {@code WITH} part
 * the main query reads in turn), the rows of a table in their order. Values are rendered as {@link StorageEngine#insert} renders one row: a null value
 * leaves the column to its default ({@code DEFAULT}), {@link StorageEngine#NULL} is an explicit {@code NULL}, everything
 * else is bound. Table and column names come from metadata and are checked by {@link SqlIdentifiers#require}.
 */
final class WriteBatch {

    /** Most values one statement binds. */
    static final int MAX_BINDS = 30_000;

    /** A row to insert, with the mapping of a conflict it causes back to the error of the write it belongs to. */
    record Row(String table, Map<String, Object> record, StorageEngine.InsertConflicts conflicts) {
        Row {
            Objects.requireNonNull(table, "table must not be null");
            // The record may hold nulls (a column left to its default), so it is copied, not Map.copyOf'd.
            record = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(record));
        }

        /** Values this row binds. */
        int binds() {
            int count = 0;
            for (Object value : record.values()) {
                if (value != null && value != StorageEngine.NULL) {
                    count++;
                }
            }
            return count;
        }
    }

    /** One statement: its SQL, its values by position (bound as {@code v0}, {@code v1} …) and the rows it writes. */
    record Statement(String sql, List<Object> values, List<Row> rows) {}

    private WriteBatch() {}

    static List<Statement> render(List<Row> rows) {
        return render(rows, MAX_BINDS);
    }

    static List<Statement> render(List<Row> rows, int maxBinds) {
        List<Statement> statements = new ArrayList<>();
        List<Row> chunk = new ArrayList<>();
        int binds = 0;
        for (Row row : rows) {
            int needed = row.binds();
            if (!chunk.isEmpty() && binds + needed > maxBinds) {
                statements.add(statement(chunk));
                chunk = new ArrayList<>();
                binds = 0;
            }
            chunk.add(row);
            binds += needed;
        }
        if (!chunk.isEmpty()) {
            statements.add(statement(chunk));
        }
        return statements;
    }

    private static Statement statement(List<Row> rows) {
        Map<String, List<Row>> byTable = new LinkedHashMap<>();
        for (Row row : rows) {
            byTable.computeIfAbsent(SqlIdentifiers.require(row.table()), table -> new ArrayList<>()).add(row);
        }
        List<Object> values = new ArrayList<>();
        List<String> inserts = new ArrayList<>();
        byTable.forEach((table, tableRows) -> inserts.add(insert(table, tableRows, values)));
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

    /** The multi-row insert of one table's rows; its values are appended to {@code values}. */
    private static String insert(String table, List<Row> rows, List<Object> values) {
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
            row.record().forEach((column, value) -> byColumn.put(column.toLowerCase(java.util.Locale.ROOT), value));
            List<String> markers = new ArrayList<>();
            for (String column : columns) {
                Object value = byColumn.get(column.toLowerCase(java.util.Locale.ROOT));
                if (value == null) {
                    // Left out of this row: the column's default, as a single-row insert that omits it.
                    markers.add("DEFAULT");
                } else if (value == StorageEngine.NULL) {
                    // An untyped literal takes the column's type, which a bound NULL would have to name.
                    markers.add("NULL");
                } else {
                    markers.add(":v" + values.size());
                    values.add(value);
                }
            }
            tuples.add("(" + String.join(", ", markers) + ")");
        }
        return "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES " + String.join(", ", tuples);
    }
}
