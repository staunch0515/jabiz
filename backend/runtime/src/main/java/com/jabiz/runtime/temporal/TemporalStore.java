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

    /** A version with the operation that wrote it. */
    public record HistoryEntry(EntityVersion version, String actorId, String processName, Instant opTime,
        String reason) {

        /** The entry as returned by the history API. */
        public Map<String, Object> describe() {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("versionNo", version.versionNo());
            entry.put("effectStartTime", version.effectiveFrom());
            entry.put("createdTime", version.recordedAt());
            entry.put("deleted", version.deleted());
            entry.put("action", version.action());
            entry.put("baseVersionNo", version.baseVersionNo());
            entry.put("changedFields", version.changedFields().stream().sorted().toList());
            entry.put("processSeqId", version.processSeqId());
            entry.put("actorId", actorId);
            entry.put("processName", processName);
            entry.put("opTime", opTime);
            entry.put("reason", reason);
            entry.put("attributes", version.state());
            return entry;
        }
    }

    /**
     * History of an instance: every version by number with the item and the operation that wrote it
     * (docs/design/04-temporal-append-only.md section 5.2).
     */
    public Mono<List<HistoryEntry>> history(StorageEngine engine, String table, EntityDefinition def, UUID id) {
        return select(engine, table, def, id, ", p.actor_id AS jabiz_actor_id, p.process_name AS jabiz_process_name, "
                + "p.op_time AS jabiz_op_time, p.reason AS jabiz_reason")
            .map(row -> new HistoryEntry(toVersion(def, row),
                Rows.string(row.get("jabiz_actor_id")),
                Rows.string(row.get("jabiz_process_name")),
                row.get("jabiz_op_time") == null ? null : Rows.instant(row.get("jabiz_op_time")),
                Rows.string(row.get("jabiz_reason"))))
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
            row.put(def.physicalColumn(field), value != null ? value : StorageEngine.NULL);
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
        List<Candidate> raw = new ArrayList<>();
        for (UniqueConstraint unique : def.uniqueConstraints) {
            for (Map<String, Object> state : states) {
                List<Object> values = new ArrayList<>();
                for (String field : unique.fields()) {
                    values.add(state.get(field));
                }
                if (!values.contains(null)) {
                    raw.add(new Candidate(unique, values));
                }
            }
        }
        Set<String> reported = new HashSet<>();
        // Regardless of case (decision D36): the database folds the values, with the same lower() its index and the
        // lookup use, so that the lock and the comparison can never disagree with it (Java folds some letters
        // differently, "İ" for one).
        return Flux.fromIterable(raw).concatMap(candidate -> folded(engine, candidate))
            .collectList()
            .flatMapMany(folded -> {
                // Sorted by lock key so that concurrent writers take their locks in the same order.
                TreeMap<Long, Candidate> candidates = new TreeMap<>();
                folded.forEach(c -> candidates.putIfAbsent(lockKey(def, c.unique(), c.values()), c));
                return Flux.fromIterable(candidates.entrySet());
            })
            .concatMap(entry -> engine.select("SELECT pg_advisory_xact_lock(:key)",
                    Map.of("key", BoundValue.of(entry.getKey())))
                .then(Mono.defer(() -> taken(engine, table, def, self, entry.getValue(), now)))
                .filter(taken -> taken && reported.add(entry.getValue().unique().name()))
                .map(taken -> violation(def, entry.getValue().unique())))
            .collectList();
    }

    /** The candidate with its text values folded by the database's {@code lower()} when its constraint ignores case. */
    private static Mono<Candidate> folded(StorageEngine engine, Candidate candidate) {
        if (!candidate.unique().ignoreCase()) {
            return Mono.just(candidate);
        }
        return Flux.fromIterable(candidate.values())
            .concatMap(value -> value instanceof String text
                ? engine.select("SELECT lower(CAST(:v AS text)) AS folded", Map.of("v", BoundValue.of(text)))
                    .next().map(row -> (Object) String.valueOf(row.get("folded")))
                : Mono.just(value))
            .collectList()
            .map(values -> new Candidate(candidate.unique(), values));
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
        List<String> columns = new ArrayList<>();
        for (int i = 0; i < candidate.unique().fields().size(); i++) {
            columns.add(SqlIdentifiers.require(def.physicalColumn(candidate.unique().fields().get(i))));
            params.put("u" + i, BoundValue.of(candidate.values().get(i)));
        }
        // Only an instance that has used the values in some version can use them now or later: its candidates come
        // from the index on the values (decision D29), and only their versions are looked at.
        String sql = "WITH cand AS (SELECT DISTINCT " + id + " AS cid FROM " + source
            + " WHERE " + matches(columns, "", candidate.unique().ignoreCase()) + " AND " + id + " <> :self)"
            // The version in effect now …
            + " SELECT 1 AS hit FROM cand JOIN LATERAL (SELECT * FROM " + source + " v WHERE v." + id + " = cand.cid"
            + " AND v." + effective + " <= :now ORDER BY v." + effective + " DESC, v." + version + " DESC LIMIT 1) c"
            + " ON true WHERE NOT c." + deleted + " AND " + matches(columns, "c.", candidate.unique().ignoreCase())
            // … and the winning version of every later effective time (the scheduled ones).
            + " UNION ALL SELECT 1 AS hit FROM cand JOIN LATERAL (SELECT DISTINCT ON (v." + effective + ") * FROM "
            + source + " v WHERE v." + id + " = cand.cid AND v." + effective + " > :now ORDER BY v." + effective
            + ", v." + version + " DESC) s ON true WHERE NOT s." + deleted + " AND "
            + matches(columns, "s.", candidate.unique().ignoreCase())
            + " LIMIT 1";
        return engine.select(sql, params).hasElements();
    }

    /**
     * The columns equal to the bound values; regardless of case as {@code col IS NOT NULL AND lower(col) = lower(:u)},
     * the form of the partial index on {@code lower(col)}, both sides folded by the database.
     */
    private static String matches(List<String> columns, String alias, boolean ignoreCase) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            String column = alias + columns.get(i);
            parts.add(ignoreCase ? column + " IS NOT NULL AND lower(" + column + ") = lower(CAST(:u" + i + " AS text))"
                : column + " = :u" + i);
        }
        return String.join(" AND ", parts);
    }

    /**
     * Whether a version scheduled after {@code now} (the winner of its effective time, not a tombstone) refers to
     * {@code targetId} through {@code field}; {@code excludeId}, if given, is an instance that does not count. The
     * candidates come from the versions that refer to the target (through the index on the field), so the table is
     * not read as a whole.
     */
    public Mono<Boolean> referencedLater(StorageEngine engine, String table, EntityDefinition def, String field,
        Object targetId, Object excludeId, Instant now) {
        String id = SqlIdentifiers.require(def.primaryKeyColumn());
        String effective = SqlIdentifiers.require(def.systemColumn(TemporalSpec.EFFECT_START_TIME));
        String version = SqlIdentifiers.require(def.systemColumn(TemporalSpec.VERSION_NO));
        String column = SqlIdentifiers.require(def.physicalColumn(field));
        String source = SqlIdentifiers.require(table);
        Map<String, BoundValue> params = new LinkedHashMap<>();
        params.put("now", BoundValue.of(now));
        params.put("target", BoundValue.of(targetId));
        String exclude = "";
        if (excludeId != null) {
            exclude = " AND " + id + " <> :self";
            params.put("self", BoundValue.of(excludeId));
        }
        String sql = "WITH cand AS (SELECT DISTINCT " + id + " AS cid FROM " + source + " WHERE " + column
            + " = :target AND " + effective + " > :now" + exclude + ")"
            + " SELECT 1 AS hit FROM cand JOIN LATERAL (SELECT DISTINCT ON (v." + effective + ") * FROM " + source
            + " v WHERE v." + id + " = cand.cid AND v." + effective + " > :now ORDER BY v." + effective + ", v."
            + version + " DESC) s ON true WHERE NOT s." + SqlIdentifiers.require(def.systemColumn(TemporalSpec.DELETED))
            + " AND s." + column + " = :target LIMIT 1";
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
