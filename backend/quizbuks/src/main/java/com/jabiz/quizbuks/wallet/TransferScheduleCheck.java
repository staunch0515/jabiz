package com.jabiz.quizbuks.wallet;

import com.jabiz.quizbuks.country.CountryCatalog;
import com.jabiz.quizbuks.stats.AnalyticsLimits;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the daily money jobs daily in production (docs/quizbuks/plans/Q9c-admin-finance.md, review M1; Q9d), at
 * startup and in {@code platformCheck} (category {@code QUIZBUKS}), as {@link StripeModeCheck} keeps the development
 * credit out of it. Two schedules are for the end-to-end tests only:
 * <ul>
 *   <li>{@code quizbuks.transfers.run-cron} ({@code QB_TRANSFER_RUN_CRON}): run every minute against Stripe, each run
 *       would charge its fee again, three failures in a row (meant as three days) would hold a taker within minutes,
 *       and Stripe would be called far more often;</li>
 *   <li>{@code quizbuks.reconcile.run-cron} ({@code QB_RECONCILE_RUN_CRON}, Q9d): the journal export waits for a clean
 *       reconciliation after the month's end; run every minute, each difference would ask a person again every
 *       minute;</li>
 *   <li>{@code quizbuks.analytics.run-cron} ({@code QB_ANALYTICS_RUN_CRON}, Q9e2): the analytics pages show a test's
 *       attempts within it; run every minute, the job's reads and audit rows would be ten times as many.</li>
 * </ul>
 * Any other schedule than the daily one is refused next to live mode or a live key. So are the end-to-end tests' own
 * roles ({@value #E2E_ROLE_PREFIX}*, such as {@code QB_E2E_FINANCE} without a second factor) among the roles the
 * administration's entry accepts ({@code QB_ADMIN_ROLES}; Q9d review L2).
 */
@Component
class TransferScheduleCheck implements PlatformCheck {

    /** The prefix of the roles only the end-to-end tests create. */
    static final String E2E_ROLE_PREFIX = "QB_E2E_";
    static final String ADMIN_ROLES_SETTING = "jabiz.security.entries.admin.accepted-roles";

    private final String cron;
    private final List<String> adminRoles;
    private final String reconcileCron;
    private final String analyticsCron;
    private final boolean livemode;
    private final String apiKey;

    TransferScheduleCheck(StripeSettings settings,
        @Value("${" + TransferProcesses.RUN_CRON_SETTING + ":" + TransferProcesses.RUN_CRON + "}") String cron,
        @Value("${" + Reconciliation.RUN_CRON_SETTING + ":" + Reconciliation.RUN_CRON + "}") String reconcileCron,
        @Value("${" + AnalyticsLimits.RUN_CRON_SETTING + ":" + AnalyticsLimits.RUN_CRON + "}") String analyticsCron,
        @Value("${" + StripeService.API_KEY + ":}") String apiKey, Environment environment) {
        this.cron = cron;
        this.adminRoles = Binder.get(environment).bind(ADMIN_ROLES_SETTING, Bindable.listOf(String.class))
            .orElse(List.of());
        this.reconcileCron = reconcileCron;
        this.analyticsCron = analyticsCron;
        this.livemode = settings.livemode();
        this.apiKey = apiKey;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>(problems(cron, livemode, apiKey));
        problems.addAll(reconcileProblems(reconcileCron, livemode, apiKey));
        problems.addAll(analyticsProblems(analyticsCron, livemode, apiKey));
        problems.addAll(e2eRoleProblems(adminRoles, livemode, apiKey));
        return problems;
    }

    static List<CheckProblem> e2eRoleProblems(List<String> adminRoles, boolean livemode, String apiKey) {
        if (!live(livemode, apiKey)) {
            return List.of();
        }
        List<String> e2e = adminRoles.stream().map(String::strip)
            .filter(role -> role.regionMatches(true, 0, E2E_ROLE_PREFIX, 0, E2E_ROLE_PREFIX.length())).toList();
        return e2e.isEmpty() ? List.of() : List.of(CheckProblem.error(CountryCatalog.CATEGORY, ADMIN_ROLES_SETTING,
            "The administration accepts the end-to-end tests' roles (" + String.join(", ", e2e) + ", QB_ADMIN_ROLES) "
                + "next to Stripe's live mode or a live key: they are for the tests only; remove them in production"));
    }

    private static boolean live(boolean livemode, String apiKey) {
        return livemode || apiKey != null && StripeModeCheck.LIVE_KEY.matcher(apiKey).lookingAt();
    }

    static List<CheckProblem> problems(String cron, boolean livemode, String apiKey) {
        return problems(TransferProcesses.RUN_CRON_SETTING, "QB_TRANSFER_RUN_CRON", "daily transfers",
            TransferProcesses.RUN_CRON, cron, livemode, apiKey);
    }

    static List<CheckProblem> reconcileProblems(String cron, boolean livemode, String apiKey) {
        return problems(Reconciliation.RUN_CRON_SETTING, "QB_RECONCILE_RUN_CRON", "daily reconciliation",
            Reconciliation.RUN_CRON, cron, livemode, apiKey);
    }

    static List<CheckProblem> analyticsProblems(String cron, boolean livemode, String apiKey) {
        return problems(AnalyticsLimits.RUN_CRON_SETTING, "QB_ANALYTICS_RUN_CRON", "sponsors' analytics",
            AnalyticsLimits.RUN_CRON, cron, livemode, apiKey);
    }

    private static List<CheckProblem> problems(String setting, String variable, String what, String daily,
        String cron, boolean livemode, String apiKey) {
        if (daily.equals(cron == null ? null : cron.strip()) || !live(livemode, apiKey)) {
            return List.of();
        }
        return List.of(CheckProblem.error(CountryCatalog.CATEGORY, setting,
            "The " + what + " run on another schedule (" + cron + ", " + variable + ") next to Stripe's live "
                + "mode or a live key: it is for the end-to-end tests only; remove it in production"));
    }
}
