package com.jabiz.runtime.process.entity;

import com.jabiz.entity.EntityDefinition;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/**
 * Default {@link EntityIdGenerator}: time-ordered UUIDv7 keys (RFC 9562), so new rows cluster at the end of
 * primary-key indexes.
 *
 * <p>Random bits come from a DRBG seeded once, here, on the startup thread. {@code UUID.randomUUID()} would
 * use the platform default {@code NativePRNG}, which reads {@code /dev/urandom} on every call: a blocking file
 * read on the event loop. The millisecond timestamp comes from the injected clock, like every other time.
 */
public final class UuidV7Generator implements EntityIdGenerator {

    private final Clock clock;
    private final SecureRandom random;

    public UuidV7Generator(Clock clock) {
        this.clock = clock;
        try {
            this.random = SecureRandom.getInstance("DRBG");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("The DRBG SecureRandom is not available", e);
        }
        // Instantiation is lazy; seed now rather than on the first request.
        random.nextBytes(new byte[16]);
    }

    @Override
    public Object next(EntityDefinition definition) {
        return nextUuid().toString();
    }

    UUID nextUuid() {
        long millis = clock.millis();
        byte[] bytes = new byte[10];
        random.nextBytes(bytes);
        long randA = ((bytes[0] & 0xFFL) << 8 | (bytes[1] & 0xFFL)) & 0x0FFFL;
        long high = (millis & 0xFFFF_FFFF_FFFFL) << 16 | 0x7000L | randA;
        long randB = 0;
        for (int i = 2; i < 10; i++) {
            randB = randB << 8 | (bytes[i] & 0xFFL);
        }
        long low = (randB & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(high, low);
    }
}
