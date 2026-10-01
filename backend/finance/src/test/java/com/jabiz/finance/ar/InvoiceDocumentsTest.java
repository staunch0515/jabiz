package com.jabiz.finance.ar;

import com.jabiz.finance.calc.BookingTime;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** When an invoice's document reads the data (FIN-AR-001 acceptance 2, FIN-AR-005). */
class InvoiceDocumentsTest {

    private static final BookingTime CHICAGO = new BookingTime(ZoneId.of("America/Chicago"));

    @Test
    void anInvoicePostedOnItsDateIsReadAtTheEndOfThatDay() {
        // 15 January ends at 06:00 UTC on the 16th; a version from the 16th is not read.
        assertThat(InvoiceDocuments.readAt(CHICAGO, LocalDate.parse("2026-01-15"),
            Instant.parse("2026-01-15T16:00:00Z"))).isEqualTo(Instant.parse("2026-01-16T05:59:59.999999Z"));
    }

    @Test
    void anInvoiceEnteredAfterItsDateIsReadWhenItWasPosted() {
        Instant posted = Instant.parse("2026-01-31T15:00:00Z");
        assertThat(InvoiceDocuments.readAt(CHICAGO, LocalDate.parse("2026-01-06"), posted)).isEqualTo(posted);
        // Dates as text, as they come back from the data API, read the same.
        assertThat(InvoiceDocuments.readAt(CHICAGO, "2026-01-06", posted)).isEqualTo(posted);
    }
}
