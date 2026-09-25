package com.jabiz.i18n;

import java.util.List;

/** Error codes raised by the platform itself; each needs a text in every supported language. */
public final class PlatformErrorCodes {

    // Input validation (400)
    public static final String UNKNOWN_FIELD = "UNKNOWN_FIELD";
    public static final String INVALID_VALUE = "INVALID_VALUE";
    public static final String REQUIRED = "REQUIRED";
    public static final String RULE_EVALUATION_FAILED = "RULE_EVALUATION_FAILED";
    public static final String REFERENCE_NOT_FOUND = "REFERENCE_NOT_FOUND";
    public static final String ID_MISMATCH = "ID_MISMATCH";

    // Business rules (422)
    public static final String IMMUTABLE_FIELD = "IMMUTABLE_FIELD";
    public static final String ILLEGAL_TRANSITION = "ILLEGAL_TRANSITION";
    public static final String INVALID_INITIAL_STATE = "INVALID_INITIAL_STATE";
    public static final String STATE_REQUIRED = "STATE_REQUIRED";
    public static final String STATE_CLEARED = "STATE_CLEARED";
    public static final String OUT_OF_SCOPE = "OUT_OF_SCOPE";
    public static final String SPATIAL_GUARD_REJECTED = "SPATIAL_GUARD_REJECTED";
    public static final String SPATIAL_GUARD_LOCATION_MISSING = "SPATIAL_GUARD_LOCATION_MISSING";
    public static final String DATASET_READ_ONLY = "DATASET_READ_ONLY";
    public static final String ENTITY_READ_ONLY = "ENTITY_READ_ONLY";
    public static final String BATCH_TOO_LARGE = "BATCH_TOO_LARGE";
    public static final String STILL_REFERENCED = "STILL_REFERENCED";

    public static final List<String> ALL = List.of(
        UNKNOWN_FIELD, INVALID_VALUE, REQUIRED, RULE_EVALUATION_FAILED, REFERENCE_NOT_FOUND, ID_MISMATCH,
        IMMUTABLE_FIELD, ILLEGAL_TRANSITION, INVALID_INITIAL_STATE, STATE_REQUIRED, STATE_CLEARED, OUT_OF_SCOPE,
        SPATIAL_GUARD_REJECTED, SPATIAL_GUARD_LOCATION_MISSING, DATASET_READ_ONLY, ENTITY_READ_ONLY,
        BATCH_TOO_LARGE, STILL_REFERENCED);

    private PlatformErrorCodes() {}
}
