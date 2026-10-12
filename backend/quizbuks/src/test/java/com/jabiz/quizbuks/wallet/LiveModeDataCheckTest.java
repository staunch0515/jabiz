package com.jabiz.quizbuks.wallet;

import com.jabiz.runtime.check.CheckProblem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class LiveModeDataCheckTest {

    static final LiveModeDataCheck.TestData NONE = new LiveModeDataCheck.TestData(0, BigDecimal.ZERO);

    @Test
    void liveModeRefusesTestModesMoneyUnlessAllowedOnPurpose() {
        LiveModeDataCheck.TestData topUps = new LiveModeDataCheck.TestData(3, BigDecimal.ZERO);
        LiveModeDataCheck.TestData wallets = new LiveModeDataCheck.TestData(0, new BigDecimal("500"));
        assertThat(LiveModeDataCheck.problems(true, false, topUps)).singleElement().satisfies(p -> {
            assertThat(p.severity()).isEqualTo(CheckProblem.Severity.ERROR);
            assertThat(p.toString()).contains("3 test-mode top-up").contains(LiveModeDataCheck.ALLOW);
        });
        assertThat(LiveModeDataCheck.problems(true, false, wallets)).singleElement()
            .satisfies(p -> assertThat(p.toString()).contains("2110").contains("500"));
        assertThat(LiveModeDataCheck.problems(true, true, wallets)).singleElement()
            .satisfies(p -> assertThat(p.severity()).isEqualTo(CheckProblem.Severity.WARNING));
    }

    @Test
    void aFreshDatabaseOrTestModeIsFine() {
        assertThat(LiveModeDataCheck.problems(true, false, NONE)).isEmpty();
        assertThat(LiveModeDataCheck.problems(false, false, new LiveModeDataCheck.TestData(3, BigDecimal.TEN)))
            .isEmpty();
    }
}
