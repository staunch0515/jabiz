package com.jabiz.quizbuks.wallet;

import com.jabiz.quizbuks.country.CountryCatalog;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Keeps Stripe's two modes and the development credit apart (docs/quizbuks/plans/Q6-payments.md, D-Q6-1, D-Q6-9), at
 * startup and in {@code platformCheck} (category {@code QUIZBUKS}):
 * <ul>
 *   <li>the API key's mode must be the configured one ({@code quizbuks.stripe.livemode}): {@code sk_test_} /
 *       {@code rk_test_} in test mode, {@code sk_live_} / {@code rk_live_} in live mode. A missing key is the platform's
 *       check {@code EXTERNAL}'s to report;</li>
 *   <li>the development credit ({@code QB_WALLET_DEV_CREDIT}, wallets credited without money) is refused next to live
 *       mode or a live key: in production it would create money;</li>
 *   <li>the settings themselves ({@link StripeSettings#problems()}); a missing sponsor console address only warns, since
 *       top-ups then refuse to start ({@code QB_TOPUP_UNAVAILABLE}).</li>
 * </ul>
 * Events of the other mode are refused by the consumers ({@link TopUpChecks}).
 */
@Component
class StripeModeCheck implements PlatformCheck {

    static final Pattern LIVE_KEY = Pattern.compile("(sk|rk)_live_");
    static final Pattern TEST_KEY = Pattern.compile("(sk|rk)_test_");

    private final StripeSettings settings;
    private final boolean devCredit;
    private final String apiKey;

    private final PayoutSettings payouts;

    StripeModeCheck(StripeSettings settings, PayoutSettings payouts,
        @Value("${" + DevCredit.ENABLED + ":false}") boolean devCredit,
        @Value("${" + StripeService.API_KEY + ":}") String apiKey) {
        this.settings = settings;
        this.payouts = payouts;
        this.devCredit = devCredit;
        this.apiKey = apiKey;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>(problems(settings, devCredit, apiKey));
        problems.addAll(payoutProblems(payouts, settings.livemode()));
        return List.copyOf(problems);
    }

    /**
     * The settings of payout accounts (Q6b); a missing app address only warns, since onboarding then refuses
     * ({@code QB_PAYOUT_UNAVAILABLE}).
     */
    static List<CheckProblem> payoutProblems(PayoutSettings payouts, boolean livemode) {
        List<CheckProblem> problems = new ArrayList<>();
        for (String problem : payouts.problems(livemode)) {
            int colon = problem.indexOf(':');
            problems.add(CheckProblem.error(CountryCatalog.CATEGORY, problem.substring(0, colon),
                problem.substring(colon + 2)));
        }
        if (payouts.appLink().isEmpty()) {
            problems.add(CheckProblem.warning(CountryCatalog.CATEGORY, "quizbuks.links.app", "No address of the app "
                + "(quizbuks.links.app): payout accounts are refused, since Stripe has nowhere to send the taker back"));
        }
        return List.copyOf(problems);
    }

    static List<CheckProblem> problems(StripeSettings settings, boolean devCredit, String apiKey) {
        List<CheckProblem> problems = new ArrayList<>();
        String category = CountryCatalog.CATEGORY;
        boolean hasKey = apiKey != null && !apiKey.isBlank();
        boolean liveKey = hasKey && LIVE_KEY.matcher(apiKey).lookingAt();
        boolean testKey = hasKey && TEST_KEY.matcher(apiKey).lookingAt();
        if (hasKey && settings.livemode() && !liveKey) {
            problems.add(CheckProblem.error(category, StripeService.API_KEY, "Live mode "
                + "(quizbuks.stripe.livemode=true) needs a live key (sk_live_ or rk_live_)"));
        }
        if (hasKey && !settings.livemode() && !testKey) {
            problems.add(CheckProblem.error(category, StripeService.API_KEY, "Test mode "
                + "(quizbuks.stripe.livemode=false) needs a test key (sk_test_ or rk_test_)"));
        }
        if (devCredit && (settings.livemode() || liveKey)) {
            problems.add(CheckProblem.error(category, DevCredit.ENABLED, "The development credit "
                + "(QB_WALLET_DEV_CREDIT) is on next to Stripe's live mode or a live key: turn it off in production"));
        }
        for (String problem : settings.problems()) {
            int colon = problem.indexOf(':');
            problems.add(CheckProblem.error(category, problem.substring(0, colon), problem.substring(colon + 2)));
        }
        if (settings.sponsorLink().isEmpty()) {
            problems.add(CheckProblem.warning(category, "quizbuks.links.sponsor", "No address of the sponsor console "
                + "(quizbuks.links.sponsor or jabiz.mail.base-url): top-ups are refused, since Stripe has nowhere to "
                + "send the sponsor back"));
        }
        return List.copyOf(problems);
    }
}
