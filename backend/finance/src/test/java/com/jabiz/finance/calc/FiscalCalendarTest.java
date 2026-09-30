package com.jabiz.finance.calc;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FiscalCalendarTest {

    /** FIN-PC-001 acceptance 1: the calendar year; 2026-01-31 belongs to period 2026-01. */
    @Test
    void theCalendarYearHasMonthlyPeriods() {
        List<FiscalCalendar.Period> periods = FiscalCalendar.periods(2026, LocalDate.of(2026, 1, 1), false);

        assertThat(periods).hasSize(12);
        assertThat(FiscalCalendar.periodOf(periods, LocalDate.of(2026, 1, 31), false)).get()
            .extracting(FiscalCalendar.Period::key).isEqualTo("2026-01");
        assertThat(periods.get(1).end()).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(periods.getLast().key()).isEqualTo("2026-12");
        assertThat(FiscalCalendar.periodOf(periods, LocalDate.of(2027, 1, 1), false)).isEmpty();
        assertThat(FiscalCalendar.periodOf(periods, LocalDate.of(2026, 12, 31), true)).isEmpty();
    }

    /** FIN-PC-001 acceptance 2: period 13 spans December and takes only entries that choose it. */
    @Test
    void theAdjustmentPeriodIsChosenExplicitly() {
        List<FiscalCalendar.Period> periods = FiscalCalendar.periods(2026, LocalDate.of(2026, 1, 1), true);

        assertThat(periods).hasSize(13);
        FiscalCalendar.Period thirteen = periods.getLast();
        assertThat(thirteen.adjustment()).isTrue();
        assertThat(thirteen.key()).isEqualTo("2026-13");
        assertThat(thirteen.start()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(FiscalCalendar.periodOf(periods, LocalDate.of(2026, 12, 31), false)).get()
            .extracting(FiscalCalendar.Period::number).isEqualTo(12);
        assertThat(FiscalCalendar.periodOf(periods, LocalDate.of(2026, 12, 31), true)).get()
            .extracting(FiscalCalendar.Period::number).isEqualTo(13);
        assertThat(FiscalCalendar.periodOf(periods, LocalDate.of(2026, 11, 30), true)).isEmpty();
    }

    @Test
    void aFiscalYearIsNamedAfterItsEnd() {
        List<FiscalCalendar.Period> periods = FiscalCalendar.periods(2027, LocalDate.of(2026, 7, 1), false);
        assertThat(periods.getFirst().key()).isEqualTo("2027-01");
        assertThat(periods.getLast().end()).isEqualTo(LocalDate.of(2027, 6, 30));
        assertThatThrownBy(() -> FiscalCalendar.periods(2026, LocalDate.of(2026, 7, 1), false))
            .hasMessageContaining("ends in 2027");
        assertThatThrownBy(() -> FiscalCalendar.periods(2026, LocalDate.of(2026, 1, 2), false))
            .hasMessageContaining("first of a month");
        assertThat(FiscalCalendar.display(LocalDate.of(2026, 1, 31))).isEqualTo("Jan 31, 2026");
        assertThatThrownBy(() -> new FiscalCalendar.Period(2026, 14, LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 31))).isInstanceOf(IllegalArgumentException.class);
    }

    /** Every day of a year falls into exactly one regular period, and the periods follow each other. */
    @Property
    void theRegularPeriodsCoverTheYear(@ForAll @IntRange(min = 2000, max = 2100) int year,
        @ForAll @IntRange(min = 1, max = 12) int startMonth, @ForAll @IntRange(min = 0, max = 364) int dayOfYear) {
        LocalDate start = LocalDate.of(startMonth == 1 ? year : year - 1, startMonth, 1);
        List<FiscalCalendar.Period> periods = FiscalCalendar.periods(year, start, true);
        for (int i = 1; i < 12; i++) {
            assertThat(periods.get(i).start()).isEqualTo(periods.get(i - 1).end().plusDays(1));
        }
        LocalDate day = start.plusDays(dayOfYear);
        assertThat(periods.stream().filter(p -> !p.adjustment() && p.contains(day))).hasSize(1);
    }
}
