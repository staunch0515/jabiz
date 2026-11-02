package com.jabiz.quizbuks.content;

import java.util.List;

/**
 * The rule and error codes of quiz content (docs/quizbuks/plans/Q3-content.md, D-Q3-5), each with a message in the
 * three languages. Field rules are refused with 400; the processes' own refusals with 422.
 */
public final class ContentCodes {

    // Field rules (400).
    public static final String QUIZ_TITLE_BLANK = "QB_QUIZ_TITLE_BLANK";
    public static final String QUIZ_TIME_LIMIT_RANGE = "QB_QUIZ_TIME_LIMIT_RANGE";
    public static final String QUESTION_POINTS_RANGE = "QB_QUESTION_POINTS_RANGE";
    public static final String MATERIAL_URL_FORMAT = "QB_MATERIAL_URL_FORMAT";

    // Editing a draft (422).
    public static final String QUIZ_CHANGED = "QB_QUIZ_CHANGED";
    public static final String CONTENT_NOT_IN_QUIZ = "QB_CONTENT_NOT_IN_QUIZ";
    public static final String TOO_MANY_QUESTIONS = "QB_QUIZ_TOO_MANY_QUESTIONS";
    public static final String TOO_MANY_MATERIALS = "QB_QUIZ_TOO_MANY_MATERIALS";
    public static final String ORDER_MISMATCH = "QB_ORDER_MISMATCH";
    public static final String MATERIAL_KIND_FIXED = "QB_MATERIAL_KIND_FIXED";
    public static final String MATERIAL_FIELD_NOT_ALLOWED = "QB_MATERIAL_FIELD_NOT_ALLOWED";
    public static final String VERSION_NOT_FOUND = "QB_QUIZ_VERSION_NOT_FOUND";
    public static final String FILE_NOT_OWN = "QB_CONTENT_FILE_NOT_OWN";
    // Quiz files (Q16): a file this system cannot read, a value or a count over the content's limits, a file of the
    // quiz the file does not list or of a type or size its field does not take, or one not uploaded for the import.
    public static final String QUIZ_FILE_UNSUPPORTED = "QB_QUIZ_FILE_UNSUPPORTED";
    public static final String QUIZ_FILE_VALUE = "QB_QUIZ_FILE_VALUE";
    public static final String QUIZ_FILE_TOO_MANY = "QB_QUIZ_FILE_TOO_MANY";
    public static final String QUIZ_FILE_MISSING = "QB_QUIZ_FILE_MISSING";
    public static final String QUIZ_FILE_TYPE = "QB_QUIZ_FILE_TYPE";
    public static final String QUIZ_FILE_NOT_UPLOADED = "QB_QUIZ_FILE_NOT_UPLOADED";
    /** A quiz's kind given that is not its own: it never changes (Q12, D-Q12-1). */
    public static final String QUIZ_KIND_FIXED = "QB_QUIZ_KIND_FIXED";
    /** A scored quiz's question given a selection: only a survey's are single or multiple choice (Q12). */
    public static final String SELECTION_NOT_ALLOWED = "QB_QUESTION_SELECTION_NOT_ALLOWED";

