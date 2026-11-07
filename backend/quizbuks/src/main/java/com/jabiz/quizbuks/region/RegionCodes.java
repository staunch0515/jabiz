package com.jabiz.quizbuks.region;

import java.util.List;

/** The error codes of the rule on foreign videos (F-S11); each has its three-language message. */
public final class RegionCodes {

    /** A start from mainland China of a publication with a foreign video and no exception (422). */
    public static final String REGION_VIDEO = "QB_PUBLICATION_REGION_VIDEO";
    /** An exception names neither one publication nor one sponsor, or both. */
    public static final String EXCEPTION_TARGET = "QB_REGION_EXCEPTION_TARGET";
    /** An exception's reason (and a revocation's) is required. */
    public static final String EXCEPTION_REASON = "QB_REGION_EXCEPTION_REASON";
    /** An exception's last day is before today. */
    public static final String EXCEPTION_EXPIRY_PAST = "QB_REGION_EXCEPTION_EXPIRY_PAST";
    /** The exception was revoked: nothing more changes it. */
    public static final String EXCEPTION_REVOKED = "QB_REGION_EXCEPTION_REVOKED";
    /** The publication or sponsor an exception names does not exist. */
    public static final String EXCEPTION_UNKNOWN_TARGET = "QB_REGION_EXCEPTION_UNKNOWN_TARGET";

    public static final List<String> ALL = List.of(REGION_VIDEO, EXCEPTION_TARGET, EXCEPTION_REASON,
        EXCEPTION_EXPIRY_PAST, EXCEPTION_REVOKED, EXCEPTION_UNKNOWN_TARGET);

    private RegionCodes() {}
}
