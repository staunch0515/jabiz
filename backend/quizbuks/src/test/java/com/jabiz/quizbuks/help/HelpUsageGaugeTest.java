package com.jabiz.quizbuks.help;

import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessStart;
import com.jabiz.context.RequestContext;
import com.jabiz.process.IdAssigner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** F-M10 (review M5, M4): the two gauges keep the month their value belongs to and read 0 in any other. */
class HelpUsageGaugeTest {

    /** A clock the test moves. */
    private static final class Moving extends Clock {
        Instant now = Instant.parse("2026-10-15T00:00:00Z");

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static ProcessContext set(Long usage, Long cost, String month) {
        ProcessContext ctx = new ProcessContext(new ProcessStart(1L, Instant.EPOCH,
            new RequestContext("it", null, Locale.ENGLISH, "req", Set.of(), Set.of("it.process")), IdAssigner.NONE));
        if (usage != null) {
            ctx.put(HelpUsageGauge.PERCENT, usage);
        }
        if (cost != null) {
            ctx.put(HelpUsageGauge.COST_PERCENT, cost);
        }
        if (month != null) {
            ctx.put(HelpUsageGauge.MONTH, month);
        }
        return ctx;
    }

    @Test
    void aValueReadsInItsMonthAndZeroInTheNextAndEachGaugeIsSetOnItsOwn() {
        Moving clock = new Moving();
        HelpUsageGauge gauge = new HelpUsageGauge(new SimpleMeterRegistry(), clock, "UTC");
        assertThat(gauge.value()).isZero();
        assertThat(gauge.costValue()).isZero();

        gauge.run(NoMetadata.INSTANCE, set(40L, 120L, "2026-10"));
        assertThat(gauge.value()).isEqualTo(40);
        assertThat(gauge.costValue()).isEqualTo(120);

        // Only the dollars (a translation check that finishes): the token share stays.
        gauge.run(NoMetadata.INSTANCE, set(null, 150L, "2026-10"));
        assertThat(gauge.value()).isEqualTo(40);
        assertThat(gauge.costValue()).isEqualTo(150);

        // Nothing in the context: nothing changes.
        gauge.run(NoMetadata.INSTANCE, set(null, null, null));
        assertThat(gauge.value()).isEqualTo(40);

        // The next month, no check run yet on this instance: last month's values are not shown.
        clock.now = Instant.parse("2026-11-01T00:30:00Z");
        assertThat(gauge.value()).isZero();
        assertThat(gauge.costValue()).isZero();

        // A value for the month before (a late outcome of the month that ended) does not show either.
        gauge.run(NoMetadata.INSTANCE, set(90L, 90L, "2026-10"));
        assertThat(gauge.value()).isZero();
        gauge.run(NoMetadata.INSTANCE, set(5L, 7L, "2026-11"));
        assertThat(gauge.value()).isEqualTo(5);
        assertThat(gauge.costValue()).isEqualTo(7);
    }

    @Test
    void theMonthIsTheDeploymentsZonesNotUtc() {
        Moving clock = new Moving();
        clock.now = Instant.parse("2026-10-31T16:00:00Z");
        HelpUsageGauge tokyo = new HelpUsageGauge(new SimpleMeterRegistry(), clock, "Asia/Tokyo");
        // 01:00 on 1 November in Tokyo.
        tokyo.run(NoMetadata.INSTANCE, set(10L, 20L, "2026-11"));
        assertThat(tokyo.value()).isEqualTo(10);
        HelpUsageGauge utc = new HelpUsageGauge(new SimpleMeterRegistry(), clock, "UTC");
        utc.run(NoMetadata.INSTANCE, set(10L, 20L, "2026-11"));
        assertThat(utc.value()).isZero();
    }
}
