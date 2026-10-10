package com.jabiz.runtime.security;

import com.jabiz.runtime.PermissionDeniedException;

/**
 * The operation, or signing in through the entry, needs a verified e-mail address the caller does not have
 * (docs/design/10-security.md section 15; decision D36 item 3); answered with 403 {@code EMAIL_NOT_VERIFIED}.
 */
public class EmailNotVerifiedException extends PermissionDeniedException {

    public EmailNotVerifiedException(String message) {
        super("-", message);
    }
}
