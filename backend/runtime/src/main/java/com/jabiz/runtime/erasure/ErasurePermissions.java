package com.jabiz.runtime.erasure;

/**
 * Permissions of account erasure (decision D62). The erasure and the purge are {@code internal()}: only the system runs
 * them, from their jobs.
 */
public final class ErasurePermissions {

    /** Erasing an account and purging an erasure (system only). */
    public static final String ERASE = "security.user.erase";

    private ErasurePermissions() {
    }
}
