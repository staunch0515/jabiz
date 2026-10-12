package com.jabiz.quizbuks.wallet;

import com.jabiz.external.ExternalService;

import java.time.Duration;

/**
 * Stripe as QuizBuks reaches it (docs/quizbuks/plans/Q6-payments.md, D-Q6-1): the external service {@value #SERVICE}
 * (the platform's JDK HTTP client only, platform decision D38: Stripe's SDK would open connections the host list does
 * not govern) and the inbound provider {@value #PROVIDER} (decision D37) with the event types QuizBuks consumes. Every
 * call to Stripe is an asynchronous external job: nothing waits for Stripe inside a transaction, and the retries and
 * idempotency keys are the platform's, kept in the database.
 *
 * <p>The API key comes from {@code STRIPE_API_KEY} only; a restricted key ({@code rk_test_…}) with only the
 * permissions the jobs need is recommended (deploy/quizbuks/README.md). Each environment may point the service
 * elsewhere ({@code jabiz.external-services.stripe.base-url | allowed-hosts}): development and the tests use a stub.
 */
public final class StripeService {

    public static final String SERVICE = "stripe";
    public static final String PROVIDER = "stripe";
    /** The environment variable of the API key. */
    public static final String API_KEY = "STRIPE_API_KEY";

    /** The checkout of a top-up was paid (cards pay at once: {@code payment_status = paid}). */
    public static final String CHECKOUT_COMPLETED = "checkout.session.completed";
    /** The checkout of a top-up expired unpaid. */
    public static final String CHECKOUT_EXPIRED = "checkout.session.expired";
    /** A charge was refunded, in part or in full ({@code amount_refunded} is the running total). */
    public static final String CHARGE_REFUNDED = "charge.refunded";
    public static final String DISPUTE_CREATED = "charge.dispute.created";
    public static final String DISPUTE_CLOSED = "charge.dispute.closed";

    /** The only currency QuizBuks charges in: Kudos are yen, which Stripe counts without a minor unit. */
    public static final String CURRENCY = "jpy";

    public static final ExternalService DEFINITION = ExternalService.define(SERVICE, s -> s
        .baseUrl("https://api.stripe.com").allowedHosts("api.stripe.com")
        .secret("apiKey", API_KEY)
        .connectTimeout(Duration.ofSeconds(5)).requestTimeout(Duration.ofSeconds(20))
        .idempotencyHeader("Idempotency-Key").maxResponseSize(256 * 1024));

    private StripeService() {}
}
