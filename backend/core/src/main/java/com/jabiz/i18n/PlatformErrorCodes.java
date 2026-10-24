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
    /** A controlled parameter changes only through a published controlled change (decision D40). */
    public static final String PARAM_CONTROLLED = "PARAM_CONTROLLED";
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
    /**
     * A read that takes a whole result (a template or an entity query in a process, a SQL dictionary) would have more
     * rows than {@code jabiz.process.max-read-rows} (decision D32): refused rather than cut short.
     */
    public static final String PROCESS_READ_TOO_LARGE = "PROCESS_READ_TOO_LARGE";
    /**
     * A query did not finish within its time limit (the dataset's {@code queryTimeout} or the template's
     * {@code timeoutMs}): stopped by the database, answered 503 (phase 14q).
     */
    public static final String QUERY_TIMEOUT = "QUERY_TIMEOUT";
    /**
     * A call to an external service failed: refused, timed out, too large an answer, no connection, or an answer the
     * step could not use (decision D38); answered 502.
     */
    public static final String EXTERNAL_CALL_FAILED = "EXTERNAL_CALL_FAILED";
    /** 422: an external job's input holds a secret, which would be stored with the job (decision D38). */
    public static final String EXTERNAL_JOB_INPUT_SECRET = "EXTERNAL_JOB_INPUT_SECRET";
    /**
     * 422: an external job's input is larger than its type stores (64 KiB of JSON unless declared, at most 1 MiB;
     * decisions D38, D50).
     */
    public static final String EXTERNAL_JOB_INPUT_TOO_LARGE = "EXTERNAL_JOB_INPUT_TOO_LARGE";
    /**
     * 422: a file given to an external job does not exist, is of a policy the job does not read, or the requester may
     * not read it; one answer for all three, so that it tells nothing of other people's files (decision D50).
     */
    public static final String EXTERNAL_JOB_INPUT_FILE = "EXTERNAL_JOB_INPUT_FILE";
    /** 422: an external job is given more files, or a larger one, than its type reads (decision D50). */
    public static final String EXTERNAL_JOB_INPUT_FILE_LIMIT = "EXTERNAL_JOB_INPUT_FILE_LIMIT";
    /** 422: the external job to cancel has finished, or was cancelled already (decision D38 item 5). */
    public static final String EXTERNAL_JOB_FINISHED = "EXTERNAL_JOB_FINISHED";
    /**
     * 422: the requester has as many open jobs of the type as it allows, or the reference has one open already
     * (decision D38).
     */
    public static final String EXTERNAL_JOB_LIMIT = "EXTERNAL_JOB_LIMIT";
    /** A report run can supersede an earlier run of the same template only (19 section 5). */
    public static final String REPORT_SUPERSEDE_MISMATCH = "REPORT_SUPERSEDE_MISMATCH";
    /** The run to supersede has been superseded already (19 section 5). */
    public static final String REPORT_ALREADY_SUPERSEDED = "REPORT_ALREADY_SUPERSEDED";
    /** A template a document reads for one row returned none or several (docs/design/22-documents.md section 3). */
    public static final String DOCUMENT_NOT_SINGLE = "DOCUMENT_NOT_SINGLE";
    /** A document would have more rows or bytes than allowed (22 section 3). */
    public static final String DOCUMENT_TOO_LARGE = "DOCUMENT_TOO_LARGE";
    /** Sending a document to an address its data does not name needs {@code document.send.any} (22 section 5). */
    public static final String DOCUMENT_RECIPIENT_NOT_ALLOWED = "DOCUMENT_RECIPIENT_NOT_ALLOWED";
    /** A document was to be sent but neither the request nor its data names an address (22 section 5). */
    public static final String DOCUMENT_NO_RECIPIENT = "DOCUMENT_NO_RECIPIENT";
    /** Sending e-mail needs {@code jabiz.mail.enabled} (22 section 5). */
    public static final String MAIL_DISABLED = "MAIL_DISABLED";
    /** A mail was to go to a user without an e-mail address (18 section 5.6). */
    public static final String MAIL_NO_ADDRESS = "MAIL_NO_ADDRESS";
    /** A one-time token of a mail is unknown, expired, used, superseded or of another purpose (18 section 5.6). */
    public static final String TOKEN_INVALID = "TOKEN_INVALID";
    /**
     * 422: the current password given to verify the address of an account that may have been registered by somebody
     * else is wrong (docs/design/10-security.md section 15.7), or to change one's own password (section 15.8).
     */
    public static final String PASSWORD_INCORRECT = "PASSWORD_INCORRECT";
    /**
     * 422: a user who signs in through an identity provider only has no password to change; a password is set through
     * the password reset (docs/design/10-security.md section 15.8).
     */
    public static final String PASSWORD_NOT_SET = "PASSWORD_NOT_SET";
    /**
     * 404: no live session of the caller's (at the caller's entry) with this id, or of the user named; told alike
     * whether it never existed, has ended or is somebody else's (docs/design/10-security.md section 15.9).
     */
    public static final String SESSION_NOT_FOUND = "SESSION_NOT_FOUND";
    /**
     * 422: the caller's access token does not name its session (issued before sessions were listed), so "the other
     * sessions" cannot be told apart; signing in again gives one that does (section 15.9).
     */
    public static final String SESSION_UNKNOWN = "SESSION_UNKNOWN";
    /**
     * 403: an identity provider vouched for an address that belongs to an account already, so no account is opened and
     * nothing is linked (decision D39 item 2): the user signs in as before and links the provider in their account
     * settings. Told only to whom the provider has just proven the address (docs/design/10-security.md section 12).
     */
    public static final String OIDC_ACCOUNT_EXISTS = "OIDC_ACCOUNT_EXISTS";
    /**
     * 422: starting to link a provider account needs a recent second factor or the current password
     * (docs/design/10-security.md section 12).
     */
    public static final String REAUTHENTICATION_REQUIRED = "REAUTHENTICATION_REQUIRED";
    /**
     * 422: starting to link a provider account needs a recent second factor, and the account has no password to confirm
     * with instead (an account a provider opened): set up a second factor and confirm with it, or set a password through
     * the password reset (docs/design/10-security.md section 12).
     */
    public static final String REAUTHENTICATION_UNAVAILABLE = "REAUTHENTICATION_UNAVAILABLE";
    /** 422: the provider account is linked to another user already. */
    public static final String IDENTITY_IN_USE = "IDENTITY_IN_USE";
    /** 422: the user has linked another account of this provider already (one per provider). */
    public static final String IDENTITY_PROVIDER_LINKED = "IDENTITY_PROVIDER_LINKED";
    /**
     * 422: the account registered itself and its address was never verified, so whoever owns the mailbox may still
     * take it over: it cannot be linked to a provider until the address is verified (section 15.7).
     */
    public static final String IDENTITY_LINK_UNAVAILABLE = "IDENTITY_LINK_UNAVAILABLE";
    /** 422: the round trip of a link through the provider failed (state, code or ID token not accepted). */
    public static final String IDENTITY_LINK_FAILED = "IDENTITY_LINK_FAILED";
    /** 422: removing the link would leave the account without a way to sign in (no password, no other link). */
    public static final String LAST_SIGN_IN_METHOD = "LAST_SIGN_IN_METHOD";
    /** A mail parameter's value is a secret ({@code @Sensitive}); a mail carries no secret but its tokens. */
    public static final String MAIL_PARAM_SECRET = "MAIL_PARAM_SECRET";
    /** The parameters of a mail are larger than {limit} characters as JSON. */
    public static final String MAIL_PARAMS_TOO_LARGE = "MAIL_PARAMS_TOO_LARGE";
    /**
     * A mail with a sign-in link names an entry that does not sign in by mailed link, or a path to return to outside
     * that entry's frontend (decision D55).
     */
    public static final String MAIL_SIGN_IN_LINK = "MAIL_SIGN_IN_LINK";
    /** The entry is within its retention period (docs/design/21-audit-retention.md section 3). */
    public static final String RETENTION_ACTIVE = "RETENTION_ACTIVE";
    /** The entry is under a legal hold (21 section 3.3). */
    public static final String LEGAL_HOLD = "LEGAL_HOLD";
    /** The hold has been released already (21 section 3.3). */
    public static final String LEGAL_HOLD_NOT_ACTIVE = "LEGAL_HOLD_NOT_ACTIVE";
    /** A task that is closed already cannot be resolved again (docs/design/18 section 5.3a); params {@code status}. */
    public static final String TASK_NOT_OPEN = "TASK_NOT_OPEN";
    /** An approval's task is closed by deciding the approval, never resolved by hand (18 section 5.3a). */
    public static final String TASK_CLOSED_BY_ITS_PROCESS = "TASK_CLOSED_BY_ITS_PROCESS";
    public static final String APPROVAL_NOT_PENDING = "APPROVAL_NOT_PENDING";
    public static final String APPROVAL_OWN_REQUEST = "APPROVAL_OWN_REQUEST";
    public static final String APPROVAL_ALREADY_DECIDED = "APPROVAL_ALREADY_DECIDED";
    public static final String APPROVAL_LIMIT_EXCEEDED = "APPROVAL_LIMIT_EXCEEDED";
    /** Also 400, for an invalid draft rule of the impact preview. */
    public static final String CONTROL_CHANGE_INVALID = "CONTROL_CHANGE_INVALID";
    public static final String CONTROL_CHANGE_NOT_PROPOSED = "CONTROL_CHANGE_NOT_PROPOSED";
    public static final String CONTROL_SAME_PERSON = "CONTROL_SAME_PERSON";
    /** The instance a change was proposed for is no longer the one it would change (decision D40). */
    public static final String CONTROL_TARGET_CHANGED = "CONTROL_TARGET_CHANGED";
    /** 422 where access is given; 403 at the entry of a process. */
    public static final String SOD_CONFLICT = "SOD_CONFLICT";
    /**
     * 422: the write gives a user a protected permission, or resets the credentials of a holder of one, and needs a
     * second administrator's approval (decision D48); params {@code entity}, {@code process} (the one to request it).
     */
    public static final String PROTECTED_CHANGE_REQUIRES_APPROVAL = "PROTECTED_CHANGE_REQUIRES_APPROVAL";
    /** 403: the first administrator may no longer make or decide protected changes once another exists (D48). */
    public static final String BOOTSTRAP_ADMIN_RETIRED = "BOOTSTRAP_ADMIN_RETIRED";
    /** 422: the approver is a user the protected change is for (D48). */
    public static final String APPROVAL_TARGET_USER = "APPROVAL_TARGET_USER";
    /** 422: the protected change is no longer pending (D48). */
    public static final String PROTECTED_CHANGE_NOT_PENDING = "PROTECTED_CHANGE_NOT_PENDING";
    /**
     * 422: a signed-in user asked to verify an address someone else changed while someone else may know a way into the
     * account (D48, review M-b): the one asking may not be the user.
     */
    public static final String EMAIL_SET_BY_ANOTHER = "EMAIL_SET_BY_ANOTHER";
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
    /**
     * The operation, or signing in through this entry, needs a verified e-mail address (decision D36 item 3; 10
     * section 15).
     */
    public static final String EMAIL_NOT_VERIFIED = "EMAIL_NOT_VERIFIED";
    /**
     * The process runs as the system only, through its platform entry point only, or as a sub-process only (decision
     * D41), whatever the caller's permissions.
     */
    public static final String PROCESS_INTERNAL = "PROCESS_INTERNAL";
    /** An application's sign-in guard refused the sign-in after the credentials were found right (D36 item 6). */
    public static final String SIGN_IN_REFUSED = "SIGN_IN_REFUSED";
    /**
     * 422: the sign-in entry does not exist or does not let people register themselves, or its registration roles do
     * not exist (decision D36 item 2). Which entries allow registration is no secret.
     */
    public static final String SIGN_UP_DISABLED = "SIGN_UP_DISABLED";

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

    /**
     * 400: an inbound webhook request was not accepted (decision D37); the same whatever the reason, which is only
     * logged.
     */
    public static final String INBOUND_REJECTED = "INBOUND_REJECTED";

    // Too many requests (429)
    public static final String RATE_LIMITED = "RATE_LIMITED";

    /**
     * 503: the queue before a hot named lock is full, or the wait in it ran out (decision D45); params {@code reason}
     * ({@code QUEUE_FULL} / {@code WAIT_TIMEOUT}) and {@code retryAfter} (seconds).
     */
    public static final String PROCESS_BUSY = "PROCESS_BUSY";

    /**
     * 503: too many sign-ins, registrations or password changes are waiting for password hashing (decision D47);
     * param {@code retryAfter} (seconds).
     */
    public static final String AUTH_BUSY = "AUTH_BUSY";

    public static final List<String> ALL = List.of(
        UNKNOWN_FIELD, INVALID_VALUE, REQUIRED, RULE_EVALUATION_FAILED, REFERENCE_NOT_FOUND, ID_MISMATCH,
        TOO_LONG, NUMERIC_PRECISION, MONETARY_SCALE, NOT_IN_DICTIONARY, UNIQUE_VIOLATION, OPERATOR_NOT_ALLOWED, FILTER_NOT_ALLOWED,
        SORT_NOT_ALLOWED, REASON_REQUIRED, NOT_TEMPORAL, TIME_TRAVEL_NOT_ALLOWED, INVALID_IDEMPOTENCY_KEY,
        SENSITIVE_FIELD, PASSWORD_TOO_SHORT, PASSWORD_TOO_LONG, TRANSLATION_REQUIRED, PROCESS_ONLY_FIELD,
        DISPLAY_NOT_DECLARED, MASKED_VALUE, DATA_PERIOD_ORDER, ACCESS_REVIEW_PERIOD, ACCESS_REVIEW_REPORT,
        IMMUTABLE_FIELD, ILLEGAL_TRANSITION, INVALID_INITIAL_STATE, STATE_REQUIRED, STATE_CLEARED, OUT_OF_SCOPE,
        GUARD_EVALUATION_FAILED, DATASET_READ_ONLY, ENTITY_READ_ONLY, BATCH_TOO_LARGE, STILL_REFERENCED,
        SCHEDULING_NOT_ALLOWED, NOT_SCHEDULED, NOTHING_TO_REVERT,
        CHECK_EVALUATION_FAILED, PARAM_NOT_FOUND, PARAM_VALUE_INVALID, EFFECTIVE_TIME_NOT_FUTURE, PARAM_CONTROLLED,
        PROCESS_ONLY_DATASET, REVERT_NOT_ALLOWED, WRITE_ONCE,
        LEDGER_TOO_FEW_LINES, LEDGER_TOO_MANY_LINES, LEDGER_AMOUNT_NOT_POSITIVE, LEDGER_AMOUNT_SCALE,
        LEDGER_UNBALANCED, LEDGER_ACCOUNT_NOT_FOUND, LEDGER_ACCOUNT_DISABLED, LEDGER_ALREADY_REVERSED,
        LEDGER_REVERSAL_NOT_REVERSIBLE, LEDGER_ACCOUNT_NOT_POSTABLE, LEDGER_DIMENSION_UNKNOWN, LEDGER_DIMENSION_INVALID,
        LEDGER_SOURCE_NOT_FOUND, LEDGER_PARENT_NOT_SUMMARY, LEDGER_ACCOUNT_CYCLE, LEDGER_SUMMARY_HAS_ENTRIES,
        LEDGER_ACCOUNT_HAS_CHILDREN, LEDGER_CURRENCY_INVALID, LEDGER_RATE_INVALID, LEDGER_FX_AMOUNT_MISMATCH,
        LEDGER_UNBALANCED_IN_CURRENCY, REPORT_TOO_LARGE, PROCESS_READ_TOO_LARGE, QUERY_TIMEOUT, REPORT_SUPERSEDE_MISMATCH,
        EXTERNAL_JOB_INPUT_SECRET, EXTERNAL_JOB_INPUT_TOO_LARGE, EXTERNAL_JOB_FINISHED, EXTERNAL_JOB_LIMIT,
        EXTERNAL_JOB_INPUT_FILE, EXTERNAL_JOB_INPUT_FILE_LIMIT,
        REPORT_ALREADY_SUPERSEDED, DOCUMENT_NOT_SINGLE, DOCUMENT_TOO_LARGE,
        DOCUMENT_RECIPIENT_NOT_ALLOWED, DOCUMENT_NO_RECIPIENT, MAIL_DISABLED, MAIL_NO_ADDRESS, TOKEN_INVALID, PASSWORD_INCORRECT, PASSWORD_NOT_SET, SESSION_NOT_FOUND, SESSION_UNKNOWN,
        OIDC_ACCOUNT_EXISTS, REAUTHENTICATION_REQUIRED, REAUTHENTICATION_UNAVAILABLE, IDENTITY_IN_USE, IDENTITY_PROVIDER_LINKED, IDENTITY_LINK_UNAVAILABLE,
        IDENTITY_LINK_FAILED, LAST_SIGN_IN_METHOD, MAIL_PARAM_SECRET, MAIL_PARAMS_TOO_LARGE, MAIL_SIGN_IN_LINK,
        RETENTION_ACTIVE, LEGAL_HOLD, LEGAL_HOLD_NOT_ACTIVE,
        TASK_NOT_OPEN, TASK_CLOSED_BY_ITS_PROCESS, APPROVAL_NOT_PENDING, APPROVAL_OWN_REQUEST, APPROVAL_ALREADY_DECIDED, APPROVAL_LIMIT_EXCEEDED,
        CONTROL_CHANGE_INVALID, CONTROL_CHANGE_NOT_PROPOSED, CONTROL_SAME_PERSON, CONTROL_TARGET_CHANGED, SOD_CONFLICT,
        PROTECTED_CHANGE_REQUIRES_APPROVAL, BOOTSTRAP_ADMIN_RETIRED, APPROVAL_TARGET_USER, PROTECTED_CHANGE_NOT_PENDING,
        EMAIL_SET_BY_ANOTHER,
        MFA_CODE_INVALID, MFA_NOT_ENROLLED, MFA_ALREADY_ENROLLED,
        IDEMPOTENCY_KEY_REUSED,
        UNAUTHENTICATED, LOGIN_FAILED, INVALID_REFRESH_TOKEN,
        SCOPE_UNAVAILABLE, PERMISSION_DENIED, MFA_REQUIRED, EMAIL_NOT_VERIFIED, PROCESS_INTERNAL, SIGN_IN_REFUSED, SIGN_UP_DISABLED,
        FILE_TYPE_NOT_ALLOWED, FILE_TOO_LARGE, FILE_INVALID, FILE_NOT_FOUND, FILE_POLICY_MISMATCH, FILE_IN_USE,
        INBOUND_REJECTED, RATE_LIMITED, PROCESS_BUSY, AUTH_BUSY);

    private PlatformErrorCodes() {}
}
