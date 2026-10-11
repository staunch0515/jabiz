package com.jabiz.quizbuks.play;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The platform's commission on a reward (docs/quizbuks/plans/Q5-answering.md, D-Q5-4, requirement 5.3-13): the rate
 * of the controlled parameter {@code qb.creator-fee-rate} in effect when the reward is paid, rounded down to whole
 * Kudos (a fraction of a Kudo stays with the taker). Commission and the taker's part add up to the reward.
 */
public final class RewardSplit {

    /** @param fee the platform's part; {@code net} the taker's */
    public record Split(BigDecimal fee, BigDecimal net) {}

    /** Whether a commission rate may be applied: from 0 up to, not including, 1 (null is 0). */
    public static boolean validRate(BigDecimal rate) {
        return rate == null || rate.signum() >= 0 && rate.compareTo(BigDecimal.ONE) < 0;
    }

    /**
     * @param rate a fraction from 0 to below 1 ({@link #validRate}); null is 0
     * @throws IllegalArgumentException for another rate, or a negative reward
     */
    public static Split of(BigDecimal reward, BigDecimal rate) {
        if (reward.signum() < 0) {
            throw new IllegalArgumentException("A reward is never negative: " + reward);
        }
        if (!validRate(rate)) {
            throw new IllegalArgumentException("The commission rate must be from 0 to below 1: " + rate);
        }
        BigDecimal r = rate == null ? BigDecimal.ZERO : rate;
        BigDecimal fee = reward.multiply(r).setScale(0, RoundingMode.FLOOR);
        return new Split(fee, reward.setScale(0, RoundingMode.UNNECESSARY).subtract(fee));
    }

    private RewardSplit() {}
}
