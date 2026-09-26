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
    UNLOCKED;

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
