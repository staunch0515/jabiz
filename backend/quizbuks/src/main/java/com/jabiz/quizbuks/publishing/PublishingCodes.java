package com.jabiz.quizbuks.publishing;

import java.util.List;

/**
 * The rule and error codes of publishing (docs/quizbuks/plans/Q4-publishing.md), each with a message in the three
 * languages. Field rules are refused with 400; the entity check and the processes' own refusals with 422.
 */
public final class PublishingCodes {

    // Field rules (400).
    public static final String NAME_BLANK = "QB_PUBLICATION_NAME_BLANK";
    public static final String REGIONS_FORMAT = "QB_PUBLICATION_REGIONS_FORMAT";
    public static final String TIME_LIMIT_RANGE = "QB_PUBLICATION_TIME_LIMIT_RANGE";
    public static final String SCORE_RANGE = "QB_PUBLICATION_SCORE_RANGE";
    public static final String BUDGET_RANGE = "QB_PUBLICATION_BUDGET_RANGE";
    public static final String MAX_WINNERS_RANGE = "QB_PUBLICATION_MAX_WINNERS_RANGE";
    public static final String TIER_REWARD_RANGE = "QB_TIER_REWARD_RANGE";

    // The entity check (422): when both times are given, the start comes before the end.
    public static final String TIME_ORDER = "QB_PUBLICATION_TIME_ORDER";

    // Editing and moving a publication (422).
    public static final String NOT_DRAFT = "QB_PUBLICATION_NOT_DRAFT";
    public static final String CHANGED = "QB_PUBLICATION_CHANGED";
    public static final String QUIZ_REMOVED = "QB_PUBLICATION_QUIZ_REMOVED";
    public static final String TIER_NOT_IN_PUBLICATION = "QB_TIER_NOT_IN_PUBLICATION";
    public static final String REVIEW_NOT_CONFIGURED = "QB_REVIEW_NOT_CONFIGURED";
    public static final String SPONSOR_NOT_APPROVED = "QB_SPONSOR_NOT_APPROVED";
    /** Not a refusal: why a publication went back to its draft when its end passed while it was reviewed. */
    public static final String EXPIRED_IN_REVIEW = "QB_PUBLICATION_EXPIRED_IN_REVIEW";

    // Submitting (422): every rule of PublicationReadiness, reported at once.
    public static final String NO_VERSION = "QB_PUBLICATION_NO_VERSION";
    public static final String FIELD_REQUIRED = "QB_PUBLICATION_FIELD_REQUIRED";
    public static final String END_PASSED = "QB_PUBLICATION_END_PASSED";
    public static final String TOO_LONG = "QB_PUBLICATION_TOO_LONG";
    public static final String PASS_SCORE_RANGE = "QB_PASS_SCORE_RANGE";
    public static final String BUDGET_BELOW_MAX_REWARD = "QB_BUDGET_BELOW_MAX_REWARD";
    public static final String SPEND_RANGE = "QB_PUBLICATION_SPEND_RANGE";
    public static final String TIER_NOT_FROM_ZERO = "QB_TIER_NOT_FROM_ZERO";
    public static final String TIER_RANGE = "QB_TIER_RANGE";
    public static final String TIER_GAP = "QB_TIER_GAP";
    public static final String TIER_OVERLAP = "QB_TIER_OVERLAP";
    public static final String TIER_NOT_TO_FULL = "QB_TIER_NOT_TO_FULL";
    public static final String TIER_NO_REWARD = "QB_TIER_NO_REWARD";

    /** Every code above. */
    public static final List<String> ALL = List.of(NAME_BLANK, REGIONS_FORMAT, TIME_LIMIT_RANGE, SCORE_RANGE,
        BUDGET_RANGE, MAX_WINNERS_RANGE, TIER_REWARD_RANGE, TIME_ORDER, NOT_DRAFT, CHANGED, QUIZ_REMOVED,
        TIER_NOT_IN_PUBLICATION, REVIEW_NOT_CONFIGURED, SPONSOR_NOT_APPROVED, EXPIRED_IN_REVIEW, NO_VERSION,
        FIELD_REQUIRED, END_PASSED, TOO_LONG, PASS_SCORE_RANGE, BUDGET_BELOW_MAX_REWARD, SPEND_RANGE, TIER_NOT_FROM_ZERO,
        TIER_RANGE, TIER_GAP, TIER_OVERLAP, TIER_NOT_TO_FULL, TIER_NO_REWARD);

    private PublishingCodes() {}
}
