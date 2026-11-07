package com.jabiz.quizbuks.wallet;

import com.jabiz.process.BlockingStep;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The gauge {@value #NAME}: the differences the last reconciliation this instance committed found (0 when it found
 * none, and from the start until its first run), for the alert {@code qb-reconcile-diff}
 * (deploy/quizbuks/alerting/quizbuks-alerts.yaml). The differences themselves are in {@code qb.admin.recon-runs}; the
 * platform's metrics have the job's outcome but not the run's status. Set after the commit, so a run rolled back sets
 * nothing; a replayed run (one that had run already) leaves it as it is. No tags (platform 13: names and outcomes only).
 */
@Component
public class ReconcileGauge implements BlockingStep<NoMetadata, ProcessContext> {

    public static final String NAME = "qb.reconcile.differences";

    private final AtomicLong differences = new AtomicLong();

    public ReconcileGauge(MeterRegistry registry) {
        Gauge.builder(NAME, differences, AtomicLong::get)
            .description("Differences found by the last reconciliation of this instance")
            .register(registry);
    }

    @Override
    public void run(NoMetadata metadata, ProcessContext ctx) {
        if (ctx.contains(Reconciliation.RESULT)) {
            differences.set(ctx.get(Reconciliation.RESULT, ReconChecks.Result.class).differences());
        }
    }

    /** The value now (tests). */
    long value() {
        return differences.get();
    }
}
