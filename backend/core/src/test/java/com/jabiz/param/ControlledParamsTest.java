package com.jabiz.param;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlledParamsTest {

    @Test
    void keysAreKeptInDeclarationOrderWithoutDuplicates() {
        ControlledParams params = ControlledParams.of("quiz.creator-fee-rate", "quiz.payout_min",
            "quiz.creator-fee-rate");
        assertThat(params.keys()).containsExactly("quiz.creator-fee-rate", "quiz.payout_min");
        assertThat(params.controls("quiz.payout_min")).isTrue();
        assertThat(params.controls("quiz.other")).isFalse();
        assertThat(params.controls(null)).isFalse();
    }

    @Test
    void wellFormedKeysHaveNoProblems() {
        assertThat(ControlledParams.of("logistics.fuel-surcharge-rate", "a", "a1.b2_c3-d4").problems()).isEmpty();
    }

    @Test
    void everyMalformedKeyIsReportedAtOnce() {
        // Not refused when declared: the startup check reports all of them together.
        List<String> problems = ControlledParams.of("ok.key", "Upper.case", " spaced", "trailing.", "a..b", "",
            "x".repeat(201)).problems();
        assertThat(problems).hasSize(6);
        assertThat(problems.getFirst()).contains("'Upper.case'");
    }

    @Test
    void theFormatIsTheOneOfDeclarations() {
        assertThat(ControlledParams.validKey("quiz.fee")).isTrue();
        assertThat(ControlledParams.validKey("x".repeat(200))).isTrue();
        assertThat(ControlledParams.validKey("9quiz")).isFalse();
        assertThat(ControlledParams.validKey("quiz fee")).isFalse();
        assertThat(ControlledParams.validKey(null)).isFalse();
    }

    @Test
    void aDeclarationNamesKeys() {
        assertThatThrownBy(ControlledParams::of).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ControlledParams.of("a", null)).isInstanceOf(NullPointerException.class);
    }
}
