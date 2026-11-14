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
        // #702 review M1: the AI fee and the website draft are active rows that are off at the launch.
        assertThat(FirstLaunchSwitchesCheck.MUST_BE_OFF).containsExactlyInAnyOrder(QbParams.KUDOS_BUY_ENABLED,
            QbParams.KUDOS_SELL_ENABLED, QbParams.TRANSFER_ENABLED, QbParams.WITHDRAWAL_BANK_ENABLED,
            QbParams.REWARD_KUDOS_ENABLED, QbParams.ENTRY_FEE_ENABLED, QbParams.AI_FEE_ENABLED,
            QbParams.SITE_PROFILE_ENABLED, QbParams.FEE_AUTO_PAY_ENABLED);
        // A waiting row whose key does not exist yet never counts: there is nothing to turn on.
        FirstLaunchSwitches.ALL.stream().filter(row -> row.status() == FirstLaunchSwitches.Status.WAITING)
            .filter(row -> !FirstLaunchSwitches.KEY_BEFORE_MEANING.contains(row.key()))
            .forEach(row -> assertThat(FirstLaunchSwitchesCheck.MUST_BE_OFF).doesNotContain(row.key()));
    }

    @Test
    void theAiFeeAndTheWebsiteDraftOnAtTheLaunchWarnTheAudienceFeeDoesNot() {
        // F-M7: the audience fee's switch is on at the launch: it does not warn. #702 review M1: the AI fee's and the
        // website draft's are off at the launch, active rows (not waiting ones): on warns.
        assertThat(FirstLaunchSwitchesCheck.problems(Map.of(QbParams.AUDIENCE_BILLING_ENABLED, "true",
            QbParams.SITE_PROFILE_ENABLED, "true", QbParams.AI_FEE_ENABLED, "true",
            QbParams.REWARD_KUDOS_ENABLED, "false"))).extracting(CheckProblem::location)
            .containsExactlyInAnyOrder("parameter " + QbParams.SITE_PROFILE_ENABLED,
                "parameter " + QbParams.AI_FEE_ENABLED);
        assertThat(FirstLaunchSwitches.ALL).filteredOn(row -> row.group().equals("aifee")).hasSize(2)
            .allSatisfy(row -> {
                assertThat(row.status()).isEqualTo(FirstLaunchSwitches.Status.ACTIVE);
                assertThat(row.value()).isEqualTo(false);
                assertThat(row.why()).contains("reservation limits").contains("markup");
            });
    }

    /**
     * #702 review M2: production reads the money switches and the AI fee's warnings, staging the AI fee's only (the
     * limits are measured there), any other system nothing.
     */
    @Test
    void stagingGetsTheAiFeesWarningsOnly() {
        assertThat(FirstLaunchSwitchesCheck.reads("production", true)).isEqualTo(FirstLaunchSwitchesCheck.Reads.ALL);
        assertThat(FirstLaunchSwitchesCheck.reads("staging", false)).isEqualTo(FirstLaunchSwitchesCheck.Reads.AI_FEE);
        assertThat(FirstLaunchSwitchesCheck.reads(" Staging ", false))
            .isEqualTo(FirstLaunchSwitchesCheck.Reads.AI_FEE);
        assertThat(FirstLaunchSwitchesCheck.reads("test", false)).isEqualTo(FirstLaunchSwitchesCheck.Reads.NONE);
        assertThat(FirstLaunchSwitchesCheck.reads(null, false)).isEqualTo(FirstLaunchSwitchesCheck.Reads.NONE);
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
        // Both off: nothing.
        assertThat(FirstLaunchSwitchesCheck.aiFeeProblems(Map.of())).isEmpty();
        assertThat(FirstLaunchSwitchesCheck.aiFeeProblems(Map.of(QbParams.AI_FEE_ENABLED, "false"))).isEmpty();
    }

    /**
     * F-M9a-2b (#692 re-review L-b, #702 review M2): with the AI fee on, whether the website draft is on or not, each of
     * the website draft's reservation limits not set or not usable warns; usable ones do not.
     */
    @Test
    void theWebsiteDraftsReservationLimitsNotSetOrNotUsableWarnOnceTheAiFeeIsOn() {
        List<String> limits = List.of("parameter " + QbParams.AI_FEE_SITE_CALLS,
            "parameter " + QbParams.AI_FEE_SITE_PAGES, "parameter " + QbParams.AI_FEE_SITE_OUTPUT);
        assertThat(FirstLaunchSwitchesCheck.aiFeeProblems(Map.of(QbParams.AI_FEE_ENABLED, "true")))
            .extracting(CheckProblem::location).containsExactlyElementsOf(limits);
        Map<String, String> on = Map.of(QbParams.SITE_PROFILE_ENABLED, "true", QbParams.AI_FEE_ENABLED, "true");
        assertThat(FirstLaunchSwitchesCheck.aiFeeProblems(on)).extracting(CheckProblem::location)
            .containsExactlyElementsOf(limits);
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
        // The website draft off, the limits set: nothing.
        set.remove(QbParams.SITE_PROFILE_ENABLED);
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
