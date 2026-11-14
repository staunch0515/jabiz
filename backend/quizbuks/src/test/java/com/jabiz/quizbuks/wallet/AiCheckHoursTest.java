package com.jabiz.quizbuks.wallet;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** #702 review L3: the AI fee's two check hours are taken within 48 to 720. */
class AiCheckHoursTest {

    @Test
    void theHoursAreTakenWithinTheirRange() {
        assertThat(Reconciliation.clampAiCheckHours(BigDecimal.ONE)).isEqualByComparingTo("48");
        assertThat(Reconciliation.clampAiCheckHours(BigDecimal.valueOf(47))).isEqualByComparingTo("48");
        assertThat(Reconciliation.clampAiCheckHours(BigDecimal.valueOf(48))).isEqualByComparingTo("48");
        assertThat(Reconciliation.clampAiCheckHours(BigDecimal.valueOf(100))).isEqualByComparingTo("100");
        assertThat(Reconciliation.clampAiCheckHours(BigDecimal.valueOf(720))).isEqualByComparingTo("720");
        assertThat(Reconciliation.clampAiCheckHours(BigDecimal.valueOf(999))).isEqualByComparingTo("720");
    }
}
