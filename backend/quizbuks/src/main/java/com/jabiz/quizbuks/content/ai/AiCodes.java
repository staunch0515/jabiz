package com.jabiz.quizbuks.content.ai;

import java.util.List;

/**
 * The error codes of AI generation (docs/quizbuks/plans/Q8-ai.md), with texts in the three languages. The first group
 * refuses a process (422 unless noted); the second is what a job's failure records ({@code failureCode}), shown to the
 * sponsor in their language by the console.
 */
public final class AiCodes {

    /** AI generation is switched off ({@code qb.ai.enabled}), or its parameters cannot be used. */
    public static final String UNAVAILABLE = "QB_AI_UNAVAILABLE";
    /** No version of the AI notice is in effect: nobody can have accepted it, so nothing is sent (A-11). */
    public static final String NOTICE_MISSING = "QB_AI_NOTICE_MISSING";
    /** The AI notice in effect is not accepted yet: the request must name its version ({@code aiNoticeVersionNo}). */
    public static final String NOTICE_REQUIRED = "QB_AI_NOTICE_REQUIRED";
    /** The materials and the quiz's own text, as sent, are larger than the limit: choose fewer (A-5; not cut). */
    public static final String INPUT_TOO_LARGE = "QB_AI_INPUT_TOO_LARGE";
    /** Nothing to write questions from: no material chosen and no introduction. */
    public static final String NO_SOURCE = "QB_AI_NO_SOURCE";
    /** A chosen material is not one of the quiz's. */
    public static final String MATERIAL_NOT_IN_QUIZ = "QB_AI_MATERIAL_NOT_IN_QUIZ";
    /** The quiz has a job that has not finished. */
    public static final String QUIZ_BUSY = "QB_AI_QUIZ_BUSY";
    /** Too many of the sponsor's jobs have not finished. */
    public static final String TOO_MANY_OPEN = "QB_AI_TOO_MANY_OPEN";
    public static final String DAILY_LIMIT = "QB_AI_DAILY_LIMIT";
    public static final String MONTHLY_LIMIT = "QB_AI_MONTHLY_LIMIT";
    /** Everybody's tokens of the month are used up. */
    public static final String GLOBAL_LIMIT = "QB_AI_GLOBAL_LIMIT";
    /** The job is not one waiting for review (accepting, discarding) or not one still running (cancelling). */
    public static final String JOB_NOT_READY = "QB_AI_JOB_NOT_READY";
    public static final String JOB_EXPIRED = "QB_AI_JOB_EXPIRED";
    /** A candidate number given twice, or not one of the job's. */
    public static final String CANDIDATE_UNKNOWN = "QB_AI_CANDIDATE_UNKNOWN";
    /** A question was not marked as reviewed (400: every accepted question is reviewed, A-6). */
    public static final String NOT_REVIEWED = "QB_AI_NOT_REVIEWED";
    /** A text longer than the content limits allow. */
    public static final String TEXT_TOO_LONG = "QB_AI_TEXT_TOO_LONG";

    // ---- a job's failures --------------------------------------------------------------------------------------
    public static final String RATE_LIMITED = "QB_AI_RATE_LIMITED";
    public static final String PROVIDER_UNAVAILABLE = "QB_AI_PROVIDER_UNAVAILABLE";
    public static final String AUTH = "QB_AI_AUTH";
    public static final String REQUEST_INVALID = "QB_AI_REQUEST_INVALID";
    public static final String BAD_RESPONSE = "QB_AI_BAD_RESPONSE";
    /** The model declined (after the provider's own fallback, A-10). */
    public static final String REFUSED = "QB_AI_REFUSED";
    /** The answer was cut at the token limit. */
    public static final String TRUNCATED = "QB_AI_TRUNCATED";
    /** None of the candidates met the content rules. */
    public static final String NO_CANDIDATES = "QB_AI_NO_CANDIDATES";

    public static final List<String> ALL = List.of(UNAVAILABLE, NOTICE_MISSING, NOTICE_REQUIRED, INPUT_TOO_LARGE, NO_SOURCE,
        MATERIAL_NOT_IN_QUIZ, QUIZ_BUSY, TOO_MANY_OPEN, DAILY_LIMIT, MONTHLY_LIMIT, GLOBAL_LIMIT, JOB_NOT_READY,
        JOB_EXPIRED, CANDIDATE_UNKNOWN, NOT_REVIEWED, TEXT_TOO_LONG, RATE_LIMITED, PROVIDER_UNAVAILABLE, AUTH,
        REQUEST_INVALID, BAD_RESPONSE, REFUSED, TRUNCATED, NO_CANDIDATES);

    private AiCodes() {}
}
