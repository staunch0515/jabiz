package com.jabiz.runtime.security.protect;

import com.jabiz.runtime.PermissionDeniedException;

/**
 * The first administrator (the user {@code SEC_BOOTSTRAP_ADMIN} created) asks for, makes or decides a protected change
 * while another administrator exists (decision D48 item 1); answered with 403 {@code BOOTSTRAP_ADMIN_RETIRED}.
 */
public class BootstrapAdminRetiredException extends PermissionDeniedException {

    public BootstrapAdminRetiredException(String message) {
        super("*", message);
    }
}
