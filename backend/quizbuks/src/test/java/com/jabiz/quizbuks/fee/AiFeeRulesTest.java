package com.jabiz.quizbuks.fee;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** F-M9 (docs/quizbuks/plans/impl/F-M9.md §3): the AI fee's arithmetic, in yen, without the database. */
class AiFeeRulesTest {

    private static final BigDecimal MARKUP = new BigDecimal("1.50");
    private static final BigDecimal RATE = new BigDecimal("150.00");
    private static final BigDecimal TAX = new BigDecimal("0.10");

    private static BigDecimal yen(long value) {
        return BigDecimal.valueOf(value);
    }

    @Test
    void theFeeIsTheCostTimesTheMarkupAndTheRateRoundedUpToTheYen() {
        // $0.123456 x 1.5 x 150 = 27.7776 -> 28.
        assertThat(AiFeeRules.fee(new BigDecimal("0.123456"), MARKUP, RATE)).isEqualByComparingTo("28");
        // A whole number stays as it is.
        assertThat(AiFeeRules.fee(new BigDecimal("1"), MARKUP, RATE)).isEqualByComparingTo("225");
        // The smallest usage is a yen; none is nothing.
        assertThat(AiFeeRules.fee(new BigDecimal("0.000001"), MARKUP, RATE)).isEqualByComparingTo("1");
        assertThat(AiFeeRules.fee(BigDecimal.ZERO, MARKUP, RATE)).isEqualByComparingTo("0");
        assertThat(AiFeeRules.fee(null, MARKUP, RATE)).isEqualByComparingTo("0");
    }

    @Test
    void theCostPartIsTheDollarsAtTheRateRoundedUpWithoutTheMarkup() {
        // For the operators' share (F-M12 §3.2): $0.123456 x 150 = 18.5184 -> 19.
        assertThat(AiFeeRules.costPart(new BigDecimal("0.123456"), RATE)).isEqualByComparingTo("19");
        assertThat(AiFeeRules.costPart(BigDecimal.ZERO, RATE)).isEqualByComparingTo("0");
    }

    @Test
    void theTaxIsAddedRoundedDown() {
        assertThat(AiFeeRules.withTax(yen(28), TAX)).isEqualByComparingTo("30");
        assertThat(AiFeeRules.withTax(yen(9), TAX)).isEqualByComparingTo("9");
        assertThat(AiFeeRules.withTax(yen(0), TAX)).isEqualByComparingTo("0");
    }

    @Test
    void theReservationIsTheMostItMayCostWithItsTax() {
        // $2 at most: 2 x 1.5 x 150 = 450, with its 45 of tax.
        assertThat(AiFeeRules.reservation(new BigDecimal("2"), MARKUP, RATE, TAX)).isEqualByComparingTo("495");
    }

    @Test
    void theIncrementOfAJobIsWhatItAddsToItsGroupsTotalWithTax() {
        // Nothing before: 9 then 9 more. 9 has no tax (0.9 rounds down), 18 has 1.
        assertThat(AiFeeRules.increment(yen(0), yen(9), TAX)).isEqualByComparingTo("9");
        assertThat(AiFeeRules.increment(yen(9), yen(9), TAX)).isEqualByComparingTo("10");
        assertThat(AiFeeRules.increment(yen(5), yen(0), TAX)).isEqualByComparingTo("0");
    }

    @Test
    void theUnbilledTotalIsEachGroupsTotalWithItsTax() {
        // Two groups (operator, month): 18 -> 19, and 9 -> 9.
        assertThat(AiFeeRules.unbilledWithTax(List.of(yen(18), yen(9)), TAX)).isEqualByComparingTo("28");
        assertThat(AiFeeRules.unbilledWithTax(List.of(), TAX)).isEqualByComparingTo("0");
    }

    @Test
    void aJobWithinWhatItMayUseIsChargedItAll() {
        AiFeeRules.Settled settled = AiFeeRules.settle(yen(0), yen(28), yen(495), TAX);
        assertThat(settled.fee()).isEqualByComparingTo("28");
        assertThat(settled.absorbed()).isEqualByComparingTo("0");
        assertThat(settled.withTax()).isEqualByComparingTo("30");
    }

    @Test
    void aJobAboveWhatItMayUseIsChargedTheMostThatFitsAndThePlatformTakesTheRest() {
        // 28 would take 30 with its tax; only 25 may be used: 23 before tax takes 25 (23 + 2), 24 would take 26.
        AiFeeRules.Settled settled = AiFeeRules.settle(yen(0), yen(28), yen(25), TAX);
        assertThat(settled.fee()).isEqualByComparingTo("23");
        assertThat(settled.absorbed()).isEqualByComparingTo("5");
        assertThat(settled.withTax()).isEqualByComparingTo("25");
        // Nothing may be used: nothing is charged, all is taken on.
        AiFeeRules.Settled none = AiFeeRules.settle(yen(40), yen(28), yen(0), TAX);
        assertThat(none.fee()).isEqualByComparingTo("0");
        assertThat(none.absorbed()).isEqualByComparingTo("28");
    }

    /**
     * Review H2 (a): the jobs of a group, settled one after another with each one's increment, take exactly what the group
     * comes to when charged at once, whatever the order and the sizes (no rounding difference builds up).
     */
    @Property
    void theIncrementsOfAGroupAddUpToItsTotalWithTax(@ForAll @Size(max = 40) List<@IntRange(max = 500) Integer> fees,
        @ForAll @IntRange(max = 20) int taxPercent) {
        BigDecimal rate = BigDecimal.valueOf(taxPercent, 2);
        BigDecimal before = BigDecimal.ZERO;
        BigDecimal taken = BigDecimal.ZERO;
        for (int fee : fees) {
            taken = taken.add(AiFeeRules.increment(before, yen(fee), rate));
            before = before.add(yen(fee));
        }
        assertThat(taken).isEqualByComparingTo(AiFeeRules.withTax(before, rate));
    }

    /** The fee that fits is the largest whose increment is within the room, and never above the fee asked. */
    @Property
    void theFeeThatFitsIsTheLargestWithinTheRoom(@ForAll @IntRange(max = 1_000) int before,
        @ForAll @IntRange(max = 1_000) int fee, @ForAll @IntRange(max = 1_200) int room,
        @ForAll @IntRange(max = 20) int taxPercent) {
        BigDecimal rate = BigDecimal.valueOf(taxPercent, 2);
        AiFeeRules.Settled settled = AiFeeRules.settle(yen(before), yen(fee), yen(room), rate);
        assertThat(settled.fee().add(settled.absorbed())).isEqualByComparingTo(yen(fee));
        assertThat(settled.fee()).isBetween(BigDecimal.ZERO, yen(fee));
        assertThat(settled.withTax()).isEqualByComparingTo(AiFeeRules.increment(yen(before), settled.fee(), rate));
        assertThat(settled.withTax()).isLessThanOrEqualTo(yen(room).max(BigDecimal.ZERO));
        if (settled.fee().compareTo(yen(fee)) < 0) {
            assertThat(AiFeeRules.increment(yen(before), settled.fee().add(BigDecimal.ONE), rate))
                .isGreaterThan(yen(room));
        }
    }

    @Test
    void whatMayStillBeUsedIsTheRemainingLessTheUnbilledAndTheReservations() {
        assertThat(AiFeeRules.available(yen(1_000), yen(30), yen(495))).isEqualByComparingTo("475");
        // Never less than nothing.
        assertThat(AiFeeRules.available(yen(100), yen(30), yen(495))).isEqualByComparingTo("0");
    }
}
