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
            assertThat(statement.sql()).isEqualTo("WITH jabiz_w0 AS (INSERT INTO entity_registry (entity_id) VALUES "
                + "(:v0), (:v1)), jabiz_w1 AS (INSERT INTO item_version (item_id) VALUES (:v2), (:v3)) "
                + "INSERT INTO sys_audit_record (entity_id) VALUES (:v4), (:v5)");
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
