package com.jabiz.entity;

import com.jabiz.i18n.MessageCatalog;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.query.QueryOperator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Exports an entity definition as plain data for clients (docs/design/02-metamodel.md section 8).
 * Physical tables and columns are never exported.
 */
public final class MetaModelExporter {

    /**
     * Codes of the input checks the client repeats before submitting (decision D15): the kind constraints of
     * {@link EntityValidator} and the conversion failure. Exported rules add their own codes.
     */
    public static final List<String> CLIENT_CHECK_CODES = List.of(PlatformErrorCodes.INVALID_VALUE,
        PlatformErrorCodes.REQUIRED, PlatformErrorCodes.TOO_LONG, PlatformErrorCodes.NUMERIC_PRECISION,
        PlatformErrorCodes.MONETARY_SCALE, PlatformErrorCodes.NOT_IN_DICTIONARY);

    private MetaModelExporter() {}

    /**
     * The export plus what a client needs to render and validate in one language (docs/design/12-frontend.md):
     * {@code label} of the entity and of each field (message keys {@code entity.<Entity>} and
     * {@code entity.<Entity>.<field>}, falling back to the logical name) and {@code messages}, the message
     * templates of every code the client may report for this entity, with the same named placeholders the server
     * fills; and {@code defaultLocale}, the platform's default language.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> export(EntityDefinition def, MessageCatalog catalog, Locale locale) {
        Map<String, Object> root = export(def);
        root.put("label", catalog.find(labelKey(def.name, null), locale).orElse(def.name));
        for (Map<String, Object> field : (List<Map<String, Object>>) root.get("fields")) {
            String name = (String) field.get("name");
            field.put("label", catalog.find(labelKey(def.name, name), locale).orElse(name));
        }
        Set<String> codes = new LinkedHashSet<>(CLIENT_CHECK_CODES);
        for (FieldDefinition f : def.fields.values()) {
            if (f.kind() instanceof SemanticKind.Custom c) {
                codes.addAll(CustomKinds.require(c.kindId()).violationCodes(c.params()));
            }
            f.ruleSpecs().forEach(spec -> codes.add(spec.code()));
        }
        Map<String, Object> messages = new LinkedHashMap<>();
        for (String code : codes) {
            messages.put(code, catalog.find(code, locale).orElse(code));
        }
        root.put("messages", messages);
        // Multilingual texts fall back to it when the interface language has no text (16 section 1.3).
        root.put("defaultLocale", catalog.defaultLocale().getLanguage());
        return root;
    }

    /** Message key of an entity's label ({@code field} null) or of one of its fields' labels. */
    public static String labelKey(String entity, String field) {
        return field == null ? "entity." + entity : "entity." + entity + "." + field;
    }

    public static Map<String, Object> export(EntityDefinition def) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (FieldDefinition f : def.fields.values()) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("name", f.name());
            json.put("immutable", f.immutable());
            json.put("required", f.required());
            json.put("generated", f.generated());
            json.put("systemManaged", def.isSystemManaged(f));
            json.put("sensitive", f.sensitive());
            json.put("processOnly", f.processOnly());
            if (f.isMasked()) {
                json.put("masked", Map.of("permission", f.masked().permission(), "style", f.masked().style().name()));
            }
            json.putAll(kindToJson(f.kind()));
            json.put("operators", SemanticKinds.allowedOperators(f.kind()).stream()
                .map(QueryOperator::name).sorted().toList());
            json.put("rules", f.ruleSpecs());
            fields.add(json);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("entity", def.name);
        root.put("primaryKey", def.primaryKey);
        if (def.displayField != null) {
            root.put("display", def.displayField);
        }
        root.put("temporal", def.temporal);
        root.put("publishesChanges", def.publishesChanges);
        if (def.temporal) {
            root.put("allowScheduled", def.temporalSpec.allowScheduled());
            // Only inserted: pages offer no update, delete or revert (decision D29).
            root.put("writeOnce", def.temporalSpec.writeOnce());
        }
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
        root.put("checks", def.checks.stream().map(CheckDefinition::code).toList());
        root.put("unique", def.uniqueConstraints.stream()
            .map(u -> u.ignoreCase()
                ? Map.<String, Object>of("name", u.name(), "fields", u.fields(), "ignoreCase", true)
                : Map.<String, Object>of("name", u.name(), "fields", u.fields()))
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
            case SemanticKind.Date d -> json.put("type", "date");
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
