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
    void allOffIsQuiet() {
        assertThat(FirstLaunchSwitchesCheck.problems(Map.of(QbParams.KUDOS_BUY_ENABLED, "false",
            QbParams.KUDOS_SELL_ENABLED, "false", QbParams.TRANSFER_ENABLED, "false",
            QbParams.WITHDRAWAL_BANK_ENABLED, "false", QbParams.REWARD_KUDOS_ENABLED, "false"))).isEmpty();
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
        assertThat(FirstLaunchSwitchesCheck.liveModeProblems("test", true)).hasSize(1);
        assertThat(FirstLaunchSwitchesCheck.liveModeProblems("staging", true)).hasSize(1);
        assertThat(FirstLaunchSwitchesCheck.liveModeProblems("production", true)).isEmpty();
        assertThat(FirstLaunchSwitchesCheck.liveModeProblems("test", false)).isEmpty();
    }
}
