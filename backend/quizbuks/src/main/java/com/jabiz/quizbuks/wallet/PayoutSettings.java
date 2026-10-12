package com.jabiz.quizbuks.wallet;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * QuizBuks's settings of payout accounts (docs/quizbuks/plans/Q6-payments.md, D-Q6-10): configuration, not business
 * parameters.
 *
 * @param connectCountry   the only country whose takers may open a payout account ({@code quizbuks.stripe.
 *                         connect-country}, default {@code JP}): transfers stay within the deployment's country (Q18)
 * @param appLink          the app's address ({@code quizbuks.links.app}), where Stripe sends the taker back after
 *                         onboarding; empty when not configured (onboarding is then refused)
 * @param connectUrlPrefix what an onboarding link must start with: Stripe's Connect host, or a local stub's in
 *                         development
 */
public record PayoutSettings(String connectCountry, String appLink, String connectUrlPrefix) {

    public static final String STRIPE_CONNECT = "https://connect.stripe.com/";
    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");

    public PayoutSettings {
        connectCountry = connectCountry == null ? "" : connectCountry.strip();
        appLink = appLink == null ? "" : appLink.strip();
        connectUrlPrefix = connectUrlPrefix == null || connectUrlPrefix.isBlank() ? STRIPE_CONNECT
            : connectUrlPrefix.strip();
    }

    /** Where Stripe sends the taker back: the app's payout page, done or to be refreshed (a link used once). */
    public String returnUrl(String outcome) {
        String base = appLink.endsWith("/") ? appLink : appLink + "/";
        return base + "wallet/payout?onboarding=" + outcome;
    }

    /** What is wrong with the settings, as {@code "property: message"}. */
    public List<String> problems(boolean livemode) {
        List<String> problems = new ArrayList<>();
        if (!COUNTRY.matcher(connectCountry).matches()) {
            problems.add("quizbuks.stripe.connect-country: '" + connectCountry + "' is not a country code");
        }
        if (livemode && !STRIPE_CONNECT.equals(connectUrlPrefix)) {
            problems.add("quizbuks.stripe.connect-url-prefix: live mode accepts Stripe's Connect (" + STRIPE_CONNECT
                + ") only");
        }
        if (!connectUrlPrefix.startsWith("https://") && !connectUrlPrefix.startsWith("http://localhost:")
            && !connectUrlPrefix.startsWith("http://127.0.0.1:")) {
            problems.add("quizbuks.stripe.connect-url-prefix: must be an https address (or a local stub's)");
        }
        if (!appLink.isEmpty() && !appLink.startsWith("https://") && !appLink.startsWith("http://localhost")
            && !appLink.startsWith("http://127.0.0.1")) {
            problems.add("quizbuks.links.app: must be an https address");
        }
        return List.copyOf(problems);
    }
}
