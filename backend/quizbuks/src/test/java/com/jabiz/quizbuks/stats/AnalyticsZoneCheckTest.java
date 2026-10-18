package com.jabiz.quizbuks.stats;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsZoneCheckTest {

    @Test
    void regionsAndUtcAreAccepted() {
        for (String zone : java.util.List.of("Asia/Tokyo", "UTC", "Europe/London", "America/Argentina/Buenos_Aires",
            "Asia/Kolkata")) {
            assertThat(AnalyticsZoneCheck.problems(zone)).as(zone).isEmpty();
        }
    }

    @Test
    void offsetsThatPostgresqlReadsTheOtherWayRoundAreRefused() {
        for (String zone : java.util.List.of("+09:00", "-05:00", "Z", "UTC+9", "UTC+09:00", "GMT+9", "UT+9",
            "Etc/GMT-9", "GMT")) {
            assertThat(AnalyticsZoneCheck.problems(zone)).as(zone).singleElement()
                .satisfies(problem -> assertThat(problem.toString()).contains("jabiz.fiscal-year-zone", "offset"));
        }
    }

    @Test
    void anInvalidNameIsLeftToThePlatformsCheck() {
        assertThat(AnalyticsZoneCheck.problems("Nowhere/Never")).isEmpty();
    }
}
