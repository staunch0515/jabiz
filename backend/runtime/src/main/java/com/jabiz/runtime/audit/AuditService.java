package com.jabiz.runtime.audit;

import com.jabiz.query.BoundValue;
import com.jabiz.runtime.operation.OperationItem;
import com.jabiz.runtime.operation.OperationRecorder;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.temporal.VersionAction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The audit view (docs/design/11-ledger-events-jobs.md section 3; ROADMAP phase 9): operations from
 * {@code op_process} filtered by actor, time, process and the entities they wrote ({@code op_process_item}), newest
 * first, each with the versions it wrote. Only names, ids and versions are shown, never field values. Every value
 * is bound; the SQL is fixed text.
 */
@Component
public class AuditService {

    private final StorageAdapterRegistry storages;
    private final String poolRef;

    public AuditService(StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.storages = storages;
        this.poolRef = poolRef;
    }

    /** One page of operations: {@code {items, total, offset, limit}}. */
    public Mono<Map<String, Object>> operations(AuditQuery query) {
        StorageEngine engine = storages.getEngine(poolRef);
        Map<String, BoundValue> params = new LinkedHashMap<>();
        String where = where(query, params);
        Map<String, BoundValue> pageParams = new LinkedHashMap<>(params);
        pageParams.put("offset", BoundValue.of((long) query.offset()));
        pageParams.put("limit", BoundValue.of((long) query.limit()));
        Mono<Long> total = engine.select("SELECT count(*) AS total FROM op_process p WHERE " + where, params)
            .next().map(row -> Rows.longValue(row.get("total")));
        Mono<List<Map<String, Object>>> page = engine.select("SELECT p.* FROM op_process p WHERE " + where
                + " ORDER BY p.op_time DESC, p.process_seq_id DESC OFFSET :offset LIMIT :limit", pageParams)
            .collectList()
            .flatMap(rows -> withItems(engine, rows));
        return Mono.zip(page, total).map(parts -> {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("items", parts.getT1());
            json.put("total", parts.getT2());
            json.put("offset", query.offset());
            json.put("limit", query.limit());
            return json;
        });
    }

    private static String where(AuditQuery query, Map<String, BoundValue> params) {
        List<String> conditions = new ArrayList<>();
        conditions.add("TRUE");
        if (query.actorId() != null) {
            conditions.add("p.actor_id = :actor");
            params.put("actor", BoundValue.of(query.actorId()));
        }
        if (query.from() != null) {
            conditions.add("p.op_time >= :from");
            params.put("from", BoundValue.of(query.from()));
        }
        if (query.to() != null) {
            conditions.add("p.op_time < :to");
            params.put("to", BoundValue.of(query.to()));
        }
        if (query.processName() != null) {
            conditions.add("p.process_name = :process");
            params.put("process", BoundValue.of(query.processName()));
        }
        if (query.entityType() != null || query.entityId() != null) {
            List<String> item = new ArrayList<>();
            item.add("i.process_seq_id = p.process_seq_id");
            if (query.entityType() != null) {
                item.add("i.entity_type = :entityType");
                params.put("entityType", BoundValue.of(query.entityType()));
            }
            if (query.entityId() != null) {
                item.add("i.entity_id = :entityId");
                params.put("entityId", BoundValue.of(query.entityId()));
            }
            conditions.add("EXISTS (SELECT 1 FROM op_process_item i WHERE " + String.join(" AND ", item) + ")");
        }
        return String.join(" AND ", conditions);
    }

    private static Mono<List<Map<String, Object>>> withItems(StorageEngine engine, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return Mono.just(List.of());
        }
        Long[] seqs = rows.stream().map(row -> Rows.longValue(row.get("process_seq_id"))).toArray(Long[]::new);
        return engine.select("""
                SELECT * FROM op_process_item WHERE process_seq_id = ANY(:seqs)
                ORDER BY process_seq_id, entity_type, entity_id, version_no""",
                Map.of("seqs", BoundValue.of(seqs)))
            .map(row -> new OperationItem(
                Rows.longValue(row.get("process_seq_id")),
                Rows.string(row.get("entity_type")),
                Rows.uuid(row.get("entity_id")),
                Rows.longValue(row.get("version_no")),
                Rows.longValue(row.get("base_version_no")),
                VersionAction.valueOf(Rows.string(row.get("action"))),
                Rows.instant(row.get("effect_start_time")),
                Rows.strings(row.get("changed_fields"))))
            .collectMultimap(OperationItem::processSeqId)
            .map(items -> rows.stream().map(row -> {
                long seq = Rows.longValue(row.get("process_seq_id"));
                Map<String, Object> json = new LinkedHashMap<>();
                json.put("processSeqId", seq);
                json.put("parentSeqId", Rows.longValue(row.get("parent_seq_id")));
                json.put("revertsSeqId", Rows.longValue(row.get("reverts_seq_id")));
                json.put("processName", Rows.string(row.get("process_name")));
                json.put("processVersion", Rows.intValue(row.get("process_version")));
                json.put("actorId", Rows.string(row.get("actor_id")));
                json.put("tenantId", Rows.string(row.get("tenant_id")));
                json.put("requestId", Rows.string(row.get("request_id")));
                json.put("reason", Rows.string(row.get("reason")));
                json.put("opTime", Rows.instant(row.get("op_time")));
                json.put("items", OperationRecorder.describe(
                    new ArrayList<>(items.getOrDefault(seq, List.of()))));
                return json;
            }).toList());
    }
}
