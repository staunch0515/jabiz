package com.jabiz.quizbuks.fee;

import com.jabiz.entity.Violation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** F-M8 (docs/quizbuks/plans/impl/F-M8.md §3.1, §7): the exposure fee's arithmetic and price checks. */
class ExposureFeeRulesTest {

    private static final BigDecimal TEN_PERCENT = new BigDecimal("0.1");
    private static final ExposureFeeRules.Range RANGE = new ExposureFeeRules.Range(new BigDecimal("1"),
        new BigDecimal("100"));

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private static List<String> codes(List<Violation> problems) {
        return problems.stream().map(Violation::ruleCode).toList();
    }

    // ---- the price ---------------------------------------------------------------------------------------------------

    @Test
    void aPriceIsMoreThanZeroAndWithinTheRange() {
        assertThat(ExposureFeeRules.priceProblems(d("1"), RANGE)).isEmpty();
        assertThat(ExposureFeeRules.priceProblems(d("100"), RANGE)).isEmpty();
        assertThat(ExposureFeeRules.priceProblems(d("1.5000"), RANGE)).isEmpty();
        assertThat(codes(ExposureFeeRules.priceProblems(d("0.9999"), RANGE))).containsExactly(FeeCodes.EXPOSURE_PRICE_RANGE);
        assertThat(codes(ExposureFeeRules.priceProblems(d("100.0001"), RANGE))).containsExactly(FeeCodes.EXPOSURE_PRICE_RANGE);
        // Zero and below are refused whatever the range says: not setting a price is how an operator charges nothing.
        ExposureFeeRules.Range wide = new ExposureFeeRules.Range(d("0"), d("100"));
        assertThat(codes(ExposureFeeRules.priceProblems(d("0"), wide))).containsExactly(FeeCodes.EXPOSURE_PRICE_RANGE);
        assertThat(codes(ExposureFeeRules.priceProblems(d("-1"), wide))).containsExactly(FeeCodes.EXPOSURE_PRICE_RANGE);
        assertThat(codes(ExposureFeeRules.priceProblems(null, RANGE))).containsExactly(FeeCodes.EXPOSURE_PRICE_REQUIRED);
    }

    @Test
    void aRangeWithABoundMissingRefusesEveryPriceRatherThanLeavingItUnbounded() {
        assertThat(codes(ExposureFeeRules.priceProblems(d("5"), new ExposureFeeRules.Range(null, d("100")))))
            .containsExactly(FeeCodes.EXPOSURE_RANGE_MISSING);
        assertThat(codes(ExposureFeeRules.priceProblems(d("5"), new ExposureFeeRules.Range(d("1"), null))))
            .containsExactly(FeeCodes.EXPOSURE_RANGE_MISSING);
        assertThat(ExposureFeeRules.outOfRange(d("5"), new ExposureFeeRules.Range(null, null))).isTrue();
        assertThat(ExposureFeeRules.outOfRange(d("5"), RANGE)).isFalse();
        assertThat(ExposureFeeRules.outOfRange(d("500"), RANGE)).isTrue();
    }

    // ---- the deposit -------------------------------------------------------------------------------------------------

    @Test
    void theDepositIsWhatThePurchaseComesToRoundedUpWithTheTaxOnItRoundedDown() {
        assertThat(ExposureFeeRules.deposit(100, d("1"), TEN_PERCENT)).isEqualByComparingTo("110");
        // 105 at 1 yen: 105 and ⌊10.5⌋ = 10 tax.
        assertThat(ExposureFeeRules.deposit(105, d("1"), TEN_PERCENT)).isEqualByComparingTo("115");
        // 0.4 yen each, 7 of them: ⌈2.8⌉ = 3, tax ⌊0.3⌋ = 0.
        assertThat(ExposureFeeRules.deposit(7, d("0.4"), TEN_PERCENT)).isEqualByComparingTo("3");
        assertThat(ExposureFeeRules.deposit(0, d("1"), TEN_PERCENT)).isEqualByComparingTo("0");
        // A rate that cannot be used is no tax.
        assertThat(ExposureFeeRules.deposit(100, d("1"), null)).isEqualByComparingTo("100");
        assertThat(ExposureFeeRules.deposit(100, d("1"), d("1"))).isEqualByComparingTo("100");
    }

