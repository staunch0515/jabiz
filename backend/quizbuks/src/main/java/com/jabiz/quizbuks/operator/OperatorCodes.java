package com.jabiz.quizbuks.operator;

import java.util.List;

/**
 * The refusal codes of operators (docs/quizbuks/plans/Q48-operators.md, Q48a), each with a message in the three
 * languages. The processes refuse with 422; another operator's member or record is 404 without saying which.
 */
public final class OperatorCodes {

    /** The caller is no member of an operator (removed, or never named). */
    public static final String NOT_MEMBER = "QB_OPERATOR_NOT_MEMBER";
    /** The caller's operator is suspended: its members act no more. */
    public static final String SUSPENDED = "QB_OPERATOR_SUSPENDED";
    /** Only the operator's lead removes its members. */
    public static final String NOT_LEAD = "QB_OPERATOR_NOT_LEAD";
    /** A blank or overlong name, contact or address of an operator. */
    public static final String FIELD = "QB_OPERATOR_FIELD";
    /** An unknown status or collection of an operator. */
    public static final String CODE = "QB_OPERATOR_CODE";
    /** The operator was changed since the page read it: read it again. */
    public static final String CHANGED = "QB_OPERATOR_CHANGED";
    /** The person is a member of another operator already. */
    public static final String MEMBER_ELSEWHERE = "QB_OPERATOR_MEMBER_ELSEWHERE";
    /** The person runs a sponsor account: a member reviews sponsors, so they are not one. */
    public static final String MEMBER_IS_SPONSOR = "QB_OPERATOR_MEMBER_IS_SPONSOR";
    /** The person's e-mail address is not verified (every QuizBuks write needs one). */
    public static final String MEMBER_UNVERIFIED = "QB_OPERATOR_MEMBER_UNVERIFIED";
    /** The operator has its most members already. */
    public static final String MEMBERS_FULL = "QB_OPERATOR_MEMBERS_FULL";
    /** A lead does not remove themself (headquarters does, or names another lead first). */
    public static final String LEAD_SELF = "QB_OPERATOR_LEAD_SELF";
    /** The user an operator acts on runs no sponsor account (an operator bans sponsors only). */
    public static final String NOT_A_SPONSOR = "QB_OPERATOR_NOT_A_SPONSOR";
    /** Headquarters took the publication down, or another operator did: the operator does not resume it. */
    public static final String HQ_TAKEDOWN = "QB_OPERATOR_HQ_TAKEDOWN";
    /** Headquarters banned the user, or another operator did: the operator does not lift the ban. */
    public static final String HQ_BAN = "QB_OPERATOR_HQ_BAN";
    /** The decision is not one an operator's member made on a review (a sponsor's or a publication's). */
    public static final String NOT_AN_OPERATOR_DECISION = "QB_OPERATOR_NOT_AN_OPERATOR_DECISION";
    /** A problem found by a spot check needs a note saying what it is. */
    public static final String SPOT_CHECK_NOTE = "QB_OPERATOR_SPOT_CHECK_NOTE";
    /** The sponsor is assigned to this operator already (or to headquarters, when moving them back). */
    public static final String SAME_OPERATOR = "QB_OPERATOR_SAME";

    /** Q48b: a month is settled only once it has ended (in Japan). */
    public static final String MONTH_OPEN = "QB_OPERATOR_MONTH_OPEN";
    /** Q48b: the platform's share (qb.operator.platform-share) is not published, or not a fraction from 0 to 1. */
    public static final String NO_SHARE = "QB_OPERATOR_NO_SHARE";
    /** Q48d: the month has prospecting costs and the yen of a dollar (qb.prospect.usd-jpy) is not published. */
    public static final String NO_RATE = "QB_OPERATOR_NO_RATE";
    /** Q48b: only an issued settlement is paid (one being approved, or paid, is not again). */
    public static final String SETTLEMENT_STATE = "QB_OPERATOR_SETTLEMENT_STATE";
    /** Q48b: the settlement has nothing payable. */
    public static final String SETTLEMENT_NOTHING = "QB_OPERATOR_SETTLEMENT_NOTHING";
    /** Q48b: no approval rule asks for a second person on the operators' payments. */
    public static final String SETTLEMENT_NO_REVIEW = "QB_OPERATOR_SETTLEMENT_NO_REVIEW";

    public static final List<String> ALL = List.of(NOT_MEMBER, SUSPENDED, NOT_LEAD, FIELD, CODE, CHANGED,
        MEMBER_ELSEWHERE, MEMBER_IS_SPONSOR, MEMBER_UNVERIFIED, MEMBERS_FULL, LEAD_SELF, NOT_A_SPONSOR, HQ_TAKEDOWN,
        HQ_BAN, NOT_AN_OPERATOR_DECISION, SPOT_CHECK_NOTE, SAME_OPERATOR, MONTH_OPEN, NO_SHARE, SETTLEMENT_STATE,
        SETTLEMENT_NOTHING, SETTLEMENT_NO_REVIEW, NO_RATE);

    private OperatorCodes() {}
}
