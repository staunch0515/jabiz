package com.jabiz.quizbuks.wallet;

import com.jabiz.quizbuks.country.CountryCatalog;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Keeps the daily transfers daily in production (docs/quizbuks/plans/Q9c-admin-finance.md, review M1), at startup and
 * in {@code platformCheck} (category {@code QUIZBUKS}), as {@link StripeModeCheck} keeps the development credit out of
 * it. {@code quizbuks.transfers.run-cron} ({@code QB_TRANSFER_RUN_CRON}) is for the end-to-end tests only: run every
 * minute against Stripe, each run would charge its fee again, three failures in a row (meant as three days) would
 * hold a taker within minutes, and Stripe would be called far more often. Any other schedule than the daily one is
 * refused next to live mode or a live key.
 */
@Component
class TransferScheduleCheck implements PlatformCheck {

    private final String cron;
    private final boolean livemode;
    private final String apiKey;

    TransferScheduleCheck(StripeSettings settings,
        @Value("${" + TransferProcesses.RUN_CRON_SETTING + ":" + TransferProcesses.RUN_CRON + "}") String cron,
        @Value("${" + StripeService.API_KEY + ":}") String apiKey) {
        this.cron = cron;
        this.livemode = settings.livemode();
        this.apiKey = apiKey;
    }

    @Override
    public List<CheckProblem> check() {
        return problems(cron, livemode, apiKey);
    }

    static List<CheckProblem> problems(String cron, boolean livemode, String apiKey) {
        boolean liveKey = apiKey != null && StripeModeCheck.LIVE_KEY.matcher(apiKey).lookingAt();
        if (TransferProcesses.RUN_CRON.equals(cron == null ? null : cron.strip()) || !(livemode || liveKey)) {
            return List.of();
        }
        return List.of(CheckProblem.error(CountryCatalog.CATEGORY, TransferProcesses.RUN_CRON_SETTING,
            "The daily transfers run on another schedule (" + cron + ", QB_TRANSFER_RUN_CRON) next to Stripe's live "
                + "mode or a live key: it is for the end-to-end tests only; remove it in production"));
    }
}
