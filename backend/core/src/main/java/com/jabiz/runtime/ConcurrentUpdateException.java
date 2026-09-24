package com.jabiz.runtime;

/** Optimistic locking failed: the stored version differs from the version the caller worked with. */
public class ConcurrentUpdateException extends RuntimeException {
    public ConcurrentUpdateException(String message) {
        super(message);
    }
}
