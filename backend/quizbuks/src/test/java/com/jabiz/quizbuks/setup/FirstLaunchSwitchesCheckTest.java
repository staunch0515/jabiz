package com.jabiz.quizbuks.setup;

import com.jabiz.runtime.check.CheckProblem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** F-F10a: the production warnings of the first launch's switches. */
class FirstLaunchSwitchesCheckTest {

    @Test
    void eachSwitchThatIsOnWarnsOnce() {
        List<CheckProblem> problems = FirstLaunchSwitchesCheck.problems(Map.of(
            QbParams.KUDOS_BUY_ENABLED, "true", QbParams.KUDOS_SELL_ENABLED, "false",
            QbParams.TRANSFER_ENABLED, "true", QbParams.WITHDRAWAL_BANK_ENABLED, "false",
            QbParams.REWARD_KUDOS_ENABLED, "true"));
        assertThat(problems).hasSize(3).allSatisfy(problem -> assertThat(problem.severity().name()).isEqualTo("WARNING"));
        assertThat(problems).extracting(CheckProblem::location).containsExactlyInAnyOrder(
            "parameter " + QbParams.KUDOS_BUY_ENABLED, "parameter " + QbParams.TRANSFER_ENABLED,
            "parameter " + QbParams.REWARD_KUDOS_ENABLED);
    }

    @Test
    void anEntryFeeSwitchThatIsOnWarns() {
        assertThat(FirstLaunchSwitchesCheck.problems(Map.of(QbParams.KUDOS_BUY_ENABLED, "false",
            QbParams.KUDOS_SELL_ENABLED, "false", QbParams.TRANSFER_ENABLED, "false",
            QbParams.WITHDRAWAL_BANK_ENABLED, "false", QbParams.REWARD_KUDOS_ENABLED, "false",
            QbParams.ENTRY_FEE_ENABLED, "true"))).singleElement()
            .satisfies(problem -> assertThat(problem.location()).isEqualTo("parameter " + QbParams.ENTRY_FEE_ENABLED));
    }

    @Test
    void allOffIsQuiet() {
        assertThat(FirstLaunchSwitchesCheck.problems(Map.of(QbParams.KUDOS_BUY_ENABLED, "false",
            QbParams.KUDOS_SELL_ENABLED, "false", QbParams.TRANSFER_ENABLED, "false",
            QbParams.WITHDRAWAL_BANK_ENABLED, "false", QbParams.REWARD_KUDOS_ENABLED, "false",
            QbParams.ENTRY_FEE_ENABLED, "false"))).isEmpty();
    }

    @Test
    void theKudosRewardSwitchMissingWarnsButOtherMissingOnesLeaveToTheSetupCheck() {
        List<CheckProblem> problems = FirstLaunchSwitchesCheck.problems(Map.of());
        assertThat(problems).hasSize(1);
        assertThat(problems.getFirst().location()).isEqualTo("parameter " + QbParams.REWARD_KUDOS_ENABLED);
        assertThat(problems.getFirst().message()).contains("no value");
    }

    @Test
    void liveModeOnASystemThatIsNotProductionWarns() {
        assertThat(FirstLaunchSwitchesCheck.liveModeProblems("test", false, true)).hasSize(1);
        assertThat(FirstLaunchSwitchesCheck.liveModeProblems("production", true, true)).isEmpty();
        assertThat(FirstLaunchSwitchesCheck.liveModeProblems("test", false, false)).isEmpty();
    }

    @Test
    void theLiveModeWarningSaysWhichValuesTheSystemProposes() {
        // Staging proposes the first launch's values, any other test system the declared ones.
        assertThat(FirstLaunchSwitchesCheck.liveModeProblems("staging", false, true)).singleElement()
            .satisfies(problem -> assertThat(problem.message()).contains("first launch's values here")
                .doesNotContain("declared values"));
        assertThat(FirstLaunchSwitchesCheck.liveModeProblems("test", false, true)).singleElement()
            .satisfies(problem -> assertThat(problem.message()).contains("declared values")
                .doesNotContain("first launch's values here"));
    }

    @Test
    void theSwitchesThatMustBeOffAreTheRegistrysActiveRowsWithAFalseValue() {
        assertThat(FirstLaunchSwitchesCheck.MUST_BE_OFF).containsExactlyInAnyOrder(QbParams.KUDOS_BUY_ENABLED,
            QbParams.KUDOS_SELL_ENABLED, QbParams.TRANSFER_ENABLED, QbParams.WITHDRAWAL_BANK_ENABLED,
            QbParams.REWARD_KUDOS_ENABLED, QbParams.ENTRY_FEE_ENABLED);
        // A waiting row (the intent is true or false) never counts: its feature does not exist yet.
        FirstLaunchSwitches.ALL.stream().filter(row -> row.status() == FirstLaunchSwitches.Status.WAITING)
            .forEach(row -> assertThat(FirstLaunchSwitchesCheck.MUST_BE_OFF).doesNotContain(row.key()));
    }
}
