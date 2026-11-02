package com.jabiz.quizbuks.publishing;

import com.jabiz.quizbuks.identity.AccountCodes;
import com.jabiz.quizbuks.setup.ReviewCodes;

import java.util.List;

/**
 * The rule and error codes of publishing (docs/quizbuks/plans/Q4-publishing.md), each with a message in the three
 * languages. Field rules are refused with 400; the entity check and the processes' own refusals with 422.
 */
public final class PublishingCodes {

    // Field rules (400).
    public static final String NAME_BLANK = "QB_PUBLICATION_NAME_BLANK";
    public static final String REGIONS_FORMAT = "QB_PUBLICATION_REGIONS_FORMAT";
    /** Q25: an audience that is neither {@code PUBLIC} nor {@code INVITED}. */
    public static final String AUDIENCE_INVALID = "QB_PUBLICATION_AUDIENCE_INVALID";
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
    /** Shared with sponsor onboarding: {@link ReviewCodes}. */
    public static final String REVIEW_NOT_CONFIGURED = ReviewCodes.REVIEW_NOT_CONFIGURED;
    /** An account code ({@link AccountCodes}): submitting checks the onboarding ({@link SponsorChecks}). */
    public static final String SPONSOR_NOT_APPROVED = AccountCodes.SPONSOR_NOT_APPROVED;
    /** Not a refusal: why a publication went back to its draft when its end passed while it was reviewed. */
    public static final String EXPIRED_IN_REVIEW = "QB_PUBLICATION_EXPIRED_IN_REVIEW";

    // Switching a publication that went on to another version of its quiz (Q51, 422).
    public static final String SWITCH_NOT_RUNNING = "QB_PUBLICATION_SWITCH_NOT_RUNNING";
    public static final String SWITCH_TAKEN_DOWN = "QB_PUBLICATION_SWITCH_TAKEN_DOWN";
    public static final String SWITCH_PENDING = "QB_PUBLICATION_SWITCH_PENDING";
    public static final String SWITCH_SAME_VERSION = "QB_PUBLICATION_SWITCH_SAME_VERSION";
    public static final String SWITCH_OTHER_QUIZ = "QB_PUBLICATION_SWITCH_OTHER_QUIZ";
    public static final String SWITCH_FULL_SCORE = "QB_PUBLICATION_SWITCH_FULL_SCORE";
    public static final String SWITCH_NOT_PENDING = "QB_PUBLICATION_SWITCH_NOT_PENDING";
    /** Not a refusal: why a switch in review was dropped when its publication ended first. */
    public static final String SWITCH_ENDED = "QB_PUBLICATION_SWITCH_ENDED";

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
    /** A survey's pass mark that is not its number of questions (Q12, D-Q12-4). */
    public static final String SURVEY_PASS_SCORE = "QB_PUBLICATION_SURVEY_PASS_SCORE";
    /** A survey's tier that rewards an incomplete response (Q12, D-Q12-4). */
    public static final String SURVEY_TIERS = "QB_PUBLICATION_SURVEY_TIERS";

    /** The sponsor tries to resume a publication an administrator paused (Q10b, M2). */
    public static final String ADMIN_PAUSED = "QB_PUBLICATION_ADMIN_PAUSED";
    /** An administrator tries to resume a publication that no administrator paused (Q10b, M2). */
    public static final String NOT_ADMIN_PAUSED = "QB_PUBLICATION_NOT_ADMIN_PAUSED";
    /** An administrator tries to resume a publication of a banned sponsor (Q10b, review L9). */
    public static final String SPONSOR_BANNED = "QB_PUBLICATION_SPONSOR_BANNED";
    /** The sponsor copies a publication an administrator took down (Q10b, review M-d). */
    public static final String TAKEN_DOWN = "QB_PUBLICATION_TAKEN_DOWN";
    /**
     * A publication of a quiz an administrator took a publication of down goes on only after a person's review, and
     * none is asked for (no rule applies, or an earlier approval would let it through; Q10b, review M-d).
     */
    public static final String REVIEW_REQUIRED = "QB_PUBLICATION_REVIEW_REQUIRED";

