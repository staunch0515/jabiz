package com.jabiz.runtime.report;

import com.jabiz.approval.ContentHash;
import com.jabiz.entity.MetaModelExporter;
import com.jabiz.entity.SemanticKind;
import com.jabiz.entity.SemanticKindParser;
import com.jabiz.report.ReportColumn;
import com.jabiz.report.ReportDocument;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * How an issued report's contents are kept (docs/design/19-reports.md section 5): values normalized by their
 * column's kind - amounts as exact decimals, times as instants, multilingual texts as sorted maps - stored as JSON
 * text (decimals and instants as strings, so nothing passes through floating point) and hashed with the canonical
 * form of {@link ContentHash}. Reading back and normalizing gives the same values, so a stored run always hashes to
 * the hash it was issued with.
 */
final class ArchivedValues {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ArchivedValues() {}

    /** The value as the archive keeps and hashes it. */
    static Object normalize(ReportColumn column, Object value) {
        if (value == null) {
            return null;
        }
        if (column.numeric()) {
            return value instanceof BigDecimal d ? d : new BigDecimal(value.toString());
        }
        if (column.temporal() || value instanceof Instant || value instanceof OffsetDateTime) {
            return switch (value) {
                case Instant i -> i;
                case OffsetDateTime t -> t.toInstant();
                default -> Instant.parse(value.toString());
            };
        }
        if (column.kind() instanceof SemanticKind.Bool || value instanceof Boolean) {
            return value instanceof Boolean b ? b : Boolean.valueOf(value.toString());
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
            return sorted;
        }
        return value.toString();
    }

    static List<List<Object>> normalize(List<ReportColumn> columns, List<List<Object>> rows) {
        List<List<Object>> result = new ArrayList<>(rows.size());
        for (List<Object> row : rows) {
            List<Object> values = new ArrayList<>(columns.size());
            for (int c = 0; c < columns.size(); c++) {
                values.add(normalize(columns.get(c), row.get(c)));
            }
            result.add(values);
        }
        return result;
    }

    /** The content hash of a report: its column names and rows, in order. */
    static String hash(List<ReportColumn> columns, List<List<Object>> rows) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("columns", columns.stream().map(ReportColumn::name).toList());
        content.put("rows", normalize(columns, rows));
        return ContentHash.of(content);
    }

    static String rowsJson(List<ReportColumn> columns, List<List<Object>> rows) {
        List<List<Object>> stored = new ArrayList<>(rows.size());
        for (List<Object> row : normalize(columns, rows)) {
            List<Object> values = new ArrayList<>(row.size());
            for (Object value : row) {
                values.add(switch (value) {
                    case BigDecimal d -> d.toPlainString();
                    case Instant i -> i.toString();
                    case null -> null;
                    default -> value;
                });
            }
            stored.add(values);
        }
        return JSON.writeValueAsString(stored);
    }

    static List<List<Object>> rows(List<ReportColumn> columns, String json) {
        List<List<Object>> stored = JSON.readValue(json, new TypeReference<>() {});
        return normalize(columns, stored);
    }

    static String columnsJson(List<ReportColumn> columns) {
        List<Map<String, Object>> stored = new ArrayList<>();
        for (ReportColumn column : columns) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", column.name());
            entry.put("label", column.label());
            entry.put("kind", MetaModelExporter.kindToJson(column.kind()));
            stored.add(entry);
        }
        return JSON.writeValueAsString(stored);
    }

    @SuppressWarnings("unchecked")
    static List<ReportColumn> columns(String json) {
        List<Map<String, Object>> stored = JSON.readValue(json, new TypeReference<>() {});
        return stored.stream().map(entry -> new ReportColumn((String) entry.get("name"), (String) entry.get("label"),
            kind((Map<String, Object>) entry.get("kind")))).toList();
    }

    /** The kind as the metamodel export wrote it; the export's {@code none} has no parser of its own. */
    private static SemanticKind kind(Map<String, Object> json) {
        return json == null || "none".equals(json.get("type")) ? new SemanticKind.None() : SemanticKindParser.parse(json);
    }

    static String parametersJson(List<ReportDocument.Parameter> parameters) {
        return JSON.writeValueAsString(parameters.stream()
            .map(p -> Map.of("label", p.label(), "value", p.value())).toList());
    }

    static List<ReportDocument.Parameter> parameters(String json) {
        List<Map<String, String>> stored = JSON.readValue(json, new TypeReference<>() {});
        return stored.stream().map(p -> new ReportDocument.Parameter(p.get("label"), p.get("value"))).toList();
    }

    /** The given parameters, keys sorted so that the same parameters are stored the same way. */
    static String paramsJson(Map<String, Object> params) {
        return JSON.writeValueAsString(new TreeMap<>(params));
    }

    static Map<String, Object> params(String json) {
        return JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
    }
}
