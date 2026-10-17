package com.jabiz.runtime.storage;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** How the inserts of one commit are rendered into statements (ROADMAP phase 16p, T6; decision D46). */
class WriteBatchTest {

    private static WriteBatch.Row row(String table, Object... columnsAndValues) {
        Map<String, Object> record = new LinkedHashMap<>();
        for (int i = 0; i < columnsAndValues.length; i += 2) {
            record.put((String) columnsAndValues[i], columnsAndValues[i + 1]);
        }
        return new WriteBatch.Row(table, record, null);
    }

    @Test
    void oneRowIsTheInsertOfOneRow() {
        List<WriteBatch.Statement> statements = WriteBatch.render(List.of(row("t", "a", 1, "b", "x", "c", null)));

        assertThat(statements).singleElement().satisfies(statement -> {
            assertThat(statement.sql()).isEqualTo("INSERT INTO t (a, b) VALUES (:v0, :v1)");
            assertThat(statement.values()).containsExactly(1, "x");
        });
    }

    @Test
    void rowsOfOneTableAreOneMultiRowInsertInTheirOrder() {
        List<WriteBatch.Statement> statements = WriteBatch.render(List.of(
            row("t", "a", 1, "b", "x"),
            row("t", "a", 2, "b", StorageEngine.NULL),
            row("t", "a", 3, "c", true)));

        assertThat(statements).singleElement().satisfies(statement -> {
            // A column a row leaves out takes its default, as a single-row insert that omits it; NULL stays NULL.
            assertThat(statement.sql()).isEqualTo(
                "INSERT INTO t (a, b, c) VALUES (:v0, :v1, DEFAULT), (:v2, NULL, DEFAULT), (:v3, DEFAULT, :v4)");
            assertThat(statement.values()).containsExactly(1, "x", 2, 3, true);
            assertThat(statement.rows()).hasSize(3);
        });
    }

    @Test
    void severalTablesAreJoinedByDataModifyingWithParts() {
        List<WriteBatch.Statement> statements = WriteBatch.render(List.of(
            row("entity_registry", "entity_id", "e1"),
            row("item_version", "item_id", "e1"),
            row("sys_audit_record", "entity_id", "e1"),
            row("entity_registry", "entity_id", "e2"),
            row("item_version", "item_id", "e2"),
            row("sys_audit_record", "entity_id", "e2")));

        assertThat(statements).singleElement().satisfies(statement -> {
            // Each table a part the main query reads in turn, so that they are written in this order.
            assertThat(statement.sql()).isEqualTo("WITH jabiz_w0 AS (INSERT INTO entity_registry (entity_id) VALUES "
                + "(:v0), (:v1) RETURNING 1), jabiz_w1 AS (INSERT INTO item_version (item_id) VALUES (:v2), (:v3) "
                + "RETURNING 1), jabiz_w2 AS (INSERT INTO sys_audit_record (entity_id) VALUES (:v4), (:v5) RETURNING 1)"
                + " SELECT (SELECT count(*) FROM jabiz_w0) AS jabiz_n0, (SELECT count(*) FROM jabiz_w1) AS jabiz_n1,"
                + " (SELECT count(*) FROM jabiz_w2) AS jabiz_n2");
            // The rows of each table in the order they were registered.
            assertThat(statement.values()).containsExactly("e1", "e2", "e1", "e2", "e1", "e2");
        });
    }

    @Test
    void moreValuesThanOneStatementBindsGoIntoFurtherStatementsInOrder() {
        List<WriteBatch.Row> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            rows.add(row("t", "a", i, "b", "v" + i));
        }

        List<WriteBatch.Statement> statements = WriteBatch.render(rows, 4);

