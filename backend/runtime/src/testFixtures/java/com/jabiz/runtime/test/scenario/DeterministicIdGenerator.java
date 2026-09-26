package com.jabiz.runtime.test.scenario;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.runtime.process.entity.EntityIdGenerator;

import java.time.Clock;
import java.util.UUID;

/**
 * UUIDv7 keys without random bits, for scenario replay: the millisecond comes from the (mutable) clock and a
 * counter fills the rest. Replaying the same scenario in a fresh database therefore issues the same keys in the same
 * order, which keeps snapshots in a stable order (docs/design/07-quality.md section 3.3). Test use only.
 */
public final class DeterministicIdGenerator implements EntityIdGenerator {

    private final Clock clock;
    private long counter;

    public DeterministicIdGenerator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized Object next(EntityDefinition definition) {
        counter++;
        long high = (clock.millis() & 0xFFFF_FFFF_FFFFL) << 16 | 0x7000L | (counter >>> 62 & 0x0FFFL);
        long low = (counter & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(high, low).toString();
    }
}
