package com.jabiz.quizbuks.wallet;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * The rules of automatic transfers (docs/quizbuks/plans/Q6-payments.md, D-Q6-12), without I/O: whether a user is
 * transferred to today and how much, whether it waits for review, which failures of Stripe are certain (no transfer
 * was made: the posting is reversed) and which are not (the transfer may exist: asked about before anything is
 * undone), and what a reversal adds.
 */
public final class TransferRules {

    /** Transfers are switched off ({@code qb.transfer.enabled}). */
    public static final String DISABLED = "DISABLED";
    /** The parameters cannot be used (missing, negative). */
    public static final String INVALID_PARAMS = "INVALID_PARAMS";
    public static final String BANNED = "BANNED";
    /** The user has no payout account, or one Stripe has not enabled. */
    public static final String NO_ACCOUNT = "NO_ACCOUNT";
    public static final String ACCOUNT_NOT_ENABLED = "ACCOUNT_NOT_ENABLED";
    /** One of the user's transfers is not finished (review, sending, check). */
    public static final String OPEN_TRANSFER = "OPEN_TRANSFER";
    /** The user's last {@value #MAX_FAILURE_STREAK} transfers failed: a person looks first. */
    public static final String FAILURE_STREAK = "FAILURE_STREAK";
    public static final String BELOW_THRESHOLD = "BELOW_THRESHOLD";
    /** The fee takes the whole amount. */
    public static final String FEE_TOO_HIGH = "FEE_TOO_HIGH";
    /**
     * A transfer of the user's was rejected by a reviewer, or reversed by Stripe (wholly or in part), and finance has
     * not released them ({@code QB_TRANSFER_HOLD_RELEASE}): the same money is not sent or queued again on its own.
     */
    public static final String HELD = "HELD";
    /** It would need a review, but no review rule is in effect: nothing is written, finance is told once a day. */
    public static final String REVIEW_NOT_CONFIGURED = PayoutChecks.REVIEW_NOT_CONFIGURED;

    public static final int MAX_FAILURE_STREAK = 3;

    /** How many of a user's latest transfers the processes read (the streak skips failures that are not theirs). */
    public static final int LATEST = 10;

    /**
     * The codes of a transfer job's failure that mean Stripe refused the creation itself (the handler fails with
     * these only for the answer to {@code POST /v1/transfers}, after looking the group up): no transfer exists, so the
     * posting is reversed. A refusal is kept by Stripe under the idempotency key, so the transfer's key can never pass
     * (an insufficient balance included): the next day's transfer has a key of its own. A key refused (401) never
     * reaches the request. Every other code (time-outs, lost leases, 5xx, 429, a lookup that failed, a conflict of
     * the idempotency key) leaves the transfer in doubt: a 429 says nothing of an earlier attempt, and Stripe keeps a
     * 500 under the key.
     */
    public static final Set<String> CERTAIN_FAILURES = Set.of(StripeErrors.DECLINED,
        StripeErrors.BALANCE_INSUFFICIENT, StripeErrors.AUTH, StripeErrors.NOT_FOUND);

    /**
     * The failures that point at the user's account and count towards {@value #FAILURE_STREAK}: Stripe refused the
     * request as it was ({@code STRIPE_DECLINED}) or does not know the account ({@code STRIPE_NOT_FOUND}). The
     * platform's own (its balance, its key, Stripe unavailable, a transfer found absent after a doubt) do not count.
     */
    public static final Set<String> ACCOUNT_FAILURES = Set.of(StripeErrors.DECLINED, StripeErrors.NOT_FOUND);

    /**
     * One of the user's earlier transfers, as the rules need it.
     *
     * @param releasedAt when finance released the user after it (a hold), or null
     */
    public record Past(String status, String failureCode, BigDecimal reversedAmount, java.time.Instant releasedAt) {

        /** Whether it holds the user: rejected, or reversed in whole or part, and not released. */
        public boolean holds() {
            return releasedAt == null && (TransferStatus.REJECTED.equals(status) || TransferStatus.REVERSED.equals(
                status) || (reversedAmount != null && reversedAmount.signum() > 0));
        }
    }

    /** The values of the parameters on the day. */
    public record Params(Boolean enabled, BigDecimal threshold, BigDecimal reviewAbove, BigDecimal fee) {}

