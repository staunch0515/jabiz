package com.jabiz.entity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exports an entity definition as a JSON Schema (draft 2020-12) describing the attributes of one instance
 * (docs/design/02-metamodel.md section 8). Used by clients and tools to validate input before it is sent;
 * the server remains the authority (rules that need runtime services cannot be expressed here).
 */
public final class JsonSchemaExporter {

    public static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";

    private JsonSchemaExporter() {}

    public static Map<String, Object> export(EntityDefinition def) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (FieldDefinition field : def.fields.values()) {
            Map<String, Object> property = new LinkedHashMap<>(kindSchema(field.kind()));
            boolean systemManaged = def.isSystemManaged(field);
            if (systemManaged || field.generated() || field.processOnly()) {
                property.put("readOnly", true);
            }
            if (field.sensitive()) {
                // Accepted by the processes that set it, never returned.
                property.put("writeOnly", true);
            }
            if (field.required() && !systemManaged && !field.generated()) {
                required.add(field.name());
            } else if (!field.required()) {
                property = nullable(property);
            }
            properties.put(field.name(), property);
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("$schema", DIALECT);
        schema.put("$id", "urn:jabiz:schema:entity:" + def.name);
        schema.put("title", def.name);
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> kindSchema(SemanticKind kind) {
        Map<String, Object> s = new LinkedHashMap<>();
        switch (kind) {
            case SemanticKind.SemanticIdentity si -> s.put("type", "string");
            case SemanticKind.Reference r -> {
                s.put("type", "string");
                s.put("x-jabiz-reference", r.targetEntity());
            }
            case SemanticKind.Monetary m -> {
                s.put("type", "number");
                s.put("multipleOf", BigDecimal.ONE.movePointLeft(m.scale()));
            }
            case SemanticKind.Numeric n -> {
                s.put("type", "number");
                s.put("multipleOf", BigDecimal.ONE.movePointLeft(n.scale()));
                BigDecimal bound = BigDecimal.TEN.pow(n.precision() - n.scale());
                s.put("exclusiveMaximum", bound);
                s.put("exclusiveMinimum", bound.negate());
            }
            case SemanticKind.Temporal t -> {
                s.put("type", "string");
                s.put("format", "date-time");
            }
            case SemanticKind.Code c -> {
                s.put("type", "string");
                if (!c.allowedValues().isEmpty()) {
                    s.put("enum", c.allowedValues());
                }
                s.put("x-jabiz-dictionary", c.dictUrn());
            }
            case SemanticKind.Version v -> s.put("type", "integer");
            case SemanticKind.Text t -> {
                s.put("type", "string");
                if (t.maxLength() != null) {
                    s.put("maxLength", t.maxLength());
                }
            }
            case SemanticKind.Bool b -> s.put("type", "boolean");
            case SemanticKind.Custom c -> s.put("x-jabiz-kind", MetaModelExporter.kindToJson(c));
            case SemanticKind.None n -> { }
        }
        return s;
    }

    /** Allows null next to the declared type. */
    private static Map<String, Object> nullable(Map<String, Object> property) {
        Object type = property.get("type");
        if (type instanceof String t) {
            property.put("type", List.of(t, "null"));
        }
        if (property.get("enum") instanceof List<?> values) {
            List<Object> withNull = new ArrayList<>(values);
            withNull.add(null);
            property.put("enum", withNull);
        }
        return property;
    }
}
