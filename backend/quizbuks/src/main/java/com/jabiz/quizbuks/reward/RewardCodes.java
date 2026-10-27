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
    /** Q33: the digital prize is not in a state the action applies to. */
    public static final String DIGITAL_STATE = "QB_DIGITAL_PRIZE_STATE";
    /** Q33: the prize's content was sealed under another key: the sponsor sends it again. */
    public static final String DIGITAL_UNREADABLE = "QB_DIGITAL_UNREADABLE";
    /** Q33: a dispute needs a note saying what is wrong. */
    public static final String DIGITAL_DISPUTE_NOTE = "QB_DIGITAL_DISPUTE_NOTE";
    /** Q33: an unknown resolution of a dispute. */
    public static final String DIGITAL_RESOLUTION = "QB_DIGITAL_RESOLUTION";
    /** Q33: a load of codes with no usable line. */
    public static final String DIGITAL_CODES_EMPTY = "QB_DIGITAL_CODES_EMPTY";
    /** Q33: a load of more lines than allowed, or a line longer than allowed. */
    public static final String DIGITAL_CODES_TOO_MANY = "QB_DIGITAL_CODES_TOO_MANY";
    public static final String DIGITAL_CODE_TOO_LONG = "QB_DIGITAL_CODE_TOO_LONG";
    /** Q33: the publication is not delivered from a pool, so it has none. */
    public static final String DIGITAL_NOT_POOLED = "QB_DIGITAL_NOT_POOLED";
    /** Q33: codes cannot be revoked while the publication is running (end it first). */
    public static final String DIGITAL_POOL_LOCKED = "QB_DIGITAL_POOL_LOCKED";
    /** Q33: an ended publication takes no more codes. */
    public static final String DIGITAL_POOL_ENDED = "QB_DIGITAL_POOL_ENDED";
    /** Q39: the person to name is not a taker (no profile, or a sponsor's account). */
    public static final String STAFF_NOT_A_TAKER = "QB_STAFF_NOT_A_TAKER";
    /** Q39: a sponsor does not name themselves. */
    public static final String STAFF_SELF = "QB_STAFF_SELF";
    /** Q39: the person is on another sponsor's staff. */
    public static final String STAFF_ELSEWHERE = "QB_STAFF_ELSEWHERE";
    /** Q39: the sponsor has as many staff as they may. */
    public static final String STAFF_FULL = "QB_STAFF_FULL";
    /** Q39: the caller is on no sponsor's staff. */
    public static final String NOT_STAFF = "QB_NOT_STAFF";
    /** Q39: a staff member does not hand over their own prize. */
    public static final String STAFF_OWN_PRIZE = "QB_STAFF_OWN_PRIZE";

    public static final List<String> ALL = List.of(CASH_STATE, CASH_DISPUTE_NOTE, CASH_RESOLUTION, CASH_CONFIRM_DUE,
        REDEMPTION_STATE, REDEEM_TOKEN_INVALID, REDEEM_TOKEN_SPONSOR, REDEEM_TOKEN_KIND, REDEMPTION_CLAIM_LAPSED,
        DIGITAL_STATE, DIGITAL_UNREADABLE, DIGITAL_DISPUTE_NOTE, DIGITAL_RESOLUTION, DIGITAL_CODES_EMPTY,
        DIGITAL_CODES_TOO_MANY, DIGITAL_CODE_TOO_LONG, DIGITAL_NOT_POOLED, DIGITAL_POOL_LOCKED, DIGITAL_POOL_ENDED,
        STAFF_NOT_A_TAKER, STAFF_SELF, STAFF_ELSEWHERE, STAFF_FULL, NOT_STAFF, STAFF_OWN_PRIZE);

    private RewardCodes() {}
}
