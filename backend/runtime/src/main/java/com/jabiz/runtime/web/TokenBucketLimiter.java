package com.jabiz.runtime.web;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * In-process token buckets per key (an actor, a client address): each key may take {@code capacity} requests at once
 * and gets {@code capacity} new ones per {@code period}, spread evenly. At most {@code maxKeys} buckets are kept; the
 * least recently used is dropped first, so memory stays bounded. While all {@code maxKeys} buckets are in use, a key
 * not held (new, or dropped earlier) starts with a single token instead of a full burst: whoever floods the table
 * with keys to have its own dropped gains no burst by it (decision D47). With several instances each one limits on its own
 * (an approximation, docs/design/14-files.md section 5). Time is the monotonic clock: this is throttling, not
 * business time.
 */
public final class TokenBucketLimiter {

    /** Whether a request may proceed, and if not, after how many seconds it may. */
    public record Decision(boolean allowed, long retryAfterSeconds) {}

    private final double capacity;
    private final double tokensPerNano;
    private final LongSupplier nanoTime;
    private final int maxKeys;
    private final Map<String, double[]> buckets;

    public TokenBucketLimiter(int capacity, Duration period, int maxKeys) {
        this(capacity, period, maxKeys, System::nanoTime);
    }

    /** As above, timed by {@code nanoTime} (tests). */
    public TokenBucketLimiter(int capacity, Duration period, int maxKeys, LongSupplier nanoTime) {
        this(capacity, capacity, period, maxKeys, nanoTime);
    }

    /**
     * Buckets holding at most {@code capacity} tokens (the burst) that refill at {@code rate} tokens per
     * {@code period}, independently of the burst (for example 20 a minute with a burst of 10).
     */
    public TokenBucketLimiter(int capacity, int rate, Duration period, int maxKeys, LongSupplier nanoTime) {
        if (capacity <= 0 || rate <= 0 || maxKeys <= 0 || period.isNegative() || period.isZero()) {
            throw new IllegalArgumentException("capacity, rate, period and maxKeys must be positive");
        }
        this.capacity = capacity;
        this.tokensPerNano = rate / (double) period.toNanos();
        this.nanoTime = Objects.requireNonNull(nanoTime);
        this.maxKeys = maxKeys;
        this.buckets = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, double[]> eldest) {
                return size() > maxKeys;
            }
        };
    }

    /**
     * Whether {@code key}'s bucket has a token now, without taking it (so that a request counted by several buckets
     * takes from none when one of them refuses). A key not seen yet has a token (a full bucket, or one token when the
     * table is full) and is not remembered.
     */
    public synchronized Decision peek(String key) {
        double[] bucket = buckets.get(key);
        if (bucket == null) {
            return new Decision(true, 0);
        }
        double tokens = Math.min(capacity, bucket[0] + (nanoTime.getAsLong() - bucket[1]) * tokensPerNano);
        if (tokens >= 1) {
            return new Decision(true, 0);
        }
        return new Decision(false, (long) Math.ceil((1 - tokens) / tokensPerNano / 1_000_000_000d));
    }

    /** Takes one token of {@code key}'s bucket if there is one. */
    public synchronized Decision tryAcquire(String key) {
        long now = nanoTime.getAsLong();
        // [tokens, last refill time]
        double[] bucket = buckets.computeIfAbsent(key,
            k -> new double[] {buckets.size() >= maxKeys ? Math.min(capacity, 1) : capacity, now});
        bucket[0] = Math.min(capacity, bucket[0] + (now - bucket[1]) * tokensPerNano);
        bucket[1] = now;
        if (bucket[0] >= 1) {
            bucket[0] -= 1;
            return new Decision(true, 0);
        }
        double nanosToNext = (1 - bucket[0]) / tokensPerNano;
        return new Decision(false, (long) Math.ceil(nanosToNext / 1_000_000_000d));
    }
}
