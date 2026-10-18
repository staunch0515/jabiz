package com.jabiz.quizbuks.stats;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Which hour a run's increments go to and what they are (docs/quizbuks/plans/Q9e-analytics.md, D-Q9e-2). The counters
 * only ever grow, so a run reads them, subtracts what the cursor last saw and adds the difference to one hourly
 * bucket: the run's own hour, or the previous run's hour when a whole hour began since (so a run at 11:00 closes 10:00
 * with what came after 10:50). A bucket's edge is thus the first run after the hour, seconds after it. Increments
 * that cover a time the job did not run are not spread over the hours: they all go to the previous run's hour, which is
 * marked as a gap (the trend notes it), as is the first bucket of a publication that ran before the job saw it.
 */
public final class AnalyticsBuckets {

    /**
     * How far into a new hour a run may come and still close the previous hour without a gap: the increments after
     * the hour began that it puts in the previous hour are only those of its few minutes' delay.
     */
    public static final Duration LATE_ALLOWANCE = Duration.ofMinutes(5);

    /** A publication's six cumulative figures: first views, starts, completions, passes, winners and Kudos spent. */
    public record Totals(long views, long starts, long completions, long passes, long winners, BigDecimal spent) {

        public static final Totals ZERO = new Totals(0, 0, 0, 0, 0, BigDecimal.ZERO);

        public Totals {
            spent = spent == null ? BigDecimal.ZERO : spent;
        }

        public Totals minus(Totals other) {
            return new Totals(views - other.views, starts - other.starts, completions - other.completions,
                passes - other.passes, winners - other.winners, spent.subtract(other.spent));
        }

        public Totals plus(Totals other) {
            return new Totals(views + other.views, starts + other.starts, completions + other.completions,
                passes + other.passes, winners + other.winners, spent.add(other.spent));
        }

        public boolean isZero() {
            return views == 0 && starts == 0 && completions == 0 && passes == 0 && winners == 0
                && spent.signum() == 0;
        }

        /** Whether any figure went down, which a counter never does (the run then writes nothing for it). */
        public boolean anyNegative() {
            return views < 0 || starts < 0 || completions < 0 || passes < 0 || winners < 0 || spent.signum() < 0;
        }
    }

    /** The UTC hour {@code time} falls in. */
    public static Instant hourOf(Instant time) {
        return time.truncatedTo(ChronoUnit.HOURS);
    }

    /**
     * The hour a run at {@code at} adds its increments to, before a publication's own start is considered: the
     * previous run's hour when a new hour began since it, else the run's own hour. No previous run (the job's first),
     * or one not before {@code at}: the run's own hour.
     */
    public static Instant bucket(Instant previousRun, Instant at) {
        Instant own = hourOf(at);
        if (previousRun == null || !previousRun.isBefore(at)) {
            return own;
        }
        Instant previous = hourOf(previousRun);
        return previous.isBefore(own) ? previous : own;
    }

    /**
     * Whether the increments a run at {@code at} adds to {@code bucket} include a time the job did not run: the bucket
     * is an earlier hour and the run came more than {@link #LATE_ALLOWANCE} into the hours after it (runs were missed;
     * what happened since lands in the bucket), or the publication went on before the bucket's hour and this is the
     * first time the job writes for it (its earlier history lands in this one bucket). Runs missed within one hour
     * are no gap: everything they would have counted still belongs to that hour.
     */
    public static boolean gap(Instant at, Instant bucket, Instant wentOnAt, boolean first) {
        Instant next = bucket.plus(Duration.ofHours(1));
        boolean missed = next.isBefore(at) && Duration.between(next, at).compareTo(LATE_ALLOWANCE) > 0;
        boolean history = first && wentOnAt != null && wentOnAt.isBefore(bucket);
        return missed || history;
    }

    private AnalyticsBuckets() {}
}
