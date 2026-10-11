package com.jabiz.external;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalJobResultTest {

    record Copy(String text) {}

    @Test
    void usageIsAddedUpByMetric() {
        ExternalJobResult<Copy> result = ExternalJobResult.of(new Copy("hello"))
            .usage("promptTokens", 812).usage("completionTokens", 64).usage("promptTokens", 8);
        assertThat(result.value()).isEqualTo(new Copy("hello"));
        assertThat(result.usage()).containsExactly(Map.entry("promptTokens", 820L), Map.entry("completionTokens", 64L));
        assertThat(result.toString()).contains("promptTokens").doesNotContain("hello");
    }

    @Test
    void usageNamesAndQuantitiesAreChecked() {
        ExternalJobResult<Copy> result = ExternalJobResult.of(new Copy("x"));
        assertThatThrownBy(() -> result.usage("1tokens", 1)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must match");
        assertThatThrownBy(() -> result.usage("tokens with spaces", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result.usage("t".repeat(65), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result.usage(null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result.usage("tokens", -1)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("negative");
        assertThat(result.usage("a.b_c-D9", 0).usage()).containsEntry("a.b_c-D9", 0L);
        Map<String, Long> withNull = new LinkedHashMap<>();
        withNull.put("tokens", null);
        assertThatThrownBy(() -> new ExternalJobResult<>(new Copy("x"), withNull))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExternalJobResult.of(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> result.usage("tokens", Long.MAX_VALUE).usage("tokens", 1))
            .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void handlerFailuresCarryACodeAndWhetherToRetry() {
        ExternalJobException permanent = ExternalJobException.permanent("PROMPT_REFUSED", "The model refused");
        assertThat(permanent.code()).isEqualTo("PROMPT_REFUSED");
        assertThat(permanent.isPermanent()).isTrue();
        assertThat(permanent.getMessage()).isEqualTo("The model refused");
        ExternalJobException retryable = ExternalJobException.retryable("BUSY", null);
        assertThat(retryable.isPermanent()).isFalse();
        assertThat(retryable.getMessage()).isEqualTo("BUSY");
        assertThatThrownBy(() -> ExternalJobException.permanent("lower", "x"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExternalJobException.permanent(null, "x"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void completionsHandOverTheTypedResultAndHideIt() {
        Instant at = Instant.parse("2026-10-11T00:00:00Z");
        ExternalJobCompletion completion = new ExternalJobCompletion("j1", "t", "u1", "ref", new Copy("secret text"),
            null, 2, at);
        assertThat(completion.result(Copy.class).text()).isEqualTo("secret text");
        assertThat(completion.usage()).isEmpty();
        assertThat(completion.toString()).contains("j1", "t").doesNotContain("secret text", "ref");
        assertThatThrownBy(() -> completion.result(String.class)).isInstanceOf(ClassCastException.class);
        ExternalJobFailure failure = new ExternalJobFailure("j1", "t", "u1", null, Map.of("tokens", 3L), 3, at,
            "TIMEOUT", "no result in time");
        assertThat(failure.usage()).containsEntry("tokens", 3L);
        assertThat(new ExternalJobFailure("j1", "t", "u1", null, null, 1, at, "X", "y").usage()).isEmpty();
    }
}
