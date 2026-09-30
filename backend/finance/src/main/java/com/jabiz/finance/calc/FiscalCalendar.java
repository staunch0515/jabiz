package com.jabiz.finance.calc;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Fiscal years and their periods (FIN-PC-001, docs/finance/00-design.md section 7.1): twelve monthly periods from the
 * year's first day, and optionally an adjustment period 13 spanning the same days as period 12. A fiscal year is named
 * by the calendar year it ends in; a period's key is {@code <year>-<number>} ({@code 2026-01}, {@code 2026-13}).
 * The posting date decides the period; only an entry that explicitly chooses the adjustment period goes to 13.
 */
public final class FiscalCalendar {

    /** Regular periods of a year. */
    public static final int PERIODS = 12;
    /** Number of the adjustment period. */
    public static final int ADJUSTMENT = 13;

    /** One period of a fiscal year; {@code start} and {@code end} are both included. */
    public record Period(int fiscalYear, int number, LocalDate start, LocalDate end) {

        public Period {
            Objects.requireNonNull(start, "start must not be null");
            Objects.requireNonNull(end, "end must not be null");
            if (number < 1 || number > ADJUSTMENT || end.isBefore(start)) {
                throw new IllegalArgumentException("invalid period " + fiscalYear + "/" + number);
            }
        }

        public boolean adjustment() {
            return number == ADJUSTMENT;
        }

        public String key() {
            return FiscalCalendar.key(fiscalYear, number);
        }

        public boolean contains(LocalDate date) {
            return !date.isBefore(start) && !date.isAfter(end);
        }
    }

    private FiscalCalendar() {}

    /** The key of a period: {@code 2026-01}. */
    public static String key(int fiscalYear, int number) {
        return fiscalYear + "-" + (number < 10 ? "0" : "") + number;
    }

    /**
     * The periods of fiscal year {@code fiscalYear} starting on {@code start}.
     *
     * @throws IllegalArgumentException when {@code start} is not the first of a month or the year it ends in is not
     *                                  {@code fiscalYear}
     */
    public static List<Period> periods(int fiscalYear, LocalDate start, boolean adjustmentPeriod) {
        if (start.getDayOfMonth() != 1) {
            throw new IllegalArgumentException("a fiscal year starts on the first of a month, not " + start);
        }
        LocalDate end = start.plusMonths(PERIODS).minusDays(1);
        if (end.getYear() != fiscalYear) {
            throw new IllegalArgumentException("a fiscal year starting " + start + " ends in " + end.getYear()
                + " and is named after it, not " + fiscalYear);
        }
        List<Period> periods = new ArrayList<>(ADJUSTMENT);
        for (int n = 1; n <= PERIODS; n++) {
            LocalDate first = start.plusMonths(n - 1L);
            periods.add(new Period(fiscalYear, n, first, first.plusMonths(1).minusDays(1)));
        }
        if (adjustmentPeriod) {
            Period last = periods.getLast();
            periods.add(new Period(fiscalYear, ADJUSTMENT, last.start(), last.end()));
        }
        return List.copyOf(periods);
    }

    /**
     * The period of an entry dated {@code postingDate} among {@code periods}: the regular period containing the date,
     * or with {@code toAdjustmentPeriod} the adjustment period containing it; empty when there is none.
     */
    public static Optional<Period> periodOf(List<Period> periods, LocalDate postingDate, boolean toAdjustmentPeriod) {
        return periods.stream()
            .filter(p -> p.adjustment() == toAdjustmentPeriod && p.contains(postingDate))
            .findFirst();
    }

    /** {@code 2026-01-31} as {@code Jan 31, 2026}, for messages. */
    public static String display(LocalDate date) {
        return date.format(DateTimeFormatter.ofPattern("MMM d, uuuu", java.util.Locale.US));
    }
}
