package com.jabiz.quizbuks.referral;

import com.jabiz.quizbuks.publishing.RefereeRules;
import com.jabiz.quizbuks.publishing.ReferralPolicy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Whether a referral is counted (Q70, D-Q70-12, D-Q70-15, D-Q70-19b): pure, the processes read and ask here. A claim
 * that is not counted is simply not recorded: the page opens as ever and the referee may answer; nobody is told why.
 */
public final class ReferralRules {

    /** The window of the per-day limit of a referrer: the last 24 hours. */
    public static final Duration DAY = Duration.ofDays(1);

    public static final String NOT_OPEN = "NOT_OPEN";
    public static final String SPONSOR_BANNED = "SPONSOR_BANNED";
    public static final String SELF = "SELF";
    public static final String SPONSOR_SIDE = "SPONSOR_SIDE";
    public static final String ALREADY_ANSWERING = "ALREADY_ANSWERING";
    public static final String ALREADY_REFERRED = "ALREADY_REFERRED";
    public static final String QUOTA_FULL = "QUOTA_FULL";
    public static final String REFERRER_TOTAL = "REFERRER_TOTAL";
    public static final String REFERRER_DAILY = "REFERRER_DAILY";
    /** The rules the sponsor chose ({@link RefereeRules}), one reason each. */
    public static final String REGULAR = "REGULAR";
    public static final String NOT_NEW = "NOT_NEW";
    public static final String NOT_MATCHED = "NOT_MATCHED";
    public static final String OTHER_REGION = "OTHER_REGION";

    /**
     * What a claim knows.
     *
     * @param open             the publication welcomes referrals and accepts new attempts now
     * @param referrerDates    when the referrer's referrals on this publication were claimed (every status)
     * @param regular          the referee passed a publication of this sponsor before
     * @param refereeSince     when the referee's profile was made; null without one (new)
     * @param matchesAudience  the referee's profile meets the publication's conditions now
     */
    public record Claim(ReferralPolicy policy, boolean open, boolean sponsorBanned, String publisherId,
        String referrerId, String refereeId, boolean refereeIsStaff, boolean refereeAttempted,
        boolean refereeReferred, int counted, List<Instant> referrerDates, int perReferrerDaily,
        int perReferrerTotal, boolean regular, Instant refereeSince, boolean matchesAudience,
        String refereeCountry, String referrerCountry, Instant now) {}

    /** Why the claim is not counted; null when it is. The order is the order of the checks. */
    public static String claimRefusal(Claim c) {
        if (c.policy() == null || !c.open()) {
            return NOT_OPEN;
        }
        if (c.sponsorBanned()) {
            return SPONSOR_BANNED;
        }
        if (c.referrerId() == null || c.referrerId().equals(c.refereeId())) {
            return SELF;
        }
        if (c.refereeId().equals(c.publisherId()) || c.refereeIsStaff()) {
            return SPONSOR_SIDE;
        }
        if (c.refereeAttempted()) {
            return ALREADY_ANSWERING;
        }
        if (c.refereeReferred()) {
            return ALREADY_REFERRED;
        }
        if (c.counted() >= c.policy().quota()) {
            return QUOTA_FULL;
        }
        if (c.referrerDates().size() >= c.perReferrerTotal()) {
            return REFERRER_TOTAL;
        }
        Instant dayAgo = c.now().minus(DAY);
        if (c.referrerDates().stream().filter(d -> d.isAfter(dayAgo)).count() >= c.perReferrerDaily()) {
            return REFERRER_DAILY;
        }
        return ruleRefusal(c.policy(), c.regular(), c.refereeSince(), c.matchesAudience(), c.refereeCountry(),
            c.referrerCountry(), c.now());
    }

    /** The first of the sponsor's chosen rules the referee does not meet; null when they meet all. */
    public static String ruleRefusal(ReferralPolicy policy, boolean regular, Instant refereeSince,
        boolean matchesAudience, String refereeCountry, String referrerCountry, Instant now) {
        List<String> rules = policy.refereeRules();
        if (rules.contains(RefereeRules.NOT_REGULAR) && regular) {
            return REGULAR;
        }
        if (rules.contains(RefereeRules.NEW_TO_PLATFORM) && refereeSince != null
            && refereeSince.isBefore(now.minus(RefereeRules.NEW_TO_PLATFORM_WITHIN))) {
            return NOT_NEW;
        }
        if (rules.contains(RefereeRules.MATCHES_AUDIENCE) && !matchesAudience) {
            return NOT_MATCHED;
        }
        if (rules.contains(RefereeRules.SAME_REGION_AS_REFERRER)
            && (refereeCountry == null || !Objects.equals(refereeCountry, referrerCountry))) {
            return OTHER_REGION;
        }
        return null;
    }

    /**
     * Whether the referral is earned (D-Q70-16): the referee completed the publication, passing (a survey: completed),
     * after the claim and before it expired.
     */
    public static boolean earned(Instant referredAt, Instant expiresAt, Instant submittedAt, boolean passed) {
        return submittedAt != null && passed && !submittedAt.isBefore(referredAt) && submittedAt.isBefore(expiresAt);
    }

    /**
     * Whether a referral still waiting is over (D-Q70-17): its time is up, or the referee completed the publication
     * without passing. A pass in time is not over: its reward is still to be paid.
     */
    public static boolean over(Instant referredAt, Instant expiresAt, Instant submittedAt, boolean passed,
        Instant now) {
        if (earned(referredAt, expiresAt, submittedAt, passed)) {
            return false;
        }
        return !now.isBefore(expiresAt) || submittedAt != null;
    }

    /**
     * Whether the referrer meets the publication's requirement to make a link (D-Q70-10): none; an attempt they
     * completed; or one they passed.
     */
    public static boolean referrerMeets(String requirement, boolean completed, boolean passed) {
        return switch (requirement == null ? ReferralPolicy.NONE : requirement) {
            case ReferralPolicy.ANSWERED -> completed;
            case ReferralPolicy.PASSED -> passed;
            default -> true;
        };
    }

    private ReferralRules() {}
}
