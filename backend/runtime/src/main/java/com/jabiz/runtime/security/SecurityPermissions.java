package com.jabiz.runtime.security;

/** Permission codes of the security module (docs/design/10-security.md section 3). */
public final class SecurityPermissions {

    public static final String USER_READ = "security.user.read";
    public static final String USER_WRITE = "security.user.write";
    public static final String ROLE_READ = "security.role.read";
    public static final String ROLE_WRITE = "security.role.write";
    public static final String USER_ROLE_READ = "security.user-role.read";
    public static final String USER_ROLE_WRITE = "security.user-role.write";
    public static final String MENU_READ = "security.menu.read";
    public static final String MENU_WRITE = "security.menu.write";
    public static final String LOGIN_RECORD_READ = "security.login-record.read";
    /** Login records are written by the sign-in process; nobody needs to write them through their dataset. */
    public static final String LOGIN_RECORD_WRITE = "security.login-record.write";

    /** Creating users (SEC_USER_CREATE). */
    public static final String USER_CREATE = "security.user.create";
    /** Setting another user's password (SEC_USER_SET_PASSWORD). */
    public static final String USER_PASSWORD = "security.user.password";
    /** Lifting a lock after failed sign-ins (SEC_USER_UNLOCK). */
    public static final String USER_UNLOCK = "security.user.unlock";
    /**
     * Declared by the sign-in process. Signing in goes through {@code POST /api/auth/login}, which is public; the
     * permission keeps the process itself from being run through the process API.
     */
    public static final String SIGN_IN = "auth.sign-in";
    /**
     * Linking provider accounts to users (the SecUserIdentity dataset): a link lets its subject sign in as the user, so
     * it is a credential of its own, apart from {@link #USER_WRITE} (docs/design/10-security.md section 12).
     */
    public static final String IDENTITY_WRITE = "security.user.identity.write";
    /** Resetting another user's second factor (SEC_MFA_RESET). */
    public static final String MFA_RESET = "security.user.mfa-reset";
    /**
     * Declared by the enrolment processes, which run only through {@code /api/auth/mfa/**} and
     * {@code /api/auth/challenge/**} as the user enrolling; granted to no role (docs/design/10-security.md section 9).
     */
    public static final String MFA_ENROLL = "auth.mfa-enroll";
    /**
     * Reading the access review: the access report (its template), the security changes of a period, the signed
     * reviews (docs/design/10-security.md section 13.3).
     */
    public static final String ACCESS_REVIEW_READ = "security.access-review.read";
    /** Signing an access review (ACCESS_REVIEW_SIGN_OFF), which always needs a recent second factor. */
    public static final String ACCESS_REVIEW_SIGN = "security.access-review.sign";
    /** Declared by the bootstrap process, which only the platform runs at startup. */
    public static final String BOOTSTRAP = "security.bootstrap";

    private SecurityPermissions() {}
}
