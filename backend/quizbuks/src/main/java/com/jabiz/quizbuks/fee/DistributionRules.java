package com.jabiz.quizbuks.fee;

import com.jabiz.entity.Violation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The distribution fee's rules (F-M6, docs/quizbuks/plans/impl/F-M.md §9.3): the price per start, the deposit, the
 * amount of a settlement and an operator's price against the headquarters' range. Pure Java, the only definition; the
 * processes and the pages take their figures from here.
 *
 * <p>The tiers' amounts are Kudos for every reward method (a prize's value in Kudos for the methods the sponsor pays
 * themselves); the rate is yen per start for each Kudos of the highest tier, not a percentage. A publication whose
 * highest tier is 0 or that has no tier (only bound to a node) pays the floor.
 */
public final class DistributionRules {

    /** Prices per start and rates carry four decimals. */
    public static final int SCALE = 4;

    private DistributionRules() {}

    /** An operator's price (the row of {@code QbDistributionPrice}). */
    public record Price(BigDecimal rate, BigDecimal unitFloor, BigDecimal unitCeiling, BigDecimal minimumDeposit) {}

    /**
     * The headquarters' range of the operators' prices (controlled parameters). Every bound is required (review L2):
     * one not published refuses the price and the submission ({@link #complete}), rather than leaving it unbounded.
     */
    public record Range(BigDecimal rateMin, BigDecimal rateMax, BigDecimal unitFloorMin, BigDecimal unitFloorMax,
        BigDecimal unitCeilingMax, BigDecimal minimumDepositMax) {

        /** Whether every bound is published. */
        public boolean complete() {
            return rateMin != null && rateMax != null && unitFloorMin != null && unitFloorMax != null
                && unitCeilingMax != null && minimumDepositMax != null;
        }
    }

    /** The refusal of a price or a submission while the range is not complete. */
    public static Violation rangeMissing() {
        return new Violation(null, FeeCodes.DISTRIBUTION_RANGE_MISSING,
            "The headquarters' range of the distribution prices is not published", java.util.Map.of());
    }

    /** A price per start: the rate times the highest tier, kept between the floor and the ceiling (4 decimals). */
    public record Unit(BigDecimal unit, boolean atFloor) {}

    /**
     * The price per start of a publication: {@code clamp(rate × maxReward, floor, ceiling)}, to four decimals half up;
     * the floor when the highest tier is 0 or there is no tier.
     */
    public static Unit unit(Price price, BigDecimal maxReward) {
        BigDecimal floor = price.unitFloor().setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal ceiling = price.unitCeiling().setScale(SCALE, RoundingMode.HALF_UP);
        if (maxReward == null || maxReward.signum() <= 0) {
            return new Unit(floor, true);
        }
        BigDecimal raw = price.rate().multiply(maxReward).setScale(SCALE, RoundingMode.HALF_UP);
        if (raw.compareTo(floor) <= 0) {
            return new Unit(floor, true);
        }
        return new Unit(raw.min(ceiling), false);
    }

    /** What a number of starts at a price comes to, rounded up to the yen (before tax). */
    public static BigDecimal cumulative(long starts, BigDecimal unit) {
        return unit.multiply(BigDecimal.valueOf(Math.max(0, starts))).setScale(0, RoundingMode.CEILING);
    }

    /**
     * One settlement's figures: everything up to {@code toStarts} rounded up at once, less what earlier rows charged
     * ({@code charged}; never negative); the tax on that, rounded down ({@link FeeRules#tax}).
     */
    public record Settlement(BigDecimal cumulative, BigDecimal amount, BigDecimal tax, BigDecimal total) {}

    public static Settlement settle(long toStarts, BigDecimal unit, BigDecimal charged, BigDecimal taxRate) {
        BigDecimal cumulative = cumulative(toStarts, unit);
        BigDecimal amount = cumulative.subtract(charged == null ? BigDecimal.ZERO : charged).max(BigDecimal.ZERO);
        BigDecimal tax = amount.signum() == 0 ? BigDecimal.ZERO : FeeRules.tax(amount, taxRate);
        if (tax == null) {
            tax = BigDecimal.ZERO;
        }
        return new Settlement(cumulative, amount, tax, amount.add(tax));
    }

    /**
     * What the submission sets aside: for a cap on the winners, what that many starts come to with tax
     * ({@code ⌈maxWinners × unit⌉ + tax}); without one, the operator's least deposit.
     */
    public static BigDecimal deposit(Integer maxWinners, BigDecimal unit, BigDecimal minimumDeposit,
        BigDecimal taxRate) {
        if (maxWinners == null) {
            return minimumDeposit == null ? BigDecimal.ZERO : minimumDeposit.max(BigDecimal.ZERO);
        }
        return settle(maxWinners, unit, BigDecimal.ZERO, taxRate).total();
    }

    /** The before-tax part of {@link #deposit} (what a cap on the winners would be charged). */
    public static BigDecimal depositBeforeTax(Integer maxWinners, BigDecimal unit, BigDecimal minimumDeposit) {
        return maxWinners == null ? (minimumDeposit == null ? BigDecimal.ZERO : minimumDeposit.max(BigDecimal.ZERO))
            : cumulative(maxWinners, unit);
    }

    /** An operator's price checked as it is saved: none negative, the floor not above the ceiling, all within range. */
    public static List<Violation> priceProblems(Price price, Range range) {
        if (!range.complete()) {
            return List.of(rangeMissing());
        }
        List<Violation> problems = new ArrayList<>();
        check(problems, "rate", price.rate(), range.rateMin(), range.rateMax());
        check(problems, "unitFloor", price.unitFloor(), range.unitFloorMin(), range.unitFloorMax());
        check(problems, "unitCeiling", price.unitCeiling(), null, range.unitCeilingMax());
        check(problems, "minimumDeposit", price.minimumDeposit(), null, range.minimumDepositMax());
        if (price.unitFloor() != null && price.unitCeiling() != null
            && price.unitFloor().compareTo(price.unitCeiling()) > 0) {
            problems.add(new Violation("unitCeiling", FeeCodes.DISTRIBUTION_FLOOR_ABOVE_CEILING,
                "The floor of the price per start is above its ceiling", java.util.Map.of()));
        }
        return List.copyOf(problems);
    }

    /** Whether a saved price is outside today's range (the range narrowed after it was saved: the daily check). */
    public static boolean outOfRange(Price price, Range range) {
        return !priceProblems(price, range).isEmpty();
    }

    private static void check(List<Violation> problems, String field, BigDecimal value, BigDecimal min,
        BigDecimal max) {
        if (value == null) {
            problems.add(new Violation(field, FeeCodes.DISTRIBUTION_PRICE_REQUIRED, "Required", java.util.Map.of()));
        } else if (value.signum() < 0 || (min != null && value.compareTo(min) < 0)
            || (max != null && value.compareTo(max) > 0)) {
            problems.add(new Violation(field, FeeCodes.DISTRIBUTION_PRICE_RANGE, "Outside the allowed range",
                java.util.Map.of("min", min == null ? "0" : min.toPlainString(),
                    "max", max == null ? "" : max.toPlainString())));
        }
    }
}
