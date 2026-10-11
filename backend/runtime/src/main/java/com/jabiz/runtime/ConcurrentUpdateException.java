package com.jabiz.runtime;

/**
 * Optimistic locking failed: the stored version differs from the version the caller worked with; or the database gave
 * up waiting for a lock (deadlock, lock timeout, {@link LockNotAvailableException}); or the write conflicts with data
 * written meanwhile (an entity that already exists, a revert or rebase conflict). Either way the request may be retried
 * (409).
 *
 * <p>{@link #busy()} tells the conflicts that only mean "someone else is working on it right now" (a lock not
 * obtained, a deadlock, a stale version: the same work may well succeed later as it is) from those that need a
 * different request. A per-item job leaves busy items to its next run and counts the others as failed (decision D42).
 */
public class ConcurrentUpdateException extends RuntimeException {

    private final boolean busy;

    public ConcurrentUpdateException(String message) {
        this(message, null, false);
    }

    public ConcurrentUpdateException(String message, Throwable cause) {
        this(message, cause, false);
    }

    protected ConcurrentUpdateException(String message, Throwable cause, boolean busy) {
        super(message, cause);
        this.busy = busy;
    }

    /** The version the caller worked with is no longer the stored one (optimistic locking). */
    public static ConcurrentUpdateException stale(String message) {
        return new ConcurrentUpdateException(message, null, true);
    }

    /** Whether the conflict only means that others hold or just changed what this work needs. */
    public boolean busy() {
        return busy;
    }
}
