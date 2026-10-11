package com.jabiz.external;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Whatever the declared waits: the backoff never shrinks with more failures and never passes the maximum. */
class ExternalJobBackoffProperties {

    record In(String id) {}

    record Out(String text) {}

    private static ExternalJobType<In, Out> type(long initialMillis, long maxMillis) {
        return ExternalJobType.define("x", In.class, Out.class, t -> t
            .retry(3, Duration.ofMillis(initialMillis), Duration.ofMillis(maxMillis)).onCompleted("A").onFailed("B"));
    }

    @Property(tries = 500)
    void backoffIsMonotoneAndCapped(@ForAll @LongRange(min = 1, max = 3_600_000) long initial,
        @ForAll @LongRange(min = 0, max = 86_400_000) long extra, @ForAll @IntRange(min = 1, max = 200) int failures) {
        ExternalJobType<In, Out> type = type(initial, initial + extra);
        Duration now = type.backoffAfter(failures);
        Duration next = type.backoffAfter(failures + 1);
        assertThat(next).isGreaterThanOrEqualTo(now);
        assertThat(now).isLessThanOrEqualTo(type.maxBackoff());
        assertThat(now).isGreaterThanOrEqualTo(type.initialBackoff());
    }

    @Property(tries = 200)
    void theFirstRetryWaitsTheInitialBackoff(@ForAll @LongRange(min = 1, max = 3_600_000) long initial,
        @ForAll @LongRange(min = 0, max = 3_600_000) long extra) {
        ExternalJobType<In, Out> type = type(initial, initial + extra);
        assertThat(type.backoffAfter(1)).isEqualTo(Duration.ofMillis(initial));
        assertThat(type.backoffAfter(0)).isEqualTo(Duration.ZERO);
    }
}
