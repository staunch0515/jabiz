package com.jabiz.retention;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.time.Period;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Retention periods and legal holds (docs/design/21-audit-retention.md section 3). */
class RetentionPolicyTest {

    private static final RetentionPolicy SEVEN_YEARS = RetentionPolicy.of("Invoice").keep(Period.ofYears(7))
        .from("invoiceDate");
    private static final RetentionPolicy FISCAL = SEVEN_YEARS.afterFiscalYearEnd();

    @Test
    void aPeriodCountsFromTheDate() {
        LocalDate start = LocalDate.of(2020, 5, 10);
        assertThat(SEVEN_YEARS.complete()).isTrue();
        assertThat(SEVEN_YEARS.expiry(start, Month.DECEMBER)).isEqualTo(LocalDate.of(2027, 5, 10));
        assertThat(SEVEN_YEARS.keeps(start, LocalDate.of(2027, 5, 9), Month.DECEMBER)).isTrue();
        assertThat(SEVEN_YEARS.keeps(start, LocalDate.of(2027, 5, 10), Month.DECEMBER)).isFalse();
        // Without its date an entry is kept.
        assertThat(SEVEN_YEARS.keeps(null, LocalDate.of(2099, 1, 1), Month.DECEMBER)).isTrue();
    }

    @Test
    void orFromTheEndOfTheFiscalYearTheDateFallsIn() {
        assertThat(RetentionPolicy.fiscalYearEnd(LocalDate.of(2020, 5, 10), Month.DECEMBER))
            .isEqualTo(LocalDate.of(2020, 12, 31));
        assertThat(RetentionPolicy.fiscalYearEnd(LocalDate.of(2020, 5, 10), Month.MARCH))
            .isEqualTo(LocalDate.of(2021, 3, 31));
        assertThat(RetentionPolicy.fiscalYearEnd(LocalDate.of(2020, 3, 31), Month.MARCH))
            .isEqualTo(LocalDate.of(2020, 3, 31));
        // A fiscal year ending in February ends on the 29th in leap years.
        assertThat(RetentionPolicy.fiscalYearEnd(LocalDate.of(2023, 6, 1), Month.FEBRUARY))
            .isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(FISCAL.expiry(LocalDate.of(2020, 5, 10), Month.DECEMBER)).isEqualTo(LocalDate.of(2027, 12, 31));
        assertThat(FISCAL.expiry(LocalDate.of(2023, 6, 1), Month.FEBRUARY)).isEqualTo(LocalDate.of(2031, 2, 28));
        assertThat(FISCAL.keeps(LocalDate.of(2020, 1, 1), LocalDate.of(2027, 12, 30), Month.DECEMBER)).isTrue();
        assertThat(FISCAL.keeps(LocalDate.of(2020, 12, 31), LocalDate.of(2027, 12, 31), Month.DECEMBER)).isFalse();
    }

    @Test
    void theLatestExpiredDateIsExact() {
        LocalDate today = LocalDate.of(2031, 2, 28);
        // 2024-02-29 plus seven years is 2031-02-28: expired today, like the 28th.
        assertThat(SEVEN_YEARS.expiredThrough(today, Month.DECEMBER)).isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(FISCAL.expiredThrough(LocalDate.of(2027, 12, 30), Month.DECEMBER))
            .isEqualTo(LocalDate.of(2019, 12, 31));
        assertThat(FISCAL.expiredThrough(LocalDate.of(2027, 12, 31), Month.DECEMBER))
            .isEqualTo(LocalDate.of(2020, 12, 31));
        for (LocalDate day = LocalDate.of(2027, 1, 1); day.isBefore(LocalDate.of(2029, 1, 1)); day = day.plusDays(17)) {
            for (RetentionPolicy policy : List.of(SEVEN_YEARS, FISCAL)) {
                LocalDate through = policy.expiredThrough(day, Month.MARCH);
                assertThat(policy.keeps(through, day, Month.MARCH)).isFalse();
                assertThat(policy.keeps(through.plusDays(1), day, Month.MARCH)).isTrue();
            }
        }
    }

    @Test
    void policiesAreValidated() {
        assertThat(RetentionPolicy.of("X").complete()).isFalse();
        assertThat(RetentionPolicy.of("X").keep(Period.ofYears(1)).complete()).isFalse();
        assertThatThrownBy(() -> RetentionPolicy.of("X").keep(Period.ZERO)).hasMessageContaining("positive");
        assertThatThrownBy(() -> RetentionPolicy.of("X").keep(Period.ofDays(-1))).hasMessageContaining("positive");
        assertThatThrownBy(() -> RetentionPolicy.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void holdsCoverEntriesByIdOrByFieldValue() {
        LegalHold byId = new LegalHold("h1", "Invoice", List.of("I-1", "I-2"), null, null);
        assertThat(byId.covers("Invoice", "I-2", Map.of())).isTrue();
        assertThat(byId.covers("Invoice", "I-3", Map.of())).isFalse();
        assertThat(byId.covers("Payment", "I-1", Map.of())).isFalse();

        LegalHold byVendor = new LegalHold("h2", "Invoice", List.of(), "vendorId", "V200");
        assertThat(byVendor.covers("Invoice", "I-9", Map.of("vendorId", "V200"))).isTrue();
        assertThat(byVendor.covers("Invoice", "I-9", Map.of("vendorId", "V201"))).isFalse();
        assertThat(byVendor.covers("Invoice", "I-9", Map.of())).isFalse();
        assertThat(byVendor.covers("Invoice", "I-9", null)).isFalse();

        LegalHold byAmount = new LegalHold("h3", "Invoice", null, "amount", "10");
        assertThat(byAmount.covers("Invoice", "I-1", Map.of("amount", new BigDecimal("10.00")))).isTrue();
        assertThat(byAmount.covers("Invoice", "I-1", Map.of("amount", 10L))).isTrue();
        assertThat(byAmount.covers("Invoice", "I-1", Map.of("amount", new BigDecimal("10.01")))).isFalse();
        assertThat(new LegalHold("h4", "Invoice", null, "note", null).covers("Invoice", "I", Map.of("note", "x")))
            .isFalse();

        assertThatThrownBy(() -> new LegalHold("h", "Invoice", List.of(), null, null))
            .hasMessageContaining("either ids or a field");
        assertThatThrownBy(() -> new LegalHold("h", "Invoice", List.of("1"), "f", "v"))
            .hasMessageContaining("either ids or a field");
    }
}
