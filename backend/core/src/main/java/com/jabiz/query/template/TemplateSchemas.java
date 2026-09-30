package com.jabiz.query.template;

import com.jabiz.entity.JsonSchemaExporter;
import com.jabiz.entity.SemanticKind;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.custom.QueryParameter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON Schema of a template's parameters (docs/design/19-reports.md section 3.2), in the same form as process
 * inputs so that one form generator serves both: decimals as {@code format: decimal} (exact text or number), times as
 * {@code format: date-time}, list parameters as arrays.
 */
public final class TemplateSchemas {

    private TemplateSchemas() {}

    /** @param query a query whose kinds have been resolved */
    public static Map<String, Object> params(AdvancedQueryDefinition query) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (QueryParameter parameter : query.parameters()) {
            Map<String, Object> property = kind(parameter.kind());
            if (parameter.list()) {
                Map<String, Object> array = new LinkedHashMap<>();
                array.put("type", "array");
                array.put("items", property);
                property = array;
            }
            if (parameter.description() != null && !parameter.description().isBlank()) {
                property.put("description", parameter.description());
            }
            if (parameter.defaultValue() != null) {
                property.put("default", parameter.defaultValue());
            }
            if (parameter.required()) {
                required.add(parameter.name());
            }
            properties.put(parameter.name(), property);
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("$schema", JsonSchemaExporter.DIALECT);
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> kind(SemanticKind kind) {
        Map<String, Object> s = new LinkedHashMap<>();
        switch (kind) {
            case SemanticKind.Monetary m -> decimal(s);
            case SemanticKind.Numeric n -> decimal(s);
            case SemanticKind.Temporal t -> {
                s.put("type", "string");
                s.put("format", "date-time");
            }
            case SemanticKind.Date d -> {
                s.put("type", "string");
                s.put("format", "date");
            }
            case SemanticKind.Code c -> {
                s.put("type", "string");
                if (!c.allowedValues().isEmpty()) {
                    s.put("enum", c.allowedValues());
                }
            }
            case SemanticKind.Text t -> {
                s.put("type", "string");
                if (t.maxLength() != null) {
                    s.put("maxLength", t.maxLength());
                }
            }
            case SemanticKind.Bool b -> s.put("type", "boolean");
            case SemanticKind.Version v -> s.put("type", "integer");
            case SemanticKind.SemanticIdentity i -> s.put("type", "string");
            case SemanticKind.Reference r -> s.put("type", "string");
            case SemanticKind.Custom c -> { }
            case SemanticKind.None n -> { }
            case null -> { }
        }
        return s;
    }

    private static void decimal(Map<String, Object> s) {
        s.put("type", List.of("string", "number"));
        s.put("format", "decimal");
    }
}