        assertThat(statements).extracting(WriteBatch.Statement::sql).containsExactly(
            "INSERT INTO t (a, b) VALUES (:v0, :v1), (:v2, :v3)",
            "INSERT INTO t (a, b) VALUES (:v0, :v1), (:v2, :v3)",
            "INSERT INTO t (a, b) VALUES (:v0, :v1)");
        assertThat(statements.stream().flatMap(statement -> statement.values().stream()).toList())
            .containsExactly(0, "v0", 1, "v1", 2, "v2", 3, "v3", 4, "v4");
        assertThat(statements).allSatisfy(statement -> assertThat(statement.values().size()).isLessThanOrEqualTo(4));
    }

    @Test
    void theDefaultBoundStaysWellBelowTheDatabasesLimit() {
        assertThat(WriteBatch.MAX_BINDS).isEqualTo(30_000).isLessThan(65_535);
        List<WriteBatch.Row> rows = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            rows.add(row("t", "a", i, "b", i));
        }
        assertThat(WriteBatch.render(rows)).hasSize(2)
            .allSatisfy(statement -> assertThat(statement.values().size()).isLessThanOrEqualTo(30_000));
    }

    @Test
    void namesComeFromMetadataAndAreChecked() {
        assertThatThrownBy(() -> WriteBatch.render(List.of(row("t; DROP TABLE x", "a", 1))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WriteBatch.render(List.of(row("t", "a) VALUES (1); --", 1))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WriteBatch.render(List.of(row("t", "a", null))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no values");
    }

    // ---- held for the whole transaction (16p-2, T9) -----------------------------------------------------------------

    private static WriteBatch.Update update(String table, Object id, long version, Map<String, Object> updates,
        StorageEngine.Insert... dependents) {
        return new WriteBatch.Update(table, "f_id", id, version, "f_version", updates, List.of(dependents),
            () -> reactor.core.publisher.Mono.empty(), "TEST step 'x'");
    }

    @Test
    void anUpdateDividesTheRowsSoThatEveryTableKeepsItsOrder() {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("f_title", "new");
        changes.put("f_note", null);
        changes.put("f_version", 99);
        List<WriteBatch.Entry> entries = List.of(
            row("sys_audit_record", "entity_id", "a1"),
            update("it_ticket", "t1", 3L, changes, new StorageEngine.Insert("sys_audit_record",
                Map.of("entity_id", "t1"))),
            row("sys_audit_record", "entity_id", "a2"),
            row("sys_outbox_event", "event_type", "e"));

        WriteBatch.Parts parts = WriteBatch.parts(entries, "v");

        assertThat(parts.parts()).containsExactly(
            "jabiz_w0 AS (INSERT INTO sys_audit_record (entity_id) VALUES (:v0) RETURNING 1)",
            // The version is the database's to raise; NULL is set as NULL.
            "jabiz_w1 AS (UPDATE it_ticket SET f_title = :v1, f_note = NULL, f_version = f_version + 1 WHERE f_id = :v2"
                + " AND f_version = :v3 RETURNING 1)",
            "jabiz_w2 AS (INSERT INTO sys_audit_record (entity_id) SELECT r.entity_id FROM jabiz_w1, "
                + "jsonb_populate_record(NULL::sys_audit_record, CAST(:v4 AS jsonb)) r RETURNING 1)",
            // The rows after the update: the audit table again, after its earlier rows and the update's own.
            "jabiz_w3 AS (INSERT INTO sys_audit_record (entity_id) VALUES (:v5) RETURNING 1)",
            "jabiz_w4 AS (INSERT INTO sys_outbox_event (event_type) VALUES (:v6) RETURNING 1)");
        assertThat(parts.terms().get(1)).isEqualTo("CAST(CASE WHEN (SELECT count(*) FROM jabiz_w1) > 0 THEN '0'::text"
            + " ELSE 'jabiz_stale_1'::text END AS bigint)");
        assertThat(parts.terms().get(0)).isEqualTo("(SELECT count(*) FROM jabiz_w0)");
        assertThat(parts.values()).containsOnlyKeys("v0", "v1", "v2", "v3", "v4", "v5", "v6");
        assertThat(parts.values().get("v3")).isEqualTo(3L);
        assertThat(parts.values().get("v4")).isEqualTo(new JsonText("{\"entity_id\":\"t1\"}"));
        assertThat(parts.updates()).containsOnlyKeys(1);
        assertThat(parts.rows()).extracting(WriteBatch.Row::table)
            .containsExactly("sys_audit_record", "sys_audit_record", "sys_outbox_event");
    }

    @Test
    void thePartsAloneAreReadInTurnAndCarriedTheyGateTheStatement() {
        WriteBatch.Parts parts = WriteBatch.parts(List.of(row("a", "k", 1), row("b", "k", 2)), "jabiz_v");

        assertThat(parts.alone()).isEqualTo("WITH jabiz_w0 AS (INSERT INTO a (k) VALUES (:jabiz_v0) RETURNING 1), "
            + "jabiz_w1 AS (INSERT INTO b (k) VALUES (:jabiz_v1) RETURNING 1) SELECT (SELECT count(*) FROM jabiz_w0) AS "
            + "jabiz_n0, (SELECT count(*) FROM jabiz_w1) AS jabiz_n1");
        // A condition on no row: evaluated once, before the statement runs (a one-time filter).
        assertThat(parts.carriedBy("SELECT pg_advisory_xact_lock(:key)")).isEqualTo("WITH jabiz_w0 AS (INSERT INTO a"
            + " (k) VALUES (:jabiz_v0) RETURNING 1), jabiz_w1 AS (INSERT INTO b (k) VALUES (:jabiz_v1) RETURNING 1) "
            + "SELECT * FROM (SELECT pg_advisory_xact_lock(:key)) jabiz_m WHERE ((SELECT count(*) FROM jabiz_w0) + "
            + "(SELECT count(*) FROM jabiz_w1)) >= 0");
    }

    @Test
    void anUpdateIsNeverSplitFromItsRows() {
        WriteBatch.Update update = update("t", "id", 1L, Map.of("a", 1, "b", 2),
            new StorageEngine.Insert("sys_audit_record", Map.of("x", 1)));
        assertThat(update.binds()).isEqualTo(5);

        List<List<WriteBatch.Entry>> chunks = WriteBatch.chunks(List.of(row("t", "a", 1, "b", 2), update,
            row("t", "a", 3)), 6);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).hasSize(1);
        assertThat(chunks.get(1)).containsExactly(update, chunks.get(1).get(1));
    }

    @Test
    void anUpdatesNamesAreCheckedToo() {
        assertThatThrownBy(() -> WriteBatch.parts(List.of(update("t; --", "id", 1L, Map.of("a", 1))), "v"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WriteBatch.parts(List.of(update("t", "id", 1L, Map.of("a = 1; --", 1))), "v"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRowKeepsItsRecordAsGivenNullsIncluded() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("a", null);
        record.put("b", 1);
        WriteBatch.Row row = new WriteBatch.Row("t", record, null);
        record.put("c", 2);

        assertThat(row.record()).containsOnlyKeys("a", "b");
        assertThat(row.binds()).isEqualTo(1);
    }
}
