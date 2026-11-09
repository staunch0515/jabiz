package com.jabiz.quizbuks.help;

import com.jabiz.process.BlockingStep;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The gauge {@value #NAME} (F-C10): the share of {@code qb.help.global-monthly-tokens} the AI help used this month, in
 * whole percent, as the last job outcome or daily check of this instance committed it (0 from the start until then),
 * for the alert {@code qb-help-usage-high} (deploy/quizbuks/alerting/quizbuks-alerts.yaml). Set after the commit, so an
 * outcome rolled back sets nothing. No tags (platform 13: names and outcomes only).
 */
@Component
public class HelpUsageGauge implements BlockingStep<NoMetadata, ProcessContext> {

    public static final String NAME = "qb.help.usage.percent";
    /** Where the processes leave the value. */
    static final String PERCENT = "helpUsagePercent";

    private final AtomicLong percent = new AtomicLong();

    public HelpUsageGauge(MeterRegistry registry) {
        Gauge.builder(NAME, percent, AtomicLong::get)
            .description("Share of the AI help's monthly tokens used, in percent")
            .register(registry);
    }

    @Override
    public void run(NoMetadata metadata, ProcessContext ctx) {
        if (ctx.contains(PERCENT)) {
            percent.set(((Number) ctx.get(PERCENT)).longValue());
        }
    }

    /** The value now (tests). */
    public long value() {
        return percent.get();
    }
}
