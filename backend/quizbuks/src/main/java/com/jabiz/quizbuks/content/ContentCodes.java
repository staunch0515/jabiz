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

    // Making a version (422): the content rules of M-31.
    public static final String NO_QUESTIONS = "QB_QUIZ_NO_QUESTIONS";
    public static final String STEM_REQUIRED = "QB_QUESTION_STEM_REQUIRED";
    public static final String OPTION_COUNT = "QB_QUESTION_OPTION_COUNT";
    public static final String NO_CORRECT = "QB_QUESTION_NO_CORRECT";
    public static final String OPTION_EMPTY = "QB_OPTION_EMPTY";
    public static final String MATERIAL_TITLE_REQUIRED = "QB_MATERIAL_TITLE_REQUIRED";
    public static final String MATERIAL_CONTENT_REQUIRED = "QB_MATERIAL_CONTENT_REQUIRED";
    public static final String MATERIAL_NO_IMAGES = "QB_MATERIAL_NO_IMAGES";
    public static final String VERSION_UNCHANGED = "QB_QUIZ_VERSION_UNCHANGED";
    public static final String TOO_LARGE = "QB_QUIZ_TOO_LARGE";

    /** Every code above. */
    public static final List<String> ALL = List.of(QUIZ_TITLE_BLANK, QUIZ_TIME_LIMIT_RANGE, QUESTION_POINTS_RANGE,
        MATERIAL_URL_FORMAT, QUIZ_CHANGED, CONTENT_NOT_IN_QUIZ, TOO_MANY_QUESTIONS, TOO_MANY_MATERIALS, ORDER_MISMATCH,
        MATERIAL_KIND_FIXED, MATERIAL_FIELD_NOT_ALLOWED, VERSION_NOT_FOUND, FILE_NOT_OWN, NO_QUESTIONS, STEM_REQUIRED, OPTION_COUNT,
        NO_CORRECT, OPTION_EMPTY, MATERIAL_TITLE_REQUIRED, MATERIAL_CONTENT_REQUIRED, MATERIAL_NO_IMAGES,
        VERSION_UNCHANGED, TOO_LARGE);

    private ContentCodes() {}
}