    @Test
    void aTopUpFreezesTheDifferenceOfTheCumulativeDepositsSoTheTotalIsAsIfBoughtAtOnce() {
        // The review's example (unit 1, tax 10 %): 105 first (115 frozen), then 5 more.
        BigDecimal first = ExposureFeeRules.deposit(105, d("1"), TEN_PERCENT);
        assertThat(first).isEqualByComparingTo("115");
        BigDecimal more = ExposureFeeRules.topUpDeposit(105, 5, d("1"), TEN_PERCENT, first);
        // Added up by the 5 alone it would be 5 and ⌊0.5⌋ = 5 (120 in all); by the cumulative total it is 121 - 115.
        assertThat(more).isEqualByComparingTo("6");
        assertThat(first.add(more)).isEqualByComparingTo(ExposureFeeRules.deposit(110, d("1"), TEN_PERCENT));
        // However the same total is split, the sum is the same.
        BigDecimal frozen = BigDecimal.ZERO;
        long bought = 0;
        for (long step : new long[] {30, 17, 1, 40, 22}) {
            frozen = frozen.add(ExposureFeeRules.topUpDeposit(bought, step, d("0.7"), TEN_PERCENT, frozen));
            bought += step;
        }
        assertThat(frozen).isEqualByComparingTo(ExposureFeeRules.deposit(110, d("0.7"), TEN_PERCENT));
        // Never negative (a price that fell would not give anything back through this).
        assertThat(ExposureFeeRules.topUpDeposit(10, 1, d("1"), TEN_PERCENT, d("1000"))).isEqualByComparingTo("0");
    }

    // ---- the settlement ----------------------------------------------------------------------------------------------

    @Test
    void theBillableIsTheSuccessfulOnesButNoMoreThanWereBoughtNeverNegative() {
        assertThat(ExposureFeeRules.billable(3, 2)).isEqualTo(2);
        assertThat(ExposureFeeRules.billable(1, 2)).isEqualTo(1);
        assertThat(ExposureFeeRules.billable(0, 0)).isZero();
        assertThat(ExposureFeeRules.billable(-4, 10)).isZero();
        assertThat(ExposureFeeRules.billable(4, -10)).isZero();
    }

    @Test
    void thePricesBelowAYenAreRoundedUpOnTheCumulativeCountNotEachMonth() {
        // 0.4 yen: one exposure a month for three months charges 1, 0, 1 (cumulative ⌈0.4⌉, ⌈0.8⌉, ⌈1.2⌉ = 1, 1, 2).
        BigDecimal chargedAmount = BigDecimal.ZERO;
        BigDecimal chargedTax = BigDecimal.ZERO;
        List<BigDecimal> amounts = new java.util.ArrayList<>();
        for (long toCount = 1; toCount <= 3; toCount++) {
            ExposureFeeRules.Settlement settlement = ExposureFeeRules.settle(toCount, d("0.4"), chargedAmount,
                chargedTax, TEN_PERCENT);
            amounts.add(settlement.amount());
            chargedAmount = chargedAmount.add(settlement.amount());
            chargedTax = chargedTax.add(settlement.tax());
        }
        assertThat(amounts).extracting(BigDecimal::intValue).containsExactly(1, 0, 1);
    }

    @Test
    void theTaxIsCumulativeSoUsingEveryExposureBoughtUsesTheDepositUpExactly() {
        // The review's example (unit 1, bought 100, deposit 110): month one 5 exposures, then the final 95.
        BigDecimal deposit = ExposureFeeRules.deposit(100, d("1"), TEN_PERCENT);
        ExposureFeeRules.Settlement one = ExposureFeeRules.settle(5, d("1"), BigDecimal.ZERO, BigDecimal.ZERO,
            TEN_PERCENT);
        assertThat(one.amount()).isEqualByComparingTo("5");
        assertThat(one.tax()).isEqualByComparingTo("0");
        ExposureFeeRules.Settlement fin = ExposureFeeRules.settle(100, d("1"), one.amount(), one.tax(), TEN_PERCENT);
        assertThat(fin.amount()).isEqualByComparingTo("95");
        // Per row the final would have been ⌊9.5⌋ = 9 (109 in all, 1 left); cumulatively it is ⌊10⌋ - 0 = 10.
        assertThat(fin.tax()).isEqualByComparingTo("10");
        assertThat(one.total().add(fin.total())).isEqualByComparingTo(deposit);
    }

    @Test
    void theCumulativeTaxCoversTheDepositForAnySplitOfTheMonths() {
        for (String unit : new String[] {"1", "0.4", "2.3333", "7.77", "13"}) {
            for (long bought : new long[] {1, 9, 100, 105, 999}) {
                BigDecimal deposit = ExposureFeeRules.deposit(bought, d(unit), TEN_PERCENT);
                BigDecimal amount = BigDecimal.ZERO;
                BigDecimal tax = BigDecimal.ZERO;
                BigDecimal total = BigDecimal.ZERO;
                for (long to : new long[] {bought / 3, bought / 2, bought}) {
                    ExposureFeeRules.Settlement s = ExposureFeeRules.settle(to, d(unit), amount, tax, TEN_PERCENT);
                    amount = amount.add(s.amount());
                    tax = tax.add(s.tax());
                    total = total.add(s.total());
                }
                // Charged in all what the deposit is, except a tax of an empty row that waits (never more than it).
                assertThat(total).as(unit + " x " + bought).isLessThanOrEqualTo(deposit);
                assertThat(deposit.subtract(total)).as(unit + " x " + bought).isLessThanOrEqualTo(BigDecimal.ONE);
            }
        }
    }

