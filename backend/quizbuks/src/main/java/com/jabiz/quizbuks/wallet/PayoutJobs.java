package com.jabiz.quizbuks.wallet;

import com.jabiz.external.ExternalJobType;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * The asynchronous external jobs of payout accounts and transfers (docs/quizbuks/plans/Q6-payments.md, D-Q6-10,
 * D-Q6-12; platform decision D38), as {@link TopUpJobs}: registered in the transaction that needs them, run outside
 * any transaction with retries and stable idempotency keys, answered by {@code internal()} processes. Together with
 * the top-ups' they make at most a few calls at a time.
 */
public final class PayoutJobs {

    public static final String CONNECT_ONBOARD = "qb.stripe.connect-onboard";
    public static final String TRANSFER = "qb.stripe.transfer";
    public static final String TRANSFER_CHECK = "qb.stripe.transfer-check";

    /**
     * The input of {@value #CONNECT_ONBOARD}.
     *
     * @param accountId the user's Stripe account when they have one; null: one is created
     */
    public record OnboardInput(String userId, String country, String accountId, String returnUrl,
        String refreshUrl) {}

    /**
     * The input of {@value #TRANSFER} and {@value #TRANSFER_CHECK}: the transfer as recorded.
     *
     * @param destination the user's Stripe account
     * @param net         what the user receives
     */
    public record TransferInput(String transferId, String destination, BigDecimal net) {}

    /**
     * Creates the user's Express account (once: the key is the user's) and a link to Stripe's onboarding, for the user
     * (the requester: one at a time).
     */
    public static final ExternalJobType<OnboardInput, PayoutObjects.Onboarding> ONBOARD_JOB = ExternalJobType.define(
        CONNECT_ONBOARD, OnboardInput.class, PayoutObjects.Onboarding.class, t -> t
            .handler(ConnectOnboardHandler.class).services(StripeService.SERVICE)
            .timeout(Duration.ofSeconds(40)).retry(4, Duration.ofSeconds(10), Duration.ofMinutes(2))
            .maxConcurrency(1).maxOpenPerRequester(1).oneOpenPerReference()
            .onCompleted(PayoutProcesses.ONBOARD_READY).onFailed(PayoutProcesses.ONBOARD_FAILED));

    /**
     * Sends a transfer (the system's). An insufficient balance is retried for hours (the platform's balance becomes
     * available days after the top-ups); two at a time.
     */
    public static final ExternalJobType<TransferInput, PayoutObjects.TransferLookup> TRANSFER_JOB =
        ExternalJobType.define(TRANSFER, TransferInput.class, PayoutObjects.TransferLookup.class, t -> t
            .handler(StripeTransferHandler.class).services(StripeService.SERVICE)
            .timeout(Duration.ofSeconds(40)).retry(8, Duration.ofMinutes(2), Duration.ofHours(6))
            .maxConcurrency(2).maxOpenPerRequester(ExternalJobType.MAX_OPEN_PER_REQUESTER).oneOpenPerReference()
            .onCompleted(TransferProcesses.SENT).onFailed(TransferProcesses.SEND_FAILED));

    /** Asks Stripe whether a transfer in doubt was made (lists the transfer's group only). */
    public static final ExternalJobType<TransferInput, PayoutObjects.TransferLookup> TRANSFER_CHECK_JOB =
        ExternalJobType.define(TRANSFER_CHECK, TransferInput.class, PayoutObjects.TransferLookup.class, t -> t
            .handler(TransferCheckHandler.class).services(StripeService.SERVICE)
            .timeout(Duration.ofSeconds(30)).retry(5, Duration.ofSeconds(30), Duration.ofMinutes(10))
            .maxConcurrency(1).maxOpenPerRequester(ExternalJobType.MAX_OPEN_PER_REQUESTER).oneOpenPerReference()
            .onCompleted(TransferProcesses.CHECKED).onFailed(TransferProcesses.CHECK_FAILED));

    private PayoutJobs() {}
}