    /** Q22: the publication's reward method is not one the sponsor may publish with. */
    public static final String REWARD_METHOD_NOT_ALLOWED = "QB_REWARD_METHOD_NOT_ALLOWED";
    /** Q33: a digital prize names no delivery, or one that is unknown. */
    public static final String DIGITAL_DELIVERY_INVALID = "QB_DIGITAL_DELIVERY_INVALID";
    /** Q33: the days a sponsor has to send a digital prize are not within 1 and the limit. */
    public static final String DIGITAL_SEND_DAYS_RANGE = "QB_DIGITAL_SEND_DAYS_RANGE";
    /** Q33: a pool-delivered publication's winners are not covered by its codes, or too many for a pool. */
    public static final String DIGITAL_POOL_SHORT = "QB_DIGITAL_POOL_SHORT";
    /** Q22: a setting's methods are not written as {@code RewardMethod.format} writes them (400). */
    public static final String REWARD_METHODS_FORMAT = "QB_REWARD_METHODS_FORMAT";
    /** A tier pays more than the highest reward one attempt may win (Q28). */
    public static final String REWARD_ABOVE_MAX = "QB_REWARD_ABOVE_MAX";
    /** S2: a channel's publication names a sponsor (or no known kind) as its publisher. */
    public static final String CHANNEL_PUBLISHER = "QB_CHANNEL_PUBLISHER";
    /** S2: a channel's video to watch is not a YouTube or Vimeo link. */
    public static final String CHANNEL_VIDEO = "QB_CHANNEL_VIDEO";
    /** S2: a channel's quiz is too large to keep as a version. */
    public static final String CHANNEL_TOO_LARGE = "QB_CHANNEL_TOO_LARGE";
    /** Q70 (D-Q70-11): an invited publication welcomes no referrals. */
    public static final String REFERRAL_INVITED = "QB_REFERRAL_INVITED";
    public static final String REFERRAL_QUOTA_RANGE = "QB_REFERRAL_QUOTA_RANGE";
    public static final String REFERRAL_DAYS_RANGE = "QB_REFERRAL_DAYS_RANGE";
    public static final String REFERRAL_AMOUNT_RANGE = "QB_REFERRAL_AMOUNT_RANGE";
    public static final String REFERRAL_METHOD_INVALID = "QB_REFERRAL_METHOD_INVALID";
    public static final String REFERRAL_REQUIREMENT_INVALID = "QB_REFERRAL_REQUIREMENT_INVALID";
    /** Q70 (D-Q70-11): a survey has no pass, so its referrer cannot be required to have passed. */
    public static final String REFERRAL_SURVEY_PASS = "QB_REFERRAL_SURVEY_PASS";
    public static final String REFERRAL_RULE_INVALID = "QB_REFERRAL_RULE_INVALID";
    /** Q70 (D-Q70-19): the pool covers the attempts that may win and the referral quota too. */
    public static final String REFERRAL_POOL_SHORT = "QB_REFERRAL_POOL_SHORT";
    /** Q73b: the languages a publication offers and its texts in them. */
    public static final String LANGUAGES_INVALID = "QB_PUBLICATION_LANGUAGES_INVALID";
    public static final String LANGUAGE_NOT_IN_VERSION = "QB_PUBLICATION_LANGUAGE_NOT_IN_VERSION";
    public static final String TEXT_MISSING = "QB_PUBLICATION_TEXT_MISSING";
    public static final String TEXT_STALE = "QB_PUBLICATION_TEXT_STALE";
    public static final String TEXT_TOO_LONG = "QB_PUBLICATION_TEXT_TOO_LONG";
    public static final String SWITCH_LANGUAGES = "QB_PUBLICATION_VERSION_LANGUAGES";
    /** Q76: more prerequisites than {@link QbPrerequisites#MAX_PREREQUISITES}. */
    public static final String PREREQUISITE_TOO_MANY = "QB_PREREQUISITE_TOO_MANY";
    /** Q76: the same publication required twice. */
    public static final String PREREQUISITE_REPEATED = "QB_PREREQUISITE_REPEATED";
    /** Q76: a publication requiring itself. */
    public static final String PREREQUISITE_SELF = "QB_PREREQUISITE_SELF";
    /** Q76: the required publication is not the sponsor's own (or does not exist). */
    public static final String PREREQUISITE_NOT_OWN = "QB_PREREQUISITE_NOT_OWN";
    /** Q76: the required publication is still a draft. */
    public static final String PREREQUISITE_DRAFT = "QB_PREREQUISITE_DRAFT";
    /** Q76: the required publication requires this one, directly or through others. */
    public static final String PREREQUISITE_CYCLE = "QB_PREREQUISITE_CYCLE";
    /** Q76: the chain would be longer than {@link QbPrerequisites#MAX_DEPTH}. */
    public static final String PREREQUISITE_TOO_DEEP = "QB_PREREQUISITE_TOO_DEEP";
    /** Every code above that is publishing's own (not the shared review and account codes). */