    @Test
    void aRaisedOrLoweredTaxRateAppliesToTheCumulativeAmountAndTakesNothingBack() {
        // 100 exposures at 1 yen, 50 settled at 10 % (5 tax), then the rate rises to 20 %: the rest charges the tax of
        // the whole at 20 % less the 5 already charged.
        ExposureFeeRules.Settlement first = ExposureFeeRules.settle(50, d("1"), BigDecimal.ZERO, BigDecimal.ZERO,
            TEN_PERCENT);
        assertThat(first.tax()).isEqualByComparingTo("5");
        ExposureFeeRules.Settlement up = ExposureFeeRules.settle(100, d("1"), first.amount(), first.tax(), d("0.2"));
        assertThat(up.amount()).isEqualByComparingTo("50");
        assertThat(up.tax()).isEqualByComparingTo("15");
        // Lowered to 5 %: the tax of the whole is 5, already charged: nothing more, and nothing is given back.
        ExposureFeeRules.Settlement down = ExposureFeeRules.settle(100, d("1"), first.amount(), first.tax(), d("0.05"));
        assertThat(down.amount()).isEqualByComparingTo("50");
        assertThat(down.tax()).isEqualByComparingTo("0");
    }

    @Test
    void aRowWithoutAnAmountChargesNoTaxEitherSoNothingIsChargedForNothing() {
        // The rate rose after the amount was charged and no new exposure came: no charge at all (the tax waits).
        ExposureFeeRules.Settlement first = ExposureFeeRules.settle(10, d("1"), BigDecimal.ZERO, BigDecimal.ZERO,
            TEN_PERCENT);
        ExposureFeeRules.Settlement none = ExposureFeeRules.settle(10, d("1"), first.amount(), first.tax(), d("0.5"));
        assertThat(none.amount()).isZero();
        assertThat(none.tax()).isZero();
        assertThat(none.total()).isZero();
    }

    @Test
    void theCumulativeCountNeverGoesBackAndAMissedMonthRunLateWritesNothing() {
        // Nothing yet: the month's successes up to the number bought.
        assertThat(ExposureFeeRules.toCount(0, false, 7, 10)).isEqualTo(7);
        assertThat(ExposureFeeRules.toCount(0, false, 15, 10)).isEqualTo(10);
        // Rows written already reach 8: a month with fewer successes by its end does not go below.
        assertThat(ExposureFeeRules.toCount(8, false, 5, 10)).isEqualTo(8);
        assertThat(ExposureFeeRules.toCount(8, false, 9, 10)).isEqualTo(9);
        // A later month's row exists (this month was missed and is run now): it adds nothing.
        assertThat(ExposureFeeRules.toCount(8, true, 5, 10)).isEqualTo(8);
        assertThat(ExposureFeeRules.toCount(8, true, 20, 10)).isEqualTo(8);
    }

    @Test
    void theReminderComesWhenWhatIsLeftIsAtOrBelowTheShareBoughtAndNeverWithoutOne() {
        assertThat(ExposureFeeRules.running(100, 90, d("0.1"))).isTrue();
        assertThat(ExposureFeeRules.running(100, 89, d("0.1"))).isFalse();
        assertThat(ExposureFeeRules.running(100, 100, d("0.1"))).isTrue();
        assertThat(ExposureFeeRules.running(100, 120, d("0.1"))).isTrue();
        assertThat(ExposureFeeRules.running(100, 99, d("0"))).isFalse();
        assertThat(ExposureFeeRules.running(100, 99, null)).isFalse();
        assertThat(ExposureFeeRules.running(0, 0, d("0.1"))).isFalse();
    }

    @Test
    void theUnitFeeRulesGiveTheDistributionFeeTheSameFiguresAsBefore() {
        assertThat(DistributionRules.cumulative(3, d("0.4"))).isEqualByComparingTo("2");
        assertThat(DistributionRules.cumulative(-3, d("0.4"))).isEqualByComparingTo("0");
        assertThat(UnitFeeRules.amount(d("5"), d("7"))).isEqualByComparingTo("0");
        assertThat(UnitFeeRules.amount(d("9"), null)).isEqualByComparingTo("9");
        DistributionRules.Settlement settlement = DistributionRules.settle(10, d("1.5"), d("4"), TEN_PERCENT);
        assertThat(settlement.cumulative()).isEqualByComparingTo("15");
        assertThat(settlement.amount()).isEqualByComparingTo("11");
        assertThat(settlement.tax()).isEqualByComparingTo("1");
    }
}
