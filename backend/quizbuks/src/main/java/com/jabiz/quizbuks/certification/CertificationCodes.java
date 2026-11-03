package com.jabiz.quizbuks.certification;

import java.util.List;

/** The certifications' rule and error codes (Q69); their texts are in the three languages' messages. */
public final class CertificationCodes {

    public static final String NOT_APPROVED_SPONSOR = "QB_CERTIFICATION_NOT_APPROVED_SPONSOR";
    public static final String NO_SUCH_OPERATOR = "QB_CERTIFICATION_NO_SUCH_OPERATOR";
    public static final String NOT_OFFICIAL = "QB_CERTIFICATION_NOT_OFFICIAL";
    public static final String ALREADY_A_ROUND = "QB_CERTIFICATION_ALREADY_A_ROUND";
    /** The sponsor entry's signed-in join without the certification (the sign-up guard's reason). */
    public static final String REQUIRED = "QB_CERTIFICATION_REQUIRED";

    /** Q70a (D-Q70-1, option B): who holds a certification starts none of its rounds until it ends. */
    public static final String HELD = "QB_CERTIFICATION_HELD";

    /** Q88a: the sponsors' own certifications (docs/quizbuks/plans/Q88-sponsor-certifications-entry-fee.md). */
    /** The switch {@code qb.certification.sponsor-rounds.enabled} is off (D-Q88-7). */
    public static final String SPONSOR_OFF = "QB_SPONSOR_CERTIFICATIONS_OFF";
    public static final String SPONSOR_NAME_REQUIRED = "QB_SPONSOR_CERTIFICATION_NAME_REQUIRED";
    public static final String SPONSOR_LANGUAGE = "QB_SPONSOR_CERTIFICATION_LANGUAGE";
    public static final String SPONSOR_TEXT_TOO_LONG = "QB_SPONSOR_CERTIFICATION_TEXT_TOO_LONG";
    public static final String SPONSOR_VALIDITY_RANGE = "QB_SPONSOR_CERTIFICATION_VALIDITY_RANGE";
    public static final String SPONSOR_LIMIT = "QB_SPONSOR_CERTIFICATION_LIMIT";
    /** A retired certification takes no new round and is required by no new publication (D-Q88-1). */
    public static final String SPONSOR_RETIRED = "QB_SPONSOR_CERTIFICATION_RETIRED";
    /** A sponsor's round is set while the publication is a draft; later it can only be closed (D-Q88-3). */
    public static final String ROUND_AFTER_SUBMIT = "QB_SPONSOR_ROUND_AFTER_SUBMIT";
    /** The publication is no round of anything (closing one). */
    public static final String NOT_A_ROUND = "QB_SPONSOR_NOT_A_ROUND";

    public static final List<String> ALL = List.of(NOT_APPROVED_SPONSOR, NO_SUCH_OPERATOR, NOT_OFFICIAL,
        ALREADY_A_ROUND, REQUIRED, HELD, SPONSOR_OFF, SPONSOR_NAME_REQUIRED, SPONSOR_LANGUAGE, SPONSOR_TEXT_TOO_LONG,
        SPONSOR_VALIDITY_RANGE, SPONSOR_LIMIT, SPONSOR_RETIRED, ROUND_AFTER_SUBMIT, NOT_A_ROUND);

    private CertificationCodes() {}
}
