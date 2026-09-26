package com.jabiz.runtime;

/**
 * An idempotency key was reused for a different process (409 {@code IDEMPOTENCY_KEY_REUSED}): replaying the first
 * result would answer a request that was never made.
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
