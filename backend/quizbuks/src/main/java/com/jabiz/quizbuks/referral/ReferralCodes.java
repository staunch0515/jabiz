package com.jabiz.quizbuks.referral;

import java.util.List;

/** The referrals' refusal codes (Q70); their texts are in the three languages' messages. */
public final class ReferralCodes {

    /** The publication does not welcome referrals, or does not accept new attempts now. */
    public static final String NOT_OPEN = "QB_REFERRAL_NOT_OPEN";
    /** A sponsor refers nobody to their own publication. */
    public static final String OWN = "QB_REFERRAL_OWN";
    public static final String QUOTA_FULL = "QB_REFERRAL_QUOTA_FULL";
    /** A new link needs the referrer certification (D-Q70-9). */
    public static final String NOT_REFERRER = "QB_REFERRAL_NOT_REFERRER";
    /** The publication asks its referrers to have answered (or passed) it first (D-Q70-10). */
    public static final String REQUIREMENT = "QB_REFERRAL_REQUIREMENT";

    public static final List<String> ALL = List.of(NOT_OPEN, OWN, QUOTA_FULL, NOT_REFERRER, REQUIREMENT);

    private ReferralCodes() {}
}
