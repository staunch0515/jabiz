package com.jabiz.quizbuks.usage;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** F-C2: what a run of the snapshot writes and deletes, when a crossing is noticed, and the job's time of day. */
class UsageRulesTest {

    private static final BigDecimal FREE = BigDecimal.valueOf(1000);

    private static BigDecimal n(long value) {
        return BigDecimal.valueOf(value);
    }

    private static Map<String, BigDecimal> measured(long storage) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        for (String meter : UsageMeters.codes()) {
            values.put(meter, n(1));
        }
        values.put(UsageMeters.STORAGE_BYTES, n(storage));
        return values;
    }

    @Test
    void aNewDayInsertsEveryMeterAndARunAgainChangesOnlyWhatChanged() {
        UsageRules.Plan first = UsageRules.plan(measured(10), Map.of(), null, FREE, List.of());
        assertThat(first.inserts().keySet()).containsExactlyInAnyOrderElementsOf(UsageMeters.codes());
        assertThat(first.updates()).isEmpty();
        assertThat(first.crossed()).isFalse();

        Map<String, UsageRules.Row> today = new LinkedHashMap<>();
        first.inserts().forEach((meter, value) -> today.put(meter, new UsageRules.Row("id-" + meter, 1L, value)));
        UsageRules.Plan same = UsageRules.plan(measured(10), today, null, FREE, List.of());
        assertThat(same.inserts()).isEmpty();
        assertThat(same.updates()).isEmpty();

        UsageRules.Plan changed = UsageRules.plan(measured(20), today, null, FREE, List.of());
        assertThat(changed.inserts()).isEmpty();
        assertThat(changed.updates()).singleElement()
            .isEqualTo(new UsageRules.Update("id-" + UsageMeters.STORAGE_BYTES, 1L, n(20)));
    }

    @Test
    void aMeterTheMeasureLacksIsLeftAsItWas() {
        Map<String, BigDecimal> some = Map.of(UsageMeters.STORAGE_FILES, n(3));
        UsageRules.Plan plan = UsageRules.plan(some, Map.of(), null, FREE, List.of());
        assertThat(plan.inserts()).containsOnlyKeys(UsageMeters.STORAGE_FILES);
    }

    @Test
    void theOldRowsAreDeleted() {
        List<UsageRules.Delete> old = List.of(new UsageRules.Delete("a", 1L), new UsageRules.Delete("b", 2L));
        assertThat(UsageRules.plan(measured(1), Map.of(), null, FREE, old).deletes()).isEqualTo(old);
        LocalDate day = LocalDate.of(2026, 11, 7);
        assertThat(UsageRules.firstKept(day, 800)).isEqualTo(day.minusDays(799));
        assertThat(UsageRules.firstKept(day, 1)).isEqualTo(day);
        assertThat(UsageRules.firstKept(day, 0)).as("at least the day itself").isEqualTo(day);
    }

    @Test
    void aCrossingIsNoticedOnceUntilTheStorageGoesBackBelow() {
        // No snapshot before: above now is a crossing.
        assertThat(UsageRules.crossed(null, n(1001), FREE)).isTrue();
        // At the amount is not above it.
        assertThat(UsageRules.crossed(null, n(1000), FREE)).isFalse();
        assertThat(UsageRules.crossed(n(1000), n(1001), FREE)).isTrue();
        // Staying above is the same crossing.
        assertThat(UsageRules.crossed(n(1001), n(5000), FREE)).isFalse();
        // Below again, then above: a new crossing.
        assertThat(UsageRules.crossed(n(999), n(1001), FREE)).isTrue();
        // No free amount set: nothing is noticed.
        assertThat(UsageRules.crossed(null, n(1_000_000), null)).isFalse();
        assertThat(UsageRules.plan(measured(1001), Map.of(), n(10), FREE, List.of()).crossed()).isTrue();
        assertThat(UsageRules.over(n(1001), FREE)).isTrue();
        assertThat(UsageRules.over(n(1000), FREE)).isFalse();
        assertThat(UsageRules.over(n(1001), null)).isFalse();
    }

    @Test
    void theJobRunsAtATimeOfDayOfTheDeploymentsZone() {
        assertThat(UsageRules.cron("03:17")).isEqualTo("0 17 3 * * *");
        assertThat(UsageRules.cron(" 23:59 ")).isEqualTo("0 59 23 * * *");
        assertThat(UsageRules.cron("00:00")).isEqualTo("0 0 0 * * *");
        for (String wrong : new String[] {"3:17", "24:00", "03:60", "0317", "", "03:17:00"}) {
            assertThat(UsageRules.validTime(wrong)).as(wrong).isFalse();
            assertThatThrownBy(() -> UsageRules.cron(wrong)).as(wrong).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(QbUsage.SNAPSHOT_TIME_SETTING);
        }
        assertThatThrownBy(() -> UsageRules.cron(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(UsageRules.validTime(QbUsage.SNAPSHOT_TIME_DEFAULT)).isTrue();
    }

    @Test
    void theDayAndMonthAreTheZones() {
        Instant at = Instant.parse("2026-10-31T18:17:00Z");
        assertThat(UsageRules.dayOf(at, ZoneId.of("Asia/Tokyo"))).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(UsageRules.dayOf(at, ZoneOffset.UTC)).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(UsageRules.month(LocalDate.of(2026, 11, 1))).isEqualTo("2026-11");
    }

    @Test
    void megabytesHaveOneDecimal() {
        assertThat(UsageRules.megabytes(n(524_288_000))).isEqualTo("500.0");
        assertThat(UsageRules.megabytes(n(1_572_864))).isEqualTo("1.5");
        assertThat(UsageRules.megabytes(null)).isEqualTo("0.0");
    }
}
