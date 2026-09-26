package com.jabiz.runtime.test.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Readable difference of two normalized snapshots (docs/design/07-quality.md section 3.3), one line per difference.
 * Rows are matched by their first field (the primary key):
 * <pre>
 * ~ FreightCharge[#uuid:3].surchargeAmount: 2000 → 2400
 * + FreightStatement[#uuid:9] {statementMonth=2026-03, ...}
 * - Price[#uuid:4] {sku=A-1, ...}
 * </pre>
 * The lines are what a rule change does to the business data; an empty list means the snapshots are equal.
 */
public final class SnapshotDiff {

    private SnapshotDiff() {}

    public static List<String> between(Map<String, List<Map<String, Object>>> expected,
        Map<String, List<Map<String, Object>>> actual) {
        List<String> lines = new ArrayList<>();
        Set<String> entities = new LinkedHashSet<>(expected.keySet());
        entities.addAll(actual.keySet());
        for (String entity : entities) {
            Map<Object, Map<String, Object>> before = byKey(expected.getOrDefault(entity, List.of()));
            Map<Object, Map<String, Object>> after = byKey(actual.getOrDefault(entity, List.of()));
            for (Map.Entry<Object, Map<String, Object>> row : before.entrySet()) {
                Map<String, Object> other = after.get(row.getKey());
                if (other == null) {
                    lines.add("- " + entity + "[" + row.getKey() + "] " + row.getValue());
                    continue;
                }
                Set<String> fields = new LinkedHashSet<>(row.getValue().keySet());
                fields.addAll(other.keySet());
                for (String field : fields) {
                    Object old = row.getValue().get(field);
                    Object now = other.get(field);
                    if (!Objects.equals(old, now)) {
                        lines.add("~ " + entity + "[" + row.getKey() + "]." + field + ": " + old + " → " + now);
                    }
                }
            }
            for (Map.Entry<Object, Map<String, Object>> row : after.entrySet()) {
                if (!before.containsKey(row.getKey())) {
                    lines.add("+ " + entity + "[" + row.getKey() + "] " + row.getValue());
                }
            }
        }
        return lines;
    }

    private static Map<Object, Map<String, Object>> byKey(List<Map<String, Object>> rows) {
        Map<Object, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object key = row.isEmpty() ? null : row.values().iterator().next();
            byKey.put(key, row);
        }
        return byKey;
    }
}
