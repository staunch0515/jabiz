package com.jabiz.quizbuks.content;

import com.jabiz.param.ParamValues;
import com.jabiz.quizbuks.play.PlayLimits;
import com.jabiz.quizbuks.setup.QbParams;

/**
 * F-S6: the question bank's business limits, as the parameters give them at a time, never above the technical
 * ceilings ({@link ContentLimits#MAX_QUESTIONS_TECH}, {@link PlayLimits#MAX_PER_ATTEMPT_TECH}) that size the inputs,
 * the datasets and the columns. A parameter lowered later keeps existing banks readable and editable: only adding
 * questions is refused.
 */
public final class QuizBankLimits {

    /**
     * The parameters a process reads to apply these limits, with {@code LoadParams.optional}: one not set up yet
     * counts as its declared value ({@link QbParams#defaultOf}), so a deployment before {@code QB_SETUP} keeps working.
     */
    public static final String[] PARAMS = {QbParams.CONTENT_MAX_QUESTIONS, QbParams.PLAY_MAX_PER_ATTEMPT,
        QbParams.CONTENT_IMPORT_BATCH, QbParams.CONTENT_IMPORT_BATCH_BYTES};

    private QuizBankLimits() {}

    /** The most questions one quiz has. */
    public static int maxQuestions(ParamValues params) {
        return clamp(read(params, QbParams.CONTENT_MAX_QUESTIONS), ContentLimits.MAX_QUESTIONS_TECH);
    }

    /** The most questions one attempt gives; a larger bank must be drawn from. */
    public static int maxPerAttempt(ParamValues params) {
        return clamp(read(params, QbParams.PLAY_MAX_PER_ATTEMPT), PlayLimits.MAX_PER_ATTEMPT_TECH);
    }

    /** The most questions one import batch adds. */
    public static int importBatch(ParamValues params) {
        return clamp(read(params, QbParams.CONTENT_IMPORT_BATCH), ContentLimits.MAX_QUESTIONS_TECH);
    }

    /**
     * F-F11a: the most bytes of one import request's body, which the page cuts a large bank by (besides the question
     * count); between {@link ContentLimits#IMPORT_BATCH_BYTES_MIN} and {@link ContentLimits#IMPORT_BATCH_BYTES_TECH}.
     */
    public static int importBatchBytes(ParamValues params) {
        return Math.clamp(read(params, QbParams.CONTENT_IMPORT_BATCH_BYTES), ContentLimits.IMPORT_BATCH_BYTES_MIN,
            ContentLimits.IMPORT_BATCH_BYTES_TECH);
    }

    private static int read(ParamValues params, String key) {
        java.math.BigDecimal value = params == null ? null
            : params.find(key, java.math.BigDecimal.class).orElse(null);
        if (value == null) {
            value = new java.math.BigDecimal(QbParams.defaultOf(key));
        }
        return (int) Math.clamp(value.setScale(0, java.math.RoundingMode.DOWN).longValue(), 1, Integer.MAX_VALUE);
    }

    private static int clamp(int value, int ceiling) {
        return Math.clamp(value, 1, ceiling);
    }
}
