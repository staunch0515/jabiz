package com.jabiz.event;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventSubscriptionTest {

    record In(String id) {}

    static final ProcessDefinition<In, In, ProcessContext> PROCESS = ProcessDefinition.single("CONSUME", 1,
        In.class, In.class, (in, ctx) -> in);

    @Test
    void aConsumerTurnsEventsIntoProcessInputs() {
        EventSubscription<In> subscription = EventSubscription.of("billing.on-close", "logistics.month-closed",
            PROCESS, event -> new In(String.valueOf(event.payload().get("month"))));
        DomainEvent event = new DomainEvent(UUID.randomUUID(), "logistics.month-closed", Map.of("month", "2026-01"),
            7L, Instant.EPOCH);

        assertThat(subscription.input().apply(event)).isEqualTo(new In("2026-01"));
        assertThat(new DomainEvent(event.eventId(), "t", null, null, Instant.EPOCH).payload()).isEmpty();
        assertThat(EntityChangeEvents.eventType("Order")).isEqualTo("jabiz.entity-changed.Order");
    }

    @Test
    void namesAreChecked() {
        assertThatThrownBy(() -> EventSubscription.of("bad name", "t", PROCESS, e -> null))
            .hasMessageContaining("consumer");
        assertThatThrownBy(() -> EventSubscription.of("ok", "", PROCESS, e -> null))
            .hasMessageContaining("eventType");
        assertThatThrownBy(() -> EventSubscription.of("ok", "t", null, e -> null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> EventSubscription.of("ok", "t", PROCESS, null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DomainEvent(null, "t", Map.of(), null, Instant.EPOCH))
            .isInstanceOf(NullPointerException.class);
    }
}
