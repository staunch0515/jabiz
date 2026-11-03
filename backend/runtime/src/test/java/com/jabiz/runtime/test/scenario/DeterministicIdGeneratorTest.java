package com.jabiz.runtime.test.scenario;

import com.jabiz.entity.EntityDefinition;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The keys of a scenario replay (docs/design/07-quality.md section 3.3): the same from run to run, in order within an
 * entity, and untouched by rows of other entities, so that nothing derived from a key moves when an unrelated entity
 * gains a row.
 */
class DeterministicIdGeneratorTest {

    private static final EntityDefinition ORDER = EntityDefinition.define("Order", eb -> {
        eb.physicalTable("t_order");
        eb.primaryKey("orderId");
        eb.field("orderId", f -> f.physicalColumn("order_id").asSemanticIdentity("urn:order"));
    });

    private static final EntityDefinition PARAM = EntityDefinition.define("SysParam", eb -> {
        eb.physicalTable("t_param");
        eb.primaryKey("paramId");
        eb.field("paramId", f -> f.physicalColumn("param_id").asSemanticIdentity("urn:param"));
    });

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void theSameScenarioGetsTheSameKeysAgain() {
        DeterministicIdGenerator first = new DeterministicIdGenerator(CLOCK);
        DeterministicIdGenerator second = new DeterministicIdGenerator(CLOCK);
        assertThat(first.next(ORDER)).isEqualTo(second.next(ORDER));
        assertThat(first.next(PARAM)).isEqualTo(second.next(PARAM));
        assertThat(first.next(ORDER)).isEqualTo(second.next(ORDER));
    }

    @Test
    void anEntitysKeysAreDistinctAndInOrder() {
        DeterministicIdGenerator generator = new DeterministicIdGenerator(CLOCK);
        String one = (String) generator.next(ORDER);
        String two = (String) generator.next(ORDER);
        assertThat(two).isNotEqualTo(one).isGreaterThan(one);
    }

    @Test
    void aRowOfAnotherEntityLeavesTheNextKeyAsItWas() {
        DeterministicIdGenerator plain = new DeterministicIdGenerator(CLOCK);
        DeterministicIdGenerator withParams = new DeterministicIdGenerator(CLOCK);
        plain.next(ORDER);
        withParams.next(ORDER);
        withParams.next(PARAM);
        withParams.next(PARAM);
        assertThat(withParams.next(ORDER)).isEqualTo(plain.next(ORDER));
    }

    @Test
    void keysOfDifferentEntitiesNeverCollide() {
        DeterministicIdGenerator generator = new DeterministicIdGenerator(CLOCK);
        String order = (String) generator.next(ORDER);
        String param = (String) generator.next(PARAM);
        assertThat(order).isNotEqualTo(param);
        // The same millisecond and counter: only the entity's hash tells them apart.
        assertThat(UUID.fromString(order).getMostSignificantBits())
            .isEqualTo(UUID.fromString(param).getMostSignificantBits());
    }

    @Test
    void keysOfNoEntityShareOneBucketAndCollideWithNone() {
        DeterministicIdGenerator generator = new DeterministicIdGenerator(CLOCK);
        String event = (String) generator.next(null);
        String order = (String) generator.next(ORDER);
        assertThat(event).isNotEqualTo(order).isNotEqualTo(generator.next(null));
    }

    @Test
    void theKeysAreVersionSevenUuids() {
        UUID key = UUID.fromString((String) new DeterministicIdGenerator(CLOCK).next(ORDER));
        assertThat(key.version()).isEqualTo(7);
        assertThat(key.variant()).isEqualTo(2);
        // The millisecond of the clock leads, as a UUIDv7's does.
        assertThat(key.getMostSignificantBits() >>> 16).isEqualTo(CLOCK.millis());
    }
}
