package com.jabiz.process;

import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default {@link ProcessSequence}: strictly increasing and unique within one JVM, derived from
 * the clock's milliseconds shifted left by 12 bits so that ids stay roughly time ordered.
 */
final class ClockProcessSequence implements ProcessSequence {

    private static final int COUNTER_BITS = 12;

    private final Clock clock;
    private final AtomicLong last = new AtomicLong();

    ClockProcessSequence(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public Mono<Long> next() {
        return Mono.fromSupplier(() -> {
            long candidate = clock.millis() << COUNTER_BITS;
            return last.updateAndGet(previous -> Math.max(previous + 1, candidate));
        });
    }
}
