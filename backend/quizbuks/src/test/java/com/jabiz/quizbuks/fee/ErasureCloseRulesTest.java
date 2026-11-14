package com.jabiz.quizbuks.fee;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** F-M15 b (docs/quizbuks/plans/impl/F-M15.md §5.2, §5.6): the wind-up's decisions. */
class ErasureCloseRulesTest {

    static final Instant NOW = Instant.parse("2026-12-05T01:00:00Z");

    @Test
    void feesOwedWithSomethingToPayThemWithWaitTheGrantedCreditIsVoidedOnlyAfter() {
        assertThat(ErasureCloseRules.end(new BigDecimal("8100"), new BigDecimal("7500"))).isEqualTo(MoneyClose.TO_PAY);
        // Owed with nothing left: done, with fees owed (it does not stop the deletion).
        assertThat(ErasureCloseRules.end(new BigDecimal("600"), BigDecimal.ZERO)).isEqualTo(MoneyClose.CLOSED);
        // Nothing owed: done, whatever is left (the cash stays, the granted credit is voided).
        assertThat(ErasureCloseRules.end(BigDecimal.ZERO, new BigDecimal("4000"))).isEqualTo(MoneyClose.CLOSED);
        assertThat(ErasureCloseRules.end(BigDecimal.ZERO, BigDecimal.ZERO)).isEqualTo(MoneyClose.CLOSED);
    }

    @Test
    void theReasonSaysWhyARunStopped() {
        assertThat(ErasureCloseRules.reasonOf(MoneyClose.SWITCHED_OFF, null)).isEqualTo(QbSponsorClosures.SWITCH_OFF);
        assertThat(ErasureCloseRules.reasonOf(MoneyClose.WAITING_DEPOSITS, QbSponsorClosures.SWITCH_OFF))
            .isEqualTo(QbSponsorClosures.DEPOSIT_OPEN);
        // Waiting to pay: a stale reason goes, the fees owed of a closure done stay.
        assertThat(ErasureCloseRules.reasonOf(MoneyClose.TO_PAY, QbSponsorClosures.DEPOSIT_OPEN)).isNull();
        assertThat(ErasureCloseRules.reasonOf(MoneyClose.TO_PAY, QbSponsorClosures.FEE_OWED))
            .isEqualTo(QbSponsorClosures.FEE_OWED);
        assertThat(ErasureCloseRules.reasonOf(MoneyClose.NONE, QbSponsorClosures.DEPOSIT_OPEN))
            .isEqualTo(QbSponsorClosures.DEPOSIT_OPEN);
    }

    @Test
    void financeIsToldOnceTheAlertDaysHavePassed() {
        BigDecimal three = BigDecimal.valueOf(3);
        assertThat(ErasureCloseRules.slow(NOW.minus(Duration.ofDays(3)), three, NOW)).isTrue();
        assertThat(ErasureCloseRules.slow(NOW.minus(Duration.ofDays(3)).plusSeconds(1), three, NOW)).isFalse();
        // A value out of 1 to 60, a fraction or none tells nobody.
        for (String days : new String[] {"0", "61", "1.5", "-1"}) {
            assertThat(ErasureCloseRules.slow(NOW.minus(Duration.ofDays(90)), new BigDecimal(days), NOW)).as(days)
                .isFalse();
        }
        assertThat(ErasureCloseRules.slow(NOW.minus(Duration.ofDays(90)), null, NOW)).isFalse();
        assertThat(ErasureCloseRules.slow(null, three, NOW)).isFalse();
    }
}
