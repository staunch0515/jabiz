package com.jabiz.runtime.operation;

import com.jabiz.context.RequestContext;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.storage.JsonText;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.temporal.VersionAction;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Writes and reads the operation tables ({@code op_process}, {@code op_process_item}, {@code op_process_result},
 * {@code entity_registry}; docs/design/04-temporal-append-only.md section 2.2). All of them are append-only
 * (decision D4): nothing here updates or deletes.
 *
 * <p>Every method takes the storage engine of the writes it belongs to, so that the records share their
 * transaction.
 */
@Component
public class OperationRecorder {

    private final Clock clock;

    public OperationRecorder(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Records the start of an operation. The operation time comes from the clock, truncated to the microsecond
     * precision of the database so that it compares equal to what is stored.
     */
    public Mono<Operation> begin(StorageEngine engine, OperationRequest request, RequestContext context) {
        Mono<Long> seq = request.processSeqId() != null
            ? Mono.just(request.processSeqId())
            : engine.select("SELECT nextval('op_process_seq') AS seq", Map.of())
                .next().map(row -> Rows.longValue(row.get("seq")));
        return seq.flatMap(processSeqId -> {
            Instant opTime = (request.opTime() != null ? request.opTime() : clock.instant())
                .truncatedTo(ChronoUnit.MICROS);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("process_seq_id", processSeqId);
            row.put("parent_seq_id", request.parentSeqId());
            row.put("reverts_seq_id", request.revertsSeqId());
            row.put("process_name", request.processName());
            row.put("process_version", request.processVersion());
            row.put("actor_id", context.actorId());
            row.put("tenant_id", context.tenantId());
            row.put("request_id", context.requestId());
            row.put("idempotency_key", request.idempotencyKey());
            row.put("reason", request.reason());
            row.put("op_time", opTime);
            Operation operation = new Operation(processSeqId, opTime, request.processName(),
                request.processVersion(), context.actorId(), request.reason(), request.parentSeqId(),
                request.revertsSeqId());
            return engine.insert("op_process", row).thenReturn(operation);
        });
    }

    /**
     * Joins the operation numbered {@code request.processSeqId()} when it is recorded already (a process whose
     * earlier step started it), otherwise records it as {@link #begin} does.
     */
    public Mono<Operation> beginOrJoin(StorageEngine engine, OperationRequest request, RequestContext context) {
        if (request.processSeqId() == null) {
            return begin(engine, request, context);
        }
        return find(engine, request.processSeqId())
            .map(record -> new Operation(record.processSeqId(), record.opTime(), record.processName(),
                record.processVersion(), record.actorId(), record.reason(), record.parentSeqId(), record.revertsSeqId()))
            .switchIfEmpty(Mono.defer(() -> begin(engine, request, context)));
    }

    /** Registers a new instance of a temporal entity ({@code entity_registry}). */
    public Mono<Void> registerEntity(StorageEngine engine, Operation operation, String entityType, UUID entityId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("entity_id", entityId);
        row.put("entity_type", entityType);
        row.put("created_seq_id", operation.processSeqId());
        return engine.insert("entity_registry", row);
    }

    public Mono<Void> recordItem(StorageEngine engine, OperationItem item) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("process_seq_id", item.processSeqId());
        row.put("entity_type", item.entityType());
        row.put("entity_id", item.entityId());
        row.put("version_no", Math.toIntExact(item.versionNo()));
        row.put("base_version_no", item.baseVersionNo() == null ? null : Math.toIntExact(item.baseVersionNo()));
        row.put("action", item.action().name());
        row.put("effect_start_time", item.effectStartTime());
        row.put("changed_fields", item.changedFields().stream().sorted().toArray(String[]::new));
        return engine.insert("op_process_item", row);
    }

