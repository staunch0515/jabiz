package com.jabiz.runtime.security;

import com.jabiz.runtime.PermissionDeniedException;

/**
 * An application's sign-in guard refused a sign-in whose credentials were right (decision D36 item 6); answered with
 * 403 {@code SIGN_IN_REFUSED}. The caller has proven the password, so telling the refusal apart leaks nothing; the
 * guard's reason goes to the log only.
 */
public class SignInRefusedException extends PermissionDeniedException {

    public SignInRefusedException(String message) {
        super("-", message);
    }
}
