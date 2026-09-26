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
    /** Declared by the bootstrap process, which only the platform runs at startup. */
    public static final String BOOTSTRAP = "security.bootstrap";

    private SecurityPermissions() {}
}
