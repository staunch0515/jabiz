package com.jabiz.quizbuks.stats;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsBucketsTest {

    static Instant t(String time) {
        return Instant.parse("2026-10-18T" + time + "Z");
    }

    @Test
    void aRunAddsToItsOwnHourWhileNoHourBeganSinceThePreviousRun() {
        assertThat(AnalyticsBuckets.bucket(t("10:10:00"), t("10:20:00"))).isEqualTo(t("10:00:00"));
        assertThat(AnalyticsBuckets.bucket(t("10:50:00"), t("10:59:59"))).isEqualTo(t("10:00:00"));
    }

    @Test
    void theFirstRunAfterAnHourClosesThePreviousHour() {
        // 11:00 closes 10:00 with what came after 10:50; 11:10 adds to 11:00.
        assertThat(AnalyticsBuckets.bucket(t("10:50:00"), t("11:00:00"))).isEqualTo(t("10:00:00"));
        assertThat(AnalyticsBuckets.bucket(t("11:00:00"), t("11:10:00"))).isEqualTo(t("11:00:00"));
    }

    @Test
    void afterAStopEverythingGoesToThePreviousRunsHourAsAGap() {
        Instant previous = t("10:50:00");
        Instant at = t("14:00:00");
        Instant bucket = AnalyticsBuckets.bucket(previous, at);
        assertThat(bucket).isEqualTo(t("10:00:00"));
        assertThat(AnalyticsBuckets.gap(at, bucket, t("09:00:00"), false)).isTrue();
        // A run on time is no gap; nor is one a few minutes late.
        assertThat(AnalyticsBuckets.gap(t("11:00:00"), t("10:00:00"), t("09:00:00"), false)).isFalse();
        assertThat(AnalyticsBuckets.gap(t("11:05:00"), t("10:00:00"), t("09:00:00"), false)).isFalse();
        assertThat(AnalyticsBuckets.gap(t("11:05:01"), t("10:00:00"), t("09:00:00"), false)).isTrue();
        // Runs missed within the hour are no gap: everything still belongs to it.
        assertThat(AnalyticsBuckets.bucket(t("10:10:00"), t("10:50:00"))).isEqualTo(t("10:00:00"));
        assertThat(AnalyticsBuckets.gap(t("10:50:00"), t("10:00:00"), t("09:00:00"), false)).isFalse();
    }

    @Test
    void theFirstRunAndARepeatAddToTheirOwnHour() {
        assertThat(AnalyticsBuckets.bucket(null, t("10:20:00"))).isEqualTo(t("10:00:00"));
        assertThat(AnalyticsBuckets.bucket(t("10:20:00"), t("10:20:00"))).isEqualTo(t("10:00:00"));
        assertThat(AnalyticsBuckets.bucket(t("11:20:00"), t("10:20:00"))).isEqualTo(t("10:00:00"));
        assertThat(AnalyticsBuckets.gap(t("10:20:00"), t("10:00:00"), t("10:05:00"), true)).isFalse();
    }

    @Test
    void aPublicationsHistoryBeforeItsFirstBucketIsAGap() {
        assertThat(AnalyticsBuckets.gap(t("10:20:00"), t("10:00:00"), t("08:30:00"), true)).isTrue();
        assertThat(AnalyticsBuckets.gap(t("10:20:00"), t("10:00:00"), t("08:30:00"), false))
            .isFalse();
        assertThat(AnalyticsBuckets.gap(t("10:20:00"), t("10:00:00"), t("10:00:00"), true)).isFalse();
    }

    @Test
    void incrementsAreTheDifferenceAndNeverNegative() {
        AnalyticsBuckets.Totals before = new AnalyticsBuckets.Totals(3, 2, 1, 1, 1, new BigDecimal("100"));
        AnalyticsBuckets.Totals after = new AnalyticsBuckets.Totals(5, 4, 2, 1, 1, new BigDecimal("110"));
        AnalyticsBuckets.Totals increments = after.minus(before);
        assertThat(increments).isEqualTo(new AnalyticsBuckets.Totals(2, 2, 1, 0, 0, new BigDecimal("10")));
        assertThat(increments.isZero()).isFalse();
        assertThat(increments.anyNegative()).isFalse();
        assertThat(before.plus(increments)).isEqualTo(after);
        assertThat(after.minus(after).isZero()).isTrue();
        assertThat(before.minus(after).anyNegative()).isTrue();
        assertThat(new AnalyticsBuckets.Totals(0, 0, 0, 0, 0, new BigDecimal("-1")).anyNegative()).isTrue();
        assertThat(new AnalyticsBuckets.Totals(0, 0, 0, 0, 0, null).isZero()).isTrue();
    }

    @Test
    void hoursAreUtcHours() {
        assertThat(AnalyticsBuckets.hourOf(Instant.parse("2026-10-18T10:59:59.999Z"))).isEqualTo(t("10:00:00"));
    }
}
