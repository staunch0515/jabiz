package com.jabiz.finance.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The only rounding of amounts in finance (docs/finance/00-design.md section 4.1): half away from zero to a
 * currency's minor unit ({@link RoundingMode#HALF_UP} rounds negative amounts away from zero too), applied only at
 * the level a requirement names. Nothing else calls {@code setScale} on an amount.
 */
public final class Money {

    /** Minor units of the functional currency, US dollars. */
    public static final int USD_SCALE = 2;

    private Money() {}

    /** {@code amount} rounded half away from zero to {@code scale} decimals. */
    public static BigDecimal round(BigDecimal amount, int scale) {
        Objects.requireNonNull(amount, "amount must not be null");
        if (scale < 0) {
            throw new IllegalArgumentException("scale must not be negative");
        }
        return amount.setScale(scale, RoundingMode.HALF_UP);
    }

    /** {@code amount} in US dollars and cents. */
    public static BigDecimal usd(BigDecimal amount) {
        return round(amount, USD_SCALE);
    }

    /** Whether {@code amount} has no digits beyond {@code scale} decimals (trailing zeros aside). */
    public static boolean fits(BigDecimal amount, int scale) {
        return amount.stripTrailingZeros().scale() <= scale;
    }

    /**
     * Splits {@code total} over {@code weights} in proportion, each share rounded to {@code scale}, by the largest
     * remainder: the shares add up to {@code total} exactly and no share is off by more than one minor unit. Ties go
     * to the later share, so the last line takes the odd cent (design section 4.1).
     *
     * @param total   an amount with at most {@code scale} decimals
     * @param weights non-negative, not all zero
     */
    public static List<BigDecimal> allocate(BigDecimal total, List<BigDecimal> weights, int scale) {
        Objects.requireNonNull(total, "total must not be null");
        if (!fits(total, scale)) {
            throw new IllegalArgumentException("total " + total + " has more than " + scale + " decimals");
        }
        if (weights.isEmpty() || weights.stream().anyMatch(w -> w == null || w.signum() < 0)) {
            throw new IllegalArgumentException("weights must be present and not negative");
        }
        BigDecimal sum = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.signum() == 0) {
            throw new IllegalArgumentException("weights must not all be zero");
        }
        BigDecimal unit = BigDecimal.ONE.movePointLeft(scale);
        BigDecimal magnitude = total.abs();
        List<BigDecimal> shares = new ArrayList<>(weights.size());
        List<BigDecimal> remainders = new ArrayList<>(weights.size());
        BigDecimal given = BigDecimal.ZERO;
        for (BigDecimal weight : weights) {
            BigDecimal exact = magnitude.multiply(weight).divide(sum, scale + 10, RoundingMode.DOWN);
            BigDecimal floor = exact.setScale(scale, RoundingMode.DOWN);
            shares.add(floor);
            remainders.add(exact.subtract(floor));
            given = given.add(floor);
        }
        int left = magnitude.subtract(given).divide(unit, 0, RoundingMode.UNNECESSARY).intValueExact();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < weights.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.<Integer, BigDecimal>comparing(remainders::get).reversed()
            .thenComparing(Comparator.<Integer>reverseOrder()));
        for (int i = 0; i < left; i++) {
            int index = order.get(i);
            shares.set(index, shares.get(index).add(unit));
        }
        return total.signum() < 0 ? shares.stream().map(BigDecimal::negate).toList() : List.copyOf(shares);
    }
}
