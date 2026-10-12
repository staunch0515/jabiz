package com.jabiz.quizbuks.wallet;

import java.math.BigDecimal;
import java.util.List;

/**
 * How much of a refund or a lost dispute to take back from the sponsor's wallet (docs/quizbuks/plans/Q6-payments.md,
 * D-Q6-6 and its review additions). QuizBuks offers no refunds itself: they only happen in Stripe's dashboard, so
 * this is a defence.
 * <ul>
 *   <li>{@code charge.refunded} carries the running total refunded ({@code amount_refunded}): this time's part is that
 *       total less what was accounted for before, taken back or recorded as a shortfall. So a repeated or late event
 *       (events come in any order) changes nothing, each yen is accounted for once, and a recorded shortfall is never
 *       taken again (it is finance's to settle from then on, perhaps already outside QuizBuks).</li>
 *   <li>The wallet never goes below zero: as much of the part as is available is taken back, and only the rest is
 *       recorded as the shortfall, for a person.</li>
 *   <li>An open dispute holds what is available of its amount ({@link #hold}), so it cannot be spent on rewards
 *       while it is open; a lost one is taken back from that hold and what else is available, the rest a
 *       shortfall.</li>
 *   <li>More refunded or disputed than the top-up's amount is not believable: a task, nothing taken.</li>
 * </ul>
 */
public final class RefundMath {

    /** The payment is not a paid top-up's (yet: a refund may be processed before the top-up's confirmation). */
    public static final String UNKNOWN_PAYMENT = "UNKNOWN_PAYMENT";
    public static final String LIVEMODE = "LIVEMODE";
    public static final String CURRENCY = "CURRENCY";
    /** The amount is missing, negative or not whole yen. */
    public static final String INVALID_AMOUNT = "INVALID_AMOUNT";
    /** More would be taken back than the top-up brought in. */
    public static final String OVER_REFUND = "OVER_REFUND";
    /** The wallet does not cover all of the refund: the rest is recorded for a person. */
    public static final String REFUND_SHORTFALL = "REFUND_SHORTFALL";
    /** A dispute was opened: a person answers it at Stripe. */
    public static final String DISPUTE_OPENED = "DISPUTE_OPENED";
    /** The wallet does not cover all of the lost dispute: the rest is recorded for a person. */
    public static final String DISPUTE_SHORTFALL = "DISPUTE_SHORTFALL";
    /** A second dispute of the same charge, or a dispute that does not belong to the top-up's charge. */
    public static final String DISPUTE_MISMATCH = "DISPUTE_MISMATCH";

    /** Every reason of a refund's or a dispute's task, each with its title {@code task.qb.refund-check.<reason>}. */
    public static final List<String> REASONS = List.of(UNKNOWN_PAYMENT, LIVEMODE, CURRENCY, INVALID_AMOUNT,
        OVER_REFUND, REFUND_SHORTFALL, DISPUTE_OPENED, DISPUTE_SHORTFALL, DISPUTE_MISMATCH);

    public enum Kind {
        /** Accounted for before: nothing to do. */
        NOTHING,
        /** Post and lower the wallet by {@link Plan#amount}. */
        CLAW_BACK,
        /** Post {@link Plan#amount} (perhaps 0), record {@link Plan#shortfall} and ask a person. */
        SHORTFALL,
        /** Change nothing and ask a person ({@link Plan#reason}). */
        CHECK
    }

    /** @param amount what is taken back now; @param shortfall what is recorded for a person */
    public record Plan(Kind kind, BigDecimal amount, BigDecimal shortfall, String reason) {
        static Plan nothing() {
            return new Plan(Kind.NOTHING, BigDecimal.ZERO, BigDecimal.ZERO, null);
        }

        static Plan check(String reason) {
            return new Plan(Kind.CHECK, BigDecimal.ZERO, BigDecimal.ZERO, reason);
        }
    }

    /**
     * What a top-up recorded so far.
     *
     * @param refunded         refunds taken back ({@code refundedAmount})
     * @param refundShortfall  refunds not taken back, a person's ({@code refundShortfall})
     * @param disputeClawback  a lost dispute taken back ({@code disputeClawback})
     * @param disputeShortfall a lost dispute not taken back, a person's ({@code disputeShortfall})
     */
    public record Recorded(BigDecimal topUpAmount, BigDecimal refunded, BigDecimal refundShortfall,
        BigDecimal disputeClawback, BigDecimal disputeShortfall) {

        BigDecimal refundsAccounted() {
            return refunded.add(refundShortfall);
        }

        BigDecimal disputeAccounted() {
            return disputeClawback.add(disputeShortfall);
        }
    }

    /**
     * @param totalRefunded the charge's running total refunded, as Stripe reports it
     * @param available     what the sponsor's wallet can give now (its balance less what open disputes hold)
     */
    public static Plan refund(Recorded recorded, BigDecimal totalRefunded, BigDecimal available) {
        if (!wholeAndNotNegative(totalRefunded)) {
            return Plan.check(INVALID_AMOUNT);
        }
        if (totalRefunded.add(recorded.disputeAccounted()).compareTo(recorded.topUpAmount()) > 0) {
            return Plan.check(OVER_REFUND);
        }
        BigDecimal part = totalRefunded.subtract(recorded.refundsAccounted());
        if (part.signum() <= 0) {
            return Plan.nothing();
        }
        return take(part, available, REFUND_SHORTFALL);
    }

    /**
     * A lost dispute: its amount is taken back once (the caller records the dispute's outcome and does not come
     * here again).
     *
     * @param available what the wallet can give now, including what this dispute held
     */
    public static Plan disputeLost(Recorded recorded, BigDecimal disputed, BigDecimal available) {
        if (!wholeAndNotNegative(disputed)) {
            return Plan.check(INVALID_AMOUNT);
        }
        if (disputed.signum() == 0) {
            return Plan.nothing();
        }
        if (recorded.refundsAccounted().add(recorded.disputeAccounted()).add(disputed)
            .compareTo(recorded.topUpAmount()) > 0) {
            return Plan.check(OVER_REFUND);
        }
        return take(disputed, available, DISPUTE_SHORTFALL);
    }

    /** What an opened dispute holds: as much of its amount as is available, nothing for an unusable amount. */
    public static BigDecimal hold(BigDecimal available, BigDecimal disputed) {
        if (available == null || disputed == null || available.signum() <= 0 || disputed.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return available.min(disputed);
    }

    private static Plan take(BigDecimal part, BigDecimal available, String shortfallReason) {
        BigDecimal taken = available == null || available.signum() <= 0 ? BigDecimal.ZERO : available.min(part);
        BigDecimal shortfall = part.subtract(taken);
        return shortfall.signum() == 0 ? new Plan(Kind.CLAW_BACK, taken, BigDecimal.ZERO, null)
            : new Plan(Kind.SHORTFALL, taken, shortfall, shortfallReason);
    }

    static boolean wholeAndNotNegative(BigDecimal value) {
        return value != null && value.signum() >= 0 && value.stripTrailingZeros().scale() <= 0;
    }

    private RefundMath() {}
}
