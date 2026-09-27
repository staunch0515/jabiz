package com.jabiz.entity;

import com.jabiz.testkinds.TestCellKind;
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
    void initialStatesAreInferredWhenNotDeclared() {
        EntityDefinition def = define(eb -> {
            statusField(eb);
            eb.stateTransitions("status", st -> st.from("NEW").to("SHIPPED").from("SHIPPED").to("DELIVERED"));
        });
        assertThat(def.initialStates).containsExactly("NEW");
        assertThat(def.soleInitialState()).isEqualTo("NEW");
    }

    @Test
    void aLifecycleThatReturnsToItsFirstStateDeclaresIt() {
        // Without the declaration no state is left but never entered, so there would be no initial state.
        EntityDefinition inferred = define(eb -> {
            statusField(eb);
            eb.stateTransitions("status", st -> st.from("NEW").to("SHIPPED").from("SHIPPED").to("NEW"));
        });
        assertThat(inferred.initialStates).isEmpty();

        EntityDefinition declared = define(eb -> {
            statusField(eb);
            eb.stateTransitions("status", st -> st.initial("NEW").from("NEW").to("SHIPPED").from("SHIPPED").to("NEW"));
        });
        assertThat(declared.initialStates).containsExactly("NEW");
        assertThat(declared.soleInitialState()).isEqualTo("NEW");
        assertThat(declared.allowsTransition("SHIPPED", "NEW")).isTrue();

        EntityDefinition several = define(eb -> {
            statusField(eb);
            eb.stateTransitions("status", st -> st.initial("SHIPPED", "NEW")
                .from("NEW").to("SHIPPED").from("SHIPPED").to("NEW"));
        });
        assertThat(several.initialStates).containsExactly("SHIPPED", "NEW");
        assertThat(several.soleInitialState()).isNull();
    }

    @Test
    void declaredInitialStatesMustBelongToTheDictionaryAndLeadSomewhere() {
        assertInvalid(eb -> {
            statusField(eb);
            eb.stateTransitions("status", st -> st.initial("DRAFT").from("NEW").to("SHIPPED"));
        }, "state 'DRAFT' is not among the allowed values");
        assertInvalid(eb -> {
            statusField(eb);
            eb.stateTransitions("status", st -> st.initial("DELIVERED").from("NEW").to("SHIPPED"));
        }, "initial state 'DELIVERED' has no transition out of it");
        assertThatThrownBy(() -> define(eb -> {
            statusField(eb);
            eb.stateTransitions("status", st -> st.initial("NEW", "NEW").from("NEW").to("SHIPPED"));
        })).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("declared twice");
    }

    @Test
    void guardsRequireALifecycle() {
        assertInvalid(eb -> eb.guard("G", "*", "SHIPPED", (f, t, c, i, ctx) -> List.of()),
            "transition guards require a lifecycle");
    }

    @Test
    void guardStatesMustBelongToTheDictionary() {
        assertInvalid(eb -> {
            statusField(eb);
            eb.stateTransitions("status", st -> st.from("NEW").to("SHIPPED"));
            eb.guard("G", "*", "TELEPORTED", (f, t, c, i, ctx) -> List.of());
        }, "state 'TELEPORTED' is not among the allowed values");
        assertInvalid(eb -> {
            statusField(eb);
            eb.stateTransitions("status", st -> st.from("NEW").to("SHIPPED"));
            eb.guard("G", "LIMBO", "SHIPPED", (f, t, c, i, ctx) -> List.of());
        }, "state 'LIMBO' is not among the allowed values");
    }

    @Test
    void guardCodesAreUnique() {
        assertInvalid(eb -> {
            statusField(eb);
            eb.stateTransitions("status", st -> st.from("NEW").to("SHIPPED"));
            eb.guard("G", "*", "SHIPPED", (f, t, c, i, ctx) -> List.of());
            eb.guard("G", "NEW", "SHIPPED", (f, t, c, i, ctx) -> List.of());
        }, "guard code 'G' is declared twice");
    }

    @Test
    void guardDefinitionMatchesSourceAndTarget() {
        GuardDefinition any = new GuardDefinition("G", "*", "SHIPPED", (f, t, c, i, ctx) -> List.of());
        GuardDefinition fromNew = new GuardDefinition("H", "NEW", "SHIPPED", (f, t, c, i, ctx) -> List.of());

        assertThat(any.appliesTo(null, "SHIPPED")).isTrue();
        assertThat(any.appliesTo("NEW", "SHIPPED")).isTrue();
        assertThat(any.appliesTo("NEW", "DELIVERED")).isFalse();
        assertThat(fromNew.appliesTo("NEW", "SHIPPED")).isTrue();
        assertThat(fromNew.appliesTo(null, "SHIPPED")).isFalse();
        assertThatThrownBy(() -> new GuardDefinition(" ", "*", "X", (f, t, c, i, ctx) -> List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GuardDefinition("G", "*", null, (f, t, c, i, ctx) -> List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GuardDefinition("G", "*", "X", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void referenceKindDeclaresTheReference() {
        EntityDefinition def = define(eb -> eb.field("ownerId", f -> f.physicalColumn("f_owner").asReference("Owner")));

        assertThat(def.references).containsExactly(new ReferenceDefinition("ownerId", "Owner"));
        assertInvalid(eb -> {
            eb.field("ownerId", f -> f.physicalColumn("f_owner").asReference("Owner"));
            eb.reference("ownerId", "Owner");
        }, "is a Reference and must not also be declared with reference()");
    }

    @Test
    void uniqueConstraintsAreValidated() {
        EntityDefinition def = define(eb -> {
            eb.field("code", f -> f.physicalColumn("f_code"));
            eb.unique("uk_parcel_code", "code", "parcelId");
        });
        assertThat(def.uniqueConstraints).containsExactly(new UniqueConstraint("uk_parcel_code", List.of("code", "parcelId")));

        assertInvalid(eb -> eb.unique("uk_x", "missing"), "unique constraint 'uk_x' refers to unknown field 'missing'");
        assertInvalid(eb -> eb.unique("uk x", "parcelId"), "is not a valid SQL identifier");
        assertInvalid(eb -> {
            eb.unique("uk_x", "parcelId");
            eb.unique("UK_X", "parcelId");
        }, "unique constraint 'UK_X' is declared twice");
        assertInvalid(eb -> eb.unique("uk_x", "parcelId", "parcelId"), "lists a field twice");
        assertThatThrownBy(() -> new UniqueConstraint("uk", List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UniqueConstraint(null, List.of("a"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listViewsAreValidated() {
        EntityDefinition def = define(eb -> {
            statusField(eb);
            eb.listView("default", lv -> lv.columns("parcelId", "status").filters("status").sorts("parcelId")
                .defaultSort("parcelId", false));
            eb.listView("compact", lv -> lv.columns("parcelId"));
        });

        ListViewDefinition view = def.listView("default").orElseThrow();
        assertThat(def.listViews.keySet()).containsExactly("default", "compact");
        assertThat(view.allowsFilter("status")).isTrue();
        assertThat(view.allowsFilter("parcelId")).isFalse();
        assertThat(view.allowsSort("parcelId")).isTrue();
        assertThat(view.defaultSort()).isEqualTo(new ListViewDefinition.Sort("parcelId", false));
        assertThat(def.listView("compact").orElseThrow().defaultSort()).isNull();
        assertThat(def.listView("missing")).isEmpty();

        assertInvalid(eb -> eb.listView("v", lv -> lv.filters("nope")), "list view 'v' refers to unknown field 'nope'");
        assertInvalid(eb -> eb.listView("v", lv -> lv.sorts("parcelId").defaultSort("other", true)),
            "default sort 'other' is not among its sorts");
        assertInvalid(eb -> {
            eb.listView("v", lv -> {});
            eb.listView("v", lv -> {});
        }, "list view 'v' is declared twice");
        assertThatThrownBy(() -> new ListViewDefinition(" ", List.of(), List.of(), List.of(), null))
            .isInstanceOf(IllegalArgumentException.class);
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
            eb.field("cell", f -> f.physicalColumn("f_cell").kind(TestCellKind.of(8)));
            eb.field("ownerId", f -> f.physicalColumn("f_owner").required(true).immutable(true));
            eb.field("recordedAt", f -> f.physicalColumn("f_recorded").asTemporal(TemporalRole.SYSTEM_RECORDED));
            eb.field("eventAt", f -> f.physicalColumn("f_event").asTemporal(TemporalRole.EVENT_TIME));
            eb.field("rowVersion", f -> f.physicalColumn("f_version").asVersion());
            eb.stateTransitions("status", st -> {
                st.from("NEW").to("SHIPPED", "CANCELLED");
                st.from("SHIPPED").to("DELIVERED");
            });
            eb.guard("IN_AREA", "*", "DELIVERED", (from, to, current, incoming, ctx) -> List.of());
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
        assertThat(def.guardsFor("SHIPPED", "DELIVERED")).singleElement()
            .satisfies(g -> assertThat(g.code()).isEqualTo("IN_AREA"));
        assertThat(def.guardsFor("NEW", "SHIPPED")).isEmpty();
        assertThat(def.temporal).isFalse();
        assertThat(def.dictionaryUrns()).containsExactly("urn:test:dict:parcel_status");
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
            .asNumeric(9, 3)
            .rule("MIN_WEIGHT", "RANGE", Map.of("min", 1), (v, ctx) -> true)
            .rule("MAX_WEIGHT", "RANGE", Map.of("max", 9), v -> true)
            .rule("SERVER_ONLY_CTX", (v, ctx) -> true)
            .rule("SERVER_ONLY", v -> true)));

        FieldDefinition weight = def.field("weight");
        assertThat(weight.required()).isTrue();
        assertThat(weight.immutable()).isTrue();
        assertThat(weight.generated()).isTrue();
        assertThat(weight.kind()).isEqualTo(new SemanticKind.Numeric(9, 3));
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
}
