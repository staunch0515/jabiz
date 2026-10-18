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
    void theAnalyticsRunEveryTenMinutesInLiveMode() {
        assertThat(TransferScheduleCheck.analyticsProblems(AnalyticsLimits.RUN_CRON, true, "sk_live_abc")).isEmpty();
        assertThat(TransferScheduleCheck.analyticsProblems(EVERY_MINUTE, false, "sk_test_abc")).isEmpty();
        assertThat(TransferScheduleCheck.analyticsProblems(EVERY_MINUTE, true, null)).singleElement()
            .satisfies(p -> assertThat(p.toString()).contains(AnalyticsLimits.RUN_CRON_SETTING));
        assertThat(TransferScheduleCheck.analyticsProblems(EVERY_MINUTE, false, "sk_live_abc")).hasSize(1);
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
