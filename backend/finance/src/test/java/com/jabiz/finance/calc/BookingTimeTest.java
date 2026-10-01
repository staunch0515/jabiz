package com.jabiz.finance.calc;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class BookingTimeTest {

    private final BookingTime chicago = new BookingTime(ZoneId.of("America/Chicago"));

    @Test
    void aPostingDateIsBookedAtTheStartOfTheDayInTheCompanysZone() {
        // Standard time: UTC-6; daylight saving time: UTC-5.
        assertThat(chicago.of(LocalDate.of(2026, 1, 31))).isEqualTo(Instant.parse("2026-01-31T06:00:00Z"));
        assertThat(chicago.of(LocalDate.of(2026, 7, 1))).isEqualTo(Instant.parse("2026-07-01T05:00:00Z"));
        assertThat(chicago.endOf(LocalDate.of(2026, 1, 31))).isEqualTo(Instant.parse("2026-02-01T06:00:00Z"));
        // The day daylight saving time starts is 23 hours long.
        assertThat(chicago.endOf(LocalDate.of(2026, 3, 8))).isEqualTo(Instant.parse("2026-03-09T05:00:00Z"));
        assertThat(chicago.dateOf(chicago.of(LocalDate.of(2026, 12, 31)))).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(chicago.dateOf(Instant.parse("2026-02-01T05:59:59Z"))).isEqualTo(LocalDate.of(2026, 1, 31));
    }
}
