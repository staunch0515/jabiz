package com.jabiz.entity;

import com.jabiz.query.QueryOperator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exports an entity definition as plain data for clients (docs/design/02-metamodel.md section 8).
 * Physical tables and columns are never exported.
 */
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
            json.put("systemManaged", def.isSystemManaged(f));
            json.putAll(kindToJson(f.kind()));
            json.put("operators", SemanticKinds.allowedOperators(f.kind()).stream()
                .map(QueryOperator::name).sorted().toList());
            json.put("rules", f.ruleSpecs());
            fields.add(json);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("entity", def.name);
        root.put("primaryKey", def.primaryKey);
        root.put("temporal", def.temporal);
        if (def.stateField != null) {
            root.put("stateField", def.stateField);
        }
        root.put("fields", fields);
        root.put("references", def.references.stream()
            .map(r -> Map.of("field", r.sourceField(), "targetEntity", r.targetEntity()))
            .toList());
        root.put("stateTransitions", def.transitions);
        root.put("guards", def.guards.stream()
            .map(g -> Map.of("code", g.code(), "from", g.from(), "to", g.to()))
            .toList());
        root.put("unique", def.uniqueConstraints.stream()
            .map(u -> Map.of("name", u.name(), "fields", u.fields()))
            .toList());
        root.put("listViews", def.listViews.values().stream().map(MetaModelExporter::listView).toList());
        root.put("dictionaries", def.dictionaryUrns());
        return root;
    }

    private static Map<String, Object> listView(ListViewDefinition view) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", view.name());
        json.put("columns", view.columns());
        json.put("filters", view.filters());
        json.put("sorts", view.sorts());
        if (view.defaultSort() != null) {
            json.put("defaultSort", Map.of("field", view.defaultSort().field(), "asc", view.defaultSort().ascending()));
        }
        return json;
    }

    /** The semantic kind as {@code type} plus its parameters. */
    public static Map<String, Object> kindToJson(SemanticKind kind) {
        Map<String, Object> json = new LinkedHashMap<>();
        switch (kind) {
            case SemanticKind.SemanticIdentity si -> {
                json.put("type", "semanticIdentity");
                json.put("urn", si.urn());
            }
            case SemanticKind.Monetary m -> {
                json.put("type", "monetary");
                json.put("currency", m.currency());
                json.put("scale", m.scale());
            }
            case SemanticKind.Temporal t -> {
                json.put("type", "temporal");
                json.put("role", t.role().name());
            }
            case SemanticKind.Code c -> {
                json.put("type", "code");
                json.put("dictUrn", c.dictUrn());
                json.put("allowedValues", c.allowedValues());
            }
            case SemanticKind.Version v -> json.put("type", "version");
            case SemanticKind.Text t -> {
                json.put("type", "text");
                if (t.maxLength() != null) {
                    json.put("maxLength", t.maxLength());
                }
                json.put("multiline", t.multiline());
            }
            case SemanticKind.Numeric n -> {
                json.put("type", "numeric");
                json.put("precision", n.precision());
                json.put("scale", n.scale());
            }
            case SemanticKind.Bool b -> json.put("type", "bool");
            case SemanticKind.Reference r -> {
                json.put("type", "reference");
                json.put("targetEntity", r.targetEntity());
            }
            case SemanticKind.Custom c -> {
                json.putAll(CustomKinds.require(c.kindId()).export(c.params()));
                json.put("type", "custom");
                json.put("kindId", c.kindId());
            }
            case SemanticKind.None n -> json.put("type", "none");
        }
        return json;
    }
}
