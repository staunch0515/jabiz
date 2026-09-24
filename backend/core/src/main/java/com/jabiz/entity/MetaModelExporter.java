package com.jabiz.entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exports an entity definition as plain data for clients. Physical columns are never exported. */
public final class MetaModelExporter {

    private MetaModelExporter() {}

    public static Map<String, Object> export(EntityDefinition def) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (FieldDefinition f : def.fields.values()) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("name", f.name());
            json.put("immutable", f.immutable());
            json.put("required", f.required());
            json.put("generated", f.generated());
            json.putAll(kindToJson(f.kind()));
            json.put("rules", f.ruleSpecs());
            fields.add(json);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("entity", def.name);
        root.put("primaryKey", def.primaryKey);
        if (def.stateField != null) {
            root.put("stateField", def.stateField);
        }
        root.put("fields", fields);
        root.put("references", def.references.stream()
            .map(r -> Map.of("field", r.sourceField(), "targetEntity", r.targetEntity()))
            .toList());
        root.put("stateTransitions", def.transitions);
        return root;
    }

    private static Map<String, Object> kindToJson(SemanticKind kind) {
        return switch (kind) {
            case SemanticKind.SemanticIdentity si -> Map.of("type", "semanticIdentity", "urn", si.urn());
            case SemanticKind.Monetary m -> Map.of("type", "monetary", "currency", m.currency(), "scale", m.scale());
            case SemanticKind.PhysicalQuantity q -> Map.of("type", "physicalQuantity", "dimension", q.dimension().name(), "unit", q.unitUrn());
            case SemanticKind.Temporal t -> Map.of("type", "temporal", "role", t.role().name());
            case SemanticKind.SpatialH3 s -> Map.of("type", "spatialH3", "resolution", s.resolution());
            case SemanticKind.Code c -> Map.of("type", "code", "dictUrn", c.dictUrn(), "allowedValues", c.allowedValues());
            case SemanticKind.Version v -> Map.of("type", "version");
            case SemanticKind.None n -> Map.of("type", "none");
        };
    }
}

