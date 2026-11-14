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
    /**
     * F-M9: the sponsor's AI credit cannot hold what the request may cost (details {@code needed}, {@code available} and
     * {@code minPurchase}: the page offers a purchase).
     */
    public static final String CREDIT_SHORT = "QB_AI_CREDIT_SHORT";
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
    /** More questions than the effort in force allows at once ({@link AiQuestionsJob#maxCount}). */
    public static final String COUNT_TOO_HIGH = "QB_AI_COUNT_TOO_HIGH";
    /** A chosen PDF is larger than one request may send, or all of them together are (Q8b). */
    public static final String PDF_TOO_LARGE = "QB_AI_PDF_TOO_LARGE";
    /** The job is of the other kind (questions where a cover is chosen, or the other way round): 422. */
    public static final String WRONG_KIND = "QB_AI_WRONG_KIND";

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
    /**
     * An operator stopped the job at the platform ({@code EXTERNAL_JOB_CANCEL}); it was charged what its attempts used
     * ({@code QB_AI_JOB_CANCELLED}, platform decision D52 item 2).
     */
    public static final String STOPPED = "QB_AI_STOPPED";
    /** The PDF materials have more pages than a request may send: nothing was sent (Q8b). */
    public static final String PDF_TOO_LONG = "QB_AI_PDF_TOO_LONG";
    /** A PDF material has a password or is no readable PDF: nothing was sent (Q8b). */
    public static final String PDF_UNREADABLE = "QB_AI_PDF_UNREADABLE";
    /** Q98a: AI covers were withdrawn; a cover job queued before ends with this, nothing sent. */
    public static final String COVER_WITHDRAWN = "QB_AI_COVER_WITHDRAWN";
    /** Q73d-1: every text of the language is translated and up to date (or the quiz has no text). */
    public static final String NOTHING_TO_TRANSLATE = "QB_AI_NOTHING_TO_TRANSLATE";
    /** Q73d-2: the publication has no translation into the language to compare. */
    public static final String NOTHING_TO_CHECK = "QB_AI_NOTHING_TO_CHECK";
    /** Questions asked for a survey: they would need correct options (Q12, D-Q12-9). */
    public static final String SURVEY_UNSUPPORTED = "QB_AI_SURVEY_UNSUPPORTED";

    /** F-C7: the sponsor did not declare the website theirs (or one they may use). */
    public static final String SITE_RIGHTS_REQUIRED = "QB_AI_SITE_RIGHTS_REQUIRED";
    /** F-C7: the address is not a website's (https, a host name, no user information or port). */
    public static final String SITE_URL_INVALID = "QB_AI_SITE_URL_INVALID";
    /** F-C7: no content was asked for, or the languages are not a base and up to two others. */
    public static final String SITE_REQUEST_INVALID = "QB_AI_SITE_REQUEST_INVALID";
    /** F-C7: a site draft is already being made for this sponsor. */
    public static final String SITE_BUSY = "QB_AI_SITE_BUSY";
    public static final String SITE_DAILY_LIMIT = "QB_AI_SITE_DAILY_LIMIT";
    public static final String SITE_MONTHLY_LIMIT = "QB_AI_SITE_MONTHLY_LIMIT";
    /** F-C7: why a site could not be read (the job's failure codes). */
    public static final String SITE_UNREACHABLE = "QB_AI_SITE_UNREACHABLE";
    public static final String SITE_ROBOTS = "QB_AI_SITE_ROBOTS";
    public static final String SITE_NO_TEXT = "QB_AI_SITE_NO_TEXT";
    public static final String SITE_TIMEOUT = "QB_AI_SITE_TIMEOUT";
    public static final String SITE_QUIZ_MADE = "QB_AI_SITE_QUIZ_MADE";
    public static final String SITE_NO_QUIZ = "QB_AI_SITE_NO_QUIZ";

    public static final List<String> ALL = List.of(UNAVAILABLE, NOTICE_MISSING, NOTICE_REQUIRED, INPUT_TOO_LARGE, NO_SOURCE,
        MATERIAL_NOT_IN_QUIZ, QUIZ_BUSY, TOO_MANY_OPEN, DAILY_LIMIT, MONTHLY_LIMIT, GLOBAL_LIMIT, JOB_NOT_READY,
        JOB_EXPIRED, CANDIDATE_UNKNOWN, NOT_REVIEWED, TEXT_TOO_LONG, RATE_LIMITED, PROVIDER_UNAVAILABLE, AUTH,
        REQUEST_INVALID, BAD_RESPONSE, REFUSED, TRUNCATED, NO_CANDIDATES, COUNT_TOO_HIGH, STOPPED,
        PDF_TOO_LARGE, WRONG_KIND, PDF_TOO_LONG, PDF_UNREADABLE, COVER_WITHDRAWN, SURVEY_UNSUPPORTED,
        // F-C7: drafting from a website.
        SITE_RIGHTS_REQUIRED, SITE_URL_INVALID, SITE_REQUEST_INVALID, SITE_BUSY,
        SITE_DAILY_LIMIT, SITE_MONTHLY_LIMIT, SITE_UNREACHABLE, SITE_ROBOTS, SITE_NO_TEXT, SITE_TIMEOUT, SITE_QUIZ_MADE, SITE_NO_QUIZ);

    private AiCodes() {}
}
