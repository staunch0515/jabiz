package com.jabiz.finance.calc;

import com.jabiz.finance.calc.Depreciation.Convention;
import com.jabiz.finance.calc.Depreciation.Method;
import com.jabiz.finance.calc.Depreciation.Month;
import com.jabiz.finance.calc.Depreciation.Terms;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DepreciationTest {

    private static final Terms FA_001 = new Terms(new BigDecimal("120000.00"), null, LocalDate.of(2024, 1, 1), 60,
        Method.SL, Convention.FULL_MONTH);
    private static final Terms FA_002 = new Terms(new BigDecimal("70000.00"), null, LocalDate.of(2025, 7, 1), 84,
        Method.DDB, Convention.FULL_MONTH);
    private static final Terms FA_003 = new Terms(new BigDecimal("12000.00"), null, LocalDate.of(2026, 1, 15), 36,
        Method.SL, Convention.FULL_MONTH);

    private static BigDecimal in(List<Month> months, YearMonth period) {
        return months.stream().filter(m -> m.period().equals(period)).findFirst().orElseThrow().amount();
    }

    private static BigDecimal total(List<Month> months) {
        return months.stream().map(Month::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void fa001IsDepreciated2000AMonthFromItsCutover() {
        // FIN-FA-003 acceptance 1; FIN-EXP-11.
        List<Month> months = Depreciation.scheduleFrom(FA_001, Depreciation.opening(FA_001, YearMonth.of(2026, 1),
            new BigDecimal("48000.00")));
        assertThat(months.getFirst().period()).isEqualTo(YearMonth.of(2026, 1));
        assertThat(months).allSatisfy(m -> assertThat(m.amount()).isEqualByComparingTo("2000.00"));
        assertThat(months.getFirst().accumulated()).isEqualByComparingTo("50000.00");
        assertThat(months.getFirst().netBookValue()).isEqualByComparingTo("70000.00");
        assertThat(months).hasSize(36);
    }

    @Test
    void fa002TakesItsAssetYearEvenlyAndMakesGoodTheCutoverInJune() {
        // FIN-FA-003 acceptance 2: double-declining by asset year (2025-07 … 2026-06), 20,000.00 in year 1.
        List<Month> months = Depreciation.scheduleFrom(FA_002, Depreciation.opening(FA_002, YearMonth.of(2026, 1),
            new BigDecimal("10000.00")));
        assertThat(in(months, YearMonth.of(2026, 1))).isEqualByComparingTo("1666.67");
        assertThat(months.getFirst().netBookValue()).isEqualByComparingTo("58333.33");
        // The year's last month takes what rounding and the opening left: the year is 20,000.00 to the cent.
        assertThat(in(months, YearMonth.of(2026, 6))).isEqualByComparingTo("1666.65");
        assertThat(total(months.subList(0, 6))).isEqualByComparingTo("10000.00");
        // Year 2: 50,000.00 × 2 / 7 years.
        assertThat(total(months.subList(6, 18))).isEqualByComparingTo("14285.71");
        assertThat(total(months)).isEqualByComparingTo("60000.00");
    }

    @Test
    void fa003IsDepreciatedAFullMonthInJanuaryAndExactly12000OverItsLife() {
        // FIN-FA-004 acceptance 1 and 2.
        List<Month> months = Depreciation.schedule(FA_003);
        assertThat(months.getFirst().period()).isEqualTo(YearMonth.of(2026, 1));
        assertThat(months.getFirst().amount()).isEqualByComparingTo("333.33");
        assertThat(months).hasSize(36);
        assertThat(total(months)).isEqualByComparingTo("12000.00");
        // The residue in each asset year's last month.
        assertThat(in(months, YearMonth.of(2026, 12))).isEqualByComparingTo("333.37");
        assertThat(total(months.subList(0, 12))).isEqualByComparingTo("4000.00");
    }

    @Test
    void aLongerLifeAppliesFromItsMonthOn() {
        // FIN-FA-006 acceptance 1: FA-001 extended by 12 months from February: 70,000.00 / 47 months.
        Terms longer = new Terms(FA_001.cost(), null, FA_001.inService(), 72, Method.SL, Convention.FULL_MONTH);
        List<Month> months = Depreciation.scheduleFrom(longer, Depreciation.restart(YearMonth.of(2026, 2),
            new BigDecimal("50000.00")));
        assertThat(months.getFirst().amount()).isEqualByComparingTo("1489.36");
        assertThat(months).hasSize(47);
        assertThat(total(months)).isEqualByComparingTo("70000.00");
    }

    @Test
    void decliningBalanceSwitchesToStraightLineWhenThatGivesMore() {
        // FIN-FA-003 acceptance 3. Double-declining 10,000.00 over 5 years: 4,000, 2,400, 1,440, then straight-line
        // 1,080 twice (2,160 / 2 > 2,160 × 40%).
        Terms ddb = new Terms(new BigDecimal("10000.00"), null, LocalDate.of(2026, 1, 1), 60, Method.DDB,
            Convention.FULL_MONTH);
        List<Month> months = Depreciation.schedule(ddb);
        assertThat(List.of(total(months.subList(0, 12)), total(months.subList(12, 24)), total(months.subList(24, 36)),
            total(months.subList(36, 48)), total(months.subList(48, 60))))
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactly(new BigDecimal("4000"), new BigDecimal("2400"), new BigDecimal("1440"),
                new BigDecimal("1080"), new BigDecimal("1080"));
        // 150%: 3,000, 2,100, then straight-line on what is left each year: 1,633.33, 1,633.34 and the rest.
        Terms db150 = new Terms(new BigDecimal("10000.00"), null, LocalDate.of(2026, 1, 1), 60, Method.DB150,
            Convention.FULL_MONTH);
        List<Month> slower = Depreciation.schedule(db150);
        assertThat(total(slower.subList(0, 12))).isEqualByComparingTo("3000.00");
        assertThat(total(slower.subList(12, 24))).isEqualByComparingTo("2100.00");
        assertThat(total(slower.subList(24, 36))).isEqualByComparingTo("1633.33");
        assertThat(total(slower.subList(36, 48))).isEqualByComparingTo("1633.34");
        assertThat(total(slower.subList(48, 60))).isEqualByComparingTo("1633.33");
        assertThat(total(slower)).isEqualByComparingTo("10000.00");
    }

    @Test
    void decliningBalanceStopsAtTheSalvageValue() {
        Terms truck = new Terms(new BigDecimal("50000.00"), new BigDecimal("10000.00"), LocalDate.of(2026, 1, 1), 60,
            Method.DDB, Convention.FULL_MONTH);
        List<Month> months = Depreciation.schedule(truck);
        assertThat(total(months)).isEqualByComparingTo("40000.00");
        assertThat(months.getLast().netBookValue()).isEqualByComparingTo("10000.00");
    }

    @Test
    void theConventionsDecideTheFirstAndLastMonths() {
        LocalDate placed = LocalDate.of(2026, 1, 20);
        List<Month> mid = Depreciation.schedule(new Terms(new BigDecimal("1200.00"), null, placed, 12, Method.SL,
            Convention.MID_MONTH));
        // Half a month at either end: 13 months, 50.00 first and last.
        assertThat(mid).hasSize(13);
        assertThat(mid.getFirst().amount()).isEqualByComparingTo("50.00");
        assertThat(mid.get(1).amount()).isEqualByComparingTo("100.00");
        assertThat(mid.getLast().period()).isEqualTo(YearMonth.of(2027, 1));
        assertThat(mid.getLast().amount()).isEqualByComparingTo("50.00");
        List<Month> next = Depreciation.schedule(new Terms(new BigDecimal("1200.00"), null, placed, 12, Method.SL,
            Convention.NEXT_MONTH));
        assertThat(next.getFirst().period()).isEqualTo(YearMonth.of(2026, 2));
        assertThat(next.getLast().period()).isEqualTo(YearMonth.of(2027, 1));
        assertThat(Depreciation.disposalShare(Convention.FULL_MONTH)).isZero();
        assertThat(Depreciation.disposalShare(Convention.MID_MONTH)).isEqualByComparingTo("0.5");
        assertThat(Depreciation.disposalShare(Convention.NEXT_MONTH)).isEqualByComparingTo("1");
    }

    @Test
    void aScheduleAfterTheLifeOrFullyDepreciatedIsEmpty() {
        assertThat(Depreciation.scheduleFrom(FA_003, Depreciation.restart(YearMonth.of(2029, 1), BigDecimal.ZERO)))
            .isEmpty();
        assertThat(Depreciation.scheduleFrom(FA_003, Depreciation.restart(YearMonth.of(2026, 5),
            new BigDecimal("12000.00")))).isEmpty();
        // Before the first month, from the first month.
        assertThat(Depreciation.scheduleFrom(FA_003, Depreciation.restart(YearMonth.of(2025, 11), BigDecimal.ZERO)))
            .hasSize(36);
    }

    @Test
    void unitsOfProductionDepreciateByUse() {
        assertThat(Depreciation.byUse(new BigDecimal("90000.00"), new BigDecimal("300000"), new BigDecimal("1000")))
            .isEqualByComparingTo("300.00");
        // The last units take what is left.
        assertThat(Depreciation.byUse(new BigDecimal("123.45"), new BigDecimal("10"), new BigDecimal("12")))
            .isEqualByComparingTo("123.45");
        assertThat(Depreciation.byUse(new BigDecimal("123.45"), new BigDecimal("10"), BigDecimal.ZERO)).isZero();
        assertThatThrownBy(() -> Depreciation.schedule(new Terms(BigDecimal.TEN, null, LocalDate.of(2026, 1, 1), 12,
            Method.UOP, Convention.FULL_MONTH))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void termsOutOfRangeAreRefused() {
        assertThatThrownBy(() -> new Terms(BigDecimal.ZERO, null, LocalDate.of(2026, 1, 1), 12, Method.SL,
            Convention.FULL_MONTH)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Terms(BigDecimal.TEN, new BigDecimal("11"), LocalDate.of(2026, 1, 1), 12,
            Method.SL, Convention.FULL_MONTH)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Terms(BigDecimal.TEN, null, LocalDate.of(2026, 1, 1), 0, Method.SL,
            Convention.FULL_MONTH)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Terms terms(long costCents, int salvagePercent, int life, int method, int convention) {
        BigDecimal cost = BigDecimal.valueOf(costCents, 2);
        BigDecimal salvage = Money.usd(cost.multiply(BigDecimal.valueOf(salvagePercent)).movePointLeft(2));
        return new Terms(cost, salvage, LocalDate.of(2025, 3, 17), life,
            List.of(Method.SL, Method.DDB, Method.DB150).get(method), Convention.values()[convention]);
    }

    @Property
    void theWholeLifeComesToCostLessSalvage(@ForAll @LongRange(min = 1, max = 100_000_000_00L) long costCents,
        @ForAll @IntRange(min = 0, max = 50) int salvagePercent, @ForAll @IntRange(min = 1, max = 480) int life,
        @ForAll @IntRange(min = 0, max = 2) int method, @ForAll @IntRange(min = 0, max = 2) int convention) {
        // FIN-FA-004: rounded per month, never more nor less than cost less salvage in all.
        Terms terms = terms(costCents, salvagePercent, life, method, convention);
        List<Month> months = Depreciation.schedule(terms);
        if (terms.depreciable().signum() == 0) {
            assertThat(months).isEmpty();
            return;
        }
        assertThat(total(months)).isEqualByComparingTo(terms.depreciable());
        assertThat(months).allSatisfy(m -> {
            assertThat(m.amount().signum()).isGreaterThanOrEqualTo(0);
            assertThat(m.amount().scale()).isLessThanOrEqualTo(2);
            assertThat(m.netBookValue()).isGreaterThanOrEqualTo(terms.salvage());
        });
        assertThat(months.getLast().period()).isEqualTo(terms.lastMonth());
    }

    @Property
    void startingAgainAnywhereStillComesToTheRest(@ForAll @LongRange(min = 1, max = 10_000_000_00L) long costCents,
        @ForAll @IntRange(min = 0, max = 30) int salvagePercent, @ForAll @IntRange(min = 2, max = 240) int life,
        @ForAll @IntRange(min = 0, max = 2) int method, @ForAll @IntRange(min = 0, max = 2) int convention,
        @ForAll @IntRange(min = 1, max = 240) int at) {
        Terms terms = terms(costCents, salvagePercent, life, method, convention);
        List<Month> plan = Depreciation.schedule(terms);
        int split = Math.min(at, plan.size() - 1);
        BigDecimal before = plan.get(split - 1).accumulated();
        YearMonth period = plan.get(split).period();
        if (before.compareTo(terms.depreciable()) >= 0) {
            // Nothing left to depreciate: nothing more is planned.
            assertThat(Depreciation.scheduleFrom(terms, Depreciation.opening(terms, period, before))).isEmpty();
            return;
        }
        // Brought over at a cutover with the planned accumulated: exactly the plan from there.
        List<Month> opened = Depreciation.scheduleFrom(terms, Depreciation.opening(terms, period, before));
        assertThat(opened).extracting(Month::amount).usingElementComparator(BigDecimal::compareTo)
            .containsExactlyElementsOf(plan.subList(split, plan.size()).stream().map(Month::amount).toList());
        // Restarted (a change in estimate): a different spread, the same total.
        List<Month> restarted = Depreciation.scheduleFrom(terms, Depreciation.restart(period, before));
        assertThat(total(restarted)).isEqualByComparingTo(terms.depreciable().subtract(before));
        assertThat(restarted).allSatisfy(m -> assertThat(m.amount().signum()).isGreaterThanOrEqualTo(0));
    }
}
