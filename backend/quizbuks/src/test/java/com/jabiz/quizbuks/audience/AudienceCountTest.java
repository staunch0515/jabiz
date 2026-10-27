package com.jabiz.quizbuks.audience;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AudienceCountTest {

    @Test
    void underTheThresholdOnlySaysFewer() {
        assertThat(AudienceCount.of(0)).isEqualTo(new AudienceCount(null, 20));
        assertThat(AudienceCount.of(19)).isEqualTo(new AudienceCount(null, 20));
    }

    @Test
    void otherwiseRoundsDownToTens() {
        assertThat(AudienceCount.of(20)).isEqualTo(new AudienceCount(20L, null));
        assertThat(AudienceCount.of(29)).isEqualTo(new AudienceCount(20L, null));
        assertThat(AudienceCount.of(1234)).isEqualTo(new AudienceCount(1230L, null));
    }
}
