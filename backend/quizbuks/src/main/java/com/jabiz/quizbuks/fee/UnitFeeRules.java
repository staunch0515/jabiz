package com.jabiz.quizbuks.fee;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The arithmetic the fees charged per unit share (F-M6 distribution fee per start, F-M8 exposure fee per exposure): what
 * a number of units at a price come to, rounded up to the yen, and one settlement's amount as the difference from what
 * the rows before charged, so that however the units are split into months the total is the same. Pure Java; the fees'
 * own rules ({@link DistributionRules}, {@link ExposureFeeRules}) add what is theirs (the price, the tax).
 */
public final class UnitFeeRules {

    private UnitFeeRules() {}

    /** What {@code units} at {@code unit} yen come to, rounded up to the yen (before tax). */
    public static BigDecimal cumulative(long units, BigDecimal unit) {
        return unit.multiply(BigDecimal.valueOf(Math.max(0, units))).setScale(0, RoundingMode.CEILING);
    }

    /** What the units up to {@code toUnits} come to less {@code charged}, never below 0 (before tax). */
    public static BigDecimal amount(BigDecimal cumulative, BigDecimal charged) {
        return cumulative.subtract(charged == null ? BigDecimal.ZERO : charged).max(BigDecimal.ZERO);
    }
}
