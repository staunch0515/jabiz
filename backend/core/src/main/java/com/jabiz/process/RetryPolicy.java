package com.jabiz.process;

import java.time.Duration;
import java.util.Objects;

/**
 * How often an {@link StepPhase#AFTER_COMMIT} step is attempted. The wait before attempt {@code n + 1} is
 * {@code initialBackoff * 2^(n - 1)}.
 *
 * @param maxAttempts    attempts in total, at least 1
 * @param initialBackoff wait before the second attempt
 */
public record RetryPolicy(int maxAttempts, Duration initialBackoff) {

    /** Three attempts, 200 ms and then 400 ms apart. */
    public static final RetryPolicy DEFAULT = new RetryPolicy(3, Duration.ofMillis(200));

    /** A single attempt. */
    public static final RetryPolicy NONE = new RetryPolicy(1, Duration.ZERO);

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        Objects.requireNonNull(initialBackoff, "initialBackoff must not be null");
        if (initialBackoff.isNegative()) {
            throw new IllegalArgumentException("initialBackoff must not be negative");
        }
    }

    /** Wait before the given attempt (2 for the first retry). */
    public Duration backoffBefore(int attempt) {
        if (attempt <= 1) {
            return Duration.ZERO;
        }
        return initialBackoff.multipliedBy(1L << Math.min(attempt - 2, 20));
    }
}
