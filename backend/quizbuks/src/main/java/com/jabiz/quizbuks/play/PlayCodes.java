package com.jabiz.quizbuks.play;

import java.util.List;

/**
 * The refusal and message codes of answering (docs/quizbuks/plans/Q5-answering.md, 流程), each with a message in the
 * three languages. The processes refuse with 422; another taker's attempt, or a publication that never went on, is
 * 404 without saying which.
 */
public final class PlayCodes {

    // Starting an attempt.
    public static final String NOT_OPEN = "QB_PUBLICATION_NOT_OPEN";
    public static final String NOT_VISIBLE = "QB_PUBLICATION_NOT_VISIBLE";
    public static final String BUDGET_FULL = "QB_PUBLICATION_BUDGET_FULL";
    public static final String QUOTA_FULL = "QB_PUBLICATION_QUOTA_FULL";
    public static final String FUNDS_SHORT = "QB_SPONSOR_FUNDS_SHORT";
    public static final String ALREADY_TAKEN = "QB_ATTEMPT_ALREADY_TAKEN";
    public static final String OWN_PUBLICATION = "QB_ATTEMPT_OWN_PUBLICATION";

    // Saving and submitting.
    public static final String EXPIRED = "QB_ATTEMPT_EXPIRED";
    public static final String NOT_IN_PROGRESS = "QB_ATTEMPT_NOT_IN_PROGRESS";
    public static final String FINISHED = "QB_ATTEMPT_FINISHED";
    public static final String ANSWER_INVALID = "QB_ANSWER_INVALID";

    /** Paying a reward: the commission rate in effect is not from 0 to below 1 (a misconfigured parameter). */
    public static final String FEE_RATE_INVALID = "QB_FEE_RATE_INVALID";

    /** Not a refusal: the result page's notice that the reward waits for the sponsor's top-up (U-34). */
    public static final String REWARD_PENDING = "QB_REWARD_PENDING";

    public static final List<String> ALL = List.of(NOT_OPEN, NOT_VISIBLE, BUDGET_FULL, QUOTA_FULL, FUNDS_SHORT,
        ALREADY_TAKEN, OWN_PUBLICATION, EXPIRED, NOT_IN_PROGRESS, FINISHED, ANSWER_INVALID, FEE_RATE_INVALID,
        REWARD_PENDING);

    private PlayCodes() {}
}
