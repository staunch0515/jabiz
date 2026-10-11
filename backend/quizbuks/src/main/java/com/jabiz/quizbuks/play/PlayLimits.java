package com.jabiz.quizbuks.play;

import com.jabiz.quizbuks.content.ContentLimits;

import java.time.Duration;

/** Every limit of answering in one place (docs/quizbuks/plans/Q5-answering.md). */
public final class PlayLimits {

    /**
     * How long after its deadline an attempt is still submitted by its taker (network delay, D-Q5-1); after that the
     * submission is refused and the attempt is finished with its saved answers.
     */
    public static final Duration SUBMIT_GRACE = Duration.ofSeconds(5);

    /**
     * Pending rewards one run of {@code QB_PENDING_SETTLE} pays, in order, under the sponsor's wallet lock; the rest
     * in the next run, started by an event in a transaction of its own (D-Q5-5).
     */
    public static final int SETTLE_BATCH = 50;

    /** Attempts one run of the expiry job finishes, by deadline (Q5b, D-Q5-9). */
    public static final int EXPIRE_BATCH = 2_000;

    /** Publications whose completions the statistics job counts in one run: those live or ended lately. */
    public static final int STATS_BATCH = 10_000;

    /** Days after its end a publication's completion counts are still refreshed (the 30-day window and a day). */
    public static final int STATS_DAYS = 31;

    /**
     * Longest canonical JSON of an attempt's option order and of its answers: one list of at most
     * {@link ContentLimits#MAX_OPTIONS} one-digit numbers per question, for {@link ContentLimits#MAX_QUESTIONS}.
     */
    public static final int ANSWERS_LENGTH = 4 + ContentLimits.MAX_QUESTIONS * (3 + 2 * ContentLimits.MAX_OPTIONS);

    /**
     * Longest JSON of an attempt's result: per question its number, points, what was earned and the chosen and correct
     * options (some 100 characters at most).
     */
    public static final int RESULT_LENGTH = 4 + ContentLimits.MAX_QUESTIONS * 120;

    private PlayLimits() {}
}
