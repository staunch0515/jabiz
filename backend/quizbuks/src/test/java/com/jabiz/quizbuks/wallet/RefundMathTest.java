package com.jabiz.quizbuks.wallet;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class RefundMathTest {

    static final BigDecimal AMOUNT = new BigDecimal("5000");

    static BigDecimal d(long value) {
        return BigDecimal.valueOf(value);
    }

    @Test
    void takesBackThePartNotTakenBackBefore() {
        RefundMath.Plan first = RefundMath.refund(AMOUNT, BigDecimal.ZERO, BigDecimal.ZERO, d(1000), d(5000));
        assertThat(first.kind()).isEqualTo(RefundMath.Kind.CLAW_BACK);
        assertThat(first.amount()).isEqualByComparingTo("1000");
        // The running total grew to the full amount: the rest.
        RefundMath.Plan second = RefundMath.refund(AMOUNT, d(1000), BigDecimal.ZERO, d(5000), d(4000));
        assertThat(second.kind()).isEqualTo(RefundMath.Kind.CLAW_BACK);
        assertThat(second.amount()).isEqualByComparingTo("4000");
        // The same event again, or an older one late: nothing.
        assertThat(RefundMath.refund(AMOUNT, d(5000), BigDecimal.ZERO, d(5000), d(0)).kind())
            .isEqualTo(RefundMath.Kind.NOTHING);
        assertThat(RefundMath.refund(AMOUNT, d(5000), BigDecimal.ZERO, d(1000), d(0)).kind())
            .isEqualTo(RefundMath.Kind.NOTHING);
    }

    @Test
    void neverTakesTheWalletBelowZero() {
        RefundMath.Plan plan = RefundMath.refund(AMOUNT, BigDecimal.ZERO, BigDecimal.ZERO, d(3000), d(2999));
        assertThat(plan.kind()).isEqualTo(RefundMath.Kind.SHORTFALL);
        assertThat(plan.amount()).isEqualByComparingTo("3000");
        assertThat(plan.reason()).isEqualTo(RefundMath.REFUND_SHORTFALL);
    }

    @Test
    void refusesWhatIsNotBelievable() {
        assertThat(RefundMath.refund(AMOUNT, BigDecimal.ZERO, BigDecimal.ZERO, d(5001), d(9999)).reason())
            .isEqualTo(RefundMath.OVER_REFUND);
        assertThat(RefundMath.refund(AMOUNT, BigDecimal.ZERO, d(4000), d(1001), d(9999)).reason())
            .isEqualTo(RefundMath.OVER_REFUND);
        assertThat(RefundMath.refund(AMOUNT, BigDecimal.ZERO, BigDecimal.ZERO, null, d(9999)).reason())
            .isEqualTo(RefundMath.INVALID_AMOUNT);
        assertThat(RefundMath.refund(AMOUNT, BigDecimal.ZERO, BigDecimal.ZERO, d(-1), d(9999)).reason())
            .isEqualTo(RefundMath.INVALID_AMOUNT);
        assertThat(RefundMath.refund(AMOUNT, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("10.5"), d(9999))
            .reason()).isEqualTo(RefundMath.INVALID_AMOUNT);
    }

    @Test
    void aLostDisputeIsTakenBackOnceAsARefundIs() {
        RefundMath.Plan plan = RefundMath.disputeLost(AMOUNT, d(1000), BigDecimal.ZERO, d(4000), d(4000));
        assertThat(plan.kind()).isEqualTo(RefundMath.Kind.CLAW_BACK);
        assertThat(plan.amount()).isEqualByComparingTo("4000");
        assertThat(RefundMath.disputeLost(AMOUNT, d(1000), BigDecimal.ZERO, d(4000), d(3999)).reason())
            .isEqualTo(RefundMath.DISPUTE_SHORTFALL);
        assertThat(RefundMath.disputeLost(AMOUNT, d(1001), BigDecimal.ZERO, d(4000), d(9999)).reason())
            .isEqualTo(RefundMath.OVER_REFUND);
        assertThat(RefundMath.disputeLost(AMOUNT, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, d(0)).kind())
            .isEqualTo(RefundMath.Kind.NOTHING);
    }

    /**
     * Whatever order the running totals arrive in, and however often each arrives, what is taken back in all is the
     * highest total, and never more than the top-up's amount; with enough balance nothing is left short.
     */
    @Property
    void repeatedAndReorderedTotalsTakeBackEachYenOnce(
        @ForAll @Size(min = 1, max = 8) List<@IntRange(min = 1, max = 5000) Integer> totals,
        @ForAll @LongRange(min = 0, max = 1000) long seed) {
        List<Integer> sorted = new ArrayList<>(totals);
        Collections.sort(sorted);
        List<Integer> events = new ArrayList<>(sorted);
        events.addAll(sorted);
        Collections.shuffle(events, new Random(seed));
        BigDecimal refunded = BigDecimal.ZERO;
        BigDecimal balance = d(10_000);
        for (int total : events) {
            RefundMath.Plan plan = RefundMath.refund(AMOUNT, refunded, BigDecimal.ZERO, d(total), balance);
            if (plan.kind() == RefundMath.Kind.CLAW_BACK) {
                refunded = refunded.add(plan.amount());
                balance = balance.subtract(plan.amount());
            }
            assertThat(plan.kind()).isNotIn(RefundMath.Kind.SHORTFALL, RefundMath.Kind.CHECK);
        }
        assertThat(refunded).isEqualByComparingTo(d(sorted.getLast()));
        assertThat(refunded).isLessThanOrEqualTo(AMOUNT);
        assertThat(balance.add(refunded)).isEqualByComparingTo(d(10_000));
    }

    /** A plan never takes back more than the balance holds. */
    @Property
    void neverMoreThanTheBalance(@ForAll @IntRange(min = 0, max = 5000) int total,
        @ForAll @IntRange(min = 0, max = 5000) int before, @ForAll @IntRange(min = 0, max = 6000) int balance) {
        RefundMath.Plan plan = RefundMath.refund(AMOUNT, d(Math.min(before, total)), BigDecimal.ZERO, d(total),
            d(balance));
        if (plan.kind() == RefundMath.Kind.CLAW_BACK) {
            assertThat(plan.amount()).isLessThanOrEqualTo(d(balance)).isPositive();
        }
    }
}
