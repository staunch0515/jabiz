package com.jabiz.runtime.event;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.event.EntityChangeEvents;
import com.jabiz.event.EventSubscription;
import com.jabiz.runtime.operation.Operation;
import com.jabiz.runtime.operation.Operations;
import com.jabiz.runtime.process.entity.EntityIdGenerator;
import com.jabiz.runtime.storage.JsonText;
import com.jabiz.runtime.storage.StorageEngine;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Appends events to {@code sys_outbox_event} (docs/design/11-ledger-events-jobs.md section 2). Always called within
 * the transaction of the write that publishes the event, on that write's storage engine: an event exists exactly
 * when its transaction committed. Events of a running operation carry its number and time.
 */
@Component
public class Outbox {

    static final String TABLE = "sys_outbox_event";

    private final EntityIdGenerator ids;
    private final Clock clock;
    private final JsonMapper json;

    public Outbox(EntityIdGenerator ids, Clock clock, JsonMapper json) {
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.json = Objects.requireNonNull(json, "json must not be null");
    }

    /**
     * Appends an event.
     *
     * @param payloadJson a JSON object, without secrets
     */
    public Mono<Void> append(StorageEngine engine, String eventType, String entityType, String entityId,
        String payloadJson) {
        return row(eventType, entityType, entityId, payloadJson)
            .flatMap(row -> engine.insert(row.table(), row.record()));
    }

    /** The row {@link #append} inserts, for a statement that writes it with the change it reports (16p, T7). */
    public Mono<StorageEngine.Insert> row(String eventType, String entityType, String entityId, String payloadJson) {
        if (eventType == null || !EventSubscription.NAME.matcher(eventType).matches()) {
            return Mono.error(new IllegalArgumentException("Event type '" + eventType + "' must match "
                + EventSubscription.NAME.pattern()));
        }
        return Operations.current().map(operation -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("event_id", UUID.fromString(String.valueOf(ids.next(null))));
            row.put("event_type", eventType);
            row.put("entity_type", entityType);
            row.put("entity_id", entityId);
            row.put("payload", new JsonText(payloadJson));
            row.put("process_seq_id", operation.map(Operation::processSeqId).orElse(null));
            row.put("created_time", operation.map(Operation::opTime)
                .orElseGet(() -> clock.instant().truncatedTo(ChronoUnit.MICROS)));
            return new StorageEngine.Insert(TABLE, row);
        });
    }

    /**
     * Records a committed change of an entity that {@linkplain EntityDefinition#publishesChanges publishes its
     * changes}; does nothing for other entities. The payload names the changed fields but never carries values,
     * which may be personal or secret: consumers read the entity if they need them.
     *
     * @param action        what the write did: INSERT, UPDATE, DELETE, and for temporal entities also CANCEL and
     *                      REVERT
     * @param version       version (non-temporal) or version number (temporal) after the write
     * @param effectiveTime when a temporal change takes effect; null otherwise
     */
    public Mono<Void> entityChanged(StorageEngine engine, EntityDefinition def, Object id, String action,
        long version, Instant effectiveTime, Collection<String> changedFields) {
        return entityChangedRow(def, id, action, version, effectiveTime, changedFields)
            .flatMap(row -> engine.insert(row.table(), row.record()));
    }

    /** The row {@link #entityChanged} inserts; empty for an entity that does not publish its changes. */
    public Mono<StorageEngine.Insert> entityChangedRow(EntityDefinition def, Object id, String action,
        long version, Instant effectiveTime, Collection<String> changedFields) {
        if (!def.publishesChanges) {
            return Mono.empty();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entityType", def.name);
        payload.put("entityId", String.valueOf(id));
        payload.put("action", action);
        payload.put("version", version);
        if (effectiveTime != null) {
            payload.put("effectiveTime", effectiveTime.toString());
        }
        payload.put("changedFields", changedFields.stream().sorted().toList());
        return row(EntityChangeEvents.eventType(def.name), def.name, String.valueOf(id),
            json.writeValueAsString(payload));
    }
}
