package com.jabiz.quizbuks.fee;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** Q87 (docs/quizbuks/plans/Q87-fee-account.md): the fee rules in pure Java. */
class FeeRulesTest {

    static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    @Test
    void theTaxIsRoundedDownAndARateOutOfRangeIsRefused() {
        assertThat(FeeRules.tax(new BigDecimal("1000"), new BigDecimal("0.1"))).isEqualByComparingTo("100");
        assertThat(FeeRules.tax(new BigDecimal("999"), new BigDecimal("0.1"))).isEqualByComparingTo("99");
        assertThat(FeeRules.tax(new BigDecimal("1000"), BigDecimal.ZERO)).isEqualByComparingTo("0");
        assertThat(FeeRules.tax(new BigDecimal("1000"), null)).isNull();
        assertThat(FeeRules.tax(new BigDecimal("1000"), new BigDecimal("-0.1"))).isNull();
        assertThat(FeeRules.tax(new BigDecimal("1000"), BigDecimal.ONE)).isNull();
    }

    @Test
    void theBillingModeIsTheSponsorsOwnElseTheDefaultElsePrepaid() {
        assertThat(FeeRules.billingModeOf("POSTPAID", "PREPAID")).isEqualTo("POSTPAID");
        assertThat(FeeRules.billingModeOf(null, "POSTPAID")).isEqualTo("POSTPAID");
        assertThat(FeeRules.billingModeOf("NOPE", "NOPE")).isEqualTo("PREPAID");
        assertThat(FeeRules.billingModeOf(null, null)).isEqualTo("PREPAID");
    }

    @Test
    void theMonthAndTheDueDayFollowTheFiscalZone() {
        // 15:30 UTC on the 31st is the 1st in Tokyo.
        Instant at = Instant.parse("2026-10-31T15:30:00Z");
        assertThat(FeeRules.period(at, TOKYO)).isEqualTo("2026-11");
        assertThat(FeeRules.period("2026-11")).isTrue();
        assertThat(FeeRules.period("2026-13")).isFalse();
        assertThat(FeeRules.period("202611")).isFalse();
        assertThat(FeeRules.periodStart("2026-11", TOKYO)).isEqualTo(Instant.parse("2026-10-31T15:00:00Z"));
        assertThat(FeeRules.periodEnd("2026-11", TOKYO)).isEqualTo(Instant.parse("2026-11-30T15:00:00Z"));
        assertThat(FeeRules.dueOn(at, new BigDecimal("30"), TOKYO)).isEqualTo(LocalDate.parse("2026-12-01"));
        assertThat(FeeRules.dueOn(at, null, TOKYO)).isEqualTo(LocalDate.parse("2026-11-01"));
    }

    @Test
    void unpaidFeesStopAPrepaidSponsorAtOnceAndAPostpaidOneBeyondTheirLimitOrWhenOverdue() {
        assertThat(new FeeRules.Standing("PREPAID", BigDecimal.ZERO, BigDecimal.ZERO, false).blocked()).isFalse();
        assertThat(new FeeRules.Standing("PREPAID", BigDecimal.ZERO, BigDecimal.ONE, false).blocked()).isTrue();
        assertThat(new FeeRules.Standing("POSTPAID", new BigDecimal("5000"), new BigDecimal("5000"), false).blocked())
            .isFalse();
        assertThat(new FeeRules.Standing("POSTPAID", new BigDecimal("5000"), new BigDecimal("5001"), false).blocked())
            .isTrue();
        assertThat(new FeeRules.Standing("POSTPAID", new BigDecimal("5000"), new BigDecimal("100"), true).blocked())
            .isTrue();
        assertThat(new FeeRules.Standing("POSTPAID", null, new BigDecimal("1"), false).blocked()).isTrue();
        assertThat(new FeeRules.Standing("POSTPAID", null, null, false).blocked()).isFalse();
    }
}
