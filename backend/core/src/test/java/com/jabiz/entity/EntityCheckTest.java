package com.jabiz.entity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityCheckTest {

    private static EntityDefinition define(Consumer<EntityBuilder> extra) {
        return EntityDefinition.define("Range", eb -> {
            eb.physicalTable("t_range");
            eb.primaryKey("rangeId");
            eb.field("rangeId", f -> f.physicalColumn("f_id").asSemanticIdentity("urn:test:range"));
            eb.field("low", f -> f.physicalColumn("f_low").asNumeric(9, 0));
            eb.field("high", f -> f.physicalColumn("f_high").asNumeric(9, 0));
            extra.accept(eb);
        });
    }

    private static final EntityCheck LOW_BELOW_HIGH = (state, ctx) -> {
        Number low = (Number) state.get("low");
        Number high = (Number) state.get("high");
        return low != null && high != null && low.longValue() > high.longValue()
            ? List.of(new Violation("low", "LOW_ABOVE_HIGH", "low above high"))
            : List.of();
    };

    @Test
    void checksSeeTheWholeStateAndReportTheirViolations() {
        EntityDefinition def = define(eb -> eb.check("LOW_ABOVE_HIGH", LOW_BELOW_HIGH));
        assertThat(def.checks).extracting(CheckDefinition::code).containsExactly("LOW_ABOVE_HIGH");

        List<Violation> violations = new ArrayList<>(List.of(new Violation("x", "EARLIER", "kept")));
        CheckDefinition.evaluate(def, Map.of("low", 5, "high", 3), null, violations);
        assertThat(violations).extracting(Violation::ruleCode).containsExactly("EARLIER", "LOW_ABOVE_HIGH");

        List<Violation> none = new ArrayList<>();
        CheckDefinition.evaluate(def, Map.of("low", 1, "high", 3), null, none);
        assertThat(none).isEmpty();
    }

    @Test
    void nullValuesReachTheCheck() {
        EntityDefinition def = define(eb -> eb.check("SEES_NULL", (state, ctx) -> state.containsKey("high")
            ? List.of(new Violation("high", "SEES_NULL", "null present"))
            : List.of()));
        java.util.Map<String, Object> state = new java.util.HashMap<>();
        state.put("high", null);
        List<Violation> violations = new ArrayList<>();
        CheckDefinition.evaluate(def, state, null, violations);
        assertThat(violations).hasSize(1);
    }

    @Test
    void aFailingCheckIsReportedNotThrown() {
        EntityDefinition def = define(eb -> eb.check("BROKEN", (state, ctx) -> {
            throw new IllegalStateException("boom");
        }));
        List<Violation> violations = new ArrayList<>();
        CheckDefinition.evaluate(def, Map.of(), null, violations);
        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.ruleCode()).isEqualTo("CHECK_EVALUATION_FAILED");
            assertThat(v.params()).containsEntry("check", "BROKEN");
        });
    }

    @Test
    void codesAreUniqueAndRequired() {
        assertThatThrownBy(() -> define(eb -> {
            eb.check("SAME", LOW_BELOW_HIGH);
            eb.check("SAME", LOW_BELOW_HIGH);
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("declared twice");
        assertThatThrownBy(() -> define(eb -> eb.check(" ", LOW_BELOW_HIGH)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> define(eb -> eb.check("NULL", null)))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void checksAreExportedByCode() {
        EntityDefinition def = define(eb -> eb.check("LOW_ABOVE_HIGH", LOW_BELOW_HIGH));
        assertThat(MetaModelExporter.export(def)).containsEntry("checks", List.of("LOW_ABOVE_HIGH"));
    }
}
