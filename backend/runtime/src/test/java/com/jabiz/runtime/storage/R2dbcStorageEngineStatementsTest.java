package com.jabiz.runtime.storage;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The parts of the engine's statements that need no database (ROADMAP phase 16p): the parameters of several reads
 * kept apart, a conflict traced back to the row that caused it, and a row passed as JSON.
 */
class R2dbcStorageEngineStatementsTest {

    @Test
    void parametersAreRenamedWholeAndCastsAreLeftAlone() {
        String sql = "SELECT * FROM t WHERE a = :p0 AND b = :p1 AND c = :p10 AND d::text = :__asOf AND f = :p2";

        // p2 is not a parameter of this plan: left as it is.
        String renamed = R2dbcStorageEngine.renamed(sql, Set.of("p0", "p1", "p10", "__asOf"), "b3_");

        assertThat(renamed).isEqualTo("SELECT * FROM t WHERE a = :b3_p0 AND b = :b3_p1 AND c = :b3_p10"
            + " AND d::text = :b3___asOf AND f = :p2");
    }

    private static WriteBatch.Row row(String table, String key, Object value, String label) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put(key, value);
        return new WriteBatch.Row(table, record, (conflict, t, r) -> new IllegalStateException(label + ":" + t));
    }

    @Test
    void aConflictIsReportedAsTheWriteOfTheRowWithTheReportedKey() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        List<WriteBatch.Row> rows = List.of(row("entity_registry", "entity_id", first, "first"),
            row("item_version", "item_id", first, "version"),
            row("entity_registry", "entity_id", second, "second"));
        UniqueKeyViolationException conflict = new UniqueKeyViolationException("entity_registry_pkey",
            "entity_registry", "Key (entity_id)=(" + second + ") already exists.", null);

        assertThat(R2dbcStorageEngine.mapped(conflict, rows)).hasMessage("second:entity_registry");
    }

    @Test
    void withoutAMatchingKeyTheFirstRowOfTheTableAnswers() {
        List<WriteBatch.Row> rows = List.of(row("a", "k", 1, "a"), row("b", "k", 2, "b1"), row("b", "k", 3, "b2"));

        assertThat(R2dbcStorageEngine.mapped(new UniqueKeyViolationException("b_k", "b",
            "Key (lower(k))=(9) already exists.", null), rows)).hasMessage("b1:b");
        assertThat(R2dbcStorageEngine.mapped(new UniqueKeyViolationException("b_k", "b", null, null), rows))
            .hasMessage("b1:b");
        // Neither table nor detail reported: the first row of all.
        assertThat(R2dbcStorageEngine.mapped(new UniqueKeyViolationException("x", null), rows)).hasMessage("a:a");
        // A table none of the rows went to: nothing to blame, the conflict stays as it is.
        UniqueKeyViolationException elsewhere = new UniqueKeyViolationException("c_k", "c", null, null);
        assertThat(R2dbcStorageEngine.mapped(elsewhere, rows)).isSameAs(elsewhere);
    }

    @Test
    void aRowWithoutMappingKeepsTheConflict() {
        UniqueKeyViolationException conflict = new UniqueKeyViolationException("t_pkey", "t", null, null);

        assertThat(R2dbcStorageEngine.mapped(conflict, List.of(new WriteBatch.Row("t", Map.of("k", 1), null))))
            .isSameAs(conflict);
    }

    @Test
    void onlyAStatementThatSelectsCanCarryHeldWrites() {
        assertThat(R2dbcStorageEngine.isQuery("SELECT 1 AS locked FROM (SELECT pg_advisory_xact_lock(:k)) l")).isTrue();
        assertThat(R2dbcStorageEngine.isQuery("  select\n* from t")).isTrue();
        // A WITH may hold a data-modifying part; anything else may write, set or lock: never carried.
        assertThat(R2dbcStorageEngine.isQuery("WITH c AS (SELECT 1) SELECT * FROM c")).isFalse();
        assertThat(R2dbcStorageEngine.isQuery("INSERT INTO op_process (a) VALUES (1) RETURNING a")).isFalse();
        assertThat(R2dbcStorageEngine.isQuery("UPDATE t SET a = 1 RETURNING a")).isFalse();
        assertThat(R2dbcStorageEngine.isQuery("SET TRANSACTION READ ONLY")).isFalse();
        assertThat(R2dbcStorageEngine.isQuery("SELECTED")).isFalse();
    }

    @Test
    void aConflictOfAHeldRowIsItsWritesAndOtherErrorsStayAsTheyAre() {
        UUID key = UUID.randomUUID();
        List<WriteBatch.Row> rows = List.of(row("entity_registry", "entity_id", key, "held"));
        UniqueKeyViolationException conflict = new UniqueKeyViolationException("entity_registry_pkey",
            "entity_registry", "Key (entity_id)=(" + key + ") already exists.", null);

        assertThat(R2dbcStorageEngine.heldError(conflict, rows, Map.of(), true)).hasMessage("held:entity_registry");
        IllegalStateException other = new IllegalStateException("no");
        assertThat(R2dbcStorageEngine.heldError(other, rows, Map.of(), true)).isSameAs(other);
        // A conflict of the statement itself (another table): its own.
        UniqueKeyViolationException own = new UniqueKeyViolationException("x_pkey", "x", null, null);
        assertThat(R2dbcStorageEngine.heldError(own, rows, Map.of(), true)).isSameAs(own);
    }

    @Test
    void aRowIsPassedAsJsonOfItsColumns() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("process_seq_id", 7L);
        record.put("version_no", null);
        record.put("reason", StorageEngine.NULL);
        record.put("changes", new JsonText("{\"title\": [\"a\", \"b\"]}"));
        record.put("changed_fields", new String[] {"title"});
        record.put("recorded_time", Instant.parse("2026-01-31T09:00:00.123456Z"));
        record.put("amount", new BigDecimal("12.50"));
        record.put("event_id", UUID.fromString("0199a000-0000-7000-8000-000000000001"));
        record.put("active", true);

        assertThat(R2dbcStorageEngine.rowJson(record)).isEqualTo("{\"process_seq_id\":7,\"reason\":null,"
            + "\"changes\":{\"title\":[\"a\",\"b\"]},\"changed_fields\":[\"title\"],"
            + "\"recorded_time\":\"2026-01-31T09:00:00.123456Z\",\"amount\":12.50,"
            + "\"event_id\":\"0199a000-0000-7000-8000-000000000001\",\"active\":true}");
        assertThatThrownBy(() -> R2dbcStorageEngine.rowJson(Map.of("bad", new Object())))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> R2dbcStorageEngine.rowJson(Map.of("bad name", 1)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
