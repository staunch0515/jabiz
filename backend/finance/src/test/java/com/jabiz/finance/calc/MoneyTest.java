package com.jabiz.finance.calc;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void roundsHalfAwayFromZero() {
        assertThat(Money.usd(new BigDecimal("1.005"))).isEqualTo("1.01");
        assertThat(Money.usd(new BigDecimal("-1.005"))).isEqualTo("-1.01");
        assertThat(Money.usd(new BigDecimal("1.0049"))).isEqualTo("1.00");
        assertThat(Money.usd(new BigDecimal("7"))).isEqualTo("7.00");
        assertThat(Money.round(new BigDecimal("2.5"), 0)).isEqualTo("3");
        assertThat(Money.round(new BigDecimal("-2.5"), 0)).isEqualTo("-3");
        assertThatThrownBy(() -> Money.round(BigDecimal.ONE, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Money.fits(new BigDecimal("1.10"), 1)).isTrue();
        assertThat(Money.fits(new BigDecimal("1.001"), 2)).isFalse();
    }

    @Test
    void allocationPutsTheOddCentOnTheLastLine() {
        assertThat(Money.allocate(new BigDecimal("100.00"), weights("1", "1", "1"), 2))
            .containsExactly(new BigDecimal("33.33"), new BigDecimal("33.33"), new BigDecimal("33.34"));
        assertThat(Money.allocate(new BigDecimal("-100.00"), weights("1", "1", "1"), 2))
            .containsExactly(new BigDecimal("-33.33"), new BigDecimal("-33.33"), new BigDecimal("-33.34"));
        assertThat(Money.allocate(new BigDecimal("10.00"), weights("2", "0", "1"), 2))
            .containsExactly(new BigDecimal("6.67"), new BigDecimal("0.00"), new BigDecimal("3.33"));
        assertThatThrownBy(() -> Money.allocate(new BigDecimal("1.001"), weights("1"), 2))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.allocate(BigDecimal.ONE, weights("0", "0"), 2))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.allocate(BigDecimal.ONE, weights("-1", "2"), 2))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** The shares add up to the total and none is more than a cent from its exact share. */
    @Property
    void allocationIsExactAndFair(@ForAll @BigRange(min = "-1000000", max = "1000000") BigDecimal amount,
        @ForAll @Size(min = 1, max = 12) List<@IntRange(min = 0, max = 1000) Integer> raw) {
        List<BigDecimal> weights = raw.stream().map(BigDecimal::valueOf).toList();
        if (weights.stream().allMatch(w -> w.signum() == 0)) {
            return;
        }
        BigDecimal total = Money.usd(amount);
        List<BigDecimal> shares = Money.allocate(total, weights, 2);
        assertThat(shares.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(total);
        BigDecimal sum = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        for (int i = 0; i < shares.size(); i++) {
            BigDecimal exact = total.multiply(weights.get(i)).divide(sum, 12, java.math.RoundingMode.HALF_UP);
            assertThat(shares.get(i).subtract(exact).abs()).isLessThan(new BigDecimal("0.01"));
            assertThat(shares.get(i).scale()).isEqualTo(2);
        }
    }

    @Property
    void roundingIsSymmetric(@ForAll @BigRange(min = "-100000", max = "100000") BigDecimal amount) {
        assertThat(Money.usd(amount.negate())).isEqualTo(Money.usd(amount).negate());
        assertThat(Money.usd(amount).subtract(amount).abs()).isLessThanOrEqualTo(new BigDecimal("0.005"));
    }

    private static List<BigDecimal> weights(String... values) {
        return java.util.Arrays.stream(values).map(BigDecimal::new).toList();
    }
}
