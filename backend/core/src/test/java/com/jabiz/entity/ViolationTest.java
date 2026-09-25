package com.jabiz.entity;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ViolationTest {

    @Test
    void threeArgumentFormHasNoParameters() {
        assertThat(new Violation("f", "CODE", "m").params()).isEmpty();
        assertThat(new Violation("f", "CODE", "m", null).params()).isEmpty();
    }

    @Test
    void parametersAreCopied() {
        Map<String, Object> params = new HashMap<>(Map.of("min", 0));
        Violation v = new Violation(null, "CODE", "m", params);
        params.put("max", 1);
        assertThat(v.params()).containsOnlyKeys("min");
    }

    @Test
    void codeIsRequired() {
        assertThatThrownBy(() -> new Violation("f", null, "m")).isInstanceOf(NullPointerException.class);
    }
}
