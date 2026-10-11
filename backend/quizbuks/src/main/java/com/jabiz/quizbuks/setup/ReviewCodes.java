package com.jabiz.quizbuks.setup;

import java.util.List;

/**
 * The codes the reviewed processes share (publications in Q4, sponsor onboarding in Q2). The platform lets a case
 * through when no rule applies; QuizBuks refuses instead, unless its review switch says the case needs no review
 * (platform gap 5 of the Q4 plan).
 */
public final class ReviewCodes {

    /** A review is needed, but no review rule is in effect (it was not published yet, or was disabled). */
    public static final String REVIEW_NOT_CONFIGURED = "QB_REVIEW_NOT_CONFIGURED";

    public static final List<String> ALL = List.of(REVIEW_NOT_CONFIGURED);

    private ReviewCodes() {}
}
