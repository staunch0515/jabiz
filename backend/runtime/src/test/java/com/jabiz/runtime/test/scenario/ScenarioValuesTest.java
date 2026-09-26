package com.jabiz.runtime.test.scenario;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioValuesTest {

    @Test
    void referencesKeepTheirTypeOrAreInterpolated() {
        Map<String, Object> variables = Map.of("id", "O-1", "count", 3, "output", Map.of("a", 1));
        assertThat(ScenarioValues.resolve("${count}", variables)).isEqualTo(3);
        assertThat(ScenarioValues.resolve("${output}", variables)).isEqualTo(Map.of("a", 1));
        assertThat(ScenarioValues.resolve("order ${id} x${count}", variables)).isEqualTo("order O-1 x3");
        assertThat(ScenarioValues.resolve(Map.of("list", List.of("${id}", 5)), variables))
            .isEqualTo(Map.of("list", List.of("O-1", 5)));
        assertThat(ScenarioValues.resolve(7, variables)).isEqualTo(7);
        assertThatThrownBy(() -> ScenarioValues.resolve("${nope}", variables)).hasMessageContaining("unknown variable");
    }

    @Test
    void pathsLeadIntoOutputs() {
        Object output = Map.of("order", Map.of("lines", List.of(Map.of("sku", "S1"), Map.of("sku", "S2"))));
        assertThat(ScenarioValues.extract(output, "$")).isEqualTo(output);
        assertThat(ScenarioValues.extract(output, "$.order.lines[1].sku")).isEqualTo("S2");
        assertThat(ScenarioValues.extract(List.of("a", "b"), "$[0]")).isEqualTo("a");
        assertThatThrownBy(() -> ScenarioValues.extract(output, "$.missing")).hasMessageContaining("leads nowhere");
        assertThatThrownBy(() -> ScenarioValues.extract(output, "$.order.lines[5]")).hasMessageContaining("[5]");
        assertThatThrownBy(() -> ScenarioValues.extract(output, "order")).hasMessageContaining("starts with $");
    }

    @Test
    void valuesMatchByMeaning() {
        assertThat(ScenarioValues.matches(0.1, new BigDecimal("0.1000"))).isTrue();
        assertThat(ScenarioValues.matches("2000", 2000L)).isTrue();
        assertThat(ScenarioValues.matches("2026-02-01T00:00:00+09:00", Instant.parse("2026-01-31T15:00:00Z")))
            .isTrue();
        assertThat(ScenarioValues.matches(Map.of("a", 1), Map.of("a", 1, "b", 2))).isTrue();
        assertThat(ScenarioValues.matches(Map.of("a", 1, "c", 3), Map.of("a", 1))).isFalse();
        assertThat(ScenarioValues.matches(List.of(1, 2), List.of(1, 2))).isTrue();
        assertThat(ScenarioValues.matches(List.of(1), List.of(1, 2))).isFalse();
        assertThat(ScenarioValues.matches(true, true)).isTrue();
        assertThat(ScenarioValues.matches("NEW", "OLD")).isFalse();
        assertThat(ScenarioValues.matches(null, null)).isTrue();
        assertThat(ScenarioValues.matches(null, 1)).isFalse();
        assertThat(ScenarioValues.matches(Map.of("a", 1), "a")).isFalse();
    }
}
