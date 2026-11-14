package com.jabiz.quizbuks.fee;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** The rules of the platform fee Kudos granted (F-M2). */
class FeeCreditRulesTest {

    static final Instant NOW = Instant.parse("2026-11-09T01:00:00Z");

    @Test
    void theOnboardingGivesTheBasicOrTheCertifiedAmount() {
        assertThat(FeeCreditRules.onboardingAmount(false, new BigDecimal("1000"), new BigDecimal("10000")))
            .contains(new BigDecimal("1000"));
        assertThat(FeeCreditRules.onboardingAmount(true, new BigDecimal("1000"), new BigDecimal("10000.00")))
            .contains(new BigDecimal("10000"));
        assertThat(FeeCreditRules.onboardingAmount(false, BigDecimal.ZERO, BigDecimal.TEN)).isEmpty();
        assertThat(FeeCreditRules.onboardingAmount(false, new BigDecimal("10.5"), BigDecimal.TEN)).isEmpty();
        assertThat(FeeCreditRules.onboardingAmount(true, BigDecimal.TEN, null)).isEmpty();
    }

    @Test
    void aLotIsValidForItsDays() {
        assertThat(FeeCreditRules.expiresAt(NOW, new BigDecimal("90"))).contains(NOW.plus(Duration.ofDays(90)));
        assertThat(FeeCreditRules.expiresAt(NOW, BigDecimal.ONE)).contains(NOW.plus(Duration.ofDays(1)));
        assertThat(FeeCreditRules.expiresAt(NOW, new BigDecimal("999"))).isPresent();
        assertThat(FeeCreditRules.expiresAt(NOW, new BigDecimal("1000"))).isEmpty();
        assertThat(FeeCreditRules.expiresAt(NOW, BigDecimal.ZERO)).isEmpty();
        assertThat(FeeCreditRules.expiresAt(NOW, new BigDecimal("1.5"))).isEmpty();
        assertThat(FeeCreditRules.expiresAt(NOW, null)).isEmpty();
    }

    @Test
    void noCapIsNoLimit() {
        assertThat(FeeCreditRules.withinCap(new BigDecimal("1000000"), null)).isTrue();
        assertThat(FeeCreditRules.withinCap(new BigDecimal("1500"), new BigDecimal("1500"))).isTrue();
        assertThat(FeeCreditRules.withinCap(new BigDecimal("1501"), new BigDecimal("1500"))).isFalse();
    }

    @Test
    void onlyWhatIsLeftOfAnExpiredLotIsVoided() {
        Instant expired = NOW.minusSeconds(1);
        assertThat(FeeCreditRules.voidable(new BigDecimal("670"), expired, NOW)).isEqualByComparingTo("670");
        assertThat(FeeCreditRules.voidable(new BigDecimal("670"), NOW, NOW)).isEqualByComparingTo("670");
        assertThat(FeeCreditRules.voidable(new BigDecimal("670"), NOW.plusSeconds(1), NOW)).isZero();
        assertThat(FeeCreditRules.voidable(BigDecimal.ZERO, expired, NOW)).isZero();
        assertThat(FeeCreditRules.voidable(null, expired, NOW)).isZero();
    }

    @Test
    void aLotIsRemindedOnceWithinTheNotice() {
        BigDecimal days = new BigDecimal("14");
        Instant in10 = NOW.plus(Duration.ofDays(10));
        assertThat(FeeCreditRules.remindable(BigDecimal.TEN, in10, null, NOW, days)).isTrue();
        assertThat(FeeCreditRules.remindable(BigDecimal.TEN, NOW.plus(Duration.ofDays(14)), null, NOW, days)).isTrue();
        assertThat(FeeCreditRules.remindable(BigDecimal.TEN, NOW.plus(Duration.ofDays(15)), null, NOW, days)).isFalse();
        assertThat(FeeCreditRules.remindable(BigDecimal.TEN, in10, NOW.minusSeconds(60), NOW, days)).isFalse();
        assertThat(FeeCreditRules.remindable(BigDecimal.ZERO, in10, null, NOW, days)).isFalse();
        assertThat(FeeCreditRules.remindable(BigDecimal.TEN, NOW, null, NOW, days)).isFalse();
        assertThat(FeeCreditRules.remindable(BigDecimal.TEN, in10, null, NOW, BigDecimal.ZERO)).isFalse();
        assertThat(FeeCreditRules.remindable(BigDecimal.TEN, in10, null, NOW, new BigDecimal("366"))).isFalse();
    }

    @Test
    void aSponsorIsRemindedAtMostOnceIn24Hours() {
        assertThat(FeeCreditRules.reminderAllowed(null, NOW)).isTrue();
        assertThat(FeeCreditRules.reminderAllowed(NOW.minus(Duration.ofHours(24)), NOW)).isTrue();
        assertThat(FeeCreditRules.reminderAllowed(NOW.minus(Duration.ofHours(23)), NOW)).isFalse();
        assertThat(FeeCreditRules.manualKey("r1")).isEqualTo("manual:r1");
    }

