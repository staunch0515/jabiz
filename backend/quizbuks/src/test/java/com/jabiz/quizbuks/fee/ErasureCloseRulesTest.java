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
        assertThat(ErasureCloseRules.reasonOf(MoneyClose.SWITCHED_OFF, null, false)).isEqualTo(QbSponsorClosures.SWITCH_OFF);
        assertThat(ErasureCloseRules.reasonOf(MoneyClose.WAITING_DEPOSITS, QbSponsorClosures.SWITCH_OFF, false))
            .isEqualTo(QbSponsorClosures.DEPOSIT_OPEN);
        // Waiting to pay: a stale reason goes, the fees owed of a closure done stay.
        assertThat(ErasureCloseRules.reasonOf(MoneyClose.TO_PAY, QbSponsorClosures.DEPOSIT_OPEN, false)).isNull();
        assertThat(ErasureCloseRules.reasonOf(MoneyClose.TO_PAY, QbSponsorClosures.FEE_OWED, false))
            .isEqualTo(QbSponsorClosures.FEE_OWED);
        assertThat(ErasureCloseRules.reasonOf(MoneyClose.NONE, QbSponsorClosures.DEPOSIT_OPEN, false))
            .isEqualTo(QbSponsorClosures.DEPOSIT_OPEN);
    }

    @Test
    void aWindUpDoneWithFeesOwedKeepsItsReasonWhateverStopsALaterRun() {
        // Fable M1: a CLOSED + FEE_OWED closure is taken again when cash arrives; a run it stops (the switch off, a
        // deposit held) must not overwrite FEE_OWED, or the daily job never takes it again.
        for (String outcome : new String[] {MoneyClose.SWITCHED_OFF, MoneyClose.WAITING_DEPOSITS, MoneyClose.TO_PAY,
            MoneyClose.NONE}) {
            assertThat(ErasureCloseRules.reasonOf(outcome, QbSponsorClosures.FEE_OWED, true)).as(outcome)
                .isEqualTo(QbSponsorClosures.FEE_OWED);
        }
        // Not done yet: as before.
        assertThat(ErasureCloseRules.reasonOf(MoneyClose.SWITCHED_OFF, null, false))
            .isEqualTo(QbSponsorClosures.SWITCH_OFF);
    }

    @Test
    void theSlowTaskSaysWhyInWords() {
        assertThat(ErasureCloseRules.slowReason(MoneyClose.SWITCHED_OFF)).isEqualTo("SWITCH_OFF");
        assertThat(ErasureCloseRules.slowReason(MoneyClose.WAITING_DEPOSITS)).isEqualTo("DEPOSIT_OPEN");
        assertThat(ErasureCloseRules.slowReason(MoneyClose.TO_PAY)).isEqualTo("TO_PAY");
        assertThat(ErasureCloseRules.slowReason(MoneyClose.MORE)).isEqualTo("MORE");
        assertThat(ErasureCloseRules.slowReason(null)).isEqualTo("OTHER");
        // Re-review M2: only the AI credit's prepayment held.
        assertThat(ErasureCloseRules.slowReason(MoneyClose.WAITING_DEPOSITS, true)).isEqualTo("DEPOSIT_OPEN_AI");
        assertThat(ErasureCloseRules.slowReason(MoneyClose.WAITING_DEPOSITS, false)).isEqualTo("DEPOSIT_OPEN");
        assertThat(ErasureCloseRules.slowReason(MoneyClose.TO_PAY, true)).isEqualTo("TO_PAY");
    }

    @Test
    void financeIsToldOnceTheAlertDaysHavePassed() {
        BigDecimal three = BigDecimal.valueOf(3);
        assertThat(ErasureCloseRules.slow(NOW.minus(Duration.ofDays(3)), three, NOW)).isTrue();
        assertThat(ErasureCloseRules.slow(NOW.minus(Duration.ofDays(3)).plusSeconds(1), three, NOW)).isFalse();
        // A value out of 1 to 60, a fraction or none alerts at once (review L9: set wrong, it tells finance).
        for (String days : new String[] {"0", "61", "1.5", "-1"}) {
            assertThat(ErasureCloseRules.validAlertDays(new BigDecimal(days))).as(days).isFalse();
            assertThat(ErasureCloseRules.slow(NOW, new BigDecimal(days), NOW)).as(days).isTrue();
        }
        assertThat(ErasureCloseRules.slow(NOW, null, NOW)).isTrue();
        assertThat(ErasureCloseRules.validAlertDays(BigDecimal.valueOf(60))).isTrue();
        assertThat(ErasureCloseRules.validAlertDays(new BigDecimal("1.0"))).isTrue();
        assertThat(ErasureCloseRules.slow(null, three, NOW)).isFalse();
    }
}
