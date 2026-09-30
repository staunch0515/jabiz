package com.jabiz.runtime.security;

import com.jabiz.runtime.PermissionDeniedException;

/**
 * The operation needs a recent second factor the session does not have (docs/design/10-security.md section 10);
 * answered with 403 {@code MFA_REQUIRED}. The client confirms with {@code POST /api/auth/step-up} and retries.
 */
public class MfaRequiredException extends PermissionDeniedException {

    public MfaRequiredException(String message) {
        super("-", message);
    }
}
