package com.jabiz.quizbuks.publishing;

import com.jabiz.quizbuks.content.ContentLimits;

import java.time.Duration;

/**
 * Every limit of publishing in one place (docs/quizbuks/plans/Q4-publishing.md, D-Q4-3, D-Q4-6, D-Q4-7; question
 * Q4-7). The requirements give none of them; they are the plan's values.
 */
public final class PublishingLimits {

    /** Reward tiers of one publication. */
    public static final int MAX_TIERS = 20;
    /** Longest time a publication runs, from its start to its end. */
    public static final Duration MAX_RUNNING = Duration.ofDays(366);
    /** Publications one run of the minute's tick moves on; the rest wait for the next minute. */
    public static final int TICK_BATCH = 200;

    public static final int NAME_LENGTH = 100;
    public static final int REJECT_REASON_LENGTH = 500;

    /** Time limit of an attempt in seconds, as a quiz's. */
    public static final int MIN_TIME_LIMIT_SEC = ContentLimits.MIN_TIME_LIMIT_SEC;
    public static final int MAX_TIME_LIMIT_SEC = ContentLimits.MAX_TIME_LIMIT_SEC;

    /** Largest score of a tier or pass mark (column {@code numeric(6, 0)}); a full score is at most 100 000. */
    public static final int MAX_SCORE = 999_999;
    /** Largest number of winners (column {@code numeric(9, 0)}). */
    public static final int MAX_WINNERS = 999_999_999;

    private PublishingLimits() {}
}
