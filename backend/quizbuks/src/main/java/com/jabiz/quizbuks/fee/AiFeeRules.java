package com.jabiz.quizbuks.fee;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/**
 * The AI fee's arithmetic (F-M9, docs/quizbuks/plans/impl/F-M9.md §3), in yen, without the database. A job's fee is the
 * dollars of the calls the provider answered ({@code billableUsd}) times the markup and the exchange rate frozen at its
 * request, rounded up to the yen; its tax is worked out on the month's total of its group (the operator and the month the
 * fee will be charged as), so that the jobs' increments add up to exactly what the one charge comes to (review H2 (a), as
 * the distribution fee's cumulative rounding).
 */
public final class AiFeeRules {

    /**
     * A job's fee as its end settles it.
     *
     * @param fee      the fee before tax charged to the sponsor
     * @param absorbed what of the fee before tax the platform took on, the credit falling short
     * @param withTax  what the fee adds to its group's total with tax: what it takes of the credit
     */
    public record Settled(BigDecimal fee, BigDecimal absorbed, BigDecimal withTax) {}

    /** The fee before tax: the dollars times the markup and the rate, rounded up to the yen (none for no usage). */
    public static BigDecimal fee(BigDecimal billableUsd, BigDecimal markup, BigDecimal usdJpy) {
        if (billableUsd == null || billableUsd.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return billableUsd.multiply(markup).multiply(usdJpy).setScale(0, RoundingMode.UP);
    }

    /**
     * The cost part of a fee (the dollars at the rate, rounded up, without the markup): what of an AI fee is not the
     * operators' to share in (F-M12 §3.2, {@code shareExcluded}).
     */
    public static BigDecimal costPart(BigDecimal billableUsd, BigDecimal usdJpy) {
        if (billableUsd == null || billableUsd.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return billableUsd.multiply(usdJpy).setScale(0, RoundingMode.UP);
    }

    /** An amount before tax with its tax ({@link FeeRules#tax}, rounded down). */
    public static BigDecimal withTax(BigDecimal amount, BigDecimal taxRate) {
        return amount.add(FeeRules.tax(amount, taxRate));
    }

    /** What a request holds of the credit: the most it may cost, as a fee, with its tax. */
    public static BigDecimal reservation(BigDecimal maxUsd, BigDecimal markup, BigDecimal usdJpy, BigDecimal taxRate) {
        return withTax(fee(maxUsd, markup, usdJpy), taxRate);
    }

    /**
     * What a fee {@code fee} adds to its group's total with tax when the group's fees before it come to {@code before}.
     */
    public static BigDecimal increment(BigDecimal before, BigDecimal fee, BigDecimal taxRate) {
        return withTax(before.add(fee), taxRate).subtract(withTax(before, taxRate));
    }

    /** What the fees finished and not charged yet will take of the credit: each group's total with its tax. */
    public static BigDecimal unbilledWithTax(Collection<BigDecimal> groupTotals, BigDecimal taxRate) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal group : groupTotals) {
            total = total.add(withTax(group, taxRate));
        }
        return total;
    }

    /** What a sponsor may still use of the credit: its remaining less the unbilled fees and the reservations. */
    public static BigDecimal available(BigDecimal remaining, BigDecimal unbilledWithTax, BigDecimal reserved) {
        return remaining.subtract(unbilledWithTax).subtract(reserved).max(BigDecimal.ZERO);
    }

    /**
     * Settles a job's fee: all of it when its increment fits in {@code room} (its own reservation and what the sponsor
     * may still use besides); otherwise the largest fee before tax whose increment does, the rest taken on by the
     * platform (never charged later, plan §3).
     *
     * @param before the fees of the job's group finished before it and not charged yet
     * @param fee    the job's fee before tax
     * @param room   what the job may take of the credit
     */
    public static Settled settle(BigDecimal before, BigDecimal fee, BigDecimal room, BigDecimal taxRate) {
        BigDecimal full = increment(before, fee, taxRate);
        if (full.compareTo(room) <= 0) {
            return new Settled(fee, BigDecimal.ZERO, full);
        }
        // The increment only grows with the fee: the largest that fits, by halves.
        BigDecimal low = BigDecimal.ZERO;
        BigDecimal high = fee;
        while (high.subtract(low).compareTo(BigDecimal.ONE) > 0) {
            BigDecimal middle = low.add(high).divide(BigDecimal.TWO, 0, RoundingMode.DOWN);
            if (increment(before, middle, taxRate).compareTo(room) <= 0) {
                low = middle;
            } else {
                high = middle;
            }
        }
        BigDecimal fits = increment(before, high, taxRate).compareTo(room) <= 0 ? high : low;
        return new Settled(fits, fee.subtract(fits), increment(before, fits, taxRate));
    }

    private AiFeeRules() {}
}
