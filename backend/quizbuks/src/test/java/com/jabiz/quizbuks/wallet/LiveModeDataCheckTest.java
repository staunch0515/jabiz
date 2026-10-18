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

    /** Q7b review L4: failing to read the legal texts says so, not that the top-ups could not be checked. */
    @Test
    void theLegalTextsAreCheckedOnTheirOwn() {
        assertThat(LiveModeDataCheck.legalCheck(() -> java.util.Set.of("TERMS_APP", "TERMS_SPONSOR", "PRIVACY")))
            .isEmpty();
        assertThat(LiveModeDataCheck.legalCheck(() -> java.util.Set.of("PRIVACY")))
            .extracting(CheckProblem::toString).hasSize(2)
            .allSatisfy(text -> assertThat(text).contains("Live mode without a version"));
        assertThat(LiveModeDataCheck.legalCheck(() -> {
            throw new java.sql.SQLException("relation does not exist");
        })).singleElement().satisfies(p -> {
            assertThat(p.severity()).isEqualTo(CheckProblem.Severity.WARNING);
            assertThat(p.toString()).contains("cannot check which legal texts are in effect")
                .contains("relation does not exist").doesNotContain("top-ups");
        });
    }

    @Test
    void aFreshDatabaseOrTestModeIsFine() {
        assertThat(LiveModeDataCheck.problems(true, false, NONE)).isEmpty();
        assertThat(LiveModeDataCheck.problems(false, false, new LiveModeDataCheck.TestData(3, BigDecimal.TEN)))
            .isEmpty();
    }
}
