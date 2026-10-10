package com.jabiz.quizbuks.content;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VersionLabelsTest {

    @Test
    void numbersFromV10() {
        assertThat(VersionLabels.label(1)).isEqualTo("v1.0");
        assertThat(VersionLabels.label(2)).isEqualTo("v1.1");
        assertThat(VersionLabels.label(11)).isEqualTo("v1.10");
    }

    @Test
    void refusesVersionsBelowOne() {
        assertThatThrownBy(() -> VersionLabels.label(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