    @Test
    void aVerifiedSponsorIsToppedUpToTheCertifiedAmountOnce() {
        BigDecimal basic = new BigDecimal("1000");
        BigDecimal certified = new BigDecimal("10000");
        assertThat(FeeCreditRules.certifiedTopUp(true, true, false, true, basic, certified, false, certified))
            .contains(new BigDecimal("9000"));
        // The switch off, not approved, banned, not verified, no onboarding yet, topped up already: nothing.
        assertThat(FeeCreditRules.certifiedTopUp(false, true, false, true, basic, certified, false, certified)).isEmpty();
        assertThat(FeeCreditRules.certifiedTopUp(true, false, false, true, basic, certified, false, certified)).isEmpty();
        assertThat(FeeCreditRules.certifiedTopUp(true, true, true, true, basic, certified, false, certified)).isEmpty();
        assertThat(FeeCreditRules.certifiedTopUp(true, true, false, false, basic, certified, false, certified)).isEmpty();
        assertThat(FeeCreditRules.certifiedTopUp(true, true, false, true, null, null, false, certified)).isEmpty();
        assertThat(FeeCreditRules.certifiedTopUp(true, true, false, true, basic, certified, true, certified)).isEmpty();
        // Granted certified (the lot records its own amount), or more than the parameter now: nothing.
        assertThat(FeeCreditRules.certifiedTopUp(true, true, false, true, certified, certified, false, certified))
            .isEmpty();
        assertThat(FeeCreditRules.certifiedTopUp(true, true, false, true, new BigDecimal("12000"),
            new BigDecimal("12000"), false, certified)).isEmpty();
        // No certified amount, or not whole yen: nothing.
        assertThat(FeeCreditRules.certifiedTopUp(true, true, false, true, basic, certified, false, null)).isEmpty();
        assertThat(FeeCreditRules.certifiedTopUp(true, true, false, true, basic, certified, false,
            new BigDecimal("10000.5"))).isEmpty();
        assertThat(FeeCreditRules.certifiedTopUp(true, true, false, true, basic, new BigDecimal("10000.5"), false,
            certified)).isEmpty();
    }

    @Test
    void raisingTheCertifiedAmountTopsUpNothingGrantedBefore() {
        BigDecimal basic = new BigDecimal("1000");
        BigDecimal then = new BigDecimal("10000");
        BigDecimal raised = new BigDecimal("15000");
        // Granted certified at 10000, the parameter raised to 15000 since: never topped up.
        assertThat(FeeCreditRules.certifiedTopUp(true, true, false, true, then, then, false, raised)).isEmpty();
        // Granted basic while the certified amount was 10000: topped up to 10000, not to the 15000 raised since.
        assertThat(FeeCreditRules.certifiedTopUp(true, true, false, true, basic, then, false, raised))
            .contains(new BigDecimal("9000"));
        // The parameter lowered to 5000 since: topped up to 5000 only.
        assertThat(FeeCreditRules.certifiedTopUp(true, true, false, true, basic, then, false, new BigDecimal("5000")))
            .contains(new BigDecimal("4000"));
        // A lot granted before the amount was recorded (V111): the parameter now.
        assertThat(FeeCreditRules.certifiedTopUp(true, true, false, true, basic, null, false, raised))
            .contains(new BigDecimal("14000"));
    }

    /** F-M15 b (§5.3): the wind-up marks lots not voided before and not expired, even with nothing left. */
    @org.junit.jupiter.api.Test
    void theWindUpVoidsWhatIsLeftOfLotsNotVoidedNorExpired() {
        Instant later = NOW.plusSeconds(86_400);
        org.assertj.core.api.Assertions.assertThat(FeeCreditRules.voidableOnErase(later, null, NOW)).isTrue();
        // Nothing left is marked too: what comes back to it later is voided on arrival.
        org.assertj.core.api.Assertions.assertThat(FeeCreditRules.voidedOnErase(java.math.BigDecimal.ZERO))
            .isEqualByComparingTo("0");
        org.assertj.core.api.Assertions.assertThat(FeeCreditRules.voidedOnErase(new java.math.BigDecimal("700")))
            .isEqualByComparingTo("700");
        // Voided before, expired (the expiry's), or expiring right now: not the wind-up's.
        org.assertj.core.api.Assertions.assertThat(FeeCreditRules.voidableOnErase(later, NOW, NOW)).isFalse();
        org.assertj.core.api.Assertions.assertThat(FeeCreditRules.voidableOnErase(NOW.minusSeconds(1), null, NOW))
            .isFalse();
        org.assertj.core.api.Assertions.assertThat(FeeCreditRules.voidableOnErase(NOW, null, NOW)).isFalse();
        org.assertj.core.api.Assertions.assertThat(FeeCreditRules.voidableOnErase(null, null, NOW)).isFalse();
    }
}
