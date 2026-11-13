package com.jabiz.quizbuks.fee;

import com.jabiz.entity.Violation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * The exposure fee's rules (F-M8, docs/quizbuks/plans/impl/F-M8.md §3.1): the operator's price against the
 * headquarters' range, the deposit of a purchase, a top-up as the difference of cumulative deposits, and a settlement's
 * amount and tax. Pure Java, the only definition; the processes and the pages take their figures from here.
 *
 * <p>Both the amount and the tax are <em>cumulative</em>: the amount is everything counted so far at the price, rounded
 * up, less what the rows before charged; the tax is the tax on that cumulative amount, rounded down, less the tax the
 * rows before charged. So the settlements of a purchase add up to exactly its deposit when every exposure bought is used
 * (nothing is left over or short by a rounding), however the purchase and the months are split. The distribution fee
 * keeps its per-row tax; only this fee is cumulative (the tax rate may change between two rows: the new rate applies to
 * the cumulative amount, the tax already charged is not taken back).
 */
public final class ExposureFeeRules {

    /** Prices carry four decimals. */
    public static final int SCALE = 4;

    private ExposureFeeRules() {}

    /** The headquarters' range of the operators' prices (controlled parameters); both bounds are required. */
    public record Range(BigDecimal unitMin, BigDecimal unitMax) {

        /** Whether both bounds are published. */
        public boolean complete() {
            return unitMin != null && unitMax != null;
        }
    }

    /** The refusal of a price or a submission while the range is not complete. */
    public static Violation rangeMissing() {
        return new Violation(null, FeeCodes.EXPOSURE_RANGE_MISSING,
            "The headquarters' range of the exposure prices is not published", Map.of());
    }

    /**
     * An operator's price checked as it is saved: more than 0 and within the range. A price of 0 is refused (not
     * setting a price is how an operator charges nothing).
     */
    public static List<Violation> priceProblems(BigDecimal unit, Range range) {
        if (!range.complete()) {
            return List.of(rangeMissing());
        }
        if (unit == null) {
            return List.of(new Violation("unit", FeeCodes.EXPOSURE_PRICE_REQUIRED, "Required", Map.of()));
        }
        if (unit.signum() <= 0 || unit.compareTo(range.unitMin()) < 0 || unit.compareTo(range.unitMax()) > 0) {
            return List.of(new Violation("unit", FeeCodes.EXPOSURE_PRICE_RANGE, "Outside the allowed range",
                Map.of("min", range.unitMin().toPlainString(), "max", range.unitMax().toPlainString())));
        }
        return List.of();
    }

    /** Whether a saved price is outside today's range (the range narrowed after it was saved: the daily check). */
    public static boolean outOfRange(BigDecimal unit, Range range) {
        return !priceProblems(unit, range).isEmpty();
    }

    /** The exposures billed: the successful ones, but no more than were bought ({@code ExposureRules.billable}). */
    public static long billable(long successful, long purchased) {
        return Math.min(Math.max(0, successful), Math.max(0, purchased));
    }

    /** What {@code count} exposures at {@code unit} come to, rounded up to the yen (before tax). */
    public static BigDecimal cumulative(long count, BigDecimal unit) {
        return UnitFeeRules.cumulative(count, unit);
    }

    /** The tax on a cumulative amount, rounded down (0 for a rate that is not usable). */
    public static BigDecimal tax(BigDecimal cumulativeAmount, BigDecimal taxRate) {
        BigDecimal tax = FeeRules.tax(cumulativeAmount, taxRate);
        return tax == null ? BigDecimal.ZERO : tax;
    }

    /**
     * What buying {@code purchased} exposures sets aside: {@code ⌈purchased × unit⌉} and the tax on it. Exactly what
     * settling all of them charges in all, whatever the months.
     */
    public static BigDecimal deposit(long purchased, BigDecimal unit, BigDecimal taxRate) {
        BigDecimal amount = cumulative(purchased, unit);
        return amount.add(tax(amount, taxRate));
    }

    /**
     * What a top-up freezes more: the deposit of the new total less what the earlier purchases froze, so that however
     * many times the same total is bought in the deposit is the same as buying it at once (105 and then 5 more freeze as
     * much as 110). Never below 0.
     */
    public static BigDecimal topUpDeposit(long purchasedBefore, long additional, BigDecimal unit, BigDecimal taxRate,
        BigDecimal frozenBefore) {
        BigDecimal total = deposit(Math.max(0, purchasedBefore) + Math.max(0, additional), unit, taxRate);
        return total.subtract(frozenBefore == null ? BigDecimal.ZERO : frozenBefore).max(BigDecimal.ZERO);
    }

    /**
     * One settlement's figures.
     *
     * @param cumulative the amount up to this row, rounded up, before tax
     * @param amount     this row's amount, before tax
     * @param tax        this row's tax
     * @param total      amount and tax
     */
    public record Settlement(BigDecimal cumulative, BigDecimal amount, BigDecimal tax, BigDecimal total) {}

    /**
     * Settles the exposures up to {@code toCount}: the amount is the cumulative amount less {@code chargedAmount}, the
     * tax the tax on the cumulative amount less {@code chargedTax} (both the sums of the rows before; never negative).
     * A row with no amount charges nothing, so its tax waits for the next row (a raised rate on an amount already
     * charged is caught up by the next one that has an amount, or stays in the deposit that is given back).
     */
    public static Settlement settle(long toCount, BigDecimal unit, BigDecimal chargedAmount, BigDecimal chargedTax,
        BigDecimal taxRate) {
        BigDecimal cumulative = cumulative(toCount, unit);
        BigDecimal amount = UnitFeeRules.amount(cumulative, chargedAmount);
        BigDecimal tax = amount.signum() == 0 ? BigDecimal.ZERO
            : tax(cumulative, taxRate).subtract(chargedTax == null ? BigDecimal.ZERO : chargedTax).max(BigDecimal.ZERO);
        return new Settlement(cumulative, amount, tax, amount.add(tax));
    }

    /**
     * The cumulative count a settlement of a month writes (F-M8 review H3): never below what the rows already written
     * reached, otherwise {@code min(successful by the month's end, purchased)}. A month that has a later row (a missed
     * month run after the next one) writes nothing new: its count is the one it started from.
     *
     * @param highestToCount  the largest {@code toCount} of the publication's rows (0 with none)
     * @param laterRowExists  whether a row of a later month exists already
     * @param successfulByEnd the successful exposures counted by the end of the month ({@code final}: all of them)
     */
    public static long toCount(long highestToCount, boolean laterRowExists, long successfulByEnd, long purchased) {
        long wanted = billable(successfulByEnd, purchased);
        return laterRowExists ? highestToCount : Math.max(highestToCount, wanted);
    }

    /** The largest value of qb.exposure.low-ratio (the job's candidates are chosen with it; the item decides). */
    public static final BigDecimal MAX_LOW_RATIO = new BigDecimal("0.5");

    /** The share left: whether the exposures left are at or below {@code ratio} of what was bought (0: never). */
    public static boolean running(long purchased, long used, BigDecimal ratio) {
        if (purchased <= 0 || ratio == null || ratio.signum() <= 0) {
            return false;
        }
        BigDecimal left = BigDecimal.valueOf(Math.max(0, purchased - Math.max(0, used)));
        return left.compareTo(BigDecimal.valueOf(purchased).multiply(ratio).setScale(4, RoundingMode.DOWN)) <= 0;
    }
}
