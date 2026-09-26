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
    public static final String TOO_LONG = "TOO_LONG";
    public static final String NUMERIC_PRECISION = "NUMERIC_PRECISION";
    public static final String NOT_IN_DICTIONARY = "NOT_IN_DICTIONARY";
    public static final String UNIQUE_VIOLATION = "UNIQUE_VIOLATION";
    public static final String OPERATOR_NOT_ALLOWED = "OPERATOR_NOT_ALLOWED";
    public static final String FILTER_NOT_ALLOWED = "FILTER_NOT_ALLOWED";
    public static final String SORT_NOT_ALLOWED = "SORT_NOT_ALLOWED";
    public static final String REASON_REQUIRED = "REASON_REQUIRED";
    public static final String NOT_TEMPORAL = "NOT_TEMPORAL";
    public static final String TIME_TRAVEL_NOT_ALLOWED = "TIME_TRAVEL_NOT_ALLOWED";
    public static final String INVALID_IDEMPOTENCY_KEY = "INVALID_IDEMPOTENCY_KEY";

    // Business rules (422)
    public static final String IMMUTABLE_FIELD = "IMMUTABLE_FIELD";
    public static final String ILLEGAL_TRANSITION = "ILLEGAL_TRANSITION";
    public static final String INVALID_INITIAL_STATE = "INVALID_INITIAL_STATE";
    public static final String STATE_REQUIRED = "STATE_REQUIRED";
    public static final String STATE_CLEARED = "STATE_CLEARED";
    public static final String OUT_OF_SCOPE = "OUT_OF_SCOPE";
    public static final String GUARD_EVALUATION_FAILED = "GUARD_EVALUATION_FAILED";
    public static final String DATASET_READ_ONLY = "DATASET_READ_ONLY";
    public static final String ENTITY_READ_ONLY = "ENTITY_READ_ONLY";
    public static final String BATCH_TOO_LARGE = "BATCH_TOO_LARGE";
    public static final String STILL_REFERENCED = "STILL_REFERENCED";
    public static final String SCHEDULING_NOT_ALLOWED = "SCHEDULING_NOT_ALLOWED";
    public static final String NOT_SCHEDULED = "NOT_SCHEDULED";
    public static final String NOTHING_TO_REVERT = "NOTHING_TO_REVERT";

    // Conflicts (409)
    public static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";

    // Access (403)
    public static final String SCOPE_UNAVAILABLE = "SCOPE_UNAVAILABLE";
    public static final String PERMISSION_DENIED = "PERMISSION_DENIED";

    public static final List<String> ALL = List.of(
        UNKNOWN_FIELD, INVALID_VALUE, REQUIRED, RULE_EVALUATION_FAILED, REFERENCE_NOT_FOUND, ID_MISMATCH,
        TOO_LONG, NUMERIC_PRECISION, NOT_IN_DICTIONARY, UNIQUE_VIOLATION, OPERATOR_NOT_ALLOWED, FILTER_NOT_ALLOWED,
        SORT_NOT_ALLOWED, REASON_REQUIRED, NOT_TEMPORAL, TIME_TRAVEL_NOT_ALLOWED, INVALID_IDEMPOTENCY_KEY,
        IMMUTABLE_FIELD, ILLEGAL_TRANSITION, INVALID_INITIAL_STATE, STATE_REQUIRED, STATE_CLEARED, OUT_OF_SCOPE,
        GUARD_EVALUATION_FAILED, DATASET_READ_ONLY, ENTITY_READ_ONLY, BATCH_TOO_LARGE, STILL_REFERENCED,
        SCHEDULING_NOT_ALLOWED, NOT_SCHEDULED, NOTHING_TO_REVERT,
        IDEMPOTENCY_KEY_REUSED,
        SCOPE_UNAVAILABLE, PERMISSION_DENIED);

    private PlatformErrorCodes() {}
}
