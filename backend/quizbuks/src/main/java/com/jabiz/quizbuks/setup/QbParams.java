package com.jabiz.quizbuks.setup;

import java.util.List;
import java.util.Map;

/**
 * The business parameters of QuizBuks with their kinds and first values (docs/quizbuks/plans/Q1-skeleton.md item 6).
 * {@code QB_SETUP} declares each one that does not exist yet through {@code PARAM_CREATE}; afterwards their values
 * change through the platform's parameter processes, so a value set by an administrator is never reset.
 *
 * <p>The parameters of {@link #CONTROLLED} change only with four eyes (platform decision D40): money (the commission,
 * the transfer threshold and review limit, transfers on or off) and the switches that skip a person's review. Even
 * their first values are created through a controlled change: {@code QB_SETUP} proposes them and another
 * administrator publishes the proposals.
 */
public final class QbParams {

    /** The platform's commission on each reward, as a fraction (0.1000 = 10 %); none for now. */
    public static final String CREATOR_FEE_RATE = "qb.creator-fee-rate";
    /** The balance at which a user's rewards are transferred automatically. */
    public static final String TRANSFER_THRESHOLD = "qb.transfer.threshold";
    /** Transfers above this amount wait for a person's review. */
    public static final String TRANSFER_REVIEW_ABOVE = "qb.transfer.review-above";
    /** Whether the daily automatic transfers run at all. */
    public static final String TRANSFER_ENABLED = "qb.transfer.enabled";
    /** Whether sponsor onboarding is approved without a person's review. */
    public static final String REVIEW_SPONSOR_AUTO = "qb.review.sponsor.auto";
    /** Whether publications are approved without a person's review. */
    public static final String REVIEW_PUBLICATION_AUTO = "qb.review.publication.auto";
    /** The OpenAI model of AI generation. */
    public static final String AI_MODEL = "qb.ai.model";
    /** The oldest version of the app that may still be used. */
    public static final String APP_MIN_VERSION = "qb.app.min-version";

    /**
     * The parameters that change only through controlled changes (proposed by one administrator, published by
     * another): the money settings, and the review switches, since turning one on removes a person's review.
     */
    public static final List<String> CONTROLLED = List.of(CREATOR_FEE_RATE, TRANSFER_THRESHOLD,
        TRANSFER_REVIEW_ABOVE, TRANSFER_ENABLED, REVIEW_SPONSOR_AUTO, REVIEW_PUBLICATION_AUTO);

    /** A parameter: its key, kind (as the platform writes kinds) and first value. */
    public record Param(String key, Map<String, Object> valueKind, Object value, String description) {
        public Param {
            valueKind = Map.copyOf(valueKind);
        }
    }

    private static final Map<String, Object> KUDOS = Map.of("type", "monetary", "currency", "JPY", "scale", 0);
    private static final Map<String, Object> BOOL = Map.of("type", "bool");

    public static final List<Param> ALL = List.of(
        new Param(CREATOR_FEE_RATE, Map.of("type", "numeric", "precision", 5, "scale", 4), "0",
            "Commission of the platform on each reward, as a fraction (0.1 = 10%)"),
        new Param(TRANSFER_THRESHOLD, KUDOS, "1000",
            "A user's rewards are transferred once they reach this many Kudos"),
        new Param(TRANSFER_REVIEW_ABOVE, KUDOS, "50000", "Transfers above this many Kudos wait for review"),
        new Param(TRANSFER_ENABLED, BOOL, false, "Whether the daily automatic transfers run"),
        new Param(REVIEW_SPONSOR_AUTO, BOOL, false, "Approve sponsor onboarding without review"),
        new Param(REVIEW_PUBLICATION_AUTO, BOOL, false, "Approve publications without review"),
        new Param(AI_MODEL, Map.of("type", "text", "maxLength", 100), "gpt-4o-mini",
            "OpenAI model used to generate questions and covers"),
        new Param(APP_MIN_VERSION, Map.of("type", "text", "maxLength", 20), "1.0.0",
            "Oldest app version that may still be used"));

    private QbParams() {}
}
