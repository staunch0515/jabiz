package com.jabiz.quizbuks.publishing;

import java.util.List;

/**
 * The states of a publication (docs/quizbuks/plans/Q4-publishing.md, D-Q4-1). The states hold the switches people
 * and money throw (review, pause, funds, end); start and end times are the time fields themselves, and whether a
 * publication takes new attempts is decided by {@link PublicationRules#acceptsNewAttempts} alone.
 */
public final class PublicationStatus {

    public static final String DRAFT = "DRAFT";
    public static final String PENDING_REVIEW = "PENDING_REVIEW";
    public static final String SCHEDULED = "SCHEDULED";
    public static final String LIVE = "LIVE";
    public static final String PAUSED = "PAUSED";
    /** The sponsor's wallet cannot pay a reward (Q5, Q6). */
    public static final String FUNDS_SHORT = "FUNDS_SHORT";
    public static final String ENDED = "ENDED";

    public static final List<String> ALL = List.of(DRAFT, PENDING_REVIEW, SCHEDULED, LIVE, PAUSED, FUNDS_SHORT,
        ENDED);

    /** The states that end when the end time comes. */
    public static final List<String> RUNNING = List.of(SCHEDULED, LIVE, PAUSED, FUNDS_SHORT);

    /** Why a publication ended: its end time came. */
    public static final String EXPIRED = "EXPIRED";
    /** Its maximum number of winners is reached (Q5). */
    public static final String QUOTA = "QUOTA";
    /** Its budget is spent (Q5). */
    public static final String BUDGET = "BUDGET";

    public static final List<String> END_REASONS = List.of(EXPIRED, QUOTA, BUDGET);

    private PublicationStatus() {}
}
