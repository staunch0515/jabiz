package com.jabiz.quizbuks.wallet;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransferRulesTest {

    static BigDecimal d(long value) {
        return BigDecimal.valueOf(value);
    }

    static final TransferRules.Params ON = new TransferRules.Params(true, d(1000), d(50000), d(0));

    static TransferRules.Facts facts(long balance) {
        return new TransferRules.Facts(false, PayoutAccountStatus.ENABLED, false, 0, d(balance));
    }

    @Test
    void theWholeBalanceGoesOnceItReachesTheThreshold() {
        TransferRules.Plan plan = TransferRules.plan(ON, facts(1000));
        assertThat(plan.transfers()).isTrue();
        assertThat(plan.amount()).isEqualByComparingTo("1000");
        assertThat(plan.net()).isEqualByComparingTo("1000");
        assertThat(plan.fee()).isEqualByComparingTo("0");
        assertThat(plan.review()).isFalse();
        assertThat(TransferRules.plan(ON, facts(999)).skip()).isEqualTo(TransferRules.BELOW_THRESHOLD);
        assertThat(TransferRules.plan(new TransferRules.Params(true, d(0), d(50000), d(0)), facts(0)).skip())
            .isEqualTo(TransferRules.BELOW_THRESHOLD);
    }

    @Test
    void aFeeIsTakenFromTheAmount() {
        TransferRules.Params fee = new TransferRules.Params(true, d(1000), d(50000), d(100));
        TransferRules.Plan plan = TransferRules.plan(fee, facts(1500));
        assertThat(plan.amount()).isEqualByComparingTo("1500");
        assertThat(plan.fee()).isEqualByComparingTo("100");
        assertThat(plan.net()).isEqualByComparingTo("1400");
        assertThat(TransferRules.plan(new TransferRules.Params(true, d(10), d(50000), d(100)), facts(100)).skip())
            .isEqualTo(TransferRules.FEE_TOO_HIGH);
    }

    @Test
    void onlyAboveTheLimitIsReviewed() {
        assertThat(TransferRules.plan(ON, facts(50000)).review()).isFalse();
        assertThat(TransferRules.plan(ON, facts(50001)).review()).isTrue();
    }

    @Test
    void everyReasonToSkip() {
        assertThat(TransferRules.plan(new TransferRules.Params(false, d(1000), d(50000), d(0)), facts(5000)).skip())
            .isEqualTo(TransferRules.DISABLED);
        assertThat(TransferRules.plan(new TransferRules.Params(null, d(1000), d(50000), d(0)), facts(5000)).skip())
            .isEqualTo(TransferRules.DISABLED);
        assertThat(TransferRules.plan(new TransferRules.Params(true, null, d(50000), d(0)), facts(5000)).skip())
            .isEqualTo(TransferRules.INVALID_PARAMS);
        assertThat(TransferRules.plan(new TransferRules.Params(true, d(1000), d(50000), d(-1)), facts(5000)).skip())
            .isEqualTo(TransferRules.INVALID_PARAMS);
        assertThat(TransferRules.plan(ON, new TransferRules.Facts(true, PayoutAccountStatus.ENABLED, false, 0,
            d(5000))).skip()).isEqualTo(TransferRules.BANNED);
        assertThat(TransferRules.plan(ON, new TransferRules.Facts(false, null, false, 0, d(5000))).skip())
            .isEqualTo(TransferRules.NO_ACCOUNT);
        for (String status : List.of(PayoutAccountStatus.PENDING, PayoutAccountStatus.RESTRICTED,
            PayoutAccountStatus.DEAUTHORIZED)) {
            assertThat(TransferRules.plan(ON, new TransferRules.Facts(false, status, false, 0, d(5000))).skip())
                .as(status).isEqualTo(TransferRules.ACCOUNT_NOT_ENABLED);
        }
        assertThat(TransferRules.plan(ON, new TransferRules.Facts(false, PayoutAccountStatus.ENABLED, true, 0,
            d(5000))).skip()).isEqualTo(TransferRules.OPEN_TRANSFER);
        assertThat(TransferRules.plan(ON, new TransferRules.Facts(false, PayoutAccountStatus.ENABLED, false, 3,
            d(5000))).skip()).isEqualTo(TransferRules.FAILURE_STREAK);
        assertThat(TransferRules.plan(ON, new TransferRules.Facts(false, PayoutAccountStatus.ENABLED, false, 2,
            d(5000))).transfers()).isTrue();
    }

    @Test
    void theStreakCountsTheLatestFailuresInARow() {
        assertThat(TransferRules.failureStreak(List.of())).isZero();
        assertThat(TransferRules.failureStreak(List.of("FAILED", "FAILED", "SENT", "FAILED"))).isEqualTo(2);
        assertThat(TransferRules.failureStreak(List.of("SENT", "FAILED", "FAILED"))).isZero();
        assertThat(TransferRules.failureStreak(List.of("FAILED", "FAILED", "FAILED"))).isEqualTo(3);
    }

    @Test
    void onlyARefusedCreationIsCertain() {
        assertThat(TransferRules.certainFailure(StripeErrors.DECLINED)).isTrue();
        assertThat(TransferRules.certainFailure(StripeErrors.BALANCE_INSUFFICIENT)).isTrue();
        for (String code : List.of(StripeErrors.UNAVAILABLE, StripeErrors.CONFLICT, StripeErrors.BAD_RESPONSE,
            StripeTransferHandler.LOOKUP_FAILED, "TIMEOUT", "LEASE_EXPIRED", "EXTERNAL_CALL_FAILED", "HANDLER_ERROR",
            "RESULT_TOO_LARGE", "ATTEMPTS_EXHAUSTED")) {
            assertThat(TransferRules.certainFailure(code)).as(code).isFalse();
        }
        assertThat(TransferRules.certainFailure(null)).isFalse();
    }

    @Test
    void reversalsAreCountedFromTheRunningTotal() {
        assertThat(TransferRules.reversalPart(d(1000), d(0), d(300))).isEqualByComparingTo("300");
        assertThat(TransferRules.reversalPart(d(1000), d(300), d(1000))).isEqualByComparingTo("700");
        assertThat(TransferRules.reversalPart(d(1000), d(1000), d(300))).isEqualByComparingTo("0");
        assertThat(TransferRules.reversalPart(d(1000), d(0), d(1001))).isNull();
        assertThat(TransferRules.reversalPart(d(1000), d(0), new BigDecimal("1.5"))).isNull();
        assertThat(TransferRules.reversalPart(d(1000), d(0), null)).isNull();
    }

    /** Whatever the balance, a transfer never takes more than it, and amount = fee + net with net > 0. */
    @Property
    void neverMoreThanTheBalance(@ForAll @IntRange(min = 0, max = 2_000_000) int balance,
        @ForAll @IntRange(min = 0, max = 5000) int fee, @ForAll @IntRange(min = 0, max = 100_000) int threshold) {
        TransferRules.Plan plan = TransferRules.plan(new TransferRules.Params(true, d(threshold), d(50000), d(fee)),
            facts(balance));
        if (plan.transfers()) {
            assertThat(plan.amount()).isEqualByComparingTo(d(balance));
            assertThat(plan.fee().add(plan.net())).isEqualByComparingTo(plan.amount());
            assertThat(plan.net().signum()).isPositive();
            assertThat(plan.amount()).isGreaterThanOrEqualTo(d(threshold));
        }
    }
}