    /**
     * What the transfer process read under the user's wallet lock.
     *
     * @param accountStatus the payout account's status; null without one
     * @param balance       account 2120 for the user, from the ledger
     */
    public record Facts(boolean banned, String accountStatus, boolean openTransfer, int failureStreak,
        boolean held, boolean reviewConfigured, BigDecimal balance) {}

    /**
     * The decision: skip (with the reason) or transfer {@code amount}, of which {@code fee} is the platform's and
     * {@code net} the user's.
     */
    public record Plan(String skip, BigDecimal amount, BigDecimal fee, BigDecimal net, boolean review) {

        static Plan skip(String reason) {
            return new Plan(reason, null, null, null, false);
        }

        public boolean transfers() {
            return skip == null;
        }
    }

    /** Whether, and how much, to transfer: the whole balance, once it reaches the threshold (U-53). */
    public static Plan plan(Params params, Facts facts) {
        if (!Boolean.TRUE.equals(params.enabled())) {
            return Plan.skip(DISABLED);
        }
        if (!wholeAndNotNegative(params.threshold()) || !wholeAndNotNegative(params.reviewAbove())
            || !wholeAndNotNegative(params.fee())) {
            return Plan.skip(INVALID_PARAMS);
        }
        if (facts.banned()) {
            return Plan.skip(BANNED);
        }
        if (facts.accountStatus() == null) {
            return Plan.skip(NO_ACCOUNT);
        }
        if (!PayoutAccountStatus.ENABLED.equals(facts.accountStatus())) {
            return Plan.skip(ACCOUNT_NOT_ENABLED);
        }
        if (facts.openTransfer()) {
            return Plan.skip(OPEN_TRANSFER);
        }
        if (facts.held()) {
            return Plan.skip(HELD);
        }
        if (facts.failureStreak() >= MAX_FAILURE_STREAK) {
            return Plan.skip(FAILURE_STREAK);
        }
        BigDecimal balance = facts.balance() == null ? BigDecimal.ZERO : facts.balance();
        if (balance.signum() <= 0 || balance.compareTo(params.threshold()) < 0
            || balance.stripTrailingZeros().scale() > 0) {
            return Plan.skip(BELOW_THRESHOLD);
        }
        BigDecimal net = balance.subtract(params.fee());
        if (net.signum() <= 0) {
            return Plan.skip(FEE_TOO_HIGH);
        }
        boolean review = balance.compareTo(params.reviewAbove()) > 0;
        if (review && !facts.reviewConfigured()) {
            return Plan.skip(REVIEW_NOT_CONFIGURED);
        }
        return new Plan(null, balance, params.fee(), net, review);
    }

    /**
     * How many of the latest transfers (newest first) failed in a row for the user's account
     * ({@link #ACCOUNT_FAILURES}); the platform's own failures in between neither count nor break the run.
     */
    public static int failureStreak(List<Past> latest) {
        int streak = 0;
        for (Past past : latest) {
            if (!TransferStatus.FAILED.equals(past.status())) {
                break;
            }
            if (ACCOUNT_FAILURES.contains(past.failureCode())) {
                streak++;
            }
        }
        return streak;
    }

    /** Whether any of the user's transfers holds them ({@link Past#holds()}). */
    public static boolean held(List<Past> transfers) {
        return transfers.stream().anyMatch(Past::holds);
    }

    /** Whether a job's failure means that Stripe made no transfer. */
    public static boolean certainFailure(String errorCode) {
        return errorCode != null && CERTAIN_FAILURES.contains(errorCode);
    }

    /**
     * The part of a reversal not recorded yet: Stripe's running total reversed less what was recorded; nothing when
     * not more (a repeated or late event). Null when the total is not believable (more than was sent, not whole).
     */
    public static BigDecimal reversalPart(BigDecimal net, BigDecimal recorded, BigDecimal totalReversed) {
        if (!wholeAndNotNegative(totalReversed) || net == null || totalReversed.compareTo(net) > 0) {
            return null;
        }
        BigDecimal part = totalReversed.subtract(recorded == null ? BigDecimal.ZERO : recorded);
        return part.signum() > 0 ? part : BigDecimal.ZERO;
    }

    static boolean wholeAndNotNegative(BigDecimal value) {
        return RefundMath.wholeAndNotNegative(value);
    }

    private TransferRules() {}
}
