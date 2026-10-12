package com.jabiz.quizbuks.wallet;

import java.math.BigDecimal;
import java.util.List;

/**
 * How much of a refund or a lost dispute to take back from the sponsor's wallet (docs/quizbuks/plans/Q6-payments.md,
 * D-Q6-6). QuizBuks offers no refunds itself: they only happen in Stripe's dashboard, so this is a defence.
 * <ul>
 *   <li>{@code charge.refunded} carries the running total refunded ({@code amount_refunded}): this time's part is that
 *       total less what was taken back before, and nothing when it is not more. So a repeated or late event (events
 *       come in any order) changes nothing, and each yen is taken back once.</li>
 *   <li>The wallet never goes below zero: when the balance does not cover the part (its Kudos already paid out as
 *       rewards), nothing is posted, the shortfall is recorded and a person settles it outside QuizBuks; a later
 *       refund of the same charge then takes back the whole outstanding part if the balance covers it.</li>
 *   <li>More refunded than the top-up's amount (less what a lost dispute took back) is not believable: a task, nothing
 *       taken.</li>
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
    /** The balance does not cover the refund: nothing posted. */
    public static final String REFUND_SHORTFALL = "REFUND_SHORTFALL";
    /** A dispute was opened: a person answers it at Stripe. */
    public static final String DISPUTE_OPENED = "DISPUTE_OPENED";
    /** The balance does not cover the lost dispute: nothing posted. */
    public static final String DISPUTE_SHORTFALL = "DISPUTE_SHORTFALL";
    /** A second dispute of the same charge, or a dispute that does not belong to the top-up's charge. */
    public static final String DISPUTE_MISMATCH = "DISPUTE_MISMATCH";

    /** Every reason of a refund's or a dispute's task, each with its title {@code task.qb.refund-check.<reason>}. */
    public static final List<String> REASONS = List.of(UNKNOWN_PAYMENT, LIVEMODE, CURRENCY, INVALID_AMOUNT,
        OVER_REFUND, REFUND_SHORTFALL, DISPUTE_OPENED, DISPUTE_SHORTFALL, DISPUTE_MISMATCH);

    public enum Kind {
        /** Taken back before: nothing to do. */
        NOTHING,
        /** Post and lower the wallet by {@link Plan#amount}. */
        CLAW_BACK,
        /** Record {@link Plan#amount} as the shortfall and ask a person; nothing posted. */
        SHORTFALL,
        /** Change nothing and ask a person ({@link Plan#reason}). */
        CHECK
    }

    public record Plan(Kind kind, BigDecimal amount, String reason) {
        static Plan nothing() {
            return new Plan(Kind.NOTHING, BigDecimal.ZERO, null);
        }

        static Plan check(String reason) {
            return new Plan(Kind.CHECK, BigDecimal.ZERO, reason);
        }
    }

    /**
     * @param topUpAmount   the top-up's amount
     * @param refunded      refunds taken back before ({@code refundedAmount})
     * @param clawedBack    lost disputes taken back before ({@code disputeClawback})
     * @param totalRefunded the charge's running total refunded, as Stripe reports it
     * @param balance       the sponsor's balance now
     */
    public static Plan refund(BigDecimal topUpAmount, BigDecimal refunded, BigDecimal clawedBack,
        BigDecimal totalRefunded, BigDecimal balance) {
        if (!wholeAndNotNegative(totalRefunded)) {
            return Plan.check(INVALID_AMOUNT);
        }
        if (totalRefunded.add(clawedBack).compareTo(topUpAmount) > 0) {
            return Plan.check(OVER_REFUND);
        }
        BigDecimal part = totalRefunded.subtract(refunded);
        if (part.signum() <= 0) {
            return Plan.nothing();
        }
        return covered(part, balance, REFUND_SHORTFALL);
    }

    /**
     * A lost dispute: its amount is taken back once (the caller records the dispute's outcome and does not come
     * here again).
     */
    public static Plan disputeLost(BigDecimal topUpAmount, BigDecimal refunded, BigDecimal clawedBack,
        BigDecimal disputed, BigDecimal balance) {
        if (!wholeAndNotNegative(disputed)) {
            return Plan.check(INVALID_AMOUNT);
        }
        if (disputed.signum() == 0) {
            return Plan.nothing();
        }
        if (refunded.add(clawedBack).add(disputed).compareTo(topUpAmount) > 0) {
            return Plan.check(OVER_REFUND);
        }
        return covered(disputed, balance, DISPUTE_SHORTFALL);
    }

    private static Plan covered(BigDecimal part, BigDecimal balance, String shortfall) {
        return balance.compareTo(part) >= 0 ? new Plan(Kind.CLAW_BACK, part, null)
            : new Plan(Kind.SHORTFALL, part, shortfall);
    }

    static boolean wholeAndNotNegative(BigDecimal value) {
        return value != null && value.signum() >= 0 && value.stripTrailingZeros().scale() <= 0;
    }

    private RefundMath() {}
}
