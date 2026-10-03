package com.jabiz.runtime;

/**
 * Optimistic locking failed: the stored version differs from the version the caller worked with; or the database gave
 * up waiting for a lock (deadlock, lock timeout). Either way the request may be retried (409).
 */
public class ConcurrentUpdateException extends RuntimeException {
    public ConcurrentUpdateException(String message) {
        super(message);
    }

    public ConcurrentUpdateException(String message, Throwable cause) {
        super(message, cause);
    }
}
