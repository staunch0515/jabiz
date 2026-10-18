package com.jabiz.quizbuks.stats;

import java.time.Duration;

/** The limits and times of the analytics job (docs/quizbuks/plans/Q9e-analytics.md, D-Q9e-2). */
public final class AnalyticsLimits {

    /**
     * How far behind the run first views and finished attempts are read: their time is taken when their process starts,
     * before its transaction commits (the queue and lock waits of a submission are seconds, far below this).
     */
    public static final Duration LAG = Duration.ofMinutes(5);

    /** Ended publications are read for this long after their end (their buckets: late first views and completions). */
    public static final Duration RECENT = Duration.ofDays(1);

    /** A publication is recounted whole this long after it ended, once its attempts have all finished. */
    public static final Duration FINALIZE_AFTER = Duration.ofMinutes(30);

    /**
     * After this long the recount is accepted even when it does not match the completions' counter (written in the
     * log): an attempt whose time could never be read must not keep a publication in every run.
     */
    public static final Duration FINALIZE_ANYWAY_AFTER = Duration.ofDays(1);

    /** Hourly buckets are kept this long (E-9) and then deleted by the job. */
    public static final Duration RETENTION = Duration.ofDays(400);

    /** Most expired hourly buckets one run deletes; the next runs delete the rest. */
    public static final int PURGE_BATCH = 2_000;

    /** Most publications the job reads in one run (those running or ended lately). */
    public static final int BATCH = 100_000;

    /**
     * Publications per chunk ({@code quizbuks.analytics.chunk-size}): a chunk is one item of the per-item job, so one
     * transaction of its own (review M3), writing at most two rows per publication and one per question when the shown
     * statistics change. A chunk that fails is retried by later runs and holds up only its own publications.
     */
    public static final int CHUNK_DEFAULT = 100;
    public static final int CHUNK_MAX = 200;

    /**
     * Most finished attempts one chunk reads per run ({@code quizbuks.analytics.max-attempts-per-chunk}): a hot
     * publication's backlog is read over several runs. The default is above a hot publication's ten minutes (50
     * completions a second: 30,000), so that it keeps up (docs/quizbuks/perf/Q9e-analytics.md).
     */
    public static final int ATTEMPTS_DEFAULT = 50_000;
    public static final int ATTEMPTS_MAX = 200_000;

    /**
     * Most completions a publication is recounted whole for after its end ({@code quizbuks.analytics.max-recount}): a
     * recount of 230,000 took about 6 seconds (docs/quizbuks/perf/Q9e-analytics.md), so a million stays well within
     * {@link #QUERY_TIMEOUT}. A larger publication is finalized with the totals read so far once they have caught up
     * with its completions (they are the same attempts, read in pieces), without the recount's cross-check.
     */
    public static final long RECOUNT_DEFAULT = 1_000_000;

    /** The query time limit of the job's reads (a finished publication is recounted whole once). */
    public static final Duration QUERY_TIMEOUT = Duration.ofMinutes(2);

    private AnalyticsLimits() {}
}
