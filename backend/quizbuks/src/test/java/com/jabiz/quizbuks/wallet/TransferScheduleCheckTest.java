package com.jabiz.quizbuks.wallet;

import com.jabiz.quizbuks.stats.AnalyticsLimits;
import com.jabiz.runtime.check.CheckProblem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The end-to-end tests' transfer schedule never meets live mode or a live key (Q9c review M1). */
class TransferScheduleCheckTest {

    static final String EVERY_MINUTE = "0 * * * * *";

    @Test
    void theDailyScheduleIsAlwaysAccepted() {
        assertThat(TransferScheduleCheck.problems(TransferProcesses.RUN_CRON, true, "sk_live_abc")).isEmpty();
        assertThat(TransferScheduleCheck.problems(" " + TransferProcesses.RUN_CRON + " ", true, "rk_live_x")).isEmpty();
    }

    @Test
    void anotherScheduleIsForTestModeOnly() {
        assertThat(TransferScheduleCheck.problems(EVERY_MINUTE, false, "sk_test_abc")).isEmpty();
        assertThat(TransferScheduleCheck.problems(EVERY_MINUTE, false, "")).isEmpty();
        assertThat(TransferScheduleCheck.problems(EVERY_MINUTE, true, "sk_live_abc")).singleElement()
            .satisfies(p -> {
                assertThat(p.severity()).isEqualTo(CheckProblem.Severity.ERROR);
                assertThat(p.toString()).contains(TransferProcesses.RUN_CRON_SETTING);
            });
        assertThat(TransferScheduleCheck.problems(EVERY_MINUTE, false, "rk_live_abc")).hasSize(1);
        assertThat(TransferScheduleCheck.problems(EVERY_MINUTE, true, null)).hasSize(1);
        assertThat(TransferScheduleCheck.problems("0 7 2 * * *", true, null)).hasSize(1);
    }

    @Test
    void theReconciliationIsDailyInLiveModeToo() {
        assertThat(TransferScheduleCheck.reconcileProblems(Reconciliation.RUN_CRON, true, "sk_live_abc")).isEmpty();
        assertThat(TransferScheduleCheck.reconcileProblems(EVERY_MINUTE, false, "sk_test_abc")).isEmpty();
        assertThat(TransferScheduleCheck.reconcileProblems(EVERY_MINUTE, true, null)).singleElement()
            .satisfies(p -> assertThat(p.toString()).contains(Reconciliation.RUN_CRON_SETTING));
        assertThat(TransferScheduleCheck.reconcileProblems(EVERY_MINUTE, false, "sk_live_abc")).hasSize(1);
    }

    @Test
    void theAnalyticsRunEveryTenMinutesUnlessTheEndToEndTestsSayOtherwise() {
        // The default is always accepted, with or without the switch, in live mode too.
        assertThat(TransferScheduleCheck.analyticsProblems(AnalyticsLimits.RUN_CRON, false, true, "sk_live_abc"))
            .isEmpty();
        assertThat(TransferScheduleCheck.analyticsProblems(" " + AnalyticsLimits.RUN_CRON, true, false, null)).isEmpty();
        // Every minute: only with the end-to-end switch, and never in live mode.
        assertThat(TransferScheduleCheck.analyticsProblems(EVERY_MINUTE, true, false, "sk_test_abc")).isEmpty();
        assertThat(TransferScheduleCheck.analyticsProblems("15 * * * * *", true, false, "sk_test_abc")).isEmpty();
        assertThat(TransferScheduleCheck.analyticsProblems(EVERY_MINUTE, false, false, "sk_test_abc")).singleElement()
            .satisfies(p -> assertThat(p.toString()).contains(AnalyticsLimits.RUN_CRON_SETTING)
                .contains(TransferScheduleCheck.E2E_SETTING));
        assertThat(TransferScheduleCheck.analyticsProblems(EVERY_MINUTE, true, true, null)).hasSize(1);
        assertThat(TransferScheduleCheck.analyticsProblems(EVERY_MINUTE, true, false, "sk_live_abc")).hasSize(1);
        // A slower schedule is a change too.
        assertThat(TransferScheduleCheck.analyticsProblems("0 0 * * * *", false, false, null)).hasSize(1);
    }

    @Test
    void theAnalyticsNeverRunMoreThanOnceAMinute() {
        for (String often : List.of("* * * * * *", "*/30 * * * * *", "0,1 * * * * *", "10,40 * * * * *")) {
            assertThat(TransferScheduleCheck.analyticsProblems(often, true, false, "sk_test_abc")).as(often)
                .singleElement().satisfies(p -> assertThat(p.toString()).contains("more than once a minute"));
        }
        assertThat(TransferScheduleCheck.analyticsProblems("not a cron", true, false, null)).singleElement()
            .satisfies(p -> assertThat(p.toString()).contains("no valid schedule"));
        assertThat(TransferScheduleCheck.shortestInterval("15 * * * * *")).hasMinutes(1);
        assertThat(TransferScheduleCheck.shortestInterval(AnalyticsLimits.RUN_CRON)).hasMinutes(10);
        assertThat(TransferScheduleCheck.shortestInterval("0 59,0 * * * *")).hasMinutes(1);
    }

    @Test
    void theEndToEndSwitchIsRefusedInLiveMode() {
        assertThat(TransferScheduleCheck.e2eSwitchProblems(false, true, "sk_live_abc")).isEmpty();
        assertThat(TransferScheduleCheck.e2eSwitchProblems(true, false, "sk_test_abc")).isEmpty();
        assertThat(TransferScheduleCheck.e2eSwitchProblems(true, true, null)).singleElement()
            .satisfies(p -> assertThat(p.toString()).contains(TransferScheduleCheck.E2E_SETTING));
        assertThat(TransferScheduleCheck.e2eSwitchProblems(true, false, "rk_live_abc")).hasSize(1);
    }

    @Test
    void theTestsRolesAreNotAdministratorsInLiveMode() {
        List<String> production = List.of("ADMIN", "QB_ADMIN_SUPER", "QB_ADMIN_CONTENT", "QB_ADMIN_FINANCE");
        List<String> e2e = List.of("ADMIN", "QB_ADMIN_FINANCE", " QB_E2E_PUBLISHER", "qb_e2e_finance");
        assertThat(TransferScheduleCheck.e2eRoleProblems(production, true, "sk_live_abc")).isEmpty();
        assertThat(TransferScheduleCheck.e2eRoleProblems(e2e, false, "sk_test_abc")).isEmpty();
        assertThat(TransferScheduleCheck.e2eRoleProblems(e2e, true, null)).singleElement()
            .satisfies(p -> assertThat(p.toString()).contains(TransferScheduleCheck.ADMIN_ROLES_SETTING)
                .contains("QB_E2E_PUBLISHER").contains("qb_e2e_finance"));
        assertThat(TransferScheduleCheck.e2eRoleProblems(e2e, false, "sk_live_abc")).hasSize(1);
    }
}
