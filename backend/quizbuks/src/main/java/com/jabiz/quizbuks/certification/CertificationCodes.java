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

    public static final List<String> ALL = List.of(NOT_APPROVED_SPONSOR, NO_SUCH_OPERATOR, NOT_OFFICIAL,
        ALREADY_A_ROUND, REQUIRED, HELD);

    private CertificationCodes() {}
}
