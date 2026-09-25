package com.jabiz.runtime.process.entity;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class UuidV7GeneratorTest {

    private static final Instant NOW = Instant.parse("2026-01-31T09:00:00.123Z");
    private final UuidV7Generator generator = new UuidV7Generator(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void layoutFollowsRfc9562() {
        UUID id = generator.nextUuid();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
        assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(NOW.toEpochMilli());
    }

    @Test
    void keysAreUniqueAndOrderedByTime() {
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            assertThat(seen.add(generator.nextUuid())).isTrue();
        }
        UUID later = new UuidV7Generator(Clock.fixed(NOW.plusMillis(1), ZoneOffset.UTC)).nextUuid();
        assertThat(seen).allSatisfy(id -> assertThat(id.toString()).isLessThan(later.toString()));
    }

    /** Regression: the former UUID.randomUUID() read /dev/urandom on a non-blocking thread (BlockHound). */
    @Test
    void generatingOnANonBlockingThreadDoesNotBlock() {
        assertThatCode(() -> Mono.fromCallable(() -> generator.next(null))
            .subscribeOn(Schedulers.parallel())
            .block()).doesNotThrowAnyException();
    }
}
