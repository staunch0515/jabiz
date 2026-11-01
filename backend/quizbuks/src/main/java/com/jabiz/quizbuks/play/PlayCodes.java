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
    /** Q25: the publication is for its invitation list and the caller is not on it. */
    public static final String NOT_INVITED = com.jabiz.quizbuks.invitation.InvitationCodes.NOT_INVITED;
    public static final String BUDGET_FULL = "QB_PUBLICATION_BUDGET_FULL";
    public static final String QUOTA_FULL = "QB_PUBLICATION_QUOTA_FULL";
    public static final String FUNDS_SHORT = "QB_SPONSOR_FUNDS_SHORT";
    public static final String ALREADY_TAKEN = "QB_ATTEMPT_ALREADY_TAKEN";
    /**
     * The publication switched to another version of its quiz while this start read it (Q51): nothing is started, and
     * starting again reads the new version.
     */
    public static final String VERSION_CHANGED = "QB_ATTEMPT_VERSION_CHANGED";
    public static final String OWN_PUBLICATION = "QB_ATTEMPT_OWN_PUBLICATION";

    // Saving and submitting.
    public static final String EXPIRED = "QB_ATTEMPT_EXPIRED";
    public static final String NOT_IN_PROGRESS = "QB_ATTEMPT_NOT_IN_PROGRESS";
    public static final String FINISHED = "QB_ATTEMPT_FINISHED";
    public static final String ANSWER_INVALID = "QB_ANSWER_INVALID";
    /** A survey submitted with questions unanswered (Q12, D-Q12-5). */
    public static final String INCOMPLETE = "QB_ATTEMPT_INCOMPLETE";
    /** A survey's single-choice question answered with several options (Q12, D-Q12-5). */
    public static final String SINGLE_CHOICE = "QB_ANSWER_SINGLE_CHOICE";

    /**
     * A submission (or expiry) names another publication than its attempt's: the publication is its queue's key
     * (platform decision D45), checked before the lock.
     */
    public static final String PUBLICATION_MISMATCH = "QB_ATTEMPT_PUBLICATION_MISMATCH";

    /** Paying a reward: the commission rate in effect is not from 0 to below 1 (a misconfigured parameter). */
    public static final String FEE_RATE_INVALID = "QB_FEE_RATE_INVALID";

    /** Not a refusal: the result page's notice that the reward waits for the sponsor's top-up (U-34). */
    public static final String REWARD_PENDING = "QB_REWARD_PENDING";
    /** Q22: the notice that the sponsor pays the reward in cash, with its code. */
    public static final String REWARD_CASH = "QB_REWARD_CASH";
    /** Q22: the notice that the prize is collected at the sponsor's place. */
    public static final String REWARD_REDEEM = "QB_REWARD_REDEEM";
    /** Q33: the reward is a digital prize, delivered or to be sent by the sponsor. */
    public static final String REWARD_DIGITAL = "QB_REWARD_DIGITAL";
    /** Q33: the digital prize will be sent by the sponsor. */
    public static final String REWARD_DIGITAL_DUE = "QB_REWARD_DIGITAL_DUE";

    /** S2: a channel's taker submits sooner after starting than anybody can answer ({@link PlayLimits#CHANNEL_MIN_ANSWER}). */
    public static final String TOO_FAST = "QB_ATTEMPT_TOO_FAST";
    /** Q73b: the language asked for is not one the publication offers. */
    public static final String LANGUAGE = "QB_ATTEMPT_LANGUAGE";
    /** Q75 (D-Q75-4): the video to watch was not watched (by the App's report) before starting. */
    public static final String WATCH_REQUIRED = "QB_ATTEMPT_WATCH_REQUIRED";

    public static final List<String> ALL = List.of(TOO_FAST, NOT_OPEN, NOT_VISIBLE, BUDGET_FULL, QUOTA_FULL, FUNDS_SHORT,
        ALREADY_TAKEN, VERSION_CHANGED, OWN_PUBLICATION, EXPIRED, NOT_IN_PROGRESS, FINISHED, ANSWER_INVALID, INCOMPLETE, SINGLE_CHOICE, PUBLICATION_MISMATCH,
        FEE_RATE_INVALID, REWARD_PENDING, REWARD_CASH, REWARD_REDEEM, REWARD_DIGITAL,
        REWARD_DIGITAL_DUE, LANGUAGE, WATCH_REQUIRED);

    private PlayCodes() {}
}
