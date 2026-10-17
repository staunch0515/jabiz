package com.jabiz.runtime.retention;

import com.jabiz.retention.RetentionPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.GenericApplicationContext;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fiscal year's zone (decision D48 item 2): a time falls on the day, and so in the fiscal year, it is in
 * {@code jabiz.fiscal-year-zone}. In Tokyo (UTC+9) the first nine hours of 1 January are still 31 December in UTC.
 */
class RetentionZoneTest {

    private static final RetentionPolicy SEVEN_YEARS = RetentionPolicy.of("Invoice").keep(Period.ofYears(7))
        .from("issuedAt").afterFiscalYearEnd();

    @Test
    void theFirstNineHoursOfJanuaryInTokyoBelongToTheNewFiscalYearThereAndToTheOldOneInUtc() {
        RetentionPolicies utc = policies("UTC");
        RetentionPolicies tokyo = policies("Asia/Tokyo");
        // 2019-01-01 00:00 JST, 08:59 JST and 09:00 JST; and 2018-12-31 23:59 JST.
        Instant midnight = Instant.parse("2018-12-31T15:00:00Z");
        Instant beforeNine = Instant.parse("2018-12-31T23:59:59Z");
        Instant nine = Instant.parse("2019-01-01T00:00:00Z");
        Instant lastMinute = Instant.parse("2018-12-31T14:59:59Z");

        assertThat(tokyo.dateOf(midnight)).isEqualTo(LocalDate.of(2019, 1, 1));
        assertThat(tokyo.dateOf(beforeNine)).isEqualTo(LocalDate.of(2019, 1, 1));
        assertThat(tokyo.dateOf(nine)).isEqualTo(LocalDate.of(2019, 1, 1));
        assertThat(tokyo.dateOf(lastMinute)).isEqualTo(LocalDate.of(2018, 12, 31));
        assertThat(utc.dateOf(midnight)).isEqualTo(LocalDate.of(2018, 12, 31));
        assertThat(utc.dateOf(beforeNine)).isEqualTo(LocalDate.of(2018, 12, 31));
        assertThat(utc.dateOf(nine)).isEqualTo(LocalDate.of(2019, 1, 1));

        // So the same record is kept a year longer when the fiscal year is counted in Tokyo.
        assertThat(SEVEN_YEARS.expiry(tokyo.dateOf(beforeNine), tokyo.fiscalYearEnd()))
            .isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(SEVEN_YEARS.expiry(utc.dateOf(beforeNine), utc.fiscalYearEnd()))
            .isEqualTo(LocalDate.of(2025, 12, 31));
    }

    @Test
    void todayAndTheStartOfADayAreThoseOfTheZone() {
        RetentionPolicies tokyo = policies("Asia/Tokyo");
        Clock newYearInTokyo = Clock.fixed(Instant.parse("2025-12-31T15:30:00Z"), ZoneOffset.UTC);
        assertThat(tokyo.today(newYearInTokyo)).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(policies("UTC").today(newYearInTokyo)).isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(tokyo.startOf(LocalDate.of(2026, 1, 1))).isEqualTo(Instant.parse("2025-12-31T15:00:00Z"));
        assertThat(policies("UTC").startOf(LocalDate.of(2026, 1, 1))).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void datesStayAsTheyAreAndTextIsReadInTheZone() {
        RetentionPolicies tokyo = policies("Asia/Tokyo");
        assertThat(tokyo.dateOf(LocalDate.of(2018, 12, 31))).isEqualTo(LocalDate.of(2018, 12, 31));
        assertThat(tokyo.dateOf("2018-12-31T20:00:00Z")).isEqualTo(LocalDate.of(2019, 1, 1));
        assertThat(tokyo.dateOf("2018-12-31")).isEqualTo(LocalDate.of(2018, 12, 31));
        assertThat(tokyo.dateOf(OffsetDateTime.parse("2018-12-31T20:00:00Z"))).isEqualTo(LocalDate.of(2019, 1, 1));
        assertThat(tokyo.dateOf("not a date")).isNull();
        assertThat(tokyo.dateOf(null)).isNull();
    }

    @Test
    void theDefaultIsUtcAndAnUnknownZoneIsAStartupError() {
        assertThat(policies("UTC").zone()).isEqualTo(ZoneId.of("UTC"));
        assertThat(policies("UTC").zoneProblem()).isEmpty();
        RetentionPolicies wrong = policies("Mars/Olympus");
        assertThat(wrong.zoneProblem()).hasValueSatisfying(problem -> assertThat(problem).contains("Mars/Olympus"));
        assertThat(new RetentionChecks(wrong, null).check()).anySatisfy(problem ->
            assertThat(problem.format()).contains("jabiz.fiscal-year-zone"));
        assertThat(wrong.fiscalYearEnd()).isEqualTo(Month.DECEMBER);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static RetentionPolicies policies(String zone) {
        GenericApplicationContext beans = new GenericApplicationContext();
        beans.refresh();
        try {
            return new RetentionPolicies((ObjectProvider) beans.getBeanProvider(RetentionPolicy.class), 12, zone);
        } finally {
            beans.close();
        }
    }
}
