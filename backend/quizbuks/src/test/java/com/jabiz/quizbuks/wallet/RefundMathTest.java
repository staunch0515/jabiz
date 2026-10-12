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
    static final BigDecimal ZERO = BigDecimal.ZERO;

    static BigDecimal d(long value) {
        return BigDecimal.valueOf(value);
    }

    /** What the top-up recorded so far. */
    static RefundMath.Recorded recorded(long refunded, long refundShortfall, long clawback, long disputeShortfall) {
        return new RefundMath.Recorded(AMOUNT, d(refunded), d(refundShortfall), d(clawback), d(disputeShortfall));
    }

    static RefundMath.Recorded none() {
        return recorded(0, 0, 0, 0);
    }

    @Test
    void takesBackThePartNotTakenBackBefore() {
        RefundMath.Plan first = RefundMath.refund(none(), d(1000), d(5000));
        assertThat(first.kind()).isEqualTo(RefundMath.Kind.CLAW_BACK);
        assertThat(first.amount()).isEqualByComparingTo("1000");
        assertThat(first.shortfall()).isEqualByComparingTo("0");
        // The running total grew to the full amount: the rest.
        RefundMath.Plan second = RefundMath.refund(recorded(1000, 0, 0, 0), d(5000), d(4000));
        assertThat(second.kind()).isEqualTo(RefundMath.Kind.CLAW_BACK);
        assertThat(second.amount()).isEqualByComparingTo("4000");
        // The same event again, or an older one late: nothing.
        assertThat(RefundMath.refund(recorded(5000, 0, 0, 0), d(5000), d(0)).kind())
            .isEqualTo(RefundMath.Kind.NOTHING);
        assertThat(RefundMath.refund(recorded(5000, 0, 0, 0), d(1000), d(0)).kind())
            .isEqualTo(RefundMath.Kind.NOTHING);
    }

    @Test
    void takesWhatTheBalanceCoversAndRecordsTheRestAsShortfall() {
        RefundMath.Plan plan = RefundMath.refund(none(), d(3000), d(1200));
        assertThat(plan.kind()).isEqualTo(RefundMath.Kind.SHORTFALL);
        assertThat(plan.amount()).isEqualByComparingTo("1200");
        assertThat(plan.shortfall()).isEqualByComparingTo("1800");
        assertThat(plan.reason()).isEqualTo(RefundMath.REFUND_SHORTFALL);
        RefundMath.Plan nothingLeft = RefundMath.refund(none(), d(3000), d(0));
        assertThat(nothingLeft.amount()).isEqualByComparingTo("0");
        assertThat(nothingLeft.shortfall()).isEqualByComparingTo("3000");
    }

    @Test
    void aRecordedShortfallIsNeverTakenAgain() {
        // 3000 refunded, 1200 taken, 1800 short (settled by finance outside QuizBuks). A later refund to 4000 takes
        // only the new 1000, whatever the balance.
        RefundMath.Plan later = RefundMath.refund(recorded(1200, 1800, 0, 0), d(4000), d(9999));
        assertThat(later.kind()).isEqualTo(RefundMath.Kind.CLAW_BACK);
        assertThat(later.amount()).isEqualByComparingTo("1000");
        // The same total again: nothing (not the shortfall).
        assertThat(RefundMath.refund(recorded(1200, 1800, 0, 0), d(3000), d(9999)).kind())
            .isEqualTo(RefundMath.Kind.NOTHING);
    }

    @Test
    void refusesWhatIsNotBelievable() {
        assertThat(RefundMath.refund(none(), d(5001), d(9999)).reason()).isEqualTo(RefundMath.OVER_REFUND);
        assertThat(RefundMath.refund(recorded(0, 0, 4000, 0), d(1001), d(9999)).reason())
            .isEqualTo(RefundMath.OVER_REFUND);
        assertThat(RefundMath.refund(recorded(0, 0, 3000, 1000), d(1001), d(9999)).reason())
            .isEqualTo(RefundMath.OVER_REFUND);
        assertThat(RefundMath.refund(none(), null, d(9999)).reason()).isEqualTo(RefundMath.INVALID_AMOUNT);
        assertThat(RefundMath.refund(none(), d(-1), d(9999)).reason()).isEqualTo(RefundMath.INVALID_AMOUNT);
        assertThat(RefundMath.refund(none(), new BigDecimal("10.5"), d(9999)).reason())
            .isEqualTo(RefundMath.INVALID_AMOUNT);
    }

    @Test
    void aLostDisputeIsTakenBackAsFarAsTheBalanceGoes() {
        RefundMath.Plan plan = RefundMath.disputeLost(recorded(1000, 0, 0, 0), d(4000), d(4000));
        assertThat(plan.kind()).isEqualTo(RefundMath.Kind.CLAW_BACK);
        assertThat(plan.amount()).isEqualByComparingTo("4000");
        RefundMath.Plan part = RefundMath.disputeLost(recorded(1000, 0, 0, 0), d(4000), d(3000));
        assertThat(part.kind()).isEqualTo(RefundMath.Kind.SHORTFALL);
        assertThat(part.amount()).isEqualByComparingTo("3000");
        assertThat(part.shortfall()).isEqualByComparingTo("1000");
        assertThat(part.reason()).isEqualTo(RefundMath.DISPUTE_SHORTFALL);
        // A refund short before counts as refunded.
        assertThat(RefundMath.disputeLost(recorded(500, 501, 0, 0), d(4000), d(9999)).reason())
            .isEqualTo(RefundMath.OVER_REFUND);
        assertThat(RefundMath.disputeLost(none(), ZERO, d(0)).kind()).isEqualTo(RefundMath.Kind.NOTHING);
    }

    @Test
    void aDisputeHoldsWhatIsAvailableUpToTheDisputedAmount() {
        assertThat(RefundMath.hold(d(3000), d(5000))).isEqualByComparingTo("3000");
        assertThat(RefundMath.hold(d(3000), d(1000))).isEqualByComparingTo("1000");
        assertThat(RefundMath.hold(null, d(1000))).isEqualByComparingTo("0");
        assertThat(RefundMath.hold(d(-5), d(1000))).isEqualByComparingTo("0");
    }

    /**
     * Whatever order the running totals arrive in, and however often each arrives, what is taken back plus what was
     * short is the highest total, never more than the top-up's amount, and never more than the balance held.
     */
    @Property
    void repeatedAndReorderedTotalsAccountForEachYenOnce(
        @ForAll @Size(min = 1, max = 8) List<@IntRange(min = 1, max = 5000) Integer> totals,
        @ForAll @LongRange(min = 0, max = 1000) long seed, @ForAll @IntRange(min = 0, max = 6000) int start) {
        List<Integer> sorted = new ArrayList<>(totals);
        Collections.sort(sorted);
        List<Integer> events = new ArrayList<>(sorted);
        events.addAll(sorted);
        Collections.shuffle(events, new Random(seed));
        BigDecimal refunded = ZERO;
        BigDecimal shortfall = ZERO;
        BigDecimal balance = d(start);
        for (int total : events) {
            RefundMath.Plan plan = RefundMath.refund(new RefundMath.Recorded(AMOUNT, refunded, shortfall, ZERO, ZERO),
                d(total), balance);
            assertThat(plan.kind()).isNotEqualTo(RefundMath.Kind.CHECK);
            assertThat(plan.amount()).isLessThanOrEqualTo(balance);
            refunded = refunded.add(plan.amount());
            shortfall = shortfall.add(plan.shortfall());
            balance = balance.subtract(plan.amount());
            assertThat(balance.signum()).isNotNegative();
        }
        assertThat(refunded.add(shortfall)).isEqualByComparingTo(d(sorted.getLast()));
        assertThat(refunded).isLessThanOrEqualTo(AMOUNT);
        assertThat(balance.add(refunded)).isEqualByComparingTo(d(start));
    }
}
