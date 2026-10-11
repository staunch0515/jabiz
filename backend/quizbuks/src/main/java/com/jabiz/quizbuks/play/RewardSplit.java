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

    /**
     * @param rate a fraction from 0 to 1; null is 0
     * @throws IllegalArgumentException for a rate outside 0 to 1, or a negative reward
     */
    public static Split of(BigDecimal reward, BigDecimal rate) {
        if (reward.signum() < 0) {
            throw new IllegalArgumentException("A reward is never negative: " + reward);
        }
        BigDecimal r = rate == null ? BigDecimal.ZERO : rate;
        if (r.signum() < 0 || r.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("The commission rate must be between 0 and 1: " + r);
        }
        BigDecimal fee = reward.multiply(r).setScale(0, RoundingMode.FLOOR);
        return new Split(fee, reward.setScale(0, RoundingMode.UNNECESSARY).subtract(fee));
    }

    private RewardSplit() {}
}