    public static final List<String> ALL = List.of(NAME_BLANK, REGIONS_FORMAT, AUDIENCE_INVALID, TIME_LIMIT_RANGE, SCORE_RANGE,
        BUDGET_RANGE, MAX_WINNERS_RANGE, TIER_REWARD_RANGE, TIME_ORDER, NOT_DRAFT, CHANGED, QUIZ_REMOVED,
        TIER_NOT_IN_PUBLICATION, EXPIRED_IN_REVIEW, NO_VERSION,
        FIELD_REQUIRED, END_PASSED, TOO_LONG, PASS_SCORE_RANGE, BUDGET_BELOW_MAX_REWARD, SPEND_RANGE, TIER_NOT_FROM_ZERO,
        TIER_RANGE, TIER_GAP, TIER_OVERLAP, TIER_NOT_TO_FULL, TIER_NO_REWARD, SURVEY_PASS_SCORE, SURVEY_TIERS,
        ADMIN_PAUSED, NOT_ADMIN_PAUSED, SPONSOR_BANNED, TAKEN_DOWN, REVIEW_REQUIRED, REWARD_METHOD_NOT_ALLOWED, DIGITAL_DELIVERY_INVALID,
        DIGITAL_SEND_DAYS_RANGE, DIGITAL_POOL_SHORT,
        REWARD_METHODS_FORMAT, REWARD_ABOVE_MAX, CHANNEL_PUBLISHER, CHANNEL_VIDEO, CHANNEL_TOO_LARGE, SWITCH_NOT_RUNNING,
        SWITCH_TAKEN_DOWN, SWITCH_PENDING, SWITCH_SAME_VERSION, SWITCH_OTHER_QUIZ, SWITCH_FULL_SCORE, SWITCH_NOT_PENDING,
        SWITCH_ENDED, REFERRAL_INVITED, REFERRAL_QUOTA_RANGE, REFERRAL_DAYS_RANGE, REFERRAL_AMOUNT_RANGE,
        REFERRAL_METHOD_INVALID, REFERRAL_REQUIREMENT_INVALID, REFERRAL_SURVEY_PASS, REFERRAL_RULE_INVALID,
        REFERRAL_POOL_SHORT, LANGUAGES_INVALID, LANGUAGE_NOT_IN_VERSION, TEXT_MISSING, TEXT_STALE, TEXT_TOO_LONG,
        SWITCH_LANGUAGES,
        PREREQUISITE_TOO_MANY, PREREQUISITE_REPEATED, PREREQUISITE_SELF, PREREQUISITE_NOT_OWN, PREREQUISITE_DRAFT,
        PREREQUISITE_CYCLE, PREREQUISITE_TOO_DEEP);

    private PublishingCodes() {}
}
