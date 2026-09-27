package com.jabiz.runtime.web;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenBucketLimiterTest {

    @Test
    void refillsEvenlyAndSaysWhenToRetry() {
        AtomicLong now = new AtomicLong();
        TokenBucketLimiter limiter = new TokenBucketLimiter(3, Duration.ofMinutes(1), 10, now::get);
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        }
        TokenBucketLimiter.Decision refused = limiter.tryAcquire("a");
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterSeconds()).isEqualTo(20);
        assertThat(limiter.tryAcquire("b").allowed()).isTrue();
        now.addAndGet(Duration.ofSeconds(20).toNanos());
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
    }

    @Test
    void keepsABoundedNumberOfBuckets() {
        AtomicLong now = new AtomicLong();
        TokenBucketLimiter limiter = new TokenBucketLimiter(1, Duration.ofMinutes(1), 2, now::get);
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("b").allowed()).isTrue();
        assertThat(limiter.tryAcquire("c").allowed()).isTrue();
        // "a" was dropped as least recently used: it starts again with a full bucket.
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThatThrownBy(() -> new TokenBucketLimiter(0, Duration.ofMinutes(1), 1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
