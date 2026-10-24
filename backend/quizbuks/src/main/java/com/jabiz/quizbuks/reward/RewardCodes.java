package com.jabiz.quizbuks.reward;

import java.util.List;

/**
 * The refusal codes of cash rewards and redemptions (docs/quizbuks/plans/Q22-reward-methods.md), each with a message
 * in the three languages. The processes refuse with 422; another's record is 404 without saying which.
 */
public final class RewardCodes {

    /** The cash reward is not in a state the action applies to (marking paid what is confirmed, and so on). */
    public static final String CASH_STATE = "QB_CASH_REWARD_STATE";
    /** A dispute needs a note saying what is wrong. */
    public static final String CASH_DISPUTE_NOTE = "QB_CASH_REWARD_DISPUTE_NOTE";
    /** An unknown resolution of a dispute. */
    public static final String CASH_RESOLUTION = "QB_CASH_REWARD_RESOLUTION";
    /**
     * The caller has a cash reward they neither confirmed nor disputed in time: no new attempt until they do
     * (QB_ATTEMPT_START).
     */
    public static final String CASH_CONFIRM_DUE = "QB_CASH_CONFIRM_DUE";

    public static final List<String> ALL = List.of(CASH_STATE, CASH_DISPUTE_NOTE, CASH_RESOLUTION, CASH_CONFIRM_DUE);

    private RewardCodes() {}
}
