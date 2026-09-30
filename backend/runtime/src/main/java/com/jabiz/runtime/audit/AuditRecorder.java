package com.jabiz.runtime.audit;

import com.jabiz.audit.AuditDiff;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.operation.Operations;
import com.jabiz.runtime.storage.StorageEngine;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes the audit trail (docs/design/21-audit-retention.md section 1, decision D27): a row of
 * {@code sys_audit_record} for every version or row an entity write produces, in the transaction of the write, with
 * the changed fields before and after ({@link AuditDiff}, secrets masked). Who and why come from the running
 * operation; a write without one (a plain entity changed through its dataset) is recorded with the caller and the
 * clock's time.
 */
@Component
public class AuditRecorder {

    static final String TABLE = "sys_audit_record";

    private final Clock clock;
    private static final Object REASON_KEY = AuditRecorder.class.getName() + ".reason";

    private final JsonMapper json;

    public AuditRecorder(Clock clock, JsonMapper json) {
        this.clock = clock;
        this.json = json;
    }

    /**
     * Records one write; nothing when no field changed.
     *
     * @param action    what the write was ({@code INSERT}, {@code UPDATE}, {@code DELETE}, {@code REBASE} ...)
     * @param version   the version the write produced (temporal entities, versioned rows); null when none
     * @param effective when the version takes effect (temporal entities); null otherwise
     * @param before    the state before; null for an insert
     * @param after     the state after; null for a delete
     */
    /**
     * The reason of writes that record no operation (batches of plain entities), for the audit records they leave.
     */
    public static reactor.util.context.Context withReason(reactor.util.context.Context context, String reason) {
        return reason == null ? context : context.put(REASON_KEY, reason);
    }

    public Mono<Void> record(StorageEngine engine, EntityDefinition def, Object id, String action, Long version,
        Instant effective, Map<String, ?> before, Map<String, ?> after) {
        Map<String, AuditDiff.Change> changes = AuditDiff.of(def, before, after);
        if (changes.isEmpty() && before != null && after != null) {
            return Mono.empty();
        }
        return Mono.deferContextual(view -> Operations.current().flatMap(operation -> (operation.isPresent()
                ? Mono.just(operation.get().actorId()) : RequestContexts.current().map(r -> r.actorId()))
            .flatMap(actor -> {
            Map<String, Object> values = new LinkedHashMap<>();
            changes.forEach((field, change) -> values.put(field, new Object[] {change.before(), change.after()}));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("process_seq_id", operation.map(o -> (Object) o.processSeqId()).orElse(StorageEngine.NULL));
            row.put("entity_type", def.name);
            row.put("entity_id", String.valueOf(id));
            row.put("action", action);
            row.put("version_no", version);
            row.put("effect_start_time", effective);
            row.put("changes", json.writeValueAsString(values));
            row.put("changed_fields", changes.keySet().toArray(String[]::new));
            row.put("actor_id", actor);
            row.put("recorded_time", operation.map(o -> o.opTime()).orElseGet(clock::instant));
            row.put("reason", operation.map(o -> o.reason())
                .orElseGet(() -> view.<String>getOrEmpty(REASON_KEY).orElse(null)));
            return engine.insert(TABLE, row);
        })));
    }
}
