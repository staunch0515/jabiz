package com.jabiz.security;

/** Result of one sign-in attempt, as recorded in the login records (docs/design/10-security.md). */
public enum LoginOutcome {
    /** Credentials correct, account usable: tokens are issued. */
    SUCCESS,
    /** Wrong password; counts towards the lock. */
    BAD_CREDENTIALS,
    /** The account was locked; the password was not considered. */
    LOCKED,
    /** Correct password, but the account is disabled. */
    DISABLED,
    /** Correct password, but the user holds no role that is in effect. */
    NO_ROLE,
    /** Not an attempt: an administrator lifted the lock. */
    UNLOCKED,
    /** Correct password; the second factor comes next (docs/design/10-security.md section 9). */
    MFA_REQUIRED,
    /** Correct password, but a role requires a second factor the user has not set up: enrolment comes next. */
    MFA_ENROLLMENT_REQUIRED,
    /** Wrong second factor; counts towards the lock like a wrong password. */
    MFA_FAILED,
    /**
     * Correct credentials, but an application's {@link SignInGuard} refused the sign-in (decision D36 item 6); not a
     * failure: the counter is neither raised nor cleared.
     */
    REFUSED,
    /**
     * Correct credentials, but the sign-in entry requires a verified e-mail address the user has not verified
     * (decision D36 item 3); not a failure.
     */
    EMAIL_NOT_VERIFIED,
    /** Not an attempt: the user set a new password through a reset link, which clears the counter and the lock. */
    PASSWORD_RESET;

    /** Codes of the login outcome dictionary. */
    public static String[] codes() {
        LoginOutcome[] values = values();
        String[] codes = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            codes[i] = values[i].name();
        }
        return codes;
    }
}
