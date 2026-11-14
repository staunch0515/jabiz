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

    @Test
    void aPostpaidFeeNeverGoesOnAMonthWhoseInvoiceIsOut() {
        // Its own month, when not invoiced.
        assertThat(FeeRules.chargePeriod("2026-10", "2026-11", java.util.Set.of())).isEqualTo("2026-10");
        assertThat(FeeRules.chargePeriod("2026-10", "2026-11", java.util.Set.of("2026-11"))).isEqualTo("2026-10");
        // F-M6: its month invoiced, the month it is charged in.
        assertThat(FeeRules.chargePeriod("2026-10", "2026-11", java.util.Set.of("2026-10"))).isEqualTo("2026-11");
        // F-M15 b: that one invoiced too (a wind-up invoices the current month), the next.
        assertThat(FeeRules.chargePeriod("2026-10", "2026-11", java.util.Set.of("2026-10", "2026-11")))
            .isEqualTo("2026-12");
        assertThat(FeeRules.chargePeriod("2026-11", "2026-11", java.util.Set.of("2026-11"))).isEqualTo("2026-12");
        assertThat(FeeRules.chargePeriod("2026-12", "2026-12", java.util.Set.of("2026-12"))).isEqualTo("2027-01");
    }

    @Test
    void aPostpaidFeeSkipsEveryMonthInARowWhoseInvoiceIsOut() {
        // Fable's second look (H1): a wind-up invoices the current month, then the next ones as fees come.
        assertThat(FeeRules.chargePeriod("2026-11", "2026-11", java.util.Set.of("2026-11", "2026-12")))
            .isEqualTo("2027-01");
        assertThat(FeeRules.chargePeriod("2026-11", "2026-11", java.util.Set.of("2026-11", "2026-12", "2027-01")))
            .isEqualTo("2027-02");
        assertThat(FeeRules.chargePeriod("2026-10", "2026-11", java.util.Set.of("2026-10", "2026-11", "2026-12")))
            .isEqualTo("2027-01");
        // A gap is used.
        assertThat(FeeRules.chargePeriod("2026-11", "2026-11", java.util.Set.of("2026-11", "2027-01")))
            .isEqualTo("2026-12");
    }

    @Test
    void theMonthsSearchedForAPostpaidFeeRunFromTheEarlierToAheadOfTheLater() {
        assertThat(FeeRules.monthsFrom("2026-11", "2026-12", 2))
            .containsExactly("2026-11", "2026-12", "2027-01", "2027-02");
        assertThat(FeeRules.monthsFrom("2026-12", "2026-11", 0)).containsExactly("2026-11", "2026-12");
        assertThat(FeeRules.monthsFrom("2026-13", "2026-11", 2)).containsExactly("2026-13");
    }

    @Test
    void aMonthPastTheMonthsReadIsNotGuessedAndAMonthLongGoneIsNotSearched() {
        // Third re-review L2: every month read has its invoice out: the month found lies past them (the charge refuses).
        java.util.List<String> read = FeeRules.monthsFrom("2026-11", "2026-11", 2);
        String found = FeeRules.chargePeriod("2026-11", "2026-11", java.util.Set.copyOf(read));
        assertThat(found).isEqualTo("2027-02");
        assertThat(read).doesNotContain(found);
        // Third re-review L3: more than 60 months before the current one.
        assertThat(FeeRules.recentEnough("2021-11", "2026-11")).isTrue();
        assertThat(FeeRules.recentEnough("2021-10", "2026-11")).isFalse();
        assertThat(FeeRules.monthsFrom("2021-10", "2026-11", 24)).containsExactly("2021-10");
    }
}
