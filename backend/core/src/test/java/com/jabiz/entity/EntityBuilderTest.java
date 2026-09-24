package com.jabiz.entity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityBuilderTest {

    /** A minimal valid entity; each test adds the declarations it needs. */
    private static EntityDefinition define(Consumer<EntityBuilder> extra) {
        return EntityDefinition.define("Parcel", eb -> {
            eb.physicalTable("t_parcel");
            eb.primaryKey("parcelId");
            eb.field("parcelId", f -> f.physicalColumn("f_id").asSemanticIdentity("urn:test:parcel"));
            extra.accept(eb);
        });
    }

    private static void statusField(EntityBuilder eb) {
        eb.field("status", f -> f.physicalColumn("f_status")
            .asCode("urn:test:dict:parcel_status", "NEW", "SHIPPED", "DELIVERED", "CANCELLED"));
    }

    private static void assertInvalid(Consumer<EntityBuilder> extra, String messagePart) {
        assertThatThrownBy(() -> define(extra))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(messagePart);
    }

    @Test
    void requiresPhysicalTable() {
        assertThatThrownBy(() -> EntityDefinition.define("X", eb -> {
            eb.primaryKey("id");
            eb.field("id", f -> f.physicalColumn("id"));
        })).isInstanceOf(IllegalStateException.class)
            .hasMessage("Invalid entity definition 'X': physical table is not set");
    }

    @Test
    void requiresNonBlankPrimaryKey() {
        assertThatThrownBy(() -> EntityDefinition.define("X", eb -> {
            eb.physicalTable("t_x");
            eb.primaryKey(" ");
            eb.field("id", f -> f.physicalColumn("id"));
        })).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("primary key is not set");
    }

    @Test
    void primaryKeyMustBeADeclaredField() {
        assertThatThrownBy(() -> EntityDefinition.define("X", eb -> {
            eb.physicalTable("t_x");
            eb.primaryKey("id");
            eb.field("other", f -> f.physicalColumn("other"));
        })).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("primary key 'id' is not a declared field");
    }

    @Test
    void fieldNeedsPhysicalColumn() {
        assertInvalid(eb -> eb.field("weight", f -> f.asVersion()), "Field 'weight' has no physical column");
        assertInvalid(eb -> eb.field("weight", f -> f.physicalColumn("  ")), "has no physical column");
    }

    @Test
    void duplicateFieldIsRejected() {
        assertInvalid(eb -> eb.field("parcelId", f -> f.physicalColumn("f_other")),
            "Duplicate field 'parcelId' on entity Parcel");
    }

    @Test
    void physicalColumnMayBeMappedOnlyOnce() {
        assertInvalid(eb -> eb.field("alias", f -> f.physicalColumn("F_ID")),
            "physical column 'F_ID' is mapped by more than one field");
    }

    @Test
    void atMostOneVersionField() {
        assertInvalid(eb -> {
            eb.field("v1", f -> f.physicalColumn("f_v1").asVersion());
            eb.field("v2", f -> f.physicalColumn("f_v2").asVersion());
        }, "at most one Version field is allowed");
    }

    @Test
    void lifecycleFieldMustBeADeclaredCodeField() {
        assertInvalid(eb -> eb.stateTransitions("status", st -> st.from("A").to("B")),
            "lifecycle field 'status' must be a declared Code field");
        assertInvalid(eb -> {
            eb.field("status", f -> f.physicalColumn("f_status"));
            eb.stateTransitions("status", st -> st.from("A").to("B"));
        }, "must be a declared Code field");
    }

    @Test
    void onlyOneLifecycleFieldIsSupported() {
        assertInvalid(eb -> {
            statusField(eb);
            eb.stateTransitions("status", st -> st.from("NEW").to("SHIPPED"));
            eb.stateTransitions("otherStatus", st -> st.from("NEW").to("SHIPPED"));
        }, "already declares lifecycle field 'status'");
    }

    @Test
    void transitionStatesMustBelongToTheDictionary() {
        assertInvalid(eb -> {
            statusField(eb);
            eb.stateTransitions("status", st -> st.from("DRAFT").to("SHIPPED"));
        }, "state 'DRAFT' is not among the allowed values");
        assertInvalid(eb -> {
            statusField(eb);
            eb.stateTransitions("status", st -> st.from("NEW").to("SHIPPED", "LOST"));
        }, "state 'LOST' is not among the allowed values");
    }

    @Test
    void spatialGuardRequiresLifecycle() {
        assertInvalid(eb -> {
            eb.field("cell", f -> f.physicalColumn("f_cell").asSpatialH3(8));
            eb.spatialGuard("SHIPPED", "cell", cell -> true);
        }, "spatial guards require a lifecycle");
    }

    @Test
    void spatialGuardTargetMustBeAKnownState() {
        assertInvalid(eb -> {
            statusField(eb);
            eb.field("cell", f -> f.physicalColumn("f_cell").asSpatialH3(8));
            eb.stateTransitions("status", st -> st.from("NEW").to("SHIPPED"));
            eb.spatialGuard("TELEPORTED", "cell", cell -> true);
        }, "state 'TELEPORTED' is not among the allowed values");
    }

    @Test
    void spatialGuardLocationMustBeASpatialH3Field() {
        assertInvalid(eb -> {
            statusField(eb);
            eb.stateTransitions("status", st -> st.from("NEW").to("SHIPPED"));
            eb.spatialGuard("SHIPPED", "missing", cell -> true);
        }, "spatial guard location 'missing' must be a declared SpatialH3 field");
        assertInvalid(eb -> {
            statusField(eb);
            eb.field("cell", f -> f.physicalColumn("f_cell"));
            eb.stateTransitions("status", st -> st.from("NEW").to("SHIPPED"));
            eb.spatialGuard("SHIPPED", "cell", cell -> true);
        }, "must be a declared SpatialH3 field");
    }

    @Test
    void referenceSourceMustBeADeclaredField() {
        assertInvalid(eb -> eb.reference("ownerId", "Owner"), "reference source 'ownerId' is not a declared field");
    }

    @Test
    void fieldMayDeclareOnlyOneReference() {
        assertInvalid(eb -> {
            eb.field("ownerId", f -> f.physicalColumn("f_owner"));
            eb.reference("ownerId", "Owner");
            eb.reference("ownerId", "Customer");
        }, "field 'ownerId' declares more than one reference");
    }

    @Test
    void referenceDefinitionRejectsBlankParts() {
        assertThatThrownBy(() -> new ReferenceDefinition(" ", "Owner")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReferenceDefinition("ownerId", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validDefinitionExposesDerivedMetadata() {
        EntityDefinition def = define(eb -> {
            statusField(eb);
            eb.field("cell", f -> f.physicalColumn("f_cell").asSpatialH3(8));
            eb.field("ownerId", f -> f.physicalColumn("f_owner").required(true).immutable(true));
            eb.field("recordedAt", f -> f.physicalColumn("f_recorded").asTemporal(TemporalRole.SYSTEM_RECORDED));
            eb.field("eventAt", f -> f.physicalColumn("f_event").asTemporal(TemporalRole.EVENT_TIME));
            eb.field("rowVersion", f -> f.physicalColumn("f_version").asVersion());
            eb.stateTransitions("status", st -> {
                st.from("NEW").to("SHIPPED", "CANCELLED");
                st.from("SHIPPED").to("DELIVERED");
            });
            eb.spatialGuard("DELIVERED", "cell", cell -> cell > 0);
            eb.reference("ownerId", "Owner");
        });

        assertThat(def.fields.keySet())
            .containsExactly("parcelId", "status", "cell", "ownerId", "recordedAt", "eventAt", "rowVersion");
        assertThat(def.initialStates).containsExactly("NEW");
        assertThat(def.versionField).isEqualTo("rowVersion");
        assertThat(def.versionColumn()).contains("f_version");
        assertThat(def.stateColumn()).contains("f_status");
        assertThat(def.primaryKeyColumn()).isEqualTo("f_id");
        assertThat(def.allowsTransition("NEW", "CANCELLED")).isTrue();
        assertThat(def.allowsTransition("NEW", "DELIVERED")).isFalse();
        assertThat(def.allowsTransition("DELIVERED", "NEW")).isFalse();
        assertThat(def.guardsFor("DELIVERED")).singleElement()
            .satisfies(g -> assertThat(g.locationField()).isEqualTo("cell"));
        assertThat(def.guardsFor("SHIPPED")).isEmpty();
        assertThat(def.references).containsExactly(new ReferenceDefinition("ownerId", "Owner"));
        assertThat(def.isSystemManaged(def.field("rowVersion"))).isTrue();
        assertThat(def.isSystemManaged(def.field("recordedAt"))).isTrue();
        assertThat(def.isSystemManaged(def.field("eventAt"))).isFalse();
        assertThat(def.findField("nope")).isEmpty();
        assertThatThrownBy(() -> def.field("nope"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Field [nope] does not exist on entity [Parcel]");
        assertThatThrownBy(() -> def.fields.put("x", null)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void entityWithoutLifecycleOrVersion() {
        EntityDefinition def = define(eb -> {});

        assertThat(def.stateField).isNull();
        assertThat(def.stateColumn()).isEmpty();
        assertThat(def.versionColumn()).isEmpty();
        assertThat(def.initialStates).isEmpty();
    }

    @Test
    void cyclicLifecycleHasNoInitialState() {
        EntityDefinition def = define(eb -> {
            statusField(eb);
            eb.stateTransitions("status", st -> {
                st.from("NEW").to("SHIPPED");
                st.from("SHIPPED").to("NEW");
            });
        });
        assertThat(def.initialStates).isEmpty();
    }

    @Test
    void fieldBuilderRecordsFlagsKindAndExportableRules() {
        EntityDefinition def = define(eb -> eb.field("weight", f -> f.physicalColumn("f_weight")
            .required(true).immutable(true).generated(true)
            .asPhysicalQuantity(DimensionType.MASS, "urn:unit:si:kilogram")
            .rule("MIN_WEIGHT", "RANGE", Map.of("min", 1), (v, ctx) -> true)
            .rule("MAX_WEIGHT", "RANGE", Map.of("max", 9), v -> true)
            .rule("SERVER_ONLY_CTX", (v, ctx) -> true)
            .rule("SERVER_ONLY", v -> true)));

        FieldDefinition weight = def.field("weight");
        assertThat(weight.required()).isTrue();
        assertThat(weight.immutable()).isTrue();
        assertThat(weight.generated()).isTrue();
        assertThat(weight.kind()).isEqualTo(new SemanticKind.PhysicalQuantity(DimensionType.MASS, "urn:unit:si:kilogram"));
        assertThat(weight.rules()).extracting(FieldRule::code)
            .containsExactly("MIN_WEIGHT", "MAX_WEIGHT", "SERVER_ONLY_CTX", "SERVER_ONLY");
        // Only rules declared with a kind and parameters are exported to clients.
        assertThat(weight.ruleSpecs()).containsExactly(
            new RuleSpec("MIN_WEIGHT", "RANGE", Map.of("min", 1)),
            new RuleSpec("MAX_WEIGHT", "RANGE", Map.of("max", 9)));
    }

    @Test
    void codeFieldKeepsDictionaryValues() {
        EntityDefinition def = define(EntityBuilderTest::statusField);
        assertThat(def.field("status").kind())
            .isEqualTo(new SemanticKind.Code("urn:test:dict:parcel_status",
                List.of("NEW", "SHIPPED", "DELIVERED", "CANCELLED")));
    }

    @Test
    void fieldRuleRejectsNulls() {
        assertThatThrownBy(() -> new FieldRule(null, (v, c) -> true)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FieldRule("C", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> FieldRule.of("C", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void sampleEntityDefinitionsAreValid() {
        assertThat(WaybillEntityDefinitions.WAYBILL.initialStates).containsExactly("CREATED");
        assertThat(CustomsDeclarationEntityDefinitions.CUSTOMS_DECLARATION.references).hasSize(1);
        assertThat(PriceEntityDefinitions.PRICE.versionField).isEqualTo("rowVersion");
    }
}
