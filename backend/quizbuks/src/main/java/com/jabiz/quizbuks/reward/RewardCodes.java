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

    /** The redemption is not in a state the action applies to (Q22b). */
    public static final String REDEMPTION_STATE = "QB_REDEMPTION_STATE";
    /** The scanned code is not one of a sponsor's now: unknown, expired (a rotating one) or replaced (a printed one). */
    public static final String REDEEM_TOKEN_INVALID = "QB_REDEEM_TOKEN_INVALID";
    /** The prize is from another sponsor than the code scanned. */
    public static final String REDEEM_TOKEN_SPONSOR = "QB_REDEEM_TOKEN_SPONSOR";
    /** An unknown kind of code. */
    public static final String REDEEM_TOKEN_KIND = "QB_REDEEM_TOKEN_KIND";
    /** The claim's ten minutes passed before the sponsor confirmed: the taker scans again. */
    public static final String REDEMPTION_CLAIM_LAPSED = "QB_REDEMPTION_CLAIM_LAPSED";

    public static final List<String> ALL = List.of(CASH_STATE, CASH_DISPUTE_NOTE, CASH_RESOLUTION, CASH_CONFIRM_DUE,
        REDEMPTION_STATE, REDEEM_TOKEN_INVALID, REDEEM_TOKEN_SPONSOR, REDEEM_TOKEN_KIND, REDEMPTION_CLAIM_LAPSED);

    private RewardCodes() {}
}
