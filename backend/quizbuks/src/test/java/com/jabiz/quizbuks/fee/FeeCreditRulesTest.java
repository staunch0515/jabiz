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
}
