package com.jabiz.quizbuks.journal;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Objects;

/**
 * The periods of the journal export (Q9d, D-1, D-Q9d-3): calendar months by Japanese dates (the accounting is in
 * Japan; the monthly report of Q9c stays in UTC), exported in order, each one after the one before; the first is the
 * month of the earliest posting.
 *
 * <p>An export takes the ledger as recorded up to its cut-off ({@code knownAt}): every posting booked before the end
 * of its period and recorded by then that no earlier export took. A posting recorded during an export's transaction
 * would be missed by it and, being recorded before its cut-off, by the next one too; so the cut-off is
 * {@link #SETTLE} before the export, far longer than any posting's transaction lasts.
 */
public final class JournalPeriods {

    /** Japan's time: the period's dates and the voucher's date. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

    /** How long before the export its cut-off lies (see the class). */
    public static final Duration SETTLE = Duration.ofMinutes(10);

    /**
     * A month of the export.
     *
     * @param from  its first date
     * @param to    its last date (the voucher's date)
     * @param start its first instant (00:00 JST of {@code from})
     * @param end   the first instant after it: postings booked before it belong to it
     */
    public record Period(YearMonth month, LocalDate from, LocalDate to, Instant start, Instant end) {

        public Period {
            Objects.requireNonNull(month, "month");
        }

        /** {@code 2026-10}. */
        public String label() {
            return month.toString();
        }
    }

    public static Period month(YearMonth month) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();
        return new Period(month, from, to, from.atStartOfDay(ZONE).toInstant(),
            to.plusDays(1).atStartOfDay(ZONE).toInstant());
    }

    /** The month a posting booked at {@code time} belongs to. */
    public static YearMonth monthOf(Instant time) {
        return YearMonth.from(time.atZone(ZONE));
    }

    /**
     * The next period to export: the month after the last export's, or the month of the earliest posting when there
     * was none; null when there is neither (nothing to export yet).
     */
    public static Period next(LocalDate previousFrom, Instant earliestBooking) {
        if (previousFrom != null) {
            return month(YearMonth.from(previousFrom).plusMonths(1));
        }
        return earliestBooking == null ? null : month(monthOf(earliestBooking));
    }

    /** The cut-off of an export at {@code now}: {@link #SETTLE} earlier, never before the previous cut-off. */
    public static Instant cutOff(Instant now, Instant previousKnownAt) {
        Instant cutOff = now.minus(SETTLE);
        return previousKnownAt != null && previousKnownAt.isAfter(cutOff) ? previousKnownAt : cutOff;
    }

    /** The scope of the voucher numbers: the year of the period. */
    public static String voucherScope(Period period) {
        return String.valueOf(period.month().getYear());
    }

    private JournalPeriods() {}
}
