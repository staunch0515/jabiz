package com.jabiz.runtime.temporal;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.entity.FieldValueCoercer;
import com.jabiz.entity.TemporalSpec;
import com.jabiz.entity.UniqueConstraint;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.query.BoundValue;
import com.jabiz.query.SqlIdentifiers;
import com.jabiz.runtime.operation.Operation;
import com.jabiz.runtime.operation.OperationItem;
import com.jabiz.runtime.operation.OperationRecorder;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.temporal.EntityVersion;
import com.jabiz.temporal.VersionAction;
import com.jabiz.temporal.VersionPlanner.PlannedVersion;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Reads and appends the versions of temporal entities (docs/design/04-temporal-append-only.md). Rows are only
 * ever inserted. Every call takes the storage engine of the surrounding transaction.
 */
@Component
public class TemporalStore {

    private static final String CHANGED_FIELDS = "jabiz_changed_fields";
    private static final String ACTION = "jabiz_action";
    private static final String BASE_VERSION_NO = "jabiz_base_version_no";

    private final OperationRecorder operations;

    public TemporalStore(OperationRecorder operations) {
        this.operations = Objects.requireNonNull(operations, "operations must not be null");
    }

    /** All versions of an instance with their operation items, by version number. */
    public Mono<List<EntityVersion>> load(StorageEngine engine, String table, EntityDefinition def, UUID id) {
        return select(engine, table, def, id, "").map(row -> toVersion(def, row)).collectList();
    }

    /**
     * History of an instance: every version by number with the item and the operation that wrote it
     * (docs/design/04-temporal-append-only.md section 5.2).
     */
    public Mono<List<Map<String, Object>>> history(StorageEngine engine, String table, EntityDefinition def, UUID id) {
        return select(engine, table, def, id, ", p.actor_id AS jabiz_actor_id, p.process_name AS jabiz_process_name, "
                + "p.op_time AS jabiz_op_time, p.reason AS jabiz_reason")
            .map(row -> {
                EntityVersion version = toVersion(def, row);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("versionNo", version.versionNo());
                entry.put("effectStartTime", version.effectiveFrom());
                entry.put("createdTime", version.recordedAt());
                entry.put("deleted", version.deleted());
                entry.put("action", version.action());
                entry.put("baseVersionNo", version.baseVersionNo());
                entry.put("changedFields", version.changedFields().stream().sorted().toList());
                entry.put("processSeqId", version.processSeqId());
                entry.put("actorId", Rows.string(row.get("jabiz_actor_id")));
                entry.put("processName", Rows.string(row.get("jabiz_process_name")));
                entry.put("opTime", row.get("jabiz_op_time") == null ? null : Rows.instant(row.get("jabiz_op_time")));
                entry.put("reason", Rows.string(row.get("jabiz_reason")));
                entry.put("attributes", version.state());
                return entry;
            })
            .collectList();
    }

    private Flux<Map<String, Object>> select(StorageEngine engine, String table, EntityDefinition def, UUID id,
        String extraColumns) {
        String idColumn = SqlIdentifiers.require(def.primaryKeyColumn());
        String versionColumn = SqlIdentifiers.require(def.systemColumn(TemporalSpec.VERSION_NO));
        String sql = "SELECT t.*, i.changed_fields AS " + CHANGED_FIELDS + ", i.action AS " + ACTION
            + ", i.base_version_no AS " + BASE_VERSION_NO + extraColumns
            + " FROM " + SqlIdentifiers.require(table) + " t"
            + " LEFT JOIN op_process_item i ON i.process_seq_id = t." + SqlIdentifiers.require(
                def.systemColumn(TemporalSpec.PROCESS_SEQ_ID))
            + " AND i.entity_type = :entityType AND i.entity_id = t." + idColumn
            + " AND i.version_no = t." + versionColumn
            + (extraColumns.isEmpty() ? "" : " LEFT JOIN op_process p ON p.process_seq_id = i.process_seq_id")
            + " WHERE t." + idColumn + " = :id ORDER BY t." + versionColumn;
        return engine.select(sql, Map.of("entityType", BoundValue.of(def.name), "id", BoundValue.of(id)));
    }

