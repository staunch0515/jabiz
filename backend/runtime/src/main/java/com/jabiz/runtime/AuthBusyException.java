package com.jabiz.runtime;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;

import java.util.List;
import java.util.Map;

/**
 * Password hashing is saturated (decision D47 item 2): a sign-in, registration or password change waited for its turn
 * as long as allowed, or the queue before hashing was full. 503 {@code AUTH_BUSY} with {@code Retry-After}: a shared
 * resource is overloaded, not this caller asking too much (that is {@link RateLimitedException}, 429). Nothing was
 * recorded: a sign-in refused this way does not count towards the lock of the account.
 */
public class AuthBusyException extends RuntimeException {

    private final long retryAfterSeconds;

    public AuthBusyException(long retryAfterSeconds) {
        // Expected under load, often many at once: no stack trace.
        super("Too many sign-ins are waiting for password hashing; try again in " + Math.max(1, retryAfterSeconds)
            + " seconds", null, false, false);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public List<Violation> violations() {
        return List.of(new Violation(null, PlatformErrorCodes.AUTH_BUSY, getMessage(),
            Map.of("retryAfter", retryAfterSeconds)));
    }
}
