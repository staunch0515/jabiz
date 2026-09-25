package com.jabiz.runtime;

/**
 * The caller lacks a permission the operation requires; answered with 403. Checks are default-deny: a missing
 * permission is never assumed to be granted.
 */
public class PermissionDeniedException extends RuntimeException {

    private final String permission;

    public PermissionDeniedException(String permission, String message) {
        super(message);
        this.permission = permission;
    }

    public String permission() {
        return permission;
    }
}
