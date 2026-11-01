package com.jabiz.runtime.security;

import com.jabiz.runtime.PermissionDeniedException;

/**
 * An application's sign-up guard refused to give an account an entry's registration roles (decision D66): 403
 * {@code SIGN_UP_REFUSED}, whose message is the text of the reason ({@code SIGN_UP_REFUSED.reason.<reason>}) and
 * whose problem names the reason as {@code reason}. Nothing the guard was given depends on whether an address has an
 * account (a registration's attempt has no user), so telling the refusal leaks nothing.
 */
public class SignUpRefusedException extends PermissionDeniedException {

    private final String reason;

    public SignUpRefusedException(String reason, String message) {
        super("-", message);
        this.reason = reason;
    }

    /** The guard's reason code. */
    public String reason() {
        return reason;
    }
}
