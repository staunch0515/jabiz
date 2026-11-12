package com.jabiz.quizbuks.fee;

import com.jabiz.entity.Violation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** F-M6: the distribution fee's rules (docs/quizbuks/plans/impl/F-M.md §9.3). */
class DistributionRulesTest {

    static final BigDecimal TAX = new BigDecimal("0.10");

    static DistributionRules.Price price(String rate, String floor, String ceiling, long minimumDeposit) {
        return new DistributionRules.Price(new BigDecimal(rate), new BigDecimal(floor), new BigDecimal(ceiling),
            BigDecimal.valueOf(minimumDeposit));
    }

    static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    @Test
    void thePriceIsTheRateTimesTheHighestTierBetweenTheFloorAndTheCeiling() {
        DistributionRules.Price price = price("0.05", "1", "20", 500);
        // 0.05 × 100 = 5, within: not the floor.
        assertThat(DistributionRules.unit(price, d("100"))).isEqualTo(new DistributionRules.Unit(d("5.0000"), false));
        // 0.05 × 10 = 0.5: below the floor of 1.
        assertThat(DistributionRules.unit(price, d("10"))).isEqualTo(new DistributionRules.Unit(d("1.0000"), true));
        // Exactly the floor counts as the floor.
        assertThat(DistributionRules.unit(price, d("20")).atFloor()).isTrue();
        // 0.05 × 1000 = 50: the ceiling of 20.
        assertThat(DistributionRules.unit(price, d("1000"))).isEqualTo(new DistributionRules.Unit(d("20.0000"), false));
    }

    @Test
    void aHighestTierOfZeroOrNoTierPaysTheFloor() {
        DistributionRules.Price price = price("0.05", "1.5", "20", 500);
        assertThat(DistributionRules.unit(price, BigDecimal.ZERO)).isEqualTo(new DistributionRules.Unit(d("1.5000"), true));
        assertThat(DistributionRules.unit(price, null)).isEqualTo(new DistributionRules.Unit(d("1.5000"), true));
    }

    @Test
    void thePriceHasFourDecimalsHalfUp() {
        // 0.00333 × 15 = 0.04995 → 0.0500 (half up), above a floor of 0.
        DistributionRules.Price price = price("0.00333", "0", "20", 0);
        assertThat(DistributionRules.unit(price, d("15")).unit()).isEqualTo(d("0.0500"));
    }

    @Test
    void settlementsRoundTheCumulativeAmountSoEachStartIsChargedOnce() {
        BigDecimal unit = d("0.4000");
        // 1 start: ⌈0.4⌉ = 1; then 2 starts: ⌈0.8⌉ = 1, nothing more; then 3: ⌈1.2⌉ = 2, one more.
        DistributionRules.Settlement first = DistributionRules.settle(1, unit, BigDecimal.ZERO, TAX);
        assertThat(first.amount()).isEqualTo(d("1"));
        DistributionRules.Settlement second = DistributionRules.settle(2, unit, first.amount(), TAX);
        assertThat(second.amount()).isEqualByComparingTo("0");
        assertThat(second.tax()).isEqualByComparingTo("0");
        DistributionRules.Settlement third = DistributionRules.settle(3, unit, first.amount().add(second.amount()), TAX);
        assertThat(third.amount()).isEqualByComparingTo("1");
        assertThat(third.cumulative()).isEqualByComparingTo("2");
    }

    @Test
    void theTaxIsRoundedDown() {
        // 1 start at 9.99: ⌈9.99⌉ = 10, tax ⌊1.0⌋ = 1; 25 starts at 1: 25, tax ⌊2.5⌋ = 2.
        DistributionRules.Settlement one = DistributionRules.settle(1, d("9.9900"), BigDecimal.ZERO, TAX);
        assertThat(one.amount()).isEqualByComparingTo("10");
        assertThat(one.tax()).isEqualByComparingTo("1");
        assertThat(one.total()).isEqualByComparingTo("11");
        assertThat(DistributionRules.settle(25, d("1"), BigDecimal.ZERO, TAX).tax()).isEqualByComparingTo("2");
    }

    @Test
    void noStartsComeToNothing() {
        DistributionRules.Settlement none = DistributionRules.settle(0, d("3"), BigDecimal.ZERO, TAX);
        assertThat(none.amount()).isEqualByComparingTo("0");
        assertThat(none.total()).isEqualByComparingTo("0");
    }

    @Test
    void theDepositCoversTheCapOnTheWinnersWithTaxOrIsTheLeastDeposit() {
        // 30 winners at 0.4: ⌈12⌉ = 12, tax 1 → 13.
        assertThat(DistributionRules.deposit(30, d("0.4"), BigDecimal.valueOf(500), TAX)).isEqualByComparingTo("13");
        assertThat(DistributionRules.depositBeforeTax(30, d("0.4"), BigDecimal.valueOf(500))).isEqualByComparingTo("12");
        // No cap: the operator's least deposit, as it is (it is what is set aside, tax and all).
        assertThat(DistributionRules.deposit(null, d("0.4"), BigDecimal.valueOf(500), TAX)).isEqualByComparingTo("500");
        assertThat(DistributionRules.deposit(null, d("0.4"), null, TAX)).isEqualByComparingTo("0");
    }

    @Test
    void aPriceIsCheckedAgainstTheRange() {
        DistributionRules.Range range = new DistributionRules.Range(d("0"), d("1"), d("1"), d("100"), d("1000"),
            d("100000"));
        assertThat(DistributionRules.priceProblems(price("0.05", "1", "20", 500), range)).isEmpty();
        List<Violation> problems = DistributionRules.priceProblems(price("2", "0.5", "2000", 200_000), range);
        assertThat(problems).extracting(Violation::field)
            .containsExactlyInAnyOrder("rate", "unitFloor", "unitCeiling", "minimumDeposit");
        assertThat(problems).extracting(Violation::ruleCode).containsOnly(FeeCodes.DISTRIBUTION_PRICE_RANGE);
        assertThat(DistributionRules.priceProblems(price("0.05", "30", "20", 500), range))
            .extracting(Violation::ruleCode).containsExactly(FeeCodes.DISTRIBUTION_FLOOR_ABOVE_CEILING);
        assertThat(DistributionRules.priceProblems(price("-1", "1", "20", 500), range))
            .extracting(Violation::field).containsExactly("rate");
        // Review L2: a bound not published refuses every price, rather than leaving it unbounded.
        DistributionRules.Range partial = new DistributionRules.Range(d("0"), d("1"), d("1"), d("100"), d("1000"), null);
        assertThat(partial.complete()).isFalse();
        assertThat(DistributionRules.priceProblems(price("0.05", "1", "20", 500), partial))
            .extracting(Violation::ruleCode).containsExactly(FeeCodes.DISTRIBUTION_RANGE_MISSING);
        // Narrowed after it was saved: the daily check reports it.
        assertThat(DistributionRules.outOfRange(price("0.05", "1", "20", 500),
            new DistributionRules.Range(d("0.1"), d("1"), d("1"), d("100"), d("1000"), d("100000")))).isTrue();
    }
}
