package com.jabiz.i18n;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MessageTemplateTest {

    @Test
    void replacesNamedPlaceholders() {
        assertThat(MessageTemplate.format("{field} must be between {min} and {max}",
            Map.of("field", "weight", "min", 10.0, "max", 50000)))
            .isEqualTo("weight must be between 10.0 and 50000");
    }

    @Test
    void keepsPlaceholdersWithoutValueAndSpecialCharacters() {
        assertThat(MessageTemplate.format("It's {missing} ({x})", Map.of("x", "$1\\")))
            .isEqualTo("It's {missing} ($1\\)");
    }

    @Test
    void textWithoutPlaceholdersIsUnchanged() {
        assertThat(MessageTemplate.format("plain", Map.of())).isEqualTo("plain");
    }
}
