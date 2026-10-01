package com.jabiz.entity;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a semantic kind written as data, as in the {@code kind:} entries of SQL template headers
 * (docs/design/05-sql-template.md section 2.1). The shape is that of {@link MetaModelExporter#kindToJson}: a
 * {@code type} and its parameters, for example {@code {type: monetary, currency: JPY, scale: 0}}. Custom kinds take
 * their parameters under {@code params} (the export instead merges what the kind's support describes).
 */
public final class SemanticKindParser {

    private SemanticKindParser() {}

    /** @throws IllegalArgumentException for an unknown type or missing or malformed parameters */
    public static SemanticKind parse(Map<String, ?> spec) {
        if (spec == null) {
            throw new IllegalArgumentException("kind must not be empty");
        }
        String type = text(spec, "type", true);
        return switch (type) {
            case "semanticIdentity" -> new SemanticKind.SemanticIdentity(text(spec, "urn", true));
            case "monetary" -> new SemanticKind.Monetary(text(spec, "currency", true), integer(spec, "scale"));
            case "temporal" -> new SemanticKind.Temporal(role(text(spec, "role", true)));
            case "date" -> new SemanticKind.Date();
            case "code" -> new SemanticKind.Code(text(spec, "dictUrn", true), strings(spec, "allowedValues"));
            case "version" -> new SemanticKind.Version();
            case "text" -> new SemanticKind.Text(integerOrNull(spec, "maxLength"),
                Boolean.TRUE.equals(spec.get("multiline")));
            case "numeric" -> new SemanticKind.Numeric(integer(spec, "precision"), integer(spec, "scale"));
            case "bool" -> new SemanticKind.Bool();
            case "reference" -> new SemanticKind.Reference(text(spec, "targetEntity", true));
            case "custom" -> new SemanticKind.Custom(text(spec, "kindId", true), params(spec));
            default -> throw new IllegalArgumentException("unknown kind type '" + type + "'");
        };
    }

    private static TemporalRole role(String value) {
        try {
            return TemporalRole.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown temporal role '" + value + "'", e);
        }
    }

    private static String text(Map<String, ?> spec, String key, boolean required) {
        Object value = spec.get(key);
        if (value == null || value.toString().isBlank()) {
            if (required) {
                throw new IllegalArgumentException("kind needs '" + key + "'");
            }
            return null;
        }
        return value.toString();
    }

    private static int integer(Map<String, ?> spec, String key) {
        Integer value = integerOrNull(spec, key);
        if (value == null) {
            throw new IllegalArgumentException("kind needs '" + key + "'");
        }
        return value;
    }

    private static Integer integerOrNull(Map<String, ?> spec, String key) {
        Object value = spec.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number n && n.doubleValue() == n.intValue()) {
            return n.intValue();
        }
        throw new IllegalArgumentException("kind parameter '" + key + "' must be an integer");
    }

    private static List<String> strings(Map<String, ?> spec, String key) {
        Object value = spec.get(key);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        throw new IllegalArgumentException("kind parameter '" + key + "' must be a list");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> params(Map<String, ?> spec) {
        Object value = spec.get("params");
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                if (v == null) {
                    throw new IllegalArgumentException("custom kind parameter '" + k + "' has no value");
                }
            });
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("kind parameter 'params' must be a map");
    }
}
