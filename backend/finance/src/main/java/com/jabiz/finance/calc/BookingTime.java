package com.jabiz.finance.calc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Posting dates on the ledger's time line (docs/finance/00-design.md section 4.2): a posting date is booked at the
 * start of that day in the company's zone ({@code finance.company.zone}, America/Chicago), and "as of a date"
 * means before the start of the next day there.
 */
public record BookingTime(ZoneId zone) {

    public BookingTime {
        java.util.Objects.requireNonNull(zone, "zone must not be null");
    }

    /** The ledger booking time of a posting date. */
    public Instant of(LocalDate postingDate) {
        return postingDate.atStartOfDay(zone).toInstant();
    }

    /** The first moment after {@code date}: balances "as of" the date are those booked before it. */
    public Instant endOf(LocalDate date) {
        return of(date.plusDays(1));
    }

    /** The posting date a booking time stands for. */
    public LocalDate dateOf(Instant bookingTime) {
        return LocalDate.ofInstant(bookingTime, zone);
    }
}
