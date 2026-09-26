package com.jabiz.runtime.test.scenario;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotTest {

    private static final UUID ORDER = UUID.fromString("019c0000-0000-7000-8000-000000000001");
    private static final String LINE = "019c0000-0000-7000-8000-000000000002";

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    @Test
    void unstableValuesBecomePlaceholdersAndTheRestIsKept() {
        Map<String, List<Map<String, Object>>> raw = new LinkedHashMap<>();
        raw.put("Order", List.of(row("orderId", ORDER, "total", new BigDecimal("10.50"), "processSeqId",
            new BigDecimal("42"), "placedAt", Instant.parse("2026-01-31T09:00:00Z"), "tags", Map.of("b", 2, "a", 1))));
        raw.put("OrderLine", List.of(row("lineId", LINE, "orderId", ORDER.toString().toUpperCase(),
            "processSeqId", 17L, "done", true, "qty", 2)));

        Map<String, List<Map<String, Object>>> normalized = SnapshotNormalizer.normalize(raw);
        assertThat(normalized.get("Order").getFirst()).containsExactly(
            Map.entry("orderId", "#uuid:1"), Map.entry("total", "10.50"), Map.entry("processSeqId", "#op:2"),
            Map.entry("placedAt", "2026-01-31T09:00:00Z"), Map.entry("tags", Map.of("a", 1, "b", 2)));
        // Same UUID, same placeholder; operations numbered in their own order.
        assertThat(normalized.get("OrderLine").getFirst()).containsEntry("lineId", "#uuid:2")
            .containsEntry("orderId", "#uuid:1").containsEntry("processSeqId", "#op:1")
            .containsEntry("done", true).containsEntry("qty", 2);
    }

    @Test
    void theDiffNamesChangedFieldsAndAddedOrRemovedRows() {
        Map<String, List<Map<String, Object>>> before = Map.of("Charge", List.of(
            row("id", "#uuid:1", "amount", "2000", "settled", true),
            row("id", "#uuid:2", "amount", "1500", "settled", false)));
        Map<String, List<Map<String, Object>>> after = Map.of("Charge", List.of(
            row("id", "#uuid:1", "amount", "2400", "settled", true),
            row("id", "#uuid:3", "amount", "100", "settled", false)));
        assertThat(SnapshotDiff.between(before, after)).containsExactly(
            "~ Charge[#uuid:1].amount: 2000 → 2400",
            "- Charge[#uuid:2] {id=#uuid:2, amount=1500, settled=false}",
            "+ Charge[#uuid:3] {id=#uuid:3, amount=100, settled=false}");
        assertThat(SnapshotDiff.between(before, before)).isEmpty();
        assertThat(SnapshotDiff.between(Map.of(), Map.of("New", List.of(row("id", "x")))))
            .containsExactly("+ New[x] {id=x}");
    }

    @Test
    void documentsCompareTimeAndRows() {
        Map<String, Object> a = Map.of("asOf", "2026-01-01T00:00:00Z", "entities",
            Map.of("E", List.of(row("id", "1", "v", "a"))));
        Map<String, Object> b = Map.of("asOf", "2026-02-01T00:00:00Z", "entities",
            Map.of("E", List.of(row("id", "1", "v", "b"))));
        assertThat(SnapshotStore.differences(a, b)).containsExactly(
            "~ asOf: 2026-01-01T00:00:00Z → 2026-02-01T00:00:00Z", "~ E[1].v: a → b");
        assertThat(SnapshotStore.parse(SnapshotStore.render(a))).isEqualTo(a);
        assertThat(SnapshotStore.snapshotResource("scenarios/a/b.yml")).isEqualTo("scenarios/a/b.snapshot.json");
    }
}
