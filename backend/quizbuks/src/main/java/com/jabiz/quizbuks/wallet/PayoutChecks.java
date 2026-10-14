package com.jabiz.quizbuks.wallet;

import java.math.BigDecimal;
import java.util.List;

/**
 * Checks of what Stripe says about payout accounts, transfers and bank payouts (docs/quizbuks/plans/Q6-payments.md,
 * D-Q6-10 – D-Q6-14) against QuizBuks's records, without I/O. A signature makes an event Stripe's, not true for the
 * record: every reference, amount, currency and mode is compared, and what does not fit becomes a task for finance
 * ({@code task.qb.payout-check.<reason>} or {@code task.qb.transfer-check.<reason>}), never a posting.
 */
public final class PayoutChecks {

    // ---- payout accounts -------------------------------------------------------------------------------------------

    /** The onboarding job's answer does not fit (account id, mode, link). */
    public static final String ONBOARD_MISMATCH = "ONBOARD_MISMATCH";
    /** The Stripe account is recorded for another user already. */
    public static final String ACCOUNT_TAKEN = "ACCOUNT_TAKEN";
    /** The user has a payout account with another Stripe account. */
    public static final String ACCOUNT_CHANGED = "ACCOUNT_CHANGED";
    /** An event of a Stripe account no user has (after a grace period: the account may be recorded meanwhile). */
    public static final String UNKNOWN_ACCOUNT = "UNKNOWN_ACCOUNT";
    /** An event of the other mode (test or live). */
    public static final String LIVEMODE = "LIVEMODE";
    /** The account's own reference to the user ({@code metadata.userId}) names someone else. */
    public static final String ACCOUNT_MISMATCH = "ACCOUNT_MISMATCH";

    public static final List<String> ACCOUNT_REASONS = List.of(ONBOARD_MISMATCH, ACCOUNT_TAKEN, ACCOUNT_CHANGED,
        UNKNOWN_ACCOUNT, LIVEMODE, ACCOUNT_MISMATCH);

    // ---- transfers -------------------------------------------------------------------------------------------------

    /** Whether Stripe made the transfer is not known: it is asked before anything is undone. */
    public static final String UNCERTAIN = "UNCERTAIN";
    /** Stripe's transfer does not fit the record (amount, destination, group, currency, mode). */
    public static final String TRANSFER_MISMATCH = "TRANSFER_MISMATCH";
    /** The user's last transfers all failed: no more are made until a person looks. */
    public static final String FAILURE_STREAK = "FAILURE_STREAK";
    /** An approved transfer can no longer be sent (balance, account or ban changed): rejected. */
    public static final String NOT_SENDABLE = "NOT_SENDABLE";
    /** A transfer above the review limit, but no review rule is in effect: rejected. */
    public static final String REVIEW_NOT_CONFIGURED = "REVIEW_NOT_CONFIGURED";
    /** Stripe reversed (part of) a transfer: the money is back on the user's balance. */
    public static final String REVERSED = "REVERSED";
    /** Stripe reports more reversed than was sent, or an unusable amount. */
    public static final String OVER_REVERSAL = "OVER_REVERSAL";
    /** A reversal or payout event names no known transfer or payout, or one in a state it cannot be. */
    public static final String UNKNOWN_TRANSFER = "UNKNOWN_TRANSFER";
    public static final String CURRENCY = "CURRENCY";
    /** A payout of the platform's balance failed after it was recorded: reversed. */
    public static final String PAYOUT_FAILED = "PAYOUT_FAILED";
    /** A payout event does not fit (amount, currency, id). */
    public static final String PAYOUT_MISMATCH = "PAYOUT_MISMATCH";

    public static final List<String> TRANSFER_REASONS = List.of(UNCERTAIN, TRANSFER_MISMATCH, FAILURE_STREAK,
        NOT_SENDABLE, REVIEW_NOT_CONFIGURED, REVERSED, OVER_REVERSAL, UNKNOWN_TRANSFER, LIVEMODE, CURRENCY,
        PAYOUT_FAILED, PAYOUT_MISMATCH);

    /** The group QuizBuks gives a transfer at Stripe: how it is looked up, whatever happened to the job. */
    public static String transferGroup(String transferId) {
        return "qb-" + transferId;
    }

    /**
     * What is wrong with the onboarding job's answer; null when it fits.
     *
     * @param linkPrefix what the onboarding link must start with: Stripe's Connect host, or a local stub's
     */
    public static String onboardingProblem(PayoutObjects.Onboarding result, boolean expectedLivemode,
        String linkPrefix) {
        if (result == null || !StripeIds.is(StripeIds.ACCOUNT, result.accountId())
            || !Boolean.valueOf(expectedLivemode).equals(result.livemode())
            || result.url() == null || result.url().length() > 2048 || !result.url().startsWith(linkPrefix)
            || !StripeUrls.plain(result.url())
            || result.expiresAt() == null || result.expiresAt() <= 0 || result.expiresAt() > TopUpChecks.LAST_EXPIRY) {
            return ONBOARD_MISMATCH;
        }
        return null;
    }

    /**
     * What is wrong with Stripe's transfer for the record; null when it is the record's.
     *
     * @param net         what the record sends
     * @param destination the record's Stripe account
     */
    public static String transferProblem(String transferId, BigDecimal net, String destination,
        PayoutObjects.Transfer transfer, boolean expectedLivemode) {
        if (transfer == null || !StripeIds.is(StripeIds.TRANSFER, transfer.id())
            || !transferGroup(transferId).equals(transfer.transferGroup())
            || !StripeService.CURRENCY.equals(transfer.currency())
            || transfer.amount() == null || net == null || transfer.amount().compareTo(net) != 0
            || destination == null || !destination.equals(transfer.destination())
            || !Boolean.valueOf(expectedLivemode).equals(transfer.livemode())) {
            return TRANSFER_MISMATCH;
        }
        return null;
    }

    private PayoutChecks() {}
}
