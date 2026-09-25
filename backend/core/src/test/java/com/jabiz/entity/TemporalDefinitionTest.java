package com.jabiz.entity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code eb.temporal()}: system fields, their columns and the build-time checks (docs/design/04 section 2.1). */
class TemporalDefinitionTest {

    private static EntityDefinition price(Consumer<EntityBuilder> extra) {
        return EntityDefinition.define("Price", eb -> {
            eb.physicalTable("t_price");
            eb.primaryKey("priceId");
            eb.field("priceId", f -> f.physicalColumn("price_id").immutable(true).required(true).generated(true)
                .asSemanticIdentity("urn:test:price"));
            eb.field("sku", f -> f.physicalColumn("sku").immutable(true).required(true).asText(32));
            eb.field("amount", f -> f.physicalColumn("amount").asMonetary("JPY", 0));
            extra.accept(eb);
        });
    }

    @Test
    void addsTheSystemFieldsWithTheirDefaultColumns() {
        EntityDefinition def = price(eb -> eb.temporal(t -> t.allowScheduled(true)));

        assertThat(def.temporal).isTrue();
        assertThat(def.temporalSpec.allowScheduled()).isTrue();
        assertThat(def.temporalSpec.rowIdColumn()).isEqualTo("row_id");
        assertThat(def.fields.keySet()).containsExactly("priceId", "sku", "amount",
            "versionNo", "effectStartTime", "createdTime", "processSeqId", "deleted");
        assertThat(def.physicalColumn("versionNo")).isEqualTo("version_no");
        assertThat(def.systemColumn("effectStartTime")).isEqualTo("effect_start_time");
        assertThat(def.systemColumn("createdTime")).isEqualTo("created_time");
        assertThat(def.systemColumn("processSeqId")).isEqualTo("process_seq_id");
        assertThat(def.systemColumn("deleted")).isEqualTo("is_deleted");
        assertThat(def.versionField).isEqualTo("versionNo");
        assertThat(def.field("effectStartTime").kind())
            .isEqualTo(new SemanticKind.Temporal(TemporalRole.VALID_FROM));

        assertThat(TemporalSpec.SYSTEM_FIELDS).allMatch(name -> def.isSystemManaged(def.field(name)));
        assertThat(def.isSystemManaged(def.field("amount"))).isFalse();
        assertThat(def.stateFields()).containsExactly("priceId", "sku", "amount");
        assertThat(def.changeableFields()).containsExactly("sku", "amount");
    }

    @Test
    void systemColumnsCanBeMapped() {
        EntityDefinition def = price(eb -> eb.temporal(t -> t
            .column("versionNo", "ver")
            .column("deleted", "tombstone")
            .rowIdColumn("rid")));

        assertThat(def.temporalSpec.allowScheduled()).isFalse();
        assertThat(def.systemColumn("versionNo")).isEqualTo("ver");
        assertThat(def.systemColumn("deleted")).isEqualTo("tombstone");
        assertThat(def.temporalSpec.rowIdColumn()).isEqualTo("rid");
    }

    @Test
    void theDefaultSettingsAllowNoScheduling() {
        EntityDefinition def = price(EntityBuilder::temporal);
        assertThat(def.temporalSpec.allowScheduled()).isFalse();
        assertThat(MetaModelExporter.export(def)).containsEntry("temporal", true).containsEntry("allowScheduled", false);
    }

    @Test
    void rejectsWhatTheSystemProvides() {
        assertThatThrownBy(() -> price(eb -> {
            eb.field("rowVersion", f -> f.physicalColumn("f_version").asVersion());
            eb.temporal();
        })).hasMessageContaining("must not declare a Version field");
        assertThatThrownBy(() -> price(eb -> {
            eb.field("recorded", f -> f.physicalColumn("f_rec").asTemporal(TemporalRole.SYSTEM_RECORDED));
            eb.temporal();
        })).hasMessageContaining("must not declare a SYSTEM_RECORDED field");
        assertThatThrownBy(() -> price(eb -> {
            eb.field("deleted", f -> f.physicalColumn("f_del").asBool());
            eb.temporal();
        })).hasMessageContaining("'deleted' is a temporal system field");
        assertThatThrownBy(() -> price(eb -> eb.temporal(t -> t.column("amount", "x"))))
            .hasMessageContaining("'amount' is not a temporal system field");
        assertThatThrownBy(() -> price(eb -> {
            eb.temporal();
            eb.temporal();
        })).hasMessageContaining("declared twice");
    }

    @Test
    void rejectsBadIdentitiesAndColumns() {
        assertThatThrownBy(() -> EntityDefinition.define("Bad", eb -> {
            eb.physicalTable("t_bad");
            eb.primaryKey("code");
            eb.field("code", f -> f.physicalColumn("code").asText(10));
            eb.temporal();
        })).hasMessageContaining("must be a SemanticIdentity");
        assertThatThrownBy(() -> price(eb -> eb.temporal(t -> t.column("versionNo", "bad column"))))
            .hasMessageContaining("is not a valid SQL identifier");
        assertThatThrownBy(() -> price(eb -> eb.temporal(t -> t.rowIdColumn("amount"))))
            .hasMessageContaining("mapped by more than one field");
        assertThatThrownBy(() -> price(eb -> eb.temporal(t -> t.column("deleted", "sku"))))
            .hasMessageContaining("mapped by more than one field");
    }

    @Test
    void temporalIdsAreUuids() {
        EntityDefinition def = price(EntityBuilder::temporal);
        UUID id = UUID.randomUUID();

        assertThat(def.normalizeId(id.toString())).isEqualTo(id);
        assertThat(def.normalizeId(id)).isSameAs(id);
        assertThat(def.normalizeId(null)).isNull();
        assertThatThrownBy(() -> def.normalizeId("P-1")).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("is not a UUID");

        EntityDefinition plain = price(eb -> eb.field("rowVersion", f -> f.physicalColumn("v").asVersion()));
        assertThat(plain.normalizeId("P-1")).isEqualTo("P-1");
        assertThat(plain.temporalSpec).isNull();
        assertThat(plain.stateFields()).containsExactly("priceId", "sku", "amount", "rowVersion");
        assertThatThrownBy(() -> plain.systemColumn("versionNo")).hasMessageContaining("is not temporal");
    }

    @Test
    void exportsSystemFieldsAsReadOnly() {
        EntityDefinition def = price(eb -> eb.temporal(t -> t.allowScheduled(true)));

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> properties =
            (Map<String, Map<String, Object>>) JsonSchemaExporter.export(def).get("properties");
        assertThat(properties.get("effectStartTime")).containsEntry("readOnly", true);
        assertThat(properties.get("deleted")).containsEntry("readOnly", true);
        assertThat(properties.get("amount")).doesNotContainKey("readOnly");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) MetaModelExporter.export(def).get("fields");
        assertThat(fields).filteredOn(f -> "versionNo".equals(f.get("name")))
            .singleElement().satisfies(f -> assertThat(f).containsEntry("systemManaged", true));
    }
}
