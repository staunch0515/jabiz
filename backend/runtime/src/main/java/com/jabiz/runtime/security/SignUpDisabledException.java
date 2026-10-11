package com.jabiz.runtime.security;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.runtime.BusinessRuleViolationException;

/**
 * The sign-in entry named by a registration does not exist or does not let people register themselves (decision D36
 * item 2): 422 {@code SIGN_UP_DISABLED}, as when the processes find the entry's registration roles missing. Which
 * entries allow it is no secret ({@code GET /api/auth/entries/{name}}).
 */
public class SignUpDisabledException extends BusinessRuleViolationException {

    public SignUpDisabledException(String message) {
        super(new Violation(null, PlatformErrorCodes.SIGN_UP_DISABLED, message));
    }
}