    // Making a version (422): the content rules of M-31.
    public static final String NO_QUESTIONS = "QB_QUIZ_NO_QUESTIONS";
    public static final String STEM_REQUIRED = "QB_QUESTION_STEM_REQUIRED";
    public static final String OPTION_COUNT = "QB_QUESTION_OPTION_COUNT";
    public static final String NO_CORRECT = "QB_QUESTION_NO_CORRECT";
    /** A survey's question that is neither single nor multiple choice (Q12, D-Q12-3). */
    public static final String SELECTION_REQUIRED = "QB_QUESTION_SELECTION_REQUIRED";
    /** A survey's question with points other than 1 or a correct option (Q12, D-Q12-3). */
    public static final String SURVEY_SCORED = "QB_QUESTION_SURVEY_SCORED";
    public static final String OPTION_EMPTY = "QB_OPTION_EMPTY";
    public static final String MATERIAL_TITLE_REQUIRED = "QB_MATERIAL_TITLE_REQUIRED";
    public static final String MATERIAL_CONTENT_REQUIRED = "QB_MATERIAL_CONTENT_REQUIRED";
    public static final String MATERIAL_NO_IMAGES = "QB_MATERIAL_NO_IMAGES";
    public static final String VERSION_UNCHANGED = "QB_QUIZ_VERSION_UNCHANGED";
    public static final String TOO_LARGE = "QB_QUIZ_TOO_LARGE";
    /** Q19: the video to watch is a YouTube or Vimeo video, one per quiz. */
    public static final String MATERIAL_VIDEO_NOT_EMBEDDABLE = "QB_MATERIAL_VIDEO_NOT_EMBEDDABLE";
    public static final String QUIZ_ONE_WATCH_VIDEO = "QB_QUIZ_ONE_WATCH_VIDEO";
    /** Q75: the video to watch needs its duration before a version is saved (D-Q75-5). */
    public static final String MATERIAL_DURATION_REQUIRED = "QB_MATERIAL_DURATION_REQUIRED";
    /** Q75: a duration outside 1 second to 2 hours ({@link ContentLimits#MAX_VIDEO_SECONDS}). */
    public static final String MATERIAL_DURATION_RANGE = "QB_MATERIAL_DURATION_RANGE";

    // Q73a: a quiz's other languages (docs/quizbuks/plans/Q73-content-languages.md).
    public static final String BASE_HAS_TRANSLATIONS = "QB_QUIZ_BASE_HAS_TRANSLATIONS";
    public static final String LANGUAGE_IS_BASE = "QB_QUIZ_LANGUAGE_IS_BASE";
    public static final String TRANSLATION_LANGUAGE = "QB_TRANSLATION_LANGUAGE";
    public static final String TRANSLATION_KEY = "QB_TRANSLATION_KEY";
    public static final String TRANSLATION_NO_SOURCE = "QB_TRANSLATION_NO_SOURCE";
    public static final String TRANSLATION_TOO_LONG = "QB_TRANSLATION_TOO_LONG";
    public static final String TRANSLATION_FIELD = "QB_TRANSLATION_FIELD";
    public static final String MATERIAL_VARIANT_EXISTS = "QB_MATERIAL_VARIANT_EXISTS";
    public static final String MATERIAL_VARIANT_FIXED = "QB_MATERIAL_VARIANT_FIXED";

    /** Every code above. */
    public static final List<String> ALL = List.of(QUIZ_TITLE_BLANK, QUIZ_TIME_LIMIT_RANGE, QUESTION_POINTS_RANGE,
        MATERIAL_URL_FORMAT, QUIZ_CHANGED, CONTENT_NOT_IN_QUIZ, TOO_MANY_QUESTIONS, TOO_MANY_MATERIALS, ORDER_MISMATCH,
        MATERIAL_KIND_FIXED, MATERIAL_FIELD_NOT_ALLOWED, VERSION_NOT_FOUND, FILE_NOT_OWN, QUIZ_KIND_FIXED, SELECTION_NOT_ALLOWED,
        NO_QUESTIONS, STEM_REQUIRED, OPTION_COUNT, NO_CORRECT, SELECTION_REQUIRED, SURVEY_SCORED, OPTION_EMPTY, MATERIAL_TITLE_REQUIRED, MATERIAL_CONTENT_REQUIRED, MATERIAL_NO_IMAGES,
        VERSION_UNCHANGED, TOO_LARGE, MATERIAL_VIDEO_NOT_EMBEDDABLE, QUIZ_ONE_WATCH_VIDEO, BASE_HAS_TRANSLATIONS,
        LANGUAGE_IS_BASE, TRANSLATION_LANGUAGE, TRANSLATION_KEY, TRANSLATION_NO_SOURCE, TRANSLATION_TOO_LONG,
        TRANSLATION_FIELD, MATERIAL_VARIANT_EXISTS, MATERIAL_VARIANT_FIXED, MATERIAL_DURATION_REQUIRED,
        MATERIAL_DURATION_RANGE);

    private ContentCodes() {}
}
