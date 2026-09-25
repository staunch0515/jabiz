package com.jabiz.runtime.process.sponsor;

import com.jabiz.process.ProcessContext;

/**
 * Context of the sponsor sign-in process. It offers typed access to the values that steps hand
 * over to each other, instead of relying on string keys at every use site.
 */
public class LoginContext extends ProcessContext {

    public static final String INPUT_USERNAME_OR_EMAIL = "usernameOrEmail";
    public static final String INPUT_PASSWORD = "password";
    public static final String KEY_AUTHENTICATED_USER = "authenticated_user";
    public static final String KEY_LOGIN_RECORD_ID = "login_record_id";

    public LoginContext(long processSeqId) {
        super(processSeqId);
    }

    public void setAuthenticatedUser(AuthenticatedUser user) {
        put(KEY_AUTHENTICATED_USER, user);
    }

    public AuthenticatedUser getAuthenticatedUser() {
        AuthenticatedUser user = get(KEY_AUTHENTICATED_USER, AuthenticatedUser.class);
        if (user == null) {
            throw new IllegalStateException("The authentication step has not run: no authenticated user in context");
        }
        return user;
    }

    public void setLoginRecordId(String loginRecordId) {
        put(KEY_LOGIN_RECORD_ID, loginRecordId);
    }

    public String getLoginRecordId() {
        String loginRecordId = get(KEY_LOGIN_RECORD_ID, String.class);
        if (loginRecordId == null) {
            throw new IllegalStateException("The login record step has not run: no login record id in context");
        }
        return loginRecordId;
    }
}
