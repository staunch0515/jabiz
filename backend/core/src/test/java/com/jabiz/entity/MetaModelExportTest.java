package com.jabiz.entity;

import com.jabiz.testkinds.TestCellKind;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link MetaModelExporter}, {@link JsonSchemaExporter}, {@link SemanticKinds} and {@link CustomKinds}. */
class MetaModelExportTest {

    private static final EntityDefinition ORDER = EntityDefinition.define("Order", eb -> {
        eb.physicalTable("t_order");
        eb.primaryKey("orderId");
        eb.publishChanges();
        eb.field("orderId", f -> f.physicalColumn("f_id").required(true).generated(true).asSemanticIdentity("urn:order"));
        eb.field("customerId", f -> f.physicalColumn("f_customer").required(true).asReference("Customer"));
        eb.field("note", f -> f.physicalColumn("f_note").asText(200, true));
        eb.field("amount", f -> f.physicalColumn("f_amount").required(true).asMonetary("JPY", 0)
            .rule("NON_NEGATIVE", "RANGE", Map.of("min", 0), v -> true));
        eb.field("ratio", f -> f.physicalColumn("f_ratio").asNumeric(5, 2));
        eb.field("express", f -> f.physicalColumn("f_express").asBool());
        eb.field("status", f -> f.physicalColumn("f_status").asCode("urn:dict:status", "OPEN", "DONE"));
        eb.field("channel", f -> f.physicalColumn("f_channel").asCode("urn:dict:channel"));
        eb.field("cell", f -> f.physicalColumn("f_cell").kind(TestCellKind.of(7)));
        eb.field("placedAt", f -> f.physicalColumn("f_placed").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("dueOn", f -> f.physicalColumn("f_due_on").asDate());
        eb.field("recordedAt", f -> f.physicalColumn("f_recorded").asTemporal(TemporalRole.SYSTEM_RECORDED));
        eb.field("legacy", f -> f.physicalColumn("f_legacy"));
        eb.field("rowVersion", f -> f.physicalColumn("f_version").asVersion());
        eb.stateTransitions("status", st -> st.from("OPEN").to("DONE"));
        eb.guard("PAID", "*", "DONE", (from, to, current, incoming, ctx) -> List.of());
        eb.unique("uk_order_note", "note");
        eb.listView("default", lv -> lv.columns("orderId", "status").filters("status").sorts("placedAt")
            .defaultSort("placedAt", false));
    });

    @SuppressWarnings("unchecked")
    private static Map<String, Object> field(Map<String, Object> export, String name) {
        return ((List<Map<String, Object>>) export.get("fields")).stream()
            .filter(f -> f.get("name").equals(name)).findFirst().orElseThrow();
    }

    @Test
    void exportDescribesTheWholeDefinitionWithoutPhysicalNames() {
        Map<String, Object> export = MetaModelExporter.export(ORDER);

        assertThat(export).containsEntry("entity", "Order").containsEntry("temporal", false)
            .containsEntry("publishesChanges", true)
            .containsEntry("stateField", "status");
        assertThat(export.get("dictionaries")).isEqualTo(List.of("urn:dict:status", "urn:dict:channel"));
        assertThat(export.get("guards")).isEqualTo(List.of(Map.of("code", "PAID", "from", "*", "to", "DONE")));
        assertThat(export.get("unique")).isEqualTo(List.of(Map.of("name", "uk_order_note", "fields", List.of("note"))));
        assertThat(export.get("references")).isEqualTo(List.of(Map.of("field", "customerId", "targetEntity", "Customer")));
        assertThat((List<?>) export.get("listViews")).singleElement().isEqualTo(Map.of(
            "name", "default", "columns", List.of("orderId", "status"), "filters", List.of("status"),
            "sorts", List.of("placedAt"), "defaultSort", Map.of("field", "placedAt", "asc", false)));
        assertThat(export.toString()).doesNotContain("f_id", "t_order");
    }

    @Test
    void fieldsCarryKindParametersAndOperators() {
        Map<String, Object> export = MetaModelExporter.export(ORDER);

        assertThat(field(export, "note")).containsEntry("type", "text").containsEntry("maxLength", 200)
            .containsEntry("multiline", true).containsEntry("operators",
                List.of("EQ", "IN", "IS_NOT_NULL", "IS_NULL", "LIKE", "NE"));
        assertThat(field(export, "ratio")).containsEntry("type", "numeric").containsEntry("precision", 5);
        assertThat(field(export, "express")).containsEntry("type", "bool");
        assertThat(field(export, "customerId")).containsEntry("type", "reference")
            .containsEntry("targetEntity", "Customer");
        assertThat(field(export, "cell")).containsEntry("type", "custom").containsEntry("kindId", "test.cell")
            .containsEntry("level", 7);
        assertThat(field(export, "channel")).containsEntry("type", "code").containsEntry("dictUrn", "urn:dict:channel");
        assertThat(field(export, "legacy")).containsEntry("type", "none");
        assertThat(field(export, "rowVersion")).containsEntry("type", "version").containsEntry("systemManaged", true);
        assertThat(field(export, "placedAt")).containsEntry("type", "temporal").containsEntry("role", "EVENT_TIME");
        assertThat(field(export, "dueOn")).containsEntry("type", "date").containsEntry("operators",
            List.of("BETWEEN", "EQ", "GT", "GTE", "IN", "IS_NOT_NULL", "IS_NULL", "LT", "LTE", "NE"));
        assertThat(field(export, "amount")).containsEntry("type", "monetary").containsEntry("currency", "JPY");
        assertThat(field(export, "orderId")).containsEntry("type", "semanticIdentity");
    }

    @Test
    @SuppressWarnings("unchecked")
    void jsonSchemaDescribesInput() {
        Map<String, Object> schema = JsonSchemaExporter.export(ORDER);
        Map<String, Map<String, Object>> props = (Map<String, Map<String, Object>>) schema.get("properties");

        assertThat(schema).containsEntry("$schema", JsonSchemaExporter.DIALECT).containsEntry("type", "object")
            .containsEntry("additionalProperties", false);
        // Generated and system-managed fields are read-only and never required.
        assertThat((List<String>) schema.get("required")).containsExactly("customerId", "amount");
        assertThat(props.get("orderId")).containsEntry("readOnly", true);
        assertThat(props.get("recordedAt")).containsEntry("readOnly", true).containsEntry("format", "date-time");
        assertThat(props.get("dueOn")).containsEntry("type", List.of("string", "null")).containsEntry("format", "date");
        assertThat(props.get("note")).containsEntry("type", List.of("string", "null")).containsEntry("maxLength", 200);
        assertThat(props.get("amount")).containsEntry("type", "number")
            .containsEntry("multipleOf", BigDecimal.ONE);
        assertThat(props.get("ratio")).containsEntry("multipleOf", new BigDecimal("0.01"))
            .containsEntry("exclusiveMaximum", new BigDecimal("1000"));
        assertThat((List<Object>) props.get("status").get("enum")).containsExactly("OPEN", "DONE", null);
        assertThat(props.get("channel")).doesNotContainKey("enum").containsEntry("x-jabiz-dictionary", "urn:dict:channel");
        assertThat(props.get("express")).containsEntry("type", List.of("boolean", "null"));
        assertThat(props.get("customerId")).containsEntry("x-jabiz-reference", "Customer");
        assertThat(props.get("cell")).containsKey("x-jabiz-kind");
        assertThat(props.get("legacy")).doesNotContainKey("type");
        assertThat(props.get("rowVersion")).containsEntry("type", List.of("integer", "null"));
    }

    @Test
    void customKindsAreRegisteredOnce() {
        assertThat(CustomKinds.find(TestCellKind.ID)).isPresent();
        CustomKinds.register(new TestCellKind()); // same implementation again: tolerated
        assertThatThrownBy(() -> CustomKinds.register(new CustomKindSupport() {
            public String kindId() { return TestCellKind.ID; }
            public Object coerce(Map<String, Object> p, Object raw, boolean in) { return raw; }
            public Class<?> javaType(Map<String, Object> p) { return Object.class; }
            public java.util.Set<com.jabiz.query.QueryOperator> allowedOperators(Map<String, Object> p) { return java.util.Set.of(); }
            public Map<String, Object> export(Map<String, Object> p) { return Map.of(); }
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("registered twice");
        assertThat(CustomKinds.find("none.such")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void localizedExportAddsLabelsAndTheMessagesTheClientMayReport() {
        com.jabiz.i18n.MessageCatalog catalog = new com.jabiz.i18n.MessageCatalog(
            List.of(com.jabiz.i18n.MessageCatalog.PLATFORM_BUNDLE, "i18ntest/messages"),
            List.of(java.util.Locale.CHINESE, java.util.Locale.JAPANESE, java.util.Locale.ENGLISH),
            java.util.Locale.ENGLISH, getClass().getClassLoader());

        Map<String, Object> ja = MetaModelExporter.export(ORDER, catalog, java.util.Locale.JAPANESE);

        assertThat(ja).containsEntry("label", "注文");
        assertThat(field(ja, "amount")).containsEntry("label", "金額");
        assertThat(field(ja, "note")).containsEntry("label", "note");
        Map<String, Object> messages = (Map<String, Object>) ja.get("messages");
        assertThat(messages.keySet()).containsExactly("INVALID_VALUE", "REQUIRED", "TOO_LONG", "NUMERIC_PRECISION",
            "MONETARY_SCALE", "NOT_IN_DICTIONARY", "NON_NEGATIVE");
        assertThat(messages).containsEntry("NON_NEGATIVE", "項目「{field}」は{min}以上でなければなりません。")
            .containsEntry("REQUIRED", "項目「{field}」は必須です。");

        EntityDefinition unlabeled = EntityDefinition.define("Bare", eb -> {
            eb.physicalTable("t_bare");
            eb.primaryKey("id");
            eb.field("id", f -> f.physicalColumn("f_id").asSemanticIdentity("urn:bare")
                .rule("NO_TEXT_ANYWHERE", "REQUIRED", Map.of(), v -> true));
        });
        Map<String, Object> en = MetaModelExporter.export(unlabeled, catalog, java.util.Locale.ENGLISH);
        assertThat(en).containsEntry("label", "Bare");
        assertThat((Map<String, Object>) en.get("messages")).containsEntry("NO_TEXT_ANYWHERE", "NO_TEXT_ANYWHERE");
        assertThat(MetaModelExporter.labelKey("Order", null)).isEqualTo("entity.Order");
        assertThat(MetaModelExporter.labelKey("Order", "amount")).isEqualTo("entity.Order.amount");
    }
}
