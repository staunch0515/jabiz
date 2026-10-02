package com.jabiz.finance.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Book depreciation of a fixed asset by month (FIN-FA-003, 004; docs/finance/00-design.md section 11): pure, exact and
 * the same wherever it is asked, by the depreciation run, the schedule and the disposal.
 *
 * <p>The life is a row of months from the first one the convention depreciates, each a whole month or, by the
 * mid-month convention, a half one at either end. They fall into asset years of twelve from that first month. Each
 * asset year has an amount: straight-line, the depreciable amount left spread over the months left; declining
 * balance, the net book value at its start times the rate over the life, until straight-line on what is left gives
 * as much, from which year on it is straight-line (FIN-FA-003 acceptance 3); the last year takes all that is left.
 * The months of a year take its amount evenly, each rounded to the cent, and the last month of the year takes what
 * rounding left over, so every asset year, and the whole life, comes to the cent (FIN-FA-004).
 *
 * <p>A schedule can start in the middle of the life: from the cutover, with the accumulated depreciation brought
 * over, the current asset year keeping its planned amount (what the year has not yet taken goes to its months left);
 * or from a change in estimate, prospectively, the year's remainder worked out afresh on the new life and salvage
 * (FIN-FA-006). Units of production depreciates by use, period by period ({@link #byUse}).
 */
public final class Depreciation {

    public enum Method {
        /** Straight-line. */
        SL,
        /** Double-declining balance, switching to straight-line. */
        DDB,
        /** 150% declining balance, switching to straight-line. */
        DB150,
        /** Units of production: by use, not by month ({@link #byUse}). */
        UOP;

        BigDecimal rate() {
            return switch (this) {
                case DDB -> BigDecimal.valueOf(2);
                case DB150 -> new BigDecimal("1.5");
                default -> null;
            };
        }
    }

    public enum Convention {
        /** A full month in the month placed in service, none in the month disposed of. */
        FULL_MONTH,
        /** Half a month in the month placed in service and half in the month disposed of. */
        MID_MONTH,
        /** From the month after the one placed in service; a full month in the month disposed of. */
        NEXT_MONTH
    }

    /**
     * What depreciates and how.
     *
     * @param lifeMonths the useful life in months, from the first month depreciated
     */
    public record Terms(BigDecimal cost, BigDecimal salvage, LocalDate inService, int lifeMonths, Method method,
        Convention convention) {

        public Terms {
            Objects.requireNonNull(cost, "cost must not be null");
            Objects.requireNonNull(inService, "inService must not be null");
            Objects.requireNonNull(method, "method must not be null");
            Objects.requireNonNull(convention, "convention must not be null");
            salvage = salvage == null ? BigDecimal.ZERO : salvage;
            if (cost.signum() <= 0) {
                throw new IllegalArgumentException("The cost must be positive");
            }
            if (salvage.signum() < 0 || salvage.compareTo(cost) > 0) {
                throw new IllegalArgumentException("The salvage value lies between zero and the cost");
            }
            if (lifeMonths < 1 || lifeMonths > 1200) {
                throw new IllegalArgumentException("The useful life is 1 to 1,200 months");
            }
        }

        /** Cost less salvage: what the whole life depreciates. */
        public BigDecimal depreciable() {
            return cost.subtract(salvage);
        }

        /** The first month depreciated. */
        public YearMonth firstMonth() {
            YearMonth placed = YearMonth.from(inService);
            return convention == Convention.NEXT_MONTH ? placed.plusMonths(1) : placed;
        }

        /** The last month depreciated. */
        public YearMonth lastMonth() {
            // By the mid-month convention the life has a half month more at its end.
            return firstMonth().plusMonths(convention == Convention.MID_MONTH ? lifeMonths : lifeMonths - 1L);
        }
    }

    /**
     * Where a schedule starts.
     *
     * @param accumulated the depreciation taken before {@code period}
     * @param yearAmount  the planned amount of the asset year {@code period} is in, when it is kept; null when the
     *                    year's remainder is worked out afresh
     * @param takenInYear what that year took before {@code period}, from {@code yearAmount}
     */
    public record Start(YearMonth period, BigDecimal accumulated, BigDecimal yearAmount, BigDecimal takenInYear) {

        public Start {
            Objects.requireNonNull(period, "period must not be null");
            accumulated = accumulated == null ? BigDecimal.ZERO : accumulated;
            takenInYear = takenInYear == null ? BigDecimal.ZERO : takenInYear;
        }
    }

    /**
     * One month of a schedule.
     *
     * @param assetYear the asset year, from 1
     */
    public record Month(YearMonth period, int assetYear, BigDecimal amount, BigDecimal accumulated,
        BigDecimal netBookValue) {}

    private static final BigDecimal HALF = new BigDecimal("0.5");
    /** Digits kept in the shares of an amount before they are rounded to the cent. */
    private static final int WORKING_SCALE = 12;

    private Depreciation() {}

    /** The whole life from the first month depreciated, nothing taken before. */
    public static List<Month> schedule(Terms terms) {
        return scheduleFrom(terms, new Start(terms.firstMonth(), BigDecimal.ZERO, null, BigDecimal.ZERO));
    }

    /**
     * Where an asset brought over at the cutover goes on: from {@code firstPeriod} with what was accumulated before,
     * keeping the planned amount of the asset year it is in; the difference between what the year should have taken
     * so far and what was brought over is made good in the year's last month.
     */
    public static Start opening(Terms terms, YearMonth firstPeriod, BigDecimal accumulated) {
        List<Month> plan = schedule(terms);
        int index = indexOf(terms, firstPeriod);
        if (index <= 0 || index >= plan.size()) {
            return new Start(firstPeriod, accumulated, null, BigDecimal.ZERO);
        }
        int yearStart = index / 12 * 12;
        int yearEnd = Math.min(yearStart + 12, plan.size()) - 1;
        BigDecimal yearAmount = BigDecimal.ZERO;
        for (int i = yearStart; i <= yearEnd; i++) {
            yearAmount = yearAmount.add(plan.get(i).amount());
        }
        BigDecimal beforeYear = yearStart == 0 ? BigDecimal.ZERO : plan.get(yearStart - 1).accumulated();
        return new Start(firstPeriod, accumulated, yearAmount, accumulated.subtract(beforeYear));
    }

    /** A prospective restart from {@code period} (a change in estimate): the year's remainder is worked out afresh. */
    public static Start restart(YearMonth period, BigDecimal accumulated) {
        return new Start(period, accumulated, null, BigDecimal.ZERO);
    }

    /** The months from {@code start} to the end of the life; empty when the life is over by then. */
    public static List<Month> scheduleFrom(Terms terms, Start start) {
        if (terms.method() == Method.UOP) {
            throw new IllegalArgumentException("Units of production depreciates by use, not by month");
        }
        List<BigDecimal> weights = weights(terms);
        int size = weights.size();
        int index = Math.max(indexOf(terms, start.period()), 0);
        List<Month> months = new ArrayList<>();
        BigDecimal depreciable = terms.depreciable();
        BigDecimal accumulated = start.accumulated();
        if (index >= size || accumulated.compareTo(depreciable) >= 0) {
            return months;
        }
        boolean straight = terms.method() == Method.SL;
        boolean first = true;
        while (index < size) {
            int yearEnd = Math.min((index / 12 + 1) * 12, size) - 1;
            BigDecimal yearWeight = sum(weights, index, yearEnd);
            BigDecimal left = depreciable.subtract(accumulated);
            BigDecimal yearAmount;
            // The months take shares of what the year is planned at, so a schedule brought over mid-year goes on
            // exactly as it was planned; the year's last month takes the rest.
            BigDecimal shareOf = null;
            BigDecimal shareWeight = yearWeight;
            if (first && start.yearAmount() != null) {
                shareOf = start.yearAmount();
                shareWeight = sum(weights, index / 12 * 12, yearEnd);
            }
            if (yearEnd == size - 1) {
                yearAmount = left;
            } else if (shareOf != null) {
                yearAmount = start.yearAmount().subtract(start.takenInYear());
            } else {
                BigDecimal leftWeight = sum(weights, index, size - 1);
                BigDecimal straightLine = left.multiply(yearWeight).divide(leftWeight, WORKING_SCALE,
                    RoundingMode.HALF_UP);
                if (!straight) {
                    BigDecimal declining = terms.cost().subtract(accumulated).multiply(terms.method().rate())
                        .multiply(yearWeight).divide(BigDecimal.valueOf(terms.lifeMonths()), WORKING_SCALE,
                            RoundingMode.HALF_UP);
                    // From the year straight-line gives at least as much, it stays straight-line (FIN-FA-003).
                    straight = straightLine.compareTo(declining) >= 0;
                    yearAmount = straight ? straightLine : declining;
                } else {
                    yearAmount = straightLine;
                }
                yearAmount = Money.usd(yearAmount);
            }
            yearAmount = yearAmount.max(BigDecimal.ZERO).min(left);
            if (shareOf == null) {
                shareOf = yearAmount;
            }
            BigDecimal taken = BigDecimal.ZERO;
            for (int i = index; i <= yearEnd; i++) {
                BigDecimal amount = i == yearEnd ? yearAmount.subtract(taken)
                    : Money.usd(shareOf.multiply(weights.get(i)).divide(shareWeight, WORKING_SCALE,
                        RoundingMode.HALF_UP)).min(yearAmount.subtract(taken));
                taken = taken.add(amount);
                accumulated = accumulated.add(amount);
                YearMonth period = terms.firstMonth().plusMonths(i);
                months.add(new Month(period, i / 12 + 1, amount, accumulated, terms.cost().subtract(accumulated)));
            }
            index = yearEnd + 1;
            first = false;
        }
        return months;
    }

    /**
     * Units of production for one period: the depreciable amount left times the units used over the units left,
     * all that is left once the units are used up.
     */
    public static BigDecimal byUse(BigDecimal depreciableLeft, BigDecimal unitsLeft, BigDecimal unitsUsed) {
        if (depreciableLeft.signum() <= 0 || unitsUsed.signum() <= 0) {
            return BigDecimal.ZERO.setScale(Money.USD_SCALE);
        }
        if (unitsUsed.compareTo(unitsLeft) >= 0) {
            return Money.usd(depreciableLeft);
        }
        return Money.usd(depreciableLeft.multiply(unitsUsed).divide(unitsLeft, WORKING_SCALE, RoundingMode.HALF_UP));
    }

    /**
     * How much of the month an asset disposed of in it is still depreciated by the convention: none by the full-month
     * convention, half by mid-month, all by next-month.
     */
    public static BigDecimal disposalShare(Convention convention) {
        return switch (convention) {
            case FULL_MONTH -> BigDecimal.ZERO;
            case MID_MONTH -> HALF;
            case NEXT_MONTH -> BigDecimal.ONE;
        };
    }

    /** The month's place in the life, from 0; negative before the first month. */
    static int indexOf(Terms terms, YearMonth period) {
        YearMonth first = terms.firstMonth();
        return (int) ((period.getYear() - (long) first.getYear()) * 12 + period.getMonthValue()
            - first.getMonthValue());
    }

    /** Each month of the life as a share of a month: whole, or half at either end by the mid-month convention. */
    static List<BigDecimal> weights(Terms terms) {
        List<BigDecimal> weights = new ArrayList<>();
        if (terms.convention() == Convention.MID_MONTH) {
            weights.add(HALF);
            for (int i = 1; i < terms.lifeMonths(); i++) {
                weights.add(BigDecimal.ONE);
            }
            weights.add(HALF);
        } else {
            for (int i = 0; i < terms.lifeMonths(); i++) {
                weights.add(BigDecimal.ONE);
            }
        }
        return weights;
    }

    private static BigDecimal sum(List<BigDecimal> weights, int from, int to) {
        BigDecimal total = BigDecimal.ZERO;
        for (int i = from; i <= to; i++) {
            total = total.add(weights.get(i));
        }
        return total;
    }
}
