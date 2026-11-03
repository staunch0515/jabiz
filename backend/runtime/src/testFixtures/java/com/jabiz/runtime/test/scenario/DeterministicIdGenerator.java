package com.jabiz.runtime.test.scenario;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.runtime.process.entity.EntityIdGenerator;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * UUIDv7 keys without random bits, for scenario replay: the millisecond comes from the (mutable) clock, the entity's
 * name and a counter of that entity's own fill the rest. Replaying the same scenario in a fresh database therefore
 * issues the same keys in the same order, which keeps snapshots in a stable order (docs/design/07-quality.md section
 * 3.3); and a row of another entity added to the scenario, or to the set-up every scenario runs (a parameter, a rule,
 * a role), leaves the keys of every other entity as they were, so that what is derived from a key, such as the order
 * a quiz's options are shown in, does not move with unrelated additions. Keys of different entities never collide:
 * the 32 bits below the variant are the entity name's hash (the registry of entities keys every row by its UUID
 * alone). Test use only.
 */
public final class DeterministicIdGenerator implements EntityIdGenerator {

    private final Clock clock;
    private final Map<String, Long> counters = new HashMap<>();

    public DeterministicIdGenerator(Clock clock) {
        this.clock = clock;
    }

    /** @param definition the entity; null for the platform's keys of no entity (events, runs, deliveries), one bucket */
    @Override
    public synchronized Object next(EntityDefinition definition) {
        String name = definition == null ? "" : definition.name;
        long counter = counters.merge(name, 1L, Long::sum);
        long high = (clock.millis() & 0xFFFF_FFFF_FFFFL) << 16 | 0x7000L | (counter >>> 30 & 0x0FFFL);
        long low = 0x8000_0000_0000_0000L | (name.hashCode() & 0xFFFF_FFFFL) << 30 | (counter & 0x3FFF_FFFFL);
        return new UUID(high, low).toString();
    }
}
