package com.jabiz.runtime.audit;

import com.jabiz.query.BoundValue;
import com.jabiz.approval.ApprovalSubject;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ApprovalSubjectRegistry;
import com.jabiz.runtime.operation.OperationItem;
import com.jabiz.runtime.operation.OperationRecorder;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.temporal.VersionAction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
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

    /**
     * Approval requests about entry {@code :entityId} of a subject in {@code :subjects} (those declared for its entity
     * type), and the decisions on them (18 section 3).
     */
    private static final String APPROVALS = """
        (r.entity_type = '%s' AND r.entity_id IN (
            SELECT q.request_id::text FROM sys_approval_request_version q
            WHERE q.entity_id = :entityId AND q.subject = ANY(:subjects))
        OR r.entity_type = '%s' AND r.entity_id IN (
            SELECT d.decision_id FROM sys_approval_decision d
            JOIN sys_approval_request_version q ON q.request_id = d.request_id
            WHERE q.entity_id = :entityId AND q.subject = ANY(:subjects)))"""
        .formatted(ApprovalEntities.REQUEST, ApprovalEntities.DECISION);

    private final StorageAdapterRegistry storages;
    private final String poolRef;
    private final JsonMapper json;
    private final ApprovalSubjectRegistry approvalSubjects;

    public AuditService(StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef, JsonMapper json,
        ApprovalSubjectRegistry approvalSubjects) {
        this.storages = storages;
        this.poolRef = poolRef;
        this.json = json;
        this.approvalSubjects = approvalSubjects;
    }

    /** One field's change as the API shows it. */
    public record AuditFieldChange(Object before, Object after) {}

    /**
     * One row of the audit trail (docs/design/21-audit-retention.md section 1).
     *
     * @param changes field to its value before and after (secrets as {@code ***})
     */
    public record AuditRecordEntry(long recordNo, Long processSeqId, String processName, String entityType,
        String entityId, String action, Long versionNo, Instant effectStartTime, Map<String, AuditFieldChange> changes,
        String actorId, Instant recordedTime, String reason) {}

    public record AuditRecordPage(List<AuditRecordEntry> items, long total, int offset, int limit) {}

    /**
     * Filters of the audit records, combined with AND; all optional.
     *
     * @param field         records that changed this field
     * @param withApprovals with {@code entityType} and {@code entityId}: also the approval requests about that entry
     *                      (of the subjects declared for its type) and the decisions on them
     */
    public record RecordQuery(String entityType, String entityId, String actorId, Instant from, Instant to,
        String processName, String field, boolean withApprovals, int offset, int limit) {}

    /** One page of audit records, newest first. */
    public Mono<AuditRecordPage> records(RecordQuery query) {
        StorageEngine engine = storages.getEngine(poolRef);
        List<String> conditions = new ArrayList<>();
        Map<String, BoundValue> params = new LinkedHashMap<>();
        conditions.add("TRUE");
        List<String> entity = new ArrayList<>();
        if (query.entityType() != null) {
            entity.add("r.entity_type = :entityType");
            params.put("entityType", BoundValue.of(query.entityType()));
        }
        if (query.entityId() != null) {
            entity.add("r.entity_id = :entityId");
            params.put("entityId", BoundValue.of(query.entityId()));
        }
        if (!entity.isEmpty()) {
            String own = "(" + String.join(" AND ", entity) + ")";
            String[] subjects = query.withApprovals() && query.entityType() != null && query.entityId() != null
                ? approvalSubjects.all().stream().filter(s -> query.entityType().equals(s.entity()))
                    .map(ApprovalSubject::name).toArray(String[]::new)
                : new String[0];
            if (subjects.length > 0) {
                params.put("subjects", BoundValue.of(subjects));
                conditions.add("(" + own + " OR " + APPROVALS + ")");
            } else {
                conditions.add(own);
            }
        }
        if (query.actorId() != null) {
            conditions.add("r.actor_id = :actor");
            params.put("actor", BoundValue.of(query.actorId()));
        }
        if (query.from() != null) {
            conditions.add("r.recorded_time >= :from");
            params.put("from", BoundValue.of(query.from()));
        }
        if (query.to() != null) {
            conditions.add("r.recorded_time < :to");
            params.put("to", BoundValue.of(query.to()));
        }
        if (query.processName() != null) {
            conditions.add("p.process_name = :process");
            params.put("process", BoundValue.of(query.processName()));
        }
        if (query.field() != null) {
            conditions.add(":field = ANY(r.changed_fields)");
            params.put("field", BoundValue.of(query.field()));
        }
        String from = " FROM sys_audit_record r LEFT JOIN op_process p ON p.process_seq_id = r.process_seq_id WHERE "
            + String.join(" AND ", conditions);
        Map<String, BoundValue> pageParams = new LinkedHashMap<>(params);
        pageParams.put("offset", BoundValue.of((long) query.offset()));
        pageParams.put("limit", BoundValue.of((long) query.limit()));
        Mono<Long> total = engine.select("SELECT count(*) AS total" + from, params)
            .next().map(row -> Rows.longValue(row.get("total")));
        Mono<List<AuditRecordEntry>> page = engine.select("SELECT r.*, p.process_name" + from
                + " ORDER BY r.recorded_time DESC, r.record_no DESC OFFSET :offset LIMIT :limit", pageParams)
            .map(this::entry)
            .collectList();
        return Mono.zip(page, total).map(parts -> new AuditRecordPage(parts.getT1(), parts.getT2(), query.offset(),
            query.limit()));
    }

    /**
     * Every audit record of the given entity types recorded in {@code [from, to)}, oldest first: the changes an access
     * review covers (docs/design/10-security.md section 13.3).
     */
    public Mono<List<AuditRecordEntry>> recordsOf(java.util.Collection<String> entityTypes, Instant from, Instant to) {
        return storages.getEngine(poolRef).select("SELECT r.*, p.process_name FROM sys_audit_record r "
                + "LEFT JOIN op_process p ON p.process_seq_id = r.process_seq_id WHERE r.entity_type = ANY(:types) "
                + "AND r.recorded_time >= :from AND r.recorded_time < :to ORDER BY r.record_no",
                Map.of("types", BoundValue.of(entityTypes.toArray(String[]::new)), "from", BoundValue.of(from),
                    "to", BoundValue.of(to)))
            .map(this::entry)
            .collectList();
    }

    /** One audit record by its number; empty when there is none. */
    public Mono<AuditRecordEntry> record(long recordNo) {
        return storages.getEngine(poolRef).select("SELECT r.*, p.process_name FROM sys_audit_record r "
                + "LEFT JOIN op_process p ON p.process_seq_id = r.process_seq_id WHERE r.record_no = :no",
                Map.of("no", BoundValue.of(recordNo)))
            .next()
            .map(this::entry);
    }

    private AuditRecordEntry entry(Map<String, Object> row) {
        Map<String, List<Object>> raw = json.readValue(Rows.string(row.get("changes")),
            new TypeReference<Map<String, List<Object>>>() {});
        Map<String, AuditFieldChange> changes = new LinkedHashMap<>();
        raw.forEach((field, pair) -> changes.put(field, new AuditFieldChange(pair.get(0), pair.get(1))));
        return new AuditRecordEntry(Rows.longValue(row.get("record_no")), Rows.longValue(row.get("process_seq_id")),
            Rows.string(row.get("process_name")), Rows.string(row.get("entity_type")),
            Rows.string(row.get("entity_id")), Rows.string(row.get("action")), Rows.longValue(row.get("version_no")),
            Rows.instant(row.get("effect_start_time")), changes, Rows.string(row.get("actor_id")),
            Rows.instant(row.get("recorded_time")), Rows.string(row.get("reason")));
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
        Mono<List<Map<String, Object>>> page = engine.select("SELECT p.*, (SELECT count(*) FROM sys_audit_record a "
                + "WHERE a.process_seq_id = p.process_seq_id) AS audit_records FROM op_process p WHERE " + where
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
                json.put("auditRecords", Rows.longValue(row.get("audit_records")));
                json.put("items", OperationRecorder.describe(
                    new ArrayList<>(items.getOrDefault(seq, List.of()))));
                return json;
            }).toList());
    }
}
