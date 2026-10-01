package com.jabiz.param;

import com.jabiz.entity.SemanticKind;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParamKindsTest {

    private static final SemanticKind RATE = ParamKinds.parse(Map.of("type", "numeric", "precision", 5, "scale", 4));

    @Test
    void numericValuesAreStoredAtTheirScale() {
        assertThat(ParamKinds.canonical(RATE, "0.1")).isEqualTo("0.1000");
        assertThat(ParamKinds.canonical(RATE, new BigDecimal("9.5"))).isEqualTo("9.5000");
        assertThat(ParamKinds.canonical(RATE, 0)).isEqualTo("0.0000");
        assertThat((BigDecimal) ParamKinds.value(RATE, "0.15")).isEqualByComparingTo("0.15");
        assertThatThrownBy(() -> ParamKinds.canonical(RATE, "12.5")).hasMessageContaining("does not fit");
        assertThatThrownBy(() -> ParamKinds.canonical(RATE, "0.12345")).hasMessageContaining("decimal places");
        assertThatThrownBy(() -> ParamKinds.canonical(RATE, "much")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ParamKinds.canonical(RATE, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void monetaryValuesKeepTheCurrencyScale() {
        SemanticKind jpy = ParamKinds.parse(Map.of("type", "monetary", "currency", "JPY", "scale", 0));
        assertThat(ParamKinds.canonical(jpy, 1500)).isEqualTo("1500");
        assertThatThrownBy(() -> ParamKinds.canonical(jpy, "10.5")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void otherKindsRoundTrip() {
        SemanticKind text = ParamKinds.parse(Map.of("type", "text", "maxLength", 3));
        assertThat(ParamKinds.value(text, "abc")).isEqualTo("abc");
        assertThatThrownBy(() -> ParamKinds.canonical(text, "abcd")).hasMessageContaining("longer than 3");

        SemanticKind bool = ParamKinds.parse(Map.of("type", "bool"));
        assertThat(ParamKinds.canonical(bool, true)).isEqualTo("true");
        assertThat(ParamKinds.value(bool, "false")).isEqualTo(false);

        SemanticKind time = ParamKinds.parse(Map.of("type", "temporal", "role", "EVENT_TIME"));
        assertThat(ParamKinds.canonical(time, "2026-02-01T00:00:00+09:00")).isEqualTo("2026-01-31T15:00:00Z");
        assertThat(ParamKinds.value(time, "2026-01-31T15:00:00Z")).isEqualTo(Instant.parse("2026-01-31T15:00:00Z"));

        SemanticKind date = ParamKinds.parse(Map.of("type", "date"));
        assertThat(ParamKinds.canonical(date, "2026-01-31")).isEqualTo("2026-01-31");
        assertThat(ParamKinds.value(date, "2026-01-31")).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThatThrownBy(() -> ParamKinds.canonical(date, "2026-02-30")).isInstanceOf(IllegalArgumentException.class);

        SemanticKind mode = ParamKinds.parse(Map.of("type", "code", "dictUrn", "urn:m", "allowedValues",
            List.of("FAST", "SLOW")));
        assertThat(ParamKinds.canonical(mode, "FAST")).isEqualTo("FAST");
        assertThatThrownBy(() -> ParamKinds.canonical(mode, "WARP")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unsupportedKindsAreRejected() {
        assertThatThrownBy(() -> ParamKinds.parse(Map.of("type", "code", "dictUrn", "urn:m")))
            .hasMessageContaining("allowed values");
        assertThatThrownBy(() -> ParamKinds.parse(Map.of("type", "version"))).hasMessageContaining("version");
        assertThatThrownBy(() -> ParamKinds.parse(Map.of("type", "reference", "targetEntity", "X")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ParamKinds.parse(Map.of("type", "nope"))).isInstanceOf(IllegalArgumentException.class);
    }
}
