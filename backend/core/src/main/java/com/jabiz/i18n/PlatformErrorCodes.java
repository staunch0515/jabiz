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
    public static final String MONETARY_SCALE = "MONETARY_SCALE";
    public static final String NOT_IN_DICTIONARY = "NOT_IN_DICTIONARY";
    public static final String UNIQUE_VIOLATION = "UNIQUE_VIOLATION";
    public static final String OPERATOR_NOT_ALLOWED = "OPERATOR_NOT_ALLOWED";
    public static final String FILTER_NOT_ALLOWED = "FILTER_NOT_ALLOWED";
    public static final String SORT_NOT_ALLOWED = "SORT_NOT_ALLOWED";
    public static final String REASON_REQUIRED = "REASON_REQUIRED";
    public static final String NOT_TEMPORAL = "NOT_TEMPORAL";
    public static final String TIME_TRAVEL_NOT_ALLOWED = "TIME_TRAVEL_NOT_ALLOWED";
    public static final String INVALID_IDEMPOTENCY_KEY = "INVALID_IDEMPOTENCY_KEY";
    public static final String SENSITIVE_FIELD = "SENSITIVE_FIELD";
    public static final String PASSWORD_TOO_SHORT = "PASSWORD_TOO_SHORT";
    public static final String PASSWORD_TOO_LONG = "PASSWORD_TOO_LONG";
    public static final String TRANSLATION_REQUIRED = "TRANSLATION_REQUIRED";
    /** Written through the dataset API or a generic entity process (400), or restored by a revert (422). */
    public static final String PROCESS_ONLY_FIELD = "PROCESS_ONLY_FIELD";
    public static final String DISPLAY_NOT_DECLARED = "DISPLAY_NOT_DECLARED";
    /** A masked field given its masked form, which would replace the plain value (docs/design/10-security.md §13.1). */
    public static final String MASKED_VALUE = "MASKED_VALUE";

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
    public static final String CHECK_EVALUATION_FAILED = "CHECK_EVALUATION_FAILED";
    public static final String PARAM_NOT_FOUND = "PARAM_NOT_FOUND";
    public static final String PARAM_VALUE_INVALID = "PARAM_VALUE_INVALID";
    public static final String EFFECTIVE_TIME_NOT_FUTURE = "EFFECTIVE_TIME_NOT_FUTURE";
    public static final String PROCESS_ONLY_DATASET = "PROCESS_ONLY_DATASET";
    public static final String REVERT_NOT_ALLOWED = "REVERT_NOT_ALLOWED";
    /** An instance of a write-once temporal entity (decision D29) was to be updated, deleted or reverted. */
    public static final String WRITE_ONCE = "WRITE_ONCE";
    public static final String LEDGER_TOO_FEW_LINES = "LEDGER_TOO_FEW_LINES";
    public static final String LEDGER_TOO_MANY_LINES = "LEDGER_TOO_MANY_LINES";
    public static final String LEDGER_AMOUNT_NOT_POSITIVE = "LEDGER_AMOUNT_NOT_POSITIVE";
    public static final String LEDGER_AMOUNT_SCALE = "LEDGER_AMOUNT_SCALE";
    public static final String LEDGER_UNBALANCED = "LEDGER_UNBALANCED";
    public static final String LEDGER_ACCOUNT_NOT_FOUND = "LEDGER_ACCOUNT_NOT_FOUND";
    public static final String LEDGER_ACCOUNT_DISABLED = "LEDGER_ACCOUNT_DISABLED";
    public static final String LEDGER_ALREADY_REVERSED = "LEDGER_ALREADY_REVERSED";
    public static final String LEDGER_REVERSAL_NOT_REVERSIBLE = "LEDGER_REVERSAL_NOT_REVERSIBLE";
    public static final String LEDGER_ACCOUNT_NOT_POSTABLE = "LEDGER_ACCOUNT_NOT_POSTABLE";
    public static final String LEDGER_DIMENSION_UNKNOWN = "LEDGER_DIMENSION_UNKNOWN";
    public static final String LEDGER_DIMENSION_INVALID = "LEDGER_DIMENSION_INVALID";
    public static final String LEDGER_SOURCE_NOT_FOUND = "LEDGER_SOURCE_NOT_FOUND";
    public static final String LEDGER_PARENT_NOT_SUMMARY = "LEDGER_PARENT_NOT_SUMMARY";
    public static final String LEDGER_ACCOUNT_CYCLE = "LEDGER_ACCOUNT_CYCLE";
    public static final String LEDGER_SUMMARY_HAS_ENTRIES = "LEDGER_SUMMARY_HAS_ENTRIES";
    public static final String LEDGER_ACCOUNT_HAS_CHILDREN = "LEDGER_ACCOUNT_HAS_CHILDREN";
    public static final String LEDGER_CURRENCY_INVALID = "LEDGER_CURRENCY_INVALID";
    public static final String LEDGER_RATE_INVALID = "LEDGER_RATE_INVALID";
    public static final String LEDGER_FX_AMOUNT_MISMATCH = "LEDGER_FX_AMOUNT_MISMATCH";
    public static final String LEDGER_UNBALANCED_IN_CURRENCY = "LEDGER_UNBALANCED_IN_CURRENCY";
    /** An export would have more rows than {@code jabiz.reports.export.max-rows} (docs/design/19-reports.md section 4). */
    public static final String REPORT_TOO_LARGE = "REPORT_TOO_LARGE";
    /** A report run can supersede an earlier run of the same template only (19 section 5). */
    public static final String REPORT_SUPERSEDE_MISMATCH = "REPORT_SUPERSEDE_MISMATCH";
    /** The run to supersede has been superseded already (19 section 5). */
    public static final String REPORT_ALREADY_SUPERSEDED = "REPORT_ALREADY_SUPERSEDED";
    /** A template a document reads for one row returned none or several (docs/design/22-documents.md section 3). */
    public static final String DOCUMENT_NOT_SINGLE = "DOCUMENT_NOT_SINGLE";
    /** A document would have more rows or bytes than allowed (22 section 3). */
    public static final String DOCUMENT_TOO_LARGE = "DOCUMENT_TOO_LARGE";
    /** The entry is within its retention period (docs/design/21-audit-retention.md section 3). */
    public static final String RETENTION_ACTIVE = "RETENTION_ACTIVE";
    /** The entry is under a legal hold (21 section 3.3). */
    public static final String LEGAL_HOLD = "LEGAL_HOLD";
    /** The hold has been released already (21 section 3.3). */
    public static final String LEGAL_HOLD_NOT_ACTIVE = "LEGAL_HOLD_NOT_ACTIVE";
    public static final String APPROVAL_NOT_PENDING = "APPROVAL_NOT_PENDING";
    public static final String APPROVAL_OWN_REQUEST = "APPROVAL_OWN_REQUEST";
    public static final String APPROVAL_ALREADY_DECIDED = "APPROVAL_ALREADY_DECIDED";
    public static final String APPROVAL_LIMIT_EXCEEDED = "APPROVAL_LIMIT_EXCEEDED";
    /** Also 400, for an invalid draft rule of the impact preview. */
    public static final String CONTROL_CHANGE_INVALID = "CONTROL_CHANGE_INVALID";
    public static final String CONTROL_CHANGE_NOT_PROPOSED = "CONTROL_CHANGE_NOT_PROPOSED";
    public static final String CONTROL_SAME_PERSON = "CONTROL_SAME_PERSON";
    /** 422 where access is given; 403 at the entry of a process. */
    public static final String SOD_CONFLICT = "SOD_CONFLICT";
    /** The second factor code (or recovery code) is wrong, or the account is locked (10 section 9). */
    public static final String MFA_CODE_INVALID = "MFA_CODE_INVALID";
    /** The user has not set up a second factor. */
    public static final String MFA_NOT_ENROLLED = "MFA_NOT_ENROLLED";
    /** The user has set up a second factor already; an administrator resets it first. */
    public static final String MFA_ALREADY_ENROLLED = "MFA_ALREADY_ENROLLED";

    // Conflicts (409)
    public static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";

    // Authentication (401)
    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    public static final String INVALID_REFRESH_TOKEN = "INVALID_REFRESH_TOKEN";

    // Access (403)
    public static final String SCOPE_UNAVAILABLE = "SCOPE_UNAVAILABLE";
    public static final String PERMISSION_DENIED = "PERMISSION_DENIED";
    /** The operation needs a recent second factor (docs/design/10-security.md section 10). */
    public static final String MFA_REQUIRED = "MFA_REQUIRED";

    // Files (docs/design/14-files.md): 400 unless noted
    public static final String FILE_TYPE_NOT_ALLOWED = "FILE_TYPE_NOT_ALLOWED";
    /** 413. */
    public static final String FILE_TOO_LARGE = "FILE_TOO_LARGE";
    public static final String FILE_INVALID = "FILE_INVALID";
    public static final String FILE_NOT_FOUND = "FILE_NOT_FOUND";
    public static final String FILE_POLICY_MISMATCH = "FILE_POLICY_MISMATCH";
    /** 422. */
    public static final String FILE_IN_USE = "FILE_IN_USE";
    /** 422: a role assignment's data period ends before it starts (docs/design/10-security.md section 13.2). */
    public static final String DATA_PERIOD_ORDER = "DATA_PERIOD_ORDER";
    /** 422: an access review of a period that has not ended, or ends before it starts (10 section 13.3). */
    public static final String ACCESS_REVIEW_PERIOD = "ACCESS_REVIEW_PERIOD";
    /** 422: the report an access review refers to is not the access report issued at the end of its period. */
    public static final String ACCESS_REVIEW_REPORT = "ACCESS_REVIEW_REPORT";

    // Too many requests (429)
    public static final String RATE_LIMITED = "RATE_LIMITED";

    public static final List<String> ALL = List.of(
        UNKNOWN_FIELD, INVALID_VALUE, REQUIRED, RULE_EVALUATION_FAILED, REFERENCE_NOT_FOUND, ID_MISMATCH,
        TOO_LONG, NUMERIC_PRECISION, MONETARY_SCALE, NOT_IN_DICTIONARY, UNIQUE_VIOLATION, OPERATOR_NOT_ALLOWED, FILTER_NOT_ALLOWED,
        SORT_NOT_ALLOWED, REASON_REQUIRED, NOT_TEMPORAL, TIME_TRAVEL_NOT_ALLOWED, INVALID_IDEMPOTENCY_KEY,
        SENSITIVE_FIELD, PASSWORD_TOO_SHORT, PASSWORD_TOO_LONG, TRANSLATION_REQUIRED, PROCESS_ONLY_FIELD,
        DISPLAY_NOT_DECLARED, MASKED_VALUE, DATA_PERIOD_ORDER, ACCESS_REVIEW_PERIOD, ACCESS_REVIEW_REPORT,
        IMMUTABLE_FIELD, ILLEGAL_TRANSITION, INVALID_INITIAL_STATE, STATE_REQUIRED, STATE_CLEARED, OUT_OF_SCOPE,
        GUARD_EVALUATION_FAILED, DATASET_READ_ONLY, ENTITY_READ_ONLY, BATCH_TOO_LARGE, STILL_REFERENCED,
        SCHEDULING_NOT_ALLOWED, NOT_SCHEDULED, NOTHING_TO_REVERT,
        CHECK_EVALUATION_FAILED, PARAM_NOT_FOUND, PARAM_VALUE_INVALID, EFFECTIVE_TIME_NOT_FUTURE,
        PROCESS_ONLY_DATASET, REVERT_NOT_ALLOWED, WRITE_ONCE,
        LEDGER_TOO_FEW_LINES, LEDGER_TOO_MANY_LINES, LEDGER_AMOUNT_NOT_POSITIVE, LEDGER_AMOUNT_SCALE,
        LEDGER_UNBALANCED, LEDGER_ACCOUNT_NOT_FOUND, LEDGER_ACCOUNT_DISABLED, LEDGER_ALREADY_REVERSED,
        LEDGER_REVERSAL_NOT_REVERSIBLE, LEDGER_ACCOUNT_NOT_POSTABLE, LEDGER_DIMENSION_UNKNOWN, LEDGER_DIMENSION_INVALID,
        LEDGER_SOURCE_NOT_FOUND, LEDGER_PARENT_NOT_SUMMARY, LEDGER_ACCOUNT_CYCLE, LEDGER_SUMMARY_HAS_ENTRIES,
        LEDGER_ACCOUNT_HAS_CHILDREN, LEDGER_CURRENCY_INVALID, LEDGER_RATE_INVALID, LEDGER_FX_AMOUNT_MISMATCH,
        LEDGER_UNBALANCED_IN_CURRENCY, REPORT_TOO_LARGE, REPORT_SUPERSEDE_MISMATCH,
        REPORT_ALREADY_SUPERSEDED, DOCUMENT_NOT_SINGLE, DOCUMENT_TOO_LARGE, RETENTION_ACTIVE, LEGAL_HOLD, LEGAL_HOLD_NOT_ACTIVE,
        APPROVAL_NOT_PENDING, APPROVAL_OWN_REQUEST, APPROVAL_ALREADY_DECIDED, APPROVAL_LIMIT_EXCEEDED,
        CONTROL_CHANGE_INVALID, CONTROL_CHANGE_NOT_PROPOSED, CONTROL_SAME_PERSON, SOD_CONFLICT,
        MFA_CODE_INVALID, MFA_NOT_ENROLLED, MFA_ALREADY_ENROLLED,
        IDEMPOTENCY_KEY_REUSED,
        UNAUTHENTICATED, LOGIN_FAILED, INVALID_REFRESH_TOKEN,
        SCOPE_UNAVAILABLE, PERMISSION_DENIED, MFA_REQUIRED,
        FILE_TYPE_NOT_ALLOWED, FILE_TOO_LARGE, FILE_INVALID, FILE_NOT_FOUND, FILE_POLICY_MISMATCH, FILE_IN_USE,
        RATE_LIMITED);

    private PlatformErrorCodes() {}
}