    /**
     * Appends a version and its operation item. Registers the instance first when the version is its insertion.
     * A version number taken meanwhile by a concurrent write fails with the unique key violation of
     * {@code (entity_id, version_no)}.
     */
    public Mono<Void> append(StorageEngine engine, String table, EntityDefinition def, UUID id,
        PlannedVersion version, Operation operation) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String field : def.stateFields()) {
            // Explicit NULLs: a version copies its state exactly, column defaults must not fill in.
            Object value = version.state().get(field);
            row.put(def.physicalColumn(field), value != null ? value
                : BoundValue.nullOf(FieldValueCoercer.javaType(def.field(field).kind())));
        }
        row.put(def.primaryKeyColumn(), id);
        row.put(def.systemColumn(TemporalSpec.VERSION_NO), Math.toIntExact(version.versionNo()));
        row.put(def.systemColumn(TemporalSpec.EFFECT_START_TIME), version.effectiveFrom());
        row.put(def.systemColumn(TemporalSpec.CREATED_TIME), operation.opTime());
        row.put(def.systemColumn(TemporalSpec.PROCESS_SEQ_ID), operation.processSeqId());
        row.put(def.systemColumn(TemporalSpec.DELETED), version.deleted());
        Mono<Void> register = version.action() == VersionAction.INSERT
            ? operations.registerEntity(engine, operation, def.name, id)
            : Mono.empty();
        OperationItem item = new OperationItem(operation.processSeqId(), def.name, id, version.versionNo(),
            version.baseVersionNo(), version.action(), version.effectiveFrom(), List.copyOf(version.changedFields()));
        return register
            .then(Mono.defer(() -> engine.insert(SqlIdentifiers.require(table), row)))
            .then(Mono.defer(() -> operations.recordItem(engine, item)));
    }

    /**
     * Uniqueness of a temporal entity (decision D6): for every declared constraint and every distinct combination of
     * values among {@code states}, takes a transaction-scoped advisory lock on the combination and then, in a new
     * statement that sees what earlier holders committed, looks for another instance whose version in effect at
     * {@code now} or whose scheduled version uses the same values. Tombstones and combinations containing null do
     * not count.
     *
     * @return one {@code UNIQUE_VIOLATION} per constraint that is violated
     */
    public Mono<List<Violation>> checkUnique(StorageEngine engine, String table, EntityDefinition def, UUID self,
        List<Map<String, Object>> states, Instant now) {
        // Sorted by lock key so that concurrent writers take their locks in the same order.
        TreeMap<Long, Candidate> candidates = new TreeMap<>();
        for (UniqueConstraint unique : def.uniqueConstraints) {
            for (Map<String, Object> state : states) {
                List<Object> values = new ArrayList<>();
                for (String field : unique.fields()) {
                    values.add(state.get(field));
                }
                if (values.contains(null)) {
                    continue;
                }
                candidates.putIfAbsent(lockKey(def, unique, values), new Candidate(unique, values));
            }
        }
        Set<String> reported = new HashSet<>();
        return Flux.fromIterable(candidates.entrySet())
            .concatMap(entry -> engine.select("SELECT pg_advisory_xact_lock(:key)",
                    Map.of("key", BoundValue.of(entry.getKey())))
                .then(Mono.defer(() -> taken(engine, table, def, self, entry.getValue(), now)))
                .filter(taken -> taken && reported.add(entry.getValue().unique().name()))
                .map(taken -> violation(def, entry.getValue().unique())))
            .collectList();
    }

    private record Candidate(UniqueConstraint unique, List<Object> values) {}

    private Mono<Boolean> taken(StorageEngine engine, String table, EntityDefinition def, UUID self,
        Candidate candidate, Instant now) {
        String id = SqlIdentifiers.require(def.primaryKeyColumn());
        String effective = SqlIdentifiers.require(def.systemColumn(TemporalSpec.EFFECT_START_TIME));
        String version = SqlIdentifiers.require(def.systemColumn(TemporalSpec.VERSION_NO));
        String deleted = SqlIdentifiers.require(def.systemColumn(TemporalSpec.DELETED));
        String source = SqlIdentifiers.require(table);

        Map<String, BoundValue> params = new LinkedHashMap<>();
        params.put("now", BoundValue.of(now));
        params.put("self", BoundValue.of(self));
        List<String> matches = new ArrayList<>();
        for (int i = 0; i < candidate.unique().fields().size(); i++) {
            String column = SqlIdentifiers.require(def.physicalColumn(candidate.unique().fields().get(i)));
            matches.add(column + " = :u" + i);
            params.put("u" + i, BoundValue.of(candidate.values().get(i)));
        }
        String condition = "NOT " + deleted + " AND " + id + " <> :self AND " + String.join(" AND ", matches);
        // Current versions, then the winning version of every later effective time (the scheduled ones).
        String sql = "SELECT 1 AS hit FROM (SELECT DISTINCT ON (" + id + ") * FROM " + source
            + " WHERE " + effective + " <= :now ORDER BY " + id + ", " + effective + " DESC, " + version + " DESC) c"
            + " WHERE " + condition
            + " UNION ALL SELECT 1 AS hit FROM (SELECT DISTINCT ON (" + id + ", " + effective + ") * FROM " + source
            + " WHERE " + effective + " > :now ORDER BY " + id + ", " + effective + ", " + version + " DESC) s"
            + " WHERE " + condition + " LIMIT 1";
        return engine.select(sql, params).hasElements();
    }

    private static Violation violation(EntityDefinition def, UniqueConstraint unique) {
        return new Violation(unique.fields().getFirst(), PlatformErrorCodes.UNIQUE_VIOLATION,
            "Values of " + unique.fields() + " are already used by another current or scheduled " + def.name,
            Map.of("constraint", unique.name(), "fields", String.join(", ", unique.fields())));
    }

    /** 64-bit hash of (entity type, constraint, values); collisions only make unrelated writers wait. */
    static long lockKey(EntityDefinition def, UniqueConstraint unique, List<Object> values) {
        StringBuilder text = new StringBuilder(def.name).append('\0').append(unique.name());
        for (Object value : values) {
            text.append('\0').append(value instanceof BigDecimal d ? d.stripTrailingZeros().toPlainString() : value);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** A stored row as a version: its state fields in canonical types, plus the item that wrote it. */
    private static EntityVersion toVersion(EntityDefinition def, Map<String, Object> row) {
        Map<String, Object> state = new LinkedHashMap<>();
        for (String name : def.stateFields()) {
            FieldDefinition field = def.field(name);
            Object value = readValue(field, row.get(field.physicalColumn()));
            state.put(name, name.equals(def.primaryKey) ? def.normalizeId(value) : value);
        }
        String action = Rows.string(row.get(ACTION));
        List<String> changed = action == null ? def.changeableFields() : Rows.strings(row.get(CHANGED_FIELDS));
        return new EntityVersion(
            Rows.longValue(row.get(def.systemColumn(TemporalSpec.VERSION_NO))),
            Rows.instant(row.get(def.systemColumn(TemporalSpec.EFFECT_START_TIME))),
            Rows.instant(row.get(def.systemColumn(TemporalSpec.CREATED_TIME))),
            Boolean.TRUE.equals(row.get(def.systemColumn(TemporalSpec.DELETED))),
            state,
            Set.copyOf(changed),
            // A row without an item cannot come from the platform; it is treated as a plain update.
            action == null ? VersionAction.UPDATE : VersionAction.valueOf(action),
            Rows.longValue(row.get(BASE_VERSION_NO)),
            Rows.longValue(row.get(def.systemColumn(TemporalSpec.PROCESS_SEQ_ID))));
    }

    private static Object readValue(FieldDefinition field, Object stored) {
        try {
            return FieldValueCoercer.coerce(field, stored, false);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Stored value of field " + field.name() + " is invalid: " + e.getMessage(), e);
        }
    }
}
