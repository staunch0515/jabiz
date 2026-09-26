package com.jabiz.runtime.test.scenario;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Makes an exported snapshot comparable across replays (docs/design/07-quality.md section 3.3). Values that differ
 * from run to run without meaning anything become numbered placeholders in order of first appearance: UUIDs
 * {@code #uuid:N} in order of first appearance (the same UUID always gets the same number, so references stay
 * recognisable) and operation numbers ({@code processSeqId}) {@code #op:N} in the order of the operations. Times come from the scenario's clock and so are kept (as ISO-8601);
 * decimals are kept as text so that their scale shows. Maps get sorted keys; rows keep the order they come in.
 */
public final class SnapshotNormalizer {

    static final String PROCESS_SEQ_ID = "processSeqId";

    private static final Pattern UUID_TEXT =
        Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final Map<String, String> uuids = new HashMap<>();
    private final Map<String, String> operations = new HashMap<>();

    /**
     * @param entities rows by entity, each row a map of field to value, rows in a deterministic order
     * @return the same structure with normalized values
     */
    public static Map<String, List<Map<String, Object>>> normalize(Map<String, List<Map<String, Object>>> entities) {
        SnapshotNormalizer normalizer = new SnapshotNormalizer();
        entities.values().stream().flatMap(List::stream)
            .map(row -> row.get(PROCESS_SEQ_ID)).filter(java.util.Objects::nonNull)
            .map(value -> new BigDecimal(value.toString()).toBigInteger()).distinct().sorted()
            .forEach(seq -> normalizer.operations.put(seq.toString(), "#op:" + (normalizer.operations.size() + 1)));
        Map<String, List<Map<String, Object>>> normalized = new LinkedHashMap<>();
        entities.forEach((entity, rows) -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Map<String, Object> fields = new LinkedHashMap<>();
                row.forEach((field, value) -> fields.put(field, PROCESS_SEQ_ID.equals(field) && value != null
                    ? normalizer.operation(value)
                    : normalizer.value(value)));
                out.add(fields);
            }
            normalized.put(entity, out);
        });
        return normalized;
    }

    private Object value(Object value) {
        return switch (value) {
            case null -> null;
            case UUID uuid -> uuid(uuid.toString());
            case String text when UUID_TEXT.matcher(text).matches() -> uuid(text);
            case Instant instant -> instant.toString();
            case OffsetDateTime time -> time.toInstant().toString();
            case BigDecimal decimal -> decimal.toPlainString();
            case Map<?, ?> map -> {
                Map<String, Object> sorted = new TreeMap<>();
                map.forEach((key, item) -> sorted.put(String.valueOf(key), value(item)));
                yield sorted;
            }
            case List<?> list -> list.stream().map(this::value).toList();
            case Boolean bool -> bool;
            case Integer number -> number;
            case Long number -> number;
            default -> String.valueOf(value);
        };
    }

    private String uuid(String text) {
        return uuids.computeIfAbsent(text.toLowerCase(java.util.Locale.ROOT), key -> "#uuid:" + (uuids.size() + 1));
    }

    private String operation(Object value) {
        return operations.get(new BigDecimal(value.toString()).toBigInteger().toString());
    }
}
