package com.jabiz.runtime;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;

import java.util.List;
import java.util.Map;

/** Too many requests of one caller (429); {@link #retryAfterSeconds()} goes into {@code Retry-After}. */
public class RateLimitedException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitedException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public List<Violation> violations() {
        return List.of(new Violation(null, PlatformErrorCodes.RATE_LIMITED, getMessage(),
            Map.of("retryAfter", retryAfterSeconds)));
    }
}
