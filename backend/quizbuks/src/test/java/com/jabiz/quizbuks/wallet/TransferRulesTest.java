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

    static final TransferRules.Params ON = new TransferRules.Params(true, d(1000), d(50000), d(0), d(7));

    static TransferRules.Facts facts(long balance) {
        return facts(false, PayoutAccountStatus.ENABLED, false, 0, d(balance));
    }

    static TransferRules.Facts facts(boolean banned, String account, boolean open, int streak, BigDecimal balance) {
        return new TransferRules.Facts(banned, account, open, streak, false, true, balance, d(0));
    }

    static TransferRules.Past failed(String code) {
        return new TransferRules.Past(TransferStatus.FAILED, code, d(0), null);
    }

    static TransferRules.Past past(String status) {
        return new TransferRules.Past(status, null, d(0), null);
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
        assertThat(TransferRules.plan(new TransferRules.Params(true, d(0), d(50000), d(0), d(7)), facts(0)).skip())
            .isEqualTo(TransferRules.BELOW_THRESHOLD);
    }

    @Test
    void aFeeIsTakenFromTheAmount() {
        TransferRules.Params fee = new TransferRules.Params(true, d(1000), d(50000), d(100), d(7));
        TransferRules.Plan plan = TransferRules.plan(fee, facts(1500));
        assertThat(plan.amount()).isEqualByComparingTo("1500");
        assertThat(plan.fee()).isEqualByComparingTo("100");
        assertThat(plan.net()).isEqualByComparingTo("1400");
        assertThat(TransferRules.plan(new TransferRules.Params(true, d(10), d(50000), d(100), d(7)), facts(100)).skip())
            .isEqualTo(TransferRules.FEE_TOO_HIGH);
    }

    @Test
    void onlyAboveTheLimitIsReviewed() {
        assertThat(TransferRules.plan(ON, facts(50000)).review()).isFalse();
        assertThat(TransferRules.plan(ON, facts(50001)).review()).isTrue();
    }

    @Test
    void everyReasonToSkip() {
        assertThat(TransferRules.plan(new TransferRules.Params(false, d(1000), d(50000), d(0), d(7)), facts(5000)).skip())
            .isEqualTo(TransferRules.DISABLED);
        assertThat(TransferRules.plan(new TransferRules.Params(null, d(1000), d(50000), d(0), d(7)), facts(5000)).skip())
            .isEqualTo(TransferRules.DISABLED);
        assertThat(TransferRules.plan(new TransferRules.Params(true, null, d(50000), d(0), d(7)), facts(5000)).skip())
            .isEqualTo(TransferRules.INVALID_PARAMS);
        assertThat(TransferRules.plan(new TransferRules.Params(true, d(1000), d(50000), d(-1), d(7)), facts(5000)).skip())
            .isEqualTo(TransferRules.INVALID_PARAMS);
        assertThat(TransferRules.plan(ON, facts(true, PayoutAccountStatus.ENABLED, false, 0,
            d(5000))).skip()).isEqualTo(TransferRules.BANNED);
        assertThat(TransferRules.plan(ON, facts(false, null, false, 0, d(5000))).skip())
            .isEqualTo(TransferRules.NO_ACCOUNT);
        for (String status : List.of(PayoutAccountStatus.PENDING, PayoutAccountStatus.RESTRICTED,
            PayoutAccountStatus.DEAUTHORIZED)) {
            assertThat(TransferRules.plan(ON, facts(false, status, false, 0, d(5000))).skip())
                .as(status).isEqualTo(TransferRules.ACCOUNT_NOT_ENABLED);
        }
        assertThat(TransferRules.plan(ON, facts(false, PayoutAccountStatus.ENABLED, true, 0,
            d(5000))).skip()).isEqualTo(TransferRules.OPEN_TRANSFER);
        assertThat(TransferRules.plan(ON, facts(false, PayoutAccountStatus.ENABLED, false, 3,
            d(5000))).skip()).isEqualTo(TransferRules.FAILURE_STREAK);
        assertThat(TransferRules.plan(ON, facts(false, PayoutAccountStatus.ENABLED, false, 2,
            d(5000))).transfers()).isTrue();
        // Held after a rejected or reversed transfer, until finance releases the taker.
        assertThat(TransferRules.plan(ON, new TransferRules.Facts(false, PayoutAccountStatus.ENABLED, false, 0, true,
            true, d(5000), d(0))).skip()).isEqualTo(TransferRules.HELD);
        // Above the review limit without a review rule: skipped (nothing written), not rejected.
        assertThat(TransferRules.plan(ON, new TransferRules.Facts(false, PayoutAccountStatus.ENABLED, false, 0, false,
            false, d(50001), d(0))).skip()).isEqualTo(TransferRules.REVIEW_NOT_CONFIGURED);
        assertThat(TransferRules.plan(ON, new TransferRules.Facts(false, PayoutAccountStatus.ENABLED, false, 0, false,
            false, d(50000), d(0))).transfers()).isTrue();
    }

    static TransferRules.Facts withHeld(long balance, long held) {
        return new TransferRules.Facts(false, PayoutAccountStatus.ENABLED, false, 0, false, true, d(balance), d(held));
    }

    @Test
    void rewardsHeldBackStayUntilFree() {
        // Q10b (M3): only the balance less the rewards held back leaves, once that reaches the threshold.
        TransferRules.Plan plan = TransferRules.plan(ON, withHeld(5000, 1500));
        assertThat(plan.amount()).isEqualByComparingTo("3500");
        assertThat(plan.net()).isEqualByComparingTo("3500");
        assertThat(TransferRules.plan(ON, withHeld(5000, 4500)).skip()).isEqualTo(TransferRules.REWARDS_HELD);
        assertThat(TransferRules.plan(ON, withHeld(5000, 5000)).skip()).isEqualTo(TransferRules.REWARDS_HELD);
        // Held more than the balance (rewards of a disputed sponsor already sent): nothing, never below 0.
        assertThat(TransferRules.plan(ON, withHeld(5000, 9000)).skip()).isEqualTo(TransferRules.REWARDS_HELD);
        assertThat(TransferRules.transferable(d(5000), d(9000))).isEqualByComparingTo("0");
        assertThat(TransferRules.transferable(d(5000), null)).isEqualByComparingTo("5000");
        assertThat(TransferRules.plan(ON, withHeld(900, 0)).skip()).isEqualTo(TransferRules.BELOW_THRESHOLD);
        // The review limit looks at what leaves.
        assertThat(TransferRules.plan(ON, withHeld(60000, 20000)).review()).isFalse();
        assertThat(TransferRules.plan(ON, withHeld(60000, 5000)).review()).isTrue();
        // A hold period that cannot be used stops transfers.
        assertThat(TransferRules.plan(new TransferRules.Params(true, d(1000), d(50000), d(0), null), withHeld(5000, 0))
            .skip()).isEqualTo(TransferRules.INVALID_PARAMS);
        assertThat(TransferRules.plan(new TransferRules.Params(true, d(1000), d(50000), d(0), d(-1)), withHeld(5000, 0))
            .skip()).isEqualTo(TransferRules.INVALID_PARAMS);
    }

    @Test
    void rejectedOrReversedTransfersHoldUntilReleased() {
        assertThat(TransferRules.held(List.of())).isFalse();
        assertThat(TransferRules.held(List.of(past(TransferStatus.SENT), failed(StripeErrors.DECLINED)))).isFalse();
        assertThat(TransferRules.held(List.of(past(TransferStatus.REJECTED)))).isTrue();
        assertThat(TransferRules.held(List.of(past(TransferStatus.REVERSED)))).isTrue();
        // Reversed in part: still SENT, but money came back.
        assertThat(TransferRules.held(List.of(new TransferRules.Past(TransferStatus.SENT, null, d(1), null))))
            .isTrue();
        assertThat(TransferRules.held(List.of(new TransferRules.Past(TransferStatus.REJECTED, null, d(0),
            java.time.Instant.EPOCH)))).isFalse();
    }

    @Test
    void theStreakCountsTheLatestFailuresOfTheAccountInARow() {
        TransferRules.Past declined = failed(StripeErrors.DECLINED);
        assertThat(TransferRules.failureStreak(List.of())).isZero();
        assertThat(TransferRules.failureStreak(List.of(declined, declined, past(TransferStatus.SENT), declined)))
            .isEqualTo(2);
        assertThat(TransferRules.failureStreak(List.of(past(TransferStatus.SENT), declined, declined))).isZero();
        assertThat(TransferRules.failureStreak(List.of(declined, failed(StripeErrors.NOT_FOUND), declined)))
            .isEqualTo(3);
        // The platform's own failures (its balance, its key, Stripe down, found absent) neither count nor break it.
        for (String code : List.of(StripeErrors.BALANCE_INSUFFICIENT, StripeErrors.AUTH, StripeErrors.RATE_LIMITED,
            StripeErrors.UNAVAILABLE, TransferProcesses.NOT_AT_STRIPE)) {
            assertThat(TransferRules.failureStreak(List.of(failed(code), failed(code), failed(code)))).as(code)
                .isZero();
            assertThat(TransferRules.failureStreak(List.of(declined, failed(code), declined))).as(code)
                .isEqualTo(2);
        }
    }

    @Test
    void onlyARefusedCreationIsCertain() {
        assertThat(TransferRules.certainFailure(StripeErrors.DECLINED)).isTrue();
        assertThat(TransferRules.certainFailure(StripeErrors.BALANCE_INSUFFICIENT)).isTrue();
        assertThat(TransferRules.certainFailure(StripeErrors.NOT_FOUND)).isTrue();
        assertThat(TransferRules.certainFailure(StripeErrors.AUTH)).isTrue();
        // A 429 says nothing about an earlier attempt under the same key.
        for (String code : List.of(StripeErrors.RATE_LIMITED, StripeErrors.UNAVAILABLE, StripeErrors.CONFLICT,
            StripeErrors.BAD_RESPONSE,
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

    /**
     * Whatever the balance and the rewards held back, a transfer never takes more than what is not held, and amount =
     * fee + net with net > 0.
     */
    @Property
    void neverMoreThanTheBalance(@ForAll @IntRange(min = 0, max = 2_000_000) int balance,
        @ForAll @IntRange(min = 0, max = 5000) int fee, @ForAll @IntRange(min = 0, max = 100_000) int threshold,
        @ForAll @IntRange(min = 0, max = 2_000_000) int held) {
        TransferRules.Plan plan = TransferRules.plan(new TransferRules.Params(true, d(threshold), d(50000), d(fee), d(7)),
            withHeld(balance, held));
        if (plan.transfers()) {
            assertThat(plan.amount()).isEqualByComparingTo(d(Math.max(0, balance - held)));
            assertThat(plan.amount()).isLessThanOrEqualTo(d(balance));
            assertThat(plan.fee().add(plan.net())).isEqualByComparingTo(plan.amount());
            assertThat(plan.net().signum()).isPositive();
            assertThat(plan.amount()).isGreaterThanOrEqualTo(d(threshold));
        }
    }
}
