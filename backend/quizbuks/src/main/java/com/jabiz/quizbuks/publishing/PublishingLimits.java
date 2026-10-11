package com.jabiz.quizbuks.publishing;

import com.jabiz.quizbuks.content.ContentLimits;

import java.math.BigDecimal;
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
    /**
     * Publications one run of the minute's tick moves on, each in a transaction of its own (Q5b, platform decision
     * D42); the rest wait for the next minute. {@code quizbuks.publication-tick.max-items} may lower it (tests).
     */
    public static final int TICK_BATCH = 2_000;

    public static final int NAME_LENGTH = 100;
    public static final int REJECT_REASON_LENGTH = 500;
    /**
     * Longest JSON of a card's tiers ({@link CardTiers}): some 100 characters a tier at most (a key, two scores of six
     * digits and a reward of thirteen) for {@link #MAX_TIERS} tiers, with room to spare.
     */
    public static final int CARD_TIERS_LENGTH = 4_000;

    /** Time limit of an attempt in seconds, as a quiz's. */
    public static final int MIN_TIME_LIMIT_SEC = ContentLimits.MIN_TIME_LIMIT_SEC;
    public static final int MAX_TIME_LIMIT_SEC = ContentLimits.MAX_TIME_LIMIT_SEC;

    /** Largest score of a tier or pass mark (column {@code numeric(6, 0)}); a full score is at most 100 000. */
    public static final int MAX_SCORE = 999_999;
    /**
     * Largest amount of money (Kudos) of a reward, a budget or the most a publication pays out: well within the
     * columns ({@code numeric(19, 0)}) even multiplied by the most winners.
     */
    public static final BigDecimal MAX_MONEY = BigDecimal.TEN.pow(12);
    /** Largest number of winners (column {@code numeric(9, 0)}). */
    public static final int MAX_WINNERS = 999_999_999;

    private PublishingLimits() {}
}
