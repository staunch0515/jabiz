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
    void theSwitchesThatMustBeOffAreTheRegistrysFalseRowsAndTheWaitingOnesWhoseKeyExists() {
        assertThat(FirstLaunchSwitchesCheck.MUST_BE_OFF).containsExactlyInAnyOrder(QbParams.KUDOS_BUY_ENABLED,
            QbParams.KUDOS_SELL_ENABLED, QbParams.TRANSFER_ENABLED, QbParams.WITHDRAWAL_BANK_ENABLED,
            QbParams.REWARD_KUDOS_ENABLED, QbParams.ENTRY_FEE_ENABLED);
        // A waiting row whose key does not exist yet never counts: there is nothing to turn on.
        FirstLaunchSwitches.ALL.stream().filter(row -> row.status() == FirstLaunchSwitches.Status.WAITING)
            .filter(row -> !FirstLaunchSwitches.KEY_BEFORE_MEANING.contains(row.key()))
            .forEach(row -> assertThat(FirstLaunchSwitchesCheck.MUST_BE_OFF).doesNotContain(row.key()));
    }

    @Test
    void aSwitchThatWaitedForItsFeatureNoLongerWarnsWhenOn() {
        // F-M7: the audience fee's switch is no longer waiting (on at the launch); F-M9a-2b: nor the AI fee's and the
        // website draft's.
        assertThat(FirstLaunchSwitchesCheck.problems(Map.of(QbParams.AUDIENCE_BILLING_ENABLED, "true",
            QbParams.SITE_PROFILE_ENABLED, "true", QbParams.AI_FEE_ENABLED, "true",
            QbParams.REWARD_KUDOS_ENABLED, "false"))).isEmpty();
    }

    /** F-M9a-2b (plan question 6): the website draft on while the AI fee is off warns (every draft is refused). */
    @Test
    void theWebsiteDraftOnWithTheAiFeeOffWarns() {
        assertThat(FirstLaunchSwitchesCheck.aiFeeProblems(Map.of(QbParams.SITE_PROFILE_ENABLED, "true",
            QbParams.AI_FEE_ENABLED, "false"))).singleElement().satisfies(problem -> {
                assertThat(problem.location()).isEqualTo("parameter " + QbParams.SITE_PROFILE_ENABLED);
                assertThat(problem.message()).contains(QbParams.AI_FEE_ENABLED).contains("refused");
            });
        assertThat(FirstLaunchSwitchesCheck.aiFeeProblems(Map.of(QbParams.SITE_PROFILE_ENABLED, "true")))
            .hasSize(1);
        // Both off, or the fee on alone: nothing.
        assertThat(FirstLaunchSwitchesCheck.aiFeeProblems(Map.of())).isEmpty();
        assertThat(FirstLaunchSwitchesCheck.aiFeeProblems(Map.of(QbParams.AI_FEE_ENABLED, "true"))).isEmpty();
    }

    /**
     * F-M9a-2b (#692 re-review L-b): with both on, each of the website draft's reservation limits not set or not usable
     * warns; usable ones do not.
     */
    @Test
    void theWebsiteDraftsReservationLimitsNotSetOrNotUsableWarn() {
        Map<String, String> on = Map.of(QbParams.SITE_PROFILE_ENABLED, "true", QbParams.AI_FEE_ENABLED, "true");
        assertThat(FirstLaunchSwitchesCheck.aiFeeProblems(on)).extracting(CheckProblem::location).containsExactly(
            "parameter " + QbParams.AI_FEE_SITE_CALLS, "parameter " + QbParams.AI_FEE_SITE_PAGES,
            "parameter " + QbParams.AI_FEE_SITE_OUTPUT);
        Map<String, String> set = new java.util.HashMap<>(on);
        set.put(QbParams.AI_FEE_SITE_CALLS, "5");
        set.put(QbParams.AI_FEE_SITE_PAGES, "0");
        set.put(QbParams.AI_FEE_SITE_OUTPUT, "2.5");
        assertThat(FirstLaunchSwitchesCheck.aiFeeProblems(set)).hasSize(3).allSatisfy(problem ->
            assertThat(problem.message()).contains("ignored"));
        set.put(QbParams.AI_FEE_SITE_CALLS, "3");
        set.put(QbParams.AI_FEE_SITE_PAGES, "8");
        set.put(QbParams.AI_FEE_SITE_OUTPUT, "4000");
        assertThat(FirstLaunchSwitchesCheck.aiFeeProblems(set)).isEmpty();
    }

    @Test
    void theExposureSwitchIsAnOrdinaryActiveOneNow() {
        // F-M8c: the settlements are there, so the switch is on at the launch and does not warn when it is on.
        assertThat(FirstLaunchSwitchesCheck.problems(Map.of(QbParams.EXPOSURE_BILLING_ENABLED, "true",
            QbParams.REWARD_KUDOS_ENABLED, "false"))).isEmpty();
        assertThat(FirstLaunchSwitches.ALL).anySatisfy(row -> {
            assertThat(row.key()).isEqualTo(QbParams.EXPOSURE_BILLING_ENABLED);
            assertThat(row.status()).isEqualTo(FirstLaunchSwitches.Status.ACTIVE);
        });
    }
}
