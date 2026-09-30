package com.jabiz.retention;

import java.time.LocalDate;
import java.time.Month;
import java.time.Period;
import java.time.YearMonth;
import java.util.Objects;

/**
 * How long entries of an entity must be kept (docs/design/21-audit-retention.md section 3), declared in code as a
 * bean: from a date field of the entry, optionally counted from the end of the fiscal year that date falls in. While
 * an entry is kept it cannot be deleted - neither a temporal entry's tombstone nor a plain row's removal.
 *
 * <pre>{@code
 * RetentionPolicy.of("FinJournal").keep(Period.ofYears(7)).from("postingDate").afterFiscalYearEnd()
 * }</pre>
 *
 * @param entity             the entity type
 * @param keep               how long; positive
 * @param from               the date or time field the period starts from
 * @param fromFiscalYearEnd  whether it starts at the end of the fiscal year the date falls in, not at the date
 */
public record RetentionPolicy(String entity, Period keep, String from, boolean fromFiscalYearEnd) {

    public RetentionPolicy {
        Objects.requireNonNull(entity, "entity must not be null");
        if (keep != null && (keep.isNegative() || keep.isZero())) {
            throw new IllegalArgumentException("The retention of " + entity + " must be a positive period");
        }
    }

    /** A policy to complete with {@link #keep} and {@link #from}. */
    public static RetentionPolicy of(String entity) {
        return new RetentionPolicy(entity, null, null, false);
    }

    public RetentionPolicy keep(Period period) {
        return new RetentionPolicy(entity, Objects.requireNonNull(period, "period must not be null"), from,
            fromFiscalYearEnd);
    }

    public RetentionPolicy from(String field) {
        return new RetentionPolicy(entity, keep, Objects.requireNonNull(field, "field must not be null"),
            fromFiscalYearEnd);
    }

    /** Counts from the end of the fiscal year the date falls in. */
    public RetentionPolicy afterFiscalYearEnd() {
        return new RetentionPolicy(entity, keep, from, true);
    }

    /** Whether both the period and the field are given. */
    public boolean complete() {
        return keep != null && from != null;
    }

    /**
     * The first day the entry may be deleted.
     *
     * @param start         the entry's date
     * @param fiscalYearEnd the last month of the fiscal year
     */
    public LocalDate expiry(LocalDate start, Month fiscalYearEnd) {
        LocalDate base = fromFiscalYearEnd ? fiscalYearEnd(start, fiscalYearEnd) : start;
        return base.plus(keep);
    }

    /** Whether the entry must still be kept on {@code today}; an entry without its date is always kept. */
    public boolean keeps(LocalDate start, LocalDate today, Month fiscalYearEnd) {
        return start == null || today.isBefore(expiry(start, fiscalYearEnd));
    }

    /**
     * The latest date whose entries may be deleted on {@code today}: entries dated on or before it are past their
     * retention. The expiry never decreases with the date, so it is found stepping back from an upper bound.
     */
    public LocalDate expiredThrough(LocalDate today, Month fiscalYearEnd) {
        LocalDate date = today.minus(keep).plusDays(3);
        while (expiry(date, fiscalYearEnd).isAfter(today)) {
            date = date.minusDays(1);
        }
        return date;
    }

    /** The last day of the fiscal year the date falls in. */
    public static LocalDate fiscalYearEnd(LocalDate date, Month lastMonth) {
        LocalDate end = YearMonth.of(date.getYear(), lastMonth).atEndOfMonth();
        return date.isAfter(end) ? YearMonth.of(date.getYear() + 1, lastMonth).atEndOfMonth() : end;
    }
}
