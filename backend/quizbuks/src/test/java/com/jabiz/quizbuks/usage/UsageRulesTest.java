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
    void aNewDayWritesOneRowOfEveryMeterAndARunAgainOnlyWhenAValueChanged() {
        UsageRules.Plan first = UsageRules.plan(measured(10), null, null, FREE, List.of());
        assertThat(first.write()).isTrue();
        assertThat(first.meters().keySet()).containsExactlyElementsOf(UsageMeters.codes());
        assertThat(first.crossed()).isFalse();

        UsageRules.Row today = new UsageRules.Row("id", 1L, first.meters());
        assertThat(UsageRules.plan(measured(10), today, null, FREE, List.of()).write()).isFalse();
        UsageRules.Plan changed = UsageRules.plan(measured(20), today, null, FREE, List.of());
        assertThat(changed.write()).isTrue();
        assertThat(changed.meters()).containsEntry(UsageMeters.STORAGE_BYTES, n(20));
        // A stored row with another set of keys (a meter added since) is rewritten with exactly the listed ones.
        UsageRules.Row older = new UsageRules.Row("id", 1L, Map.of(UsageMeters.STORAGE_BYTES, n(20)));
        assertThat(UsageRules.plan(measured(20), older, null, FREE, List.of()).write()).isTrue();
    }

    @Test
    void aMeasureLackingAMeterFailsTheItem() {
        Map<String, BigDecimal> some = Map.of(UsageMeters.STORAGE_FILES, n(3));
        assertThatThrownBy(() -> UsageRules.plan(some, null, null, FREE, List.of()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining(UsageMeters.STORAGE_BYTES);
    }

    @Test
    void theStoredFormIsAJsonObjectOfTheMeters() {
        Map<String, BigDecimal> meters = measured(524_288_000);
        String json = UsageRules.json(meters);
        assertThat(json).startsWith("{\"storage.bytes\":524288000,\"storage.files\":1,");
        assertThat(UsageRules.meters(json)).isEqualTo(meters);
        assertThat(UsageRules.meters("{\"storage.bytes\":\"x\",\"future.meter\":5}"))
            .containsExactly(Map.entry("future.meter", n(5)));
        assertThat(UsageRules.meters("not json")).isEmpty();
        assertThat(UsageRules.meters(null)).isEmpty();
    }

    @Test
    void theOldRowsAreDeleted() {
        List<UsageRules.Delete> old = List.of(new UsageRules.Delete("a", 1L), new UsageRules.Delete("b", 2L));
        assertThat(UsageRules.plan(measured(1), null, null, FREE, old).deletes()).isEqualTo(old);
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
        assertThat(UsageRules.plan(measured(1001), null, n(10), FREE, List.of()).crossed()).isTrue();
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
