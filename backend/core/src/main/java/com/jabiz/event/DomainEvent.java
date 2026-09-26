package com.jabiz.event;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * An event as a consumer receives it from the outbox (docs/design/11-ledger-events-jobs.md section 2).
 *
 * @param eventId      identity of the event; the same on every delivery
 * @param eventType    for example {@code logistics.freight-month-closed} or {@code jabiz.entity-changed.Order}
 * @param payload      the published payload as JSON values (secrets are never part of it)
 * @param processSeqId the operation that published it; null for entity changes written outside an operation
 * @param createdTime  when it was published (the operation time)
 */
public record DomainEvent(UUID eventId, String eventType, Map<String, Object> payload, Long processSeqId,
    Instant createdTime) {

    public DomainEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        payload = payload == null ? Map.of() : payload;
        Objects.requireNonNull(createdTime, "createdTime must not be null");
    }
}