    /** Stores the output of an operation when it ends, for idempotent replay (decision D4). */
    public Mono<Void> recordResult(StorageEngine engine, long processSeqId, String outputJson) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("process_seq_id", processSeqId);
        row.put("output", new JsonText(outputJson));
        return engine.insert("op_process_result", row);
    }

    /** An earlier operation of an actor with an idempotency key, and its output. */
    public record IdempotentResult(long processSeqId, String processName, String output) {}

    /** The actor's earlier operation with the given idempotency key and its output, if there is one. */
    public Mono<IdempotentResult> findByIdempotencyKey(StorageEngine engine, String actorId, String idempotencyKey) {
        return engine.select("""
                SELECT p.process_seq_id, p.process_name, r.output
                FROM op_process p JOIN op_process_result r ON r.process_seq_id = p.process_seq_id
                WHERE p.actor_id = :actor AND p.idempotency_key = :key""",
                Map.of("actor", BoundValue.of(actorId), "key", BoundValue.of(idempotencyKey)))
            .next()
            .map(row -> new IdempotentResult(Rows.longValue(row.get("process_seq_id")),
                Rows.string(row.get("process_name")), Rows.string(row.get("output"))));
    }

    /**
     * Records one attempt of a step that runs after its operation committed ({@code op_process_after_commit},
     * append-only like the other operation tables). Runs outside the operation's transaction, which is over.
     */
    public Mono<Void> recordAfterCommitAttempt(StorageEngine engine, long processSeqId, String stepName, int attempt,
        String error) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("process_seq_id", processSeqId);
        row.put("step_name", stepName);
        row.put("attempt", attempt);
        row.put("succeeded", error == null);
        row.put("error", error);
        row.put("attempted_at", clock.instant().truncatedTo(ChronoUnit.MICROS));
        return engine.insert("op_process_after_commit", row);
    }

    /** Output of the actor's earlier operation with the same idempotency key, if there is one. */
    public Mono<String> findResult(StorageEngine engine, String actorId, String idempotencyKey) {
        return engine.select("""
                SELECT r.output FROM op_process p JOIN op_process_result r ON r.process_seq_id = p.process_seq_id
                WHERE p.actor_id = :actor AND p.idempotency_key = :key""",
                Map.of("actor", BoundValue.of(actorId), "key", BoundValue.of(idempotencyKey)))
            .next()
            .map(row -> Rows.string(row.get("output")));
    }

    public Mono<OperationRecord> find(StorageEngine engine, long processSeqId) {
        return engine.select("SELECT * FROM op_process WHERE process_seq_id = :seq",
                Map.of("seq", BoundValue.of(processSeqId)))
            .next()
            .map(OperationRecorder::toRecord);
    }

    /** Direct sub-operations, in order of execution. */
    public Flux<OperationRecord> children(StorageEngine engine, long processSeqId) {
        return engine.select("SELECT * FROM op_process WHERE parent_seq_id = :seq ORDER BY process_seq_id",
                Map.of("seq", BoundValue.of(processSeqId)))
            .map(OperationRecorder::toRecord);
    }

    /** The operation and all its sub-operations, recursively, in order of execution. */
    public Flux<OperationRecord> tree(StorageEngine engine, long processSeqId) {
        return engine.select("""
                WITH RECURSIVE tree AS (
                    SELECT * FROM op_process WHERE process_seq_id = :seq
                    UNION ALL
                    SELECT p.* FROM op_process p JOIN tree t ON p.parent_seq_id = t.process_seq_id
                )
                SELECT * FROM tree ORDER BY process_seq_id""",
                Map.of("seq", BoundValue.of(processSeqId)))
            .map(OperationRecorder::toRecord);
    }

    /** Versions the operation wrote, in the order they were written. */
    public Flux<OperationItem> items(StorageEngine engine, long processSeqId) {
        return engine.select("""
                SELECT * FROM op_process_item WHERE process_seq_id = :seq
                ORDER BY entity_type, entity_id, version_no""",
                Map.of("seq", BoundValue.of(processSeqId)))
            .map(OperationRecorder::toItem);
    }

    /** A version written by a later operation, with the operation that operation reverts, if any. */
    public record LaterItem(OperationItem item, Long revertsSeqId) {}

    /** Versions of one entity written by operations after {@code processSeqId}, newest operation first. */
    public Flux<LaterItem> laterItems(StorageEngine engine, String entityType, UUID entityId, long processSeqId) {
        return engine.select("""
                SELECT i.*, p.reverts_seq_id AS jabiz_reverts_seq_id
                FROM op_process_item i JOIN op_process p ON p.process_seq_id = i.process_seq_id
                WHERE i.entity_type = :type AND i.entity_id = :id AND i.process_seq_id > :seq
                ORDER BY i.process_seq_id DESC, i.version_no""",
                Map.of("type", BoundValue.of(entityType), "id", BoundValue.of(entityId),
                    "seq", BoundValue.of(processSeqId)))
            .map(row -> new LaterItem(toItem(row), Rows.longValue(row.get("jabiz_reverts_seq_id"))));
    }

    private static OperationRecord toRecord(Map<String, Object> row) {
        return new OperationRecord(
            Rows.longValue(row.get("process_seq_id")),
            Rows.longValue(row.get("parent_seq_id")),
            Rows.longValue(row.get("reverts_seq_id")),
            Rows.string(row.get("process_name")),
            Rows.intValue(row.get("process_version")),
            Rows.string(row.get("actor_id")),
            Rows.string(row.get("tenant_id")),
            Rows.string(row.get("request_id")),
            Rows.string(row.get("idempotency_key")),
            Rows.string(row.get("reason")),
            Rows.instant(row.get("op_time")));
    }

    private static OperationItem toItem(Map<String, Object> row) {
        return new OperationItem(
            Rows.longValue(row.get("process_seq_id")),
            Rows.string(row.get("entity_type")),
            Rows.uuid(row.get("entity_id")),
            Rows.longValue(row.get("version_no")),
            Rows.longValue(row.get("base_version_no")),
            VersionAction.valueOf(Rows.string(row.get("action"))),
            Rows.instant(row.get("effect_start_time")),
            Rows.strings(row.get("changed_fields")));
    }

    /** Items as their fields, for responses. */
    public static List<Map<String, Object>> describe(List<OperationItem> items) {
        return items.stream().map(item -> {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("entityType", item.entityType());
            json.put("entityId", item.entityId());
            json.put("versionNo", item.versionNo());
            json.put("baseVersionNo", item.baseVersionNo());
            json.put("action", item.action());
            json.put("effectStartTime", item.effectStartTime());
            json.put("changedFields", item.changedFields());
            return json;
        }).toList();
    }
}
