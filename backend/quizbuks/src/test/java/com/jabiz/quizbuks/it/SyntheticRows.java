package com.jabiz.quizbuks.it;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.jabiz.runtime.test.PostgresIntegrationTest;

/**
 * Rows written straight into a table for a test that needs many of them, or one alone (F-M15): the point is what reads
 * them, not the processes that would write them. Every required column without a default gets a plain value of its
 * type; the values given are SQL expressions. (A subclass of the test base only to reach its statements.)
 */
abstract class SyntheticRows extends PostgresIntegrationTest {

    private SyntheticRows() {}

    public static String text(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /**
     * {@code count} rows of {@code table}, numbered {@code n} from 1: the values given are SQL expressions (they may
     * use {@code n} and the key {@code ids.id}); every other required column without a default gets a plain value of
     * its type. The keys are new, or those {@code idsSql} selects (columns {@code n} and {@code id}). With
     * {@code entity}, the keys are registered as its instances under the latest operation; a temporal table
     * ({@code *_version}) gets version 1.
     */
    public static void rows(String table, String entity, int count, String idsSql,
        Function<Integer, Map<String, String>> values) {
        Map<String, String> given = new LinkedHashMap<>(values.apply(0));
        List<Map<String, Object>> columns = query("SELECT column_name, data_type, character_maximum_length "
            + "FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = ? "
            + "AND is_nullable = 'NO' AND column_default IS NULL AND is_identity = 'NO' AND is_generated = 'NEVER' "
            + "ORDER BY ordinal_position", table);
        String key = String.valueOf(query("SELECT a.attname FROM pg_index i JOIN pg_attribute a "
            + "ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0] WHERE i.indrelid = CAST(? AS regclass) "
            + "AND i.indisprimary", table).getFirst().get("attname"));
        if (table.endsWith("_version")) {
            given.put("version_no", "1");
            given.put("process_seq_id", "(SELECT max(process_seq_id) FROM op_process)");
        }
        // A temporal table's key is the entity's id, given (its primary key is the row's number); else the primary key.
        if (!given.containsValue("ids.id")) {
            given.put(key, "ids.id");
        }
        List<String> names = new ArrayList<>();
        List<String> exprs = new ArrayList<>();
        for (Map<String, Object> column : columns) {
            String name = String.valueOf(column.get("column_name"));
            names.add(name);
            exprs.add(given.containsKey(name) ? given.remove(name) : plain(String.valueOf(column.get("data_type")),
                (Number) column.get("character_maximum_length")));
        }
        given.forEach((name, expr) -> {
            names.add(name);
            exprs.add(expr);
        });
        String ids = "it_ids_" + Math.abs(System.nanoTime() % 1_000_000);
        // An ordinary table: every statement here has a connection of its own.
        if (idsSql == null) {
            execute("CREATE TABLE " + ids + " AS SELECT n, gen_random_uuid() AS id "
                + "FROM generate_series(1, CAST(? AS int)) n", count);
        } else {
            execute("CREATE TABLE " + ids + " AS " + idsSql);
        }
        if (entity != null) {
            execute("INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) SELECT id, ?, "
                + "(SELECT max(process_seq_id) FROM op_process) FROM " + ids, entity);
        }
        execute("INSERT INTO " + table + " (" + String.join(", ", names) + ") SELECT " + String.join(", ", exprs)
            + " FROM " + ids + " ids");
        execute("DROP TABLE " + ids);
    }

    private static String plain(String type, Number length) {
        return switch (type) {
            case "uuid" -> "gen_random_uuid()";
            case "boolean" -> "false";
            // Amounts must mostly be positive.
            case "smallint", "integer", "bigint", "numeric", "real", "double precision" -> "1";
            case "date" -> "DATE '2026-01-01'";
            case "timestamp with time zone", "timestamp without time zone" -> "TIMESTAMPTZ '2026-01-01T00:00:00Z'";
            case "jsonb", "json" -> "'{}'";
            // Unique where a column must be (the new key's text, cut to the column's length).
            default -> length == null ? "'x' || CAST(ids.id AS text)"
                : length.intValue() < 8 ? "'x'" : "left('x' || CAST(ids.id AS text), " + length.intValue() + ")";
        };
    }
}
