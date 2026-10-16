package com.jabiz.quizbuks.wallet;

import com.jabiz.quizbuks.country.CountryCatalog;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.beans.factory.annotation.Value;
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
 *       minute.</li>
 * </ul>
 * Any other schedule than the daily one is refused next to live mode or a live key.
 */
@Component
class TransferScheduleCheck implements PlatformCheck {

    private final String cron;
    private final String reconcileCron;
    private final boolean livemode;
    private final String apiKey;

    TransferScheduleCheck(StripeSettings settings,
        @Value("${" + TransferProcesses.RUN_CRON_SETTING + ":" + TransferProcesses.RUN_CRON + "}") String cron,
        @Value("${" + Reconciliation.RUN_CRON_SETTING + ":" + Reconciliation.RUN_CRON + "}") String reconcileCron,
        @Value("${" + StripeService.API_KEY + ":}") String apiKey) {
        this.cron = cron;
        this.reconcileCron = reconcileCron;
        this.livemode = settings.livemode();
        this.apiKey = apiKey;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>(problems(cron, livemode, apiKey));
        problems.addAll(reconcileProblems(reconcileCron, livemode, apiKey));
        return problems;
    }

    static List<CheckProblem> problems(String cron, boolean livemode, String apiKey) {
        return problems(TransferProcesses.RUN_CRON_SETTING, "QB_TRANSFER_RUN_CRON", "daily transfers",
            TransferProcesses.RUN_CRON, cron, livemode, apiKey);
    }

    static List<CheckProblem> reconcileProblems(String cron, boolean livemode, String apiKey) {
        return problems(Reconciliation.RUN_CRON_SETTING, "QB_RECONCILE_RUN_CRON", "daily reconciliation",
            Reconciliation.RUN_CRON, cron, livemode, apiKey);
    }

    private static List<CheckProblem> problems(String setting, String variable, String what, String daily,
        String cron, boolean livemode, String apiKey) {
        boolean liveKey = apiKey != null && StripeModeCheck.LIVE_KEY.matcher(apiKey).lookingAt();
        if (daily.equals(cron == null ? null : cron.strip()) || !(livemode || liveKey)) {
            return List.of();
        }
        return List.of(CheckProblem.error(CountryCatalog.CATEGORY, setting,
            "The " + what + " run on another schedule (" + cron + ", " + variable + ") next to Stripe's live "
                + "mode or a live key: it is for the end-to-end tests only; remove it in production"));
    }
}
