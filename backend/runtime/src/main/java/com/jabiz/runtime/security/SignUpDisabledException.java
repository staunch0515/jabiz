package com.jabiz.runtime.security;

import com.jabiz.runtime.PermissionDeniedException;

/**
 * The sign-in entry named by a registration does not exist or does not let people register themselves (decision D36
 * item 2): 403 {@code SIGN_UP_DISABLED}. Which entries allow it is no secret ({@code GET /api/auth/entries/{name}}).
 */
public class SignUpDisabledException extends PermissionDeniedException {

    public SignUpDisabledException(String message) {
        super(SecurityPermissions.SIGN_UP, message);
    }
}
