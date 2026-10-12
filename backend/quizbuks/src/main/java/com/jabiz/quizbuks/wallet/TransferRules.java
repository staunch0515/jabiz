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

    public static final int MAX_FAILURE_STREAK = 3;

    /**
     * The codes of a transfer job's failure that mean Stripe refused the creation itself (the handler fails with
     * these only for the answer to {@code POST /v1/transfers}, after looking the group up): no transfer exists, so the
     * posting is reversed. Every other code (time-outs, lost leases, 5xx, a lookup that failed, a conflict of the
     * idempotency key) leaves the transfer in doubt.
     */
    public static final Set<String> CERTAIN_FAILURES = Set.of(StripeErrors.DECLINED,
        StripeErrors.BALANCE_INSUFFICIENT, StripeErrors.AUTH, StripeErrors.NOT_FOUND, StripeErrors.RATE_LIMITED);

    /** The values of the parameters on the day. */
    public record Params(Boolean enabled, BigDecimal threshold, BigDecimal reviewAbove, BigDecimal fee) {}

    /**
     * What the transfer process read under the user's wallet lock.
     *
     * @param accountStatus the payout account's status; null without one
     * @param balance       account 2120 for the user, from the ledger
     */
    public record Facts(boolean banned, String accountStatus, boolean openTransfer, int failureStreak,
        BigDecimal balance) {}

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
        return new Plan(null, balance, params.fee(), net, balance.compareTo(params.reviewAbove()) > 0);
    }

    /** How many of the latest transfers (newest first) failed in a row. */
    public static int failureStreak(List<String> latestStatuses) {
        int streak = 0;
        for (String status : latestStatuses) {
            if (!TransferStatus.FAILED.equals(status)) {
                break;
            }
            streak++;
        }
        return streak;
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
