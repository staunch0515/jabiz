package com.jabiz.quizbuks.help;

import com.jabiz.process.BlockingStep;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.quizbuks.content.ai.AiLimits;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The gauges of the AI help's month (F-C10, F-M10), as the last job outcome or daily check of this instance committed
 * them (0 from the start until then), for the alerts {@code qb-help-usage-high} and {@code qb-help-cost-high}
 * (deploy/quizbuks/alerting/quizbuks-alerts.yaml):
 * <ul>
 *   <li>{@value #NAME}: the share of {@code qb.help.global-monthly-tokens} used this month, in whole percent;</li>
 *   <li>{@value #COST_NAME}: the estimated dollars of this month, the help and the reviewers' translation checks
 *       together, as a share of {@code qb.help.global-monthly-usd-alert}, in whole percent (0 when the alert is 0).</li>
 * </ul>
 * Each value is kept with the month it belongs to and reads 0 in any other month: an instance that does not run the
 * daily check would otherwise show last month's value for ever (F-M10 M5). Set after the commit, so an outcome rolled
 * back sets nothing. No tags (platform 13: names and outcomes only).
 */
@Component
public class HelpUsageGauge implements BlockingStep<NoMetadata, ProcessContext> {

    public static final String NAME = "qb.help.usage.percent";
    public static final String COST_NAME = "qb.help.cost.percent";
    /** Where the processes leave the values and the month they belong to. */
    static final String PERCENT = "helpUsagePercent";
    static final String COST_PERCENT = "helpCostPercent";
    static final String MONTH = "helpUsageMonth";

    /** A value and its month. */
    private record Reading(String month, long value) {}

    private final AtomicReference<Reading> percent = new AtomicReference<>(new Reading("", 0));
    private final AtomicReference<Reading> cost = new AtomicReference<>(new Reading("", 0));
    private final Clock clock;
    private final ZoneId zone;

    public HelpUsageGauge(MeterRegistry registry, Clock clock,
        @Value("${jabiz.fiscal-year-zone:UTC}") String zone) {
        this.clock = clock;
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(zone);
        } catch (java.time.DateTimeException e) {
            // An invalid zone is the platform's startup error (RETENTION): UTC here so that it is reported, not this.
            zoneId = ZoneOffset.UTC;
        }
        this.zone = zoneId;
        Gauge.builder(NAME, this, HelpUsageGauge::value)
            .description("Share of the AI help's monthly tokens used, in percent")
            .register(registry);
        Gauge.builder(COST_NAME, this, HelpUsageGauge::costValue)
            .description("Estimated dollars of the AI help and the translation checks this month, in percent of the alert")
            .register(registry);
    }

    @Override
    public void run(NoMetadata metadata, ProcessContext ctx) {
        String month = ctx.contains(MONTH) ? String.valueOf(ctx.get(MONTH)) : AiLimits.month(clock.instant(), zone);
        if (ctx.contains(PERCENT)) {
            percent.set(new Reading(month, ((Number) ctx.get(PERCENT)).longValue()));
        }
        if (ctx.contains(COST_PERCENT)) {
            cost.set(new Reading(month, ((Number) ctx.get(COST_PERCENT)).longValue()));
        }
    }

    /** The token share now (0 for any month but this one). */
    public long value() {
        return read(percent.get());
    }

    /** The dollar share now (0 for any month but this one). */
    public long costValue() {
        return read(cost.get());
    }

    private long read(Reading reading) {
        return reading.month().equals(AiLimits.month(clock.instant(), zone)) ? reading.value() : 0;
    }
}
