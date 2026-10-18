package com.jabiz.quizbuks.wallet;

import com.jabiz.quizbuks.country.CountryCatalog;
import com.jabiz.quizbuks.stats.AnalyticsLimits;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the daily money jobs daily and the analytics every ten minutes in production
 * (docs/quizbuks/plans/Q9c-admin-finance.md, review M1; Q9d; Q9e2 review M1), at startup and in {@code platformCheck}
 * (category {@code QUIZBUKS}), as {@link StripeModeCheck} keeps the development credit out of it. Three schedules are
 * for the end-to-end tests only:
 * <ul>
 *   <li>{@code quizbuks.transfers.run-cron} ({@code QB_TRANSFER_RUN_CRON}): run every minute against Stripe, each run
 *       would charge its fee again, three failures in a row (meant as three days) would hold a taker within minutes,
 *       and Stripe would be called far more often;</li>
 *   <li>{@code quizbuks.reconcile.run-cron} ({@code QB_RECONCILE_RUN_CRON}, Q9d): the journal export waits for a clean
 *       reconciliation after the month's end; run every minute, each difference would ask a person again every
 *       minute;</li>
 *   <li>{@code quizbuks.analytics.run-cron} ({@code QB_ANALYTICS_RUN_CRON}, Q9e2): the analytics pages show a test's
 *       attempts within it. It is a privacy control (a publication's figures are the job's snapshot: the cadence decides
 *       how finely one taker's attempt can be timed), so another schedule also needs the end-to-end switch
 *       {@value #E2E_SETTING} ({@code QB_E2E_ENABLED}), and none may run more than once a minute.</li>
 * </ul>
 * Any other schedule than the usual one (daily, or every ten minutes for the analytics) is refused next to live mode
 * or a live key. So are the end-to-end switch and the end-to-end tests' own roles ({@value #E2E_ROLE_PREFIX}*, such
 * as {@code QB_E2E_FINANCE} without a second factor) among the roles the administration's entry accepts
 * ({@code QB_ADMIN_ROLES}; Q9d review L2).
 */
@Component
class TransferScheduleCheck implements PlatformCheck {

    /** The prefix of the roles only the end-to-end tests create. */
    static final String E2E_ROLE_PREFIX = "QB_E2E_";
    static final String ADMIN_ROLES_SETTING = "jabiz.security.entries.admin.accepted-roles";
    /** The end-to-end tests' switch ({@code QB_E2E_ENABLED}): off everywhere else, refused next to live mode. */
    static final String E2E_SETTING = "quizbuks.e2e.enabled";

    private final String cron;
    private final List<String> adminRoles;
    private final String reconcileCron;
    private final String analyticsCron;
    private final boolean e2e;
    private final boolean livemode;
    private final String apiKey;

    TransferScheduleCheck(StripeSettings settings,
        @Value("${" + TransferProcesses.RUN_CRON_SETTING + ":" + TransferProcesses.RUN_CRON + "}") String cron,
        @Value("${" + Reconciliation.RUN_CRON_SETTING + ":" + Reconciliation.RUN_CRON + "}") String reconcileCron,
        @Value("${" + AnalyticsLimits.RUN_CRON_SETTING + ":" + AnalyticsLimits.RUN_CRON + "}") String analyticsCron,
        @Value("${" + E2E_SETTING + ":false}") boolean e2e,
        @Value("${" + StripeService.API_KEY + ":}") String apiKey, Environment environment) {
        this.cron = cron;
        this.adminRoles = Binder.get(environment).bind(ADMIN_ROLES_SETTING, Bindable.listOf(String.class))
            .orElse(List.of());
        this.reconcileCron = reconcileCron;
        this.analyticsCron = analyticsCron;
        this.e2e = e2e;
        this.livemode = settings.livemode();
        this.apiKey = apiKey;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>(problems(cron, livemode, apiKey));
        problems.addAll(reconcileProblems(reconcileCron, livemode, apiKey));
        problems.addAll(analyticsProblems(analyticsCron, e2e, livemode, apiKey));
        problems.addAll(e2eSwitchProblems(e2e, livemode, apiKey));
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

    /**
     * The analytics' schedule is a privacy control (Q9e2 review M1): a publication's figures are the job's snapshot,
     * so the cadence decides how finely a sponsor can time one taker's attempt. Any schedule but the default needs the
     * end-to-end switch ({@value #E2E_SETTING}), which live mode refuses; none may run more than once a minute.
     */
    static List<CheckProblem> analyticsProblems(String cron, boolean e2e, boolean livemode, String apiKey) {
        String given = cron == null ? "" : cron.strip();
        if (AnalyticsLimits.RUN_CRON.equals(given)) {
            return List.of();
        }
        List<CheckProblem> problems = new ArrayList<>();
        Duration shortest = shortestInterval(given);
        if (shortest == null) {
            problems.add(CheckProblem.error(CountryCatalog.CATEGORY, AnalyticsLimits.RUN_CRON_SETTING,
                "The sponsors' analytics have no valid schedule (" + cron + ", QB_ANALYTICS_RUN_CRON)"));
        } else if (shortest.compareTo(Duration.ofMinutes(1)) < 0) {
            problems.add(CheckProblem.error(CountryCatalog.CATEGORY, AnalyticsLimits.RUN_CRON_SETTING,
                "The sponsors' analytics would run more than once a minute (" + cron + ", QB_ANALYTICS_RUN_CRON): "
                    + "their snapshots would time a taker's attempt; at most once a minute, and only in the "
                    + "end-to-end tests"));
        }
        if (!e2e) {
            problems.add(CheckProblem.error(CountryCatalog.CATEGORY, AnalyticsLimits.RUN_CRON_SETTING,
                "The sponsors' analytics run on another schedule (" + cron + ", QB_ANALYTICS_RUN_CRON) without "
                    + E2E_SETTING + " (QB_E2E_ENABLED): every ten minutes is a privacy control, only the "
                    + "end-to-end tests change it"));
        }
        problems.addAll(problems(AnalyticsLimits.RUN_CRON_SETTING, "QB_ANALYTICS_RUN_CRON", "sponsors' analytics",
            AnalyticsLimits.RUN_CRON, cron, livemode, apiKey));
        return problems;
    }

    /** The end-to-end switch never meets live mode or a live key. */
    static List<CheckProblem> e2eSwitchProblems(boolean e2e, boolean livemode, String apiKey) {
        if (!e2e || !live(livemode, apiKey)) {
            return List.of();
        }
        return List.of(CheckProblem.error(CountryCatalog.CATEGORY, E2E_SETTING,
            "The end-to-end tests' switch (" + E2E_SETTING + ", QB_E2E_ENABLED) is on next to Stripe's live mode or "
                + "a live key: it is for the tests only; remove it in production"));
    }

    /**
     * The shortest time between two runs of a six-field cron over two days from a fixed instant (every pattern
     * repeats within them but day-of-month and month ones, which are never shorter than a day); null if invalid.
     */
    static Duration shortestInterval(String cron) {
        CronExpression expression;
        try {
            expression = CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            return null;
        }
        ZonedDateTime start = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime end = start.plusDays(2);
        ZonedDateTime previous = expression.next(start);
        Duration shortest = Duration.ofDays(366);
        for (int i = 0; previous != null && previous.isBefore(end) && i < 5_000; i++) {
            ZonedDateTime next = expression.next(previous);
            if (next == null) {
                break;
            }
            Duration between = Duration.between(previous, next);
            if (between.compareTo(shortest) < 0) {
                shortest = between;
            }
            previous = next;
        }
        return shortest;
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
