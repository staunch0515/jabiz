package com.jabiz.quizbuks.wallet;

import com.jabiz.external.ExternalJobType;

import java.time.Duration;

/**
 * The asynchronous external jobs of top-ups (platform decision D38; docs/quizbuks/plans/Q6-payments.md, requirement 7):
 * every call to Stripe is one, registered in the transaction of the process that needs it, run by the platform outside
 * any transaction with retries and a stable idempotency key, and answered by an {@code internal()} process. Together
 * they make at most four calls at a time, below Stripe's rate limit.
 */
public final class TopUpJobs {

    public static final String CHECKOUT_CREATE = "qb.stripe.checkout-create";
    public static final String SESSION_CHECK = "qb.stripe.session-check";
    public static final String PAYMENT_FEE = "qb.stripe.payment-fee";

    /** The input of {@value #SESSION_CHECK}: the top-up and its checkout. */
    public record SessionCheckInput(String topUpId, String sessionId) {}

    /** The input of {@value #PAYMENT_FEE}: the top-up and its payment. */
    public record FeeInput(String topUpId, String paymentIntentId) {}

    /**
     * Creates a top-up's checkout, for the sponsor (the requester: at most three open at a time, one per top-up).
     * Quick retries: the checkout's expiry was fixed when the top-up started, and must still be half an hour away.
     */
    public static final ExternalJobType<CheckoutRequest.CheckoutInput, StripeObjects.CheckoutSession> CHECKOUT_JOB =
        ExternalJobType.define(CHECKOUT_CREATE, CheckoutRequest.CheckoutInput.class,
            StripeObjects.CheckoutSession.class, t -> t
                .handler(CheckoutCreateHandler.class).services(StripeService.SERVICE)
                .timeout(Duration.ofSeconds(30)).retry(4, Duration.ofSeconds(10), Duration.ofMinutes(2))
                .maxConcurrency(2).maxOpenPerRequester(WalletLimits.MAX_OPEN_TOPUPS).oneOpenPerReference()
                .onCompleted(TopUpProcesses.OPENED).onFailed(TopUpProcesses.OPEN_FAILED));

    /**
     * Asks Stripe about a top-up whose checkout expired without word from Stripe (the system's: platform gap 19 limits
     * its open jobs too, hence the highest limit).
     */
    public static final ExternalJobType<SessionCheckInput, StripeObjects.CheckoutSession> SESSION_CHECK_JOB =
        ExternalJobType.define(SESSION_CHECK, SessionCheckInput.class, StripeObjects.CheckoutSession.class, t -> t
            .handler(SessionCheckHandler.class).services(StripeService.SERVICE)
            .timeout(Duration.ofSeconds(30)).retry(5, Duration.ofSeconds(30), Duration.ofMinutes(10))
            .maxConcurrency(1).maxOpenPerRequester(ExternalJobType.MAX_OPEN_PER_REQUESTER).oneOpenPerReference()
            .onCompleted(TopUpProcesses.CHECKED).onFailed(TopUpProcesses.CHECK_FAILED));

    /**
     * Reads Stripe's fee of a paid top-up (F-04). The balance transaction may come a little after the payment, so the
     * retries reach over hours.
     */
    public static final ExternalJobType<FeeInput, TopUpChecks.Fee> FEE_JOB = ExternalJobType.define(PAYMENT_FEE,
        FeeInput.class, TopUpChecks.Fee.class, t -> t
            .handler(PaymentFeeHandler.class).services(StripeService.SERVICE)
            .timeout(Duration.ofSeconds(30)).retry(8, Duration.ofMinutes(1), Duration.ofHours(1))
            .maxConcurrency(1).maxOpenPerRequester(ExternalJobType.MAX_OPEN_PER_REQUESTER).oneOpenPerReference()
            .onCompleted(TopUpProcesses.FEE_RECORD).onFailed(TopUpProcesses.FEE_FAILED));

    private TopUpJobs() {}
}
