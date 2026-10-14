package com.jabiz.quizbuks.wallet;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * QuizBuks's Stripe settings ({@code quizbuks.stripe.*}, docs/quizbuks/plans/Q6-payments.md, D-Q6-1): configuration,
 * not business parameters, since they describe the deployment (which Stripe mode, which API version) rather than a
 * business decision.
 *
 * @param livemode          whether this deployment takes real money ({@code quizbuks.stripe.livemode}, default false);
 *                          the API key, Stripe's events and the development credit must agree with it
 * @param apiVersion        the API version every request pins ({@code Stripe-Version}); the webhook endpoints in
 *                          Stripe's dashboard must use the same one, since an event's body follows the endpoint's
 * @param checkoutTtl       how long a checkout stays open: Stripe accepts 30 minutes to 24 hours from its creation,
 *                          and the job creating it may start a few minutes late, so {@link #MIN_TTL} to
 *                          {@link #MAX_TTL}
 * @param checkoutUrlPrefix what the address of a checkout must start with: Stripe's, or the local stub's in development
 *                          (one origin and a path ending in {@code /}, {@link StripeUrls#prefixProblem})
 * @param sponsorLink       the sponsor console's address ({@code quizbuks.links.sponsor}), where Stripe sends the
 *                          sponsor back; empty when not configured (top-ups are then refused)
 * @param settlementCurrency the currency of the Stripe account's balance, in which a payment's balance transaction
 *                          (its fee and net) comes ({@code quizbuks.stripe.settlement-currency}, default {@code jpy}).
 *                          Charges and refunds are yen whatever it is (D-Q6-5); only a yen settlement is checked
 *                          against the payment and posted, another one is a task for finance.
 */
public record StripeSettings(boolean livemode, String apiVersion, Duration checkoutTtl, String checkoutUrlPrefix,
    String sponsorLink, String settlementCurrency) {

    private static final Pattern CURRENCY = Pattern.compile("[a-z]{3}");

    /** The settings of a yen settlement. */
    public StripeSettings(boolean livemode, String apiVersion, Duration checkoutTtl, String checkoutUrlPrefix,
        String sponsorLink) {
        this(livemode, apiVersion, checkoutTtl, checkoutUrlPrefix, sponsorLink, StripeService.CURRENCY);
    }

    public static final String STRIPE_CHECKOUT = "https://checkout.stripe.com/";
    public static final Duration MIN_TTL = Duration.ofMinutes(40);
    public static final Duration MAX_TTL = Duration.ofHours(23);
    /** Stripe's versions: a date, and from 2024-09-30 a release name ({@code 2025-03-31.basil}). */
    private static final Pattern VERSION = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}(\\.[a-z]{1,20})?");

    public StripeSettings {
        apiVersion = apiVersion == null ? "" : apiVersion.strip();
        checkoutUrlPrefix = checkoutUrlPrefix == null || checkoutUrlPrefix.isBlank() ? STRIPE_CHECKOUT
            : checkoutUrlPrefix.strip();
        sponsorLink = sponsorLink == null ? "" : sponsorLink.strip();
        settlementCurrency = settlementCurrency == null || settlementCurrency.isBlank() ? StripeService.CURRENCY
            : settlementCurrency.strip().toLowerCase(java.util.Locale.ROOT);
    }

    /** What is wrong with the settings, as {@code "property: message"}. */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (!VERSION.matcher(apiVersion).matches()) {
            problems.add("quizbuks.stripe.api-version: '" + apiVersion + "' is not a Stripe API version");
        }
        if (checkoutTtl == null || checkoutTtl.compareTo(MIN_TTL) < 0 || checkoutTtl.compareTo(MAX_TTL) > 0) {
            problems.add("quizbuks.stripe.checkout-ttl: must be between " + MIN_TTL + " and " + MAX_TTL);
        }
        if (livemode && !STRIPE_CHECKOUT.equals(checkoutUrlPrefix)) {
            problems.add("quizbuks.stripe.checkout-url-prefix: live mode accepts Stripe's checkout ("
                + STRIPE_CHECKOUT + ") only");
        }
        String prefixProblem = StripeUrls.prefixProblem(checkoutUrlPrefix);
        if (prefixProblem != null) {
            problems.add("quizbuks.stripe.checkout-url-prefix: " + prefixProblem);
        }
        if (!CURRENCY.matcher(settlementCurrency).matches()) {
            problems.add("quizbuks.stripe.settlement-currency: '" + settlementCurrency
                + "' is not a currency code (such as jpy)");
        }
        return List.copyOf(problems);
    }
}
