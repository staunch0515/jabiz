package com.jabiz.finance.tax;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A jurisdiction's rates as a time line (FIN-TX-001 acceptance 2). */
class TaxRatesTest {

    private static final LocalDate JAN = LocalDate.of(2025, 1, 1);
    private static final LocalDate APR = LocalDate.of(2026, 4, 1);

    @Test
    void theFirstRateIsOpenEnded() {
        TaxRates.Plan plan = TaxRates.plan(List.of(), JAN, new BigDecimal("6.25"));
        assertThat(plan.insertNew()).isTrue();
        assertThat(plan.insertTo()).isNull();
        assertThat(plan.updates()).isEmpty();
    }

    @Test
    void aLaterRateEndsThePreviousOneTheDayBefore() {
        TaxRates.Stored current = new TaxRates.Stored("a", 1, JAN, null, new BigDecimal("6.25"));
        TaxRates.Plan plan = TaxRates.plan(List.of(current), APR, new BigDecimal("6.50"));
        assertThat(plan.insertNew()).isTrue();
        assertThat(plan.insertTo()).isNull();
        assertThat(plan.updates()).containsExactly(new TaxRates.Update("a", 1, LocalDate.of(2026, 3, 31),
            new BigDecimal("6.25")));
    }

    @Test
    void aRateBetweenTwoEndsBeforeTheNextAndEndsTheEarlier() {
        TaxRates.Stored first = new TaxRates.Stored("a", 2, JAN, LocalDate.of(2026, 3, 31), new BigDecimal("6.25"));
        TaxRates.Stored later = new TaxRates.Stored("b", 1, APR, null, new BigDecimal("6.50"));
        TaxRates.Plan plan = TaxRates.plan(List.of(later, first), LocalDate.of(2026, 1, 1), new BigDecimal("6.30"));
        assertThat(plan.insertTo()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(plan.updates()).containsExactly(new TaxRates.Update("a", 2, LocalDate.of(2025, 12, 31),
            new BigDecimal("6.25")));
    }

    @Test
    void theSameDateCorrectsTheRateOrChangesNothing() {
        TaxRates.Stored current = new TaxRates.Stored("a", 1, JAN, null, new BigDecimal("6.25"));
        assertThat(TaxRates.plan(List.of(current), JAN, new BigDecimal("6.2500")))
            .isEqualTo(new TaxRates.Plan(false, null, List.of()));
        assertThat(TaxRates.plan(List.of(current), JAN, new BigDecimal("6.3")).updates())
            .containsExactly(new TaxRates.Update("a", 1, null, new BigDecimal("6.3")));
    }
}
