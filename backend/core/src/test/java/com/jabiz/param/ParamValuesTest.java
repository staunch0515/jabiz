package com.jabiz.param;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParamValuesTest {

    private final ParamValues values = new ParamValues(Instant.parse("2026-01-01T00:00:00Z"),
        Map.of("rate", new BigDecimal("0.1000")));

    @Test
    void loadedValuesAreTyped() {
        assertThat(values.get("rate", BigDecimal.class)).isEqualByComparingTo("0.1");
        assertThat(values.asOf()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void unknownKeysAndWrongTypesFail() {
        assertThatThrownBy(() -> values.get("other", BigDecimal.class)).hasMessageContaining("was not loaded");
        assertThatThrownBy(() -> values.get("rate", String.class)).hasMessageContaining("not a String");
        assertThatThrownBy(() -> values.values().put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
    }
}
