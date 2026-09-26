package com.jabiz.runtime;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dataset.DatasetPolicy;
import com.jabiz.dictionary.DictionaryLookup;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.EntityValidator;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.entity.FieldValueCoercer;
import com.jabiz.entity.ReferenceDefinition;
import com.jabiz.entity.SemanticKind;
import com.jabiz.entity.CheckDefinition;
import com.jabiz.entity.GuardDefinition;
import com.jabiz.entity.UniqueConstraint;
import com.jabiz.entity.TemporalRole;
import com.jabiz.entity.ValidationContext;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.PhysicalQueryPlan;
import com.jabiz.query.QueryCompiler;
import com.jabiz.query.QueryPredicate;
import com.jabiz.query.TimeSlice;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.dictionary.DictionaryRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.event.Outbox;
import com.jabiz.runtime.operation.OperationRecorder;
import com.jabiz.runtime.operation.OperationRequest;
import com.jabiz.runtime.operation.Operations;
import com.jabiz.runtime.temporal.TemporalStore;
import com.jabiz.runtime.temporal.VersionAppender;
import com.jabiz.temporal.EntityVersion;
import com.jabiz.temporal.Timeline;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.runtime.storage.UniqueKeyViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reactive entity persistence driven by entity metadata and dataset policy.
 *
 * Writes go to the dataset's primary storage engine; reads go to the read replica when one
 * is configured. Reads of the dataset's target entity always honour the dataset scope
 * (docs/design/03-dataset.md section 2.2) and the soft-delete exclusion; writes are filled into and kept
 * within the scope.
 *
 * Reads and writes need a {@link com.jabiz.context.RequestContext} in the Reactor context: the scope may take
 * its values from it, and rules receive it through their {@link ValidationContext}. The business rules of one
 * change (scope, immutability, state transitions, transition guards) are all evaluated and reported together
 * as one {@link BusinessRuleViolationException}.
 */
@Component
public class DatasetEntityManager {

    private static final Logger log = LoggerFactory.getLogger(DatasetEntityManager.class);

    private static final long INITIAL_VERSION = 1L;

    private final StorageAdapterRegistry storageRegistry;
    private final EntityDefinitionRegistry entityRegistry;
    private final DatasetRegistry datasetRegistry;
    private final QueryCompiler queryCompiler;
    private final DictionaryRegistry dictionaries;
    private final Clock clock;
    private final OperationRecorder operations;
    private final TemporalWriter temporalWriter;
    private final TemporalStore temporalStore;
    private final JsonMapper json;
    private final Outbox outbox;

    public DatasetEntityManager(
        StorageAdapterRegistry storageRegistry,
        EntityDefinitionRegistry entityRegistry,
        DatasetRegistry datasetRegistry,
        QueryCompiler queryCompiler,
        DictionaryRegistry dictionaries,
        Clock clock,
        OperationRecorder operations,
        TemporalStore temporalStore,
        VersionAppender versions,
        JsonMapper json,
        Outbox outbox
    ) {
        this.storageRegistry = Objects.requireNonNull(storageRegistry, "StorageAdapterRegistry cannot be null");
        this.entityRegistry = Objects.requireNonNull(entityRegistry, "EntityDefinitionRegistry cannot be null");
        this.datasetRegistry = Objects.requireNonNull(datasetRegistry, "DatasetRegistry cannot be null");
        this.queryCompiler = Objects.requireNonNull(queryCompiler, "QueryCompiler cannot be null");
        this.dictionaries = Objects.requireNonNull(dictionaries, "DictionaryRegistry cannot be null");
        this.clock = Objects.requireNonNull(clock, "Clock cannot be null");
        this.operations = Objects.requireNonNull(operations, "OperationRecorder cannot be null");
        this.temporalStore = Objects.requireNonNull(temporalStore, "TemporalStore cannot be null");
        this.temporalWriter = new TemporalWriter(this, Objects.requireNonNull(versions, "VersionAppender cannot be null"),
            queryCompiler);
        this.json = Objects.requireNonNull(json, "JsonMapper cannot be null");
        this.outbox = Objects.requireNonNull(outbox, "Outbox cannot be null");
    }

    // ================= Writes =================

    /**
     * Applies a batch of changes, possibly across several entity types, in a single transaction.
     * Changes are applied in the given order; if any of them fails, none is persisted.
     *
     * @param dataset dataset providing storage routing, the read-only constraint and the soft-delete policy
     * @param changes the changes to apply; INSERT/UPDATE/DELETE each follow the rules of their entity definition
     * @return snapshots of the inserted and updated entities in change order (deleted entities are not included)
     */
    public Mono<List<EntityInstance>> commitBatch(DatasetDefinition dataset, Collection<EntityChange> changes) {
        return commitBatch(dataset, changes, null);
    }

    /**
     * Applies a batch of changes in a single transaction, as {@link #commitBatch(DatasetDefinition, Collection)}.
     *
     * <p>Changes of temporal entities are appended as versions of one operation
     * (docs/design/04-temporal-append-only.md): the operation of the pipeline if there is one, otherwise one this
     * call records, named by the {@link OperationRequest} in the Reactor context or else
     * {@value OperationRequest#DATASET_COMMIT}. An operation it records also stores the returned snapshots as its
     * output. Batches of other entities are not recorded as operations.
     *
     * @param reason why the changes are made; required when a change corrects the past
     */
    public Mono<List<EntityInstance>> commitBatch(DatasetDefinition dataset, Collection<EntityChange> changes,
        String reason) {
        if (changes == null || changes.isEmpty()) {
            return Mono.just(List.of());
        }
        return RequestContexts.current().flatMap(request -> {
            ensureDatasetWritable(dataset);
            int maxBatch = dataset.policy().maxWriteBatchSize();
            if (changes.size() > maxBatch) {
                throw new BusinessRuleViolationException(new Violation(null, PlatformErrorCodes.BATCH_TOO_LARGE,
                    String.format("Batch size [%d] exceeds dataset limit [%d]", changes.size(), maxBatch),
                    Map.of("size", changes.size(), "limit", maxBatch)));
            }

            StorageEngine engine = storageRegistry.getEngine(dataset.storage().connectionPoolRef());
            List<EntityChange> ordered = List.copyOf(changes);
            ValidationContext validation = new ValidationContext(clock, request);
            Map<String, Object> scope = dataset.scope().resolve(request);

            Mono<List<EntityInstance>> apply = Flux.fromIterable(ordered)
                .concatMap(change -> applyChange(engine, dataset, scope, change, validation))
                .collectList()
                .map(Collections::unmodifiableList);

            Mono<List<EntityInstance>> work = Operations.current().flatMap(existing -> {
                if (existing.isPresent()) {
                    // The operation is recorded already and never updated, so its reason cannot change here.
                    if (reason != null && !reason.equals(existing.get().reason())) {
                        return Mono.error(new IllegalArgumentException("A reason can only be given when an "
                            + "operation starts; operation " + existing.get().processSeqId() + " is running"));
                    }
                    return apply;
                }
                if (ordered.stream().noneMatch(this::isTemporal)) {
                    return apply;
                }
                return Operations.requested().flatMap(requested -> {
                    OperationRequest operation = requested
                        .orElseGet(() -> OperationRequest.named(OperationRequest.DATASET_COMMIT, 1)
                            .withInputSummary(json.writeValueAsString(inputSummary(dataset, ordered))));
                    if (reason != null) {
                        operation = operation.withReason(reason);
                    }
                    // Operations numbered by a process are joined by its later commits; only a commit that owns its
                    // operation records the output.
                    boolean owned = operation.processSeqId() == null;
                    return operations.beginOrJoin(engine, operation, request).flatMap(started -> {
                        Mono<List<EntityInstance>> run = apply.contextWrite(view -> Operations.with(view, started));
                        return owned
                            ? run.flatMap(result -> operations.recordResult(engine, started.processSeqId(),
                                json.writeValueAsString(outputSummary(result))).thenReturn(result))
                            : run;
                    });
                });
            });

            return engine.inTransaction(work)
                .doOnSuccess(committed -> log.debug("Committed {} change(s) through dataset {}",
                    ordered.size(), dataset.resourceId()));
        });
    }

    /**
     * What a commit records as its input ({@code op_process.input_summary}): the dataset and, per change, the action,
     * entity, id and the names of the fields it sets, never their values, which may be personal or secret.
     */
    private static Map<String, Object> inputSummary(DatasetDefinition dataset, List<EntityChange> changes) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("dataset", dataset.resourceId());
        summary.put("changes", changes.stream().map(change -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("action", change.action().name());
            entry.put("entityType", change.instance().entityType());
            entry.put("id", change.instance().id() == null ? null : String.valueOf(change.instance().id()));
            entry.put("fields", change.instance().attributes().keySet().stream().sorted().toList());
            if (change.effectiveTime() != null) {
                entry.put("effectiveTime", change.effectiveTime().toString());
            }
            return entry;
        }).toList());
        return summary;
    }

    /** What a commit records as its output: which versions it wrote, without their values. */
    private static List<Map<String, Object>> outputSummary(List<EntityInstance> result) {
        return result.stream().map(instance -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("entityType", instance.entityType());
            entry.put("id", String.valueOf(instance.id()));
            entry.put("version", instance.version());
            return entry;
        }).toList();
    }

    TemporalStore temporalStore() {
        return temporalStore;
    }

    private boolean isTemporal(EntityChange change) {
        String type = change.instance().entityType();
        return type != null && entityRegistry.find(type).map(def -> def.temporal).orElse(false);
    }

    private Mono<EntityInstance> applyChange(
        StorageEngine engine, DatasetDefinition dataset, Map<String, Object> scope, EntityChange change,
        ValidationContext validation
    ) {
        return Mono.defer(() -> {
            EntityInstance instance = change.instance();
            String entityType = instance.entityType();
            if (entityType == null || entityType.isBlank()) {
                throw new ValidationException(List.of(new Violation(
                    "entityType", "REQUIRED", "EntityInstance has a missing or blank entityType")));
            }
            EntityDefinition def = entityRegistry.getOrThrow(entityType);
            if (def.temporal) {
                return temporalWriter.apply(engine, dataset, scope, def, change, validation)
                    .onErrorMap(UniqueKeyViolationException.class, e -> uniqueViolation(def, e));
            }
            if (change.effectiveTime() != null || change.action() == EntityAction.CANCEL_SCHEDULED) {
                throw notTemporal(def);
            }

            Mono<EntityInstance> result = switch (change.action()) {
                case INSERT -> insert(engine, dataset, scope, def, instance, validation);
                case UPDATE -> update(engine, dataset, scope, def, instance, validation);
                case DELETE -> delete(engine, dataset, scope, def, instance).then(Mono.<EntityInstance>empty());
                case CANCEL_SCHEDULED -> throw notTemporal(def);
            };
            return result.onErrorMap(UniqueKeyViolationException.class, e -> uniqueViolation(def, e));
        });
    }

    private Mono<EntityInstance> insert(
        StorageEngine engine, DatasetDefinition dataset, Map<String, Object> scope, EntityDefinition def,
        EntityInstance instance, ValidationContext validation
    ) {
        Map<String, Object> raw = new LinkedHashMap<>(instance.attributes());
        raw.putIfAbsent(def.primaryKey, instance.id());
        return dictionaryLookup(def, raw).flatMap(lookup -> Mono.defer(() -> {
            requireWritable(def);

            Map<String, Object> attrs = new LinkedHashMap<>(
                EntityValidator.requireValid(def, raw, validation, true, lookup));
            normalizeReferences(def, attrs);

            verifyIdMatches(def, instance, attrs);
            List<Violation> violations = new ArrayList<>();
            rejectSoftDeleteFields(dataset, def, attrs, Map.of(), violations);
            enforceScope(dataset, def, scope, attrs, true, violations);
            String state = resolveInitialState(def, attrs, instance.state(), violations);
            if (state != null) {
                evaluateGuards(def, null, state, attrs, Map.of(), validation, violations);
            }
            CheckDefinition.evaluate(def, attrs, validation, violations);
            rejectIfAny(violations);

            Instant now = clock.instant();
            String deletionTimeField = dataset.isTarget(def.name) ? dataset.policy().softDeleteTimeField() : null;
            Map<String, Object> row = new LinkedHashMap<>();
            Map<String, Object> snapshot = new LinkedHashMap<>(attrs);
            for (FieldDefinition field : def.fields.values()) {
                if (field.kind() instanceof SemanticKind.Version) {
                    row.put(field.physicalColumn(), INITIAL_VERSION);
                } else if (isSystemRecorded(field) && !field.name().equals(deletionTimeField)) {
                    row.put(field.physicalColumn(), now);
                    snapshot.put(field.name(), now);
                } else if (attrs.containsKey(field.name())) {
                    row.put(field.physicalColumn(), attrs.get(field.name()));
                }
            }

            String table = queryCompiler.resolveTable(dataset, def);
            EntityInstance created = new EntityInstance(
                attrs.get(def.primaryKey), def.name, INITIAL_VERSION, state, snapshot);
            return verifyReferences(def, attrs, attrs.keySet())
                .then(Mono.defer(() -> engine.insert(table, row)))
                .then(Mono.defer(() -> outbox.entityChanged(engine, def, created.id(), EntityAction.INSERT.name(),
                    INITIAL_VERSION, null, attrs.keySet().stream().filter(def.changeableFields()::contains).toList())))
                .thenReturn(created);
        }));
    }

    private Mono<EntityInstance> update(
        StorageEngine engine, DatasetDefinition dataset, Map<String, Object> scope, EntityDefinition def,
        EntityInstance instance, ValidationContext validation
    ) {
        return Mono.defer(() -> {
            String versionColumn = requireWritable(def);

            Map<String, Object> incoming = new LinkedHashMap<>(
                EntityValidator.requireValid(def, instance.attributes(), validation, false));
            normalizeReferences(def, incoming);
            List<Violation> scopeViolations = new ArrayList<>();
            enforceScope(dataset, def, scope, incoming, false, scopeViolations);

            String table = queryCompiler.resolveTable(dataset, def);
            return findInScope(engine, dataset, scope, def, instance.id())
                .switchIfEmpty(Mono.error(() -> notFound(def, instance.id())))
                .flatMap(current -> verifyChangedCodes(def, incoming, current, validation)
                    .then(Mono.defer(() -> applyUpdate(engine, dataset, def, table, versionColumn, instance,
                        current, incoming, scopeViolations, validation))));
        });
    }

    /**
     * Dictionary codes are checked only where the update changes them: a stored code that has been disabled
     * since does not block updates of other fields (docs/design/02-metamodel.md section 5).
     */
    Mono<Void> verifyChangedCodes(
        EntityDefinition def, Map<String, Object> incoming, EntityInstance current, ValidationContext validation
    ) {
        Map<String, Object> changed = new LinkedHashMap<>();
        incoming.forEach((field, value) -> {
            if (!sameValue(current.attributes().get(field), value)) {
                changed.put(field, value);
            }
        });
        return dictionaryLookup(def, changed).flatMap(lookup -> {
            if (lookup == DictionaryLookup.NONE) {
                return Mono.empty();
            }
            EntityValidator.requireValid(def, changed, validation, false, lookup);
            return Mono.empty();
        });
    }

    private Mono<EntityInstance> applyUpdate(
        StorageEngine engine,
        DatasetDefinition dataset,
        EntityDefinition def,
        String table,
        String versionColumn,
        EntityInstance instance,
        EntityInstance current,
        Map<String, Object> incoming,
        List<Violation> scopeViolations,
        ValidationContext validation
    ) {
        if (current.version() != instance.version()) {
            return Mono.error(new ConcurrentUpdateException(String.format(
                "Optimistic lock conflict on %s [ID: %s]: expected version [%d], stored version [%d]",
                def.name, instance.id(), instance.version(), current.version())));
        }

        List<Violation> violations = new ArrayList<>(scopeViolations);
        Map<String, Object> changes = new LinkedHashMap<>();
        String nextState = evaluateUpdate(dataset, def, instance.id(), current.attributes(), current.state(), incoming,
            changes, violations, validation);
        if (!changes.isEmpty()) {
            Map<String, Object> candidate = new LinkedHashMap<>(current.attributes());
            candidate.putAll(changes);
            CheckDefinition.evaluate(def, candidate, validation, violations);
        }
        if (changes.isEmpty() && violations.isEmpty()) {
            return Mono.just(current);
        }
        if (!violations.isEmpty()) {
            return Mono.error(new BusinessRuleViolationException(violations));
        }

        Map<String, Object> physicalUpdates = new LinkedHashMap<>();
        changes.forEach((field, value) -> physicalUpdates.put(def.physicalColumn(field), value));

        Map<String, Object> merged = new LinkedHashMap<>(current.attributes());
        merged.putAll(changes);
        EntityInstance updated = new EntityInstance(
            current.id(), def.name, current.version() + 1, nextState, merged);

        return verifyReferences(def, changes, changes.keySet())
            .then(Mono.defer(() -> engine
                .casUpdate(table, def.primaryKeyColumn(), current.id(), current.version(), versionColumn, physicalUpdates)
                .flatMap(applied -> applied
                    ? outbox.entityChanged(engine, def, current.id(), EntityAction.UPDATE.name(), updated.version(),
                        null, changes.keySet()).thenReturn(updated)
                    : Mono.<EntityInstance>error(conflict(def, instance.id())))));
    }

    /**
     * Collects the real changes of an update into {@code changes} and every rule they break into
     * {@code violations}: immutable fields, soft-delete fields, the lifecycle and its guards. Attempts to change
     * immutable fields are not kept as changes.
     *
     * @param current      stored values before the update
     * @param currentState stored lifecycle state
     * @return the lifecycle state after the update
     */
    String evaluateUpdate(
        DatasetDefinition dataset, EntityDefinition def, Object id, Map<String, Object> current, String currentState,
        Map<String, Object> incoming, Map<String, Object> changes, List<Violation> violations,
        ValidationContext validation
    ) {
        for (Map.Entry<String, Object> entry : incoming.entrySet()) {
            String fieldName = entry.getKey();
            if (sameValue(current.get(fieldName), entry.getValue())) {
                continue;
            }
            if (def.field(fieldName).immutable() || fieldName.equals(def.primaryKey)) {
                violations.add(new Violation(fieldName, PlatformErrorCodes.IMMUTABLE_FIELD, String.format(
                    "Immutability violation on %s: field [%s] cannot be altered", def.name, fieldName)));
                continue;
            }
            changes.put(fieldName, entry.getValue());
        }
        rejectSoftDeleteFields(dataset, def, changes, current, violations);

        String nextState = currentState;
        if (def.stateField != null && changes.containsKey(def.stateField)) {
            Object candidate = changes.get(def.stateField);
            if (candidate == null) {
                violations.add(new Violation(def.stateField, PlatformErrorCodes.STATE_CLEARED,
                    "The state of " + def.name + " [ID: " + id + "] cannot be cleared"));
            } else {
                String candidateState = candidate.toString();
                if (!def.allowsTransition(currentState, candidateState)) {
                    violations.add(new Violation(def.stateField, PlatformErrorCodes.ILLEGAL_TRANSITION,
                        String.format("Illegal transition from [%s] to [%s] on %s [ID: %s]",
                            currentState, candidateState, def.name, id),
                        Map.of("from", String.valueOf(currentState), "to", candidateState)));
                } else {
                    evaluateGuards(def, currentState, candidateState, changes, current, validation, violations);
                    nextState = candidateState;
                }
            }
        }
        return nextState;
    }

    private Mono<Void> delete(
        StorageEngine engine, DatasetDefinition dataset, Map<String, Object> scope, EntityDefinition def,
        EntityInstance instance
    ) {
        return Mono.defer(() -> {
            String versionColumn = requireWritable(def);
            String table = queryCompiler.resolveTable(dataset, def);

            return findInScope(engine, dataset, scope, def, instance.id())
                .switchIfEmpty(Mono.error(() -> notFound(def, instance.id())))
                .flatMap(current -> {
                    if (current.version() != instance.version()) {
                        return Mono.<Void>error(new ConcurrentUpdateException(String.format(
                            "Optimistic lock conflict on deleting %s [ID: %s]: expected version [%d], stored version [%d]",
                            def.name, instance.id(), instance.version(), current.version())));
                    }
                    return ensureNotReferenced(def, current).then(Mono.defer(() -> {
                        Mono<Boolean> removed = usesSoftDelete(dataset, def)
                            ? engine.casUpdate(table, def.primaryKeyColumn(), current.id(), current.version(),
                                versionColumn, softDeleteAssignments(def, dataset.policy()))
                            : engine.delete(table, def.primaryKeyColumn(), current.id(), versionColumn, current.version());
                        return removed.flatMap(done -> done
                            ? outbox.entityChanged(engine, def, current.id(), EntityAction.DELETE.name(),
                                current.version(), null, def.changeableFields())
                            : Mono.<Void>error(conflict(def, instance.id())));
                    }));
                });
        });
    }

    // ================= Reads =================

    /** Finds an entity by id within the dataset scope, reading from the primary engine. */
    public Mono<EntityInstance> findById(DatasetDefinition dataset, EntityDefinition def, Object id) {
        return findById(dataset, def, id, null, null);
    }

    /**
     * Finds an entity by id within the dataset scope, reading from the primary engine. A temporal entity is read
     * in the version in effect at {@code asOf} (default: now) as recorded until {@code knownAt} (default: all).
     */
    public Mono<EntityInstance> findById(DatasetDefinition dataset, EntityDefinition def, Object id, Instant asOf,
        Instant knownAt) {
        return RequestContexts.current().flatMap(request -> findInScope(
            storageRegistry.getEngine(dataset.storage().connectionPoolRef()), dataset,
            dataset.scope().resolve(request), def, id, timeSlice(dataset, def, asOf, knownAt)));
    }

    /** Runs a query within the dataset scope, reading from the read replica when one is configured. */
    public Flux<EntityInstance> query(DatasetDefinition dataset, EntityDefinition def, EntityQuery query) {
        return query(dataset, def, query, null, null);
    }

    /** As {@link #query(DatasetDefinition, EntityDefinition, EntityQuery)}, at a point in time for temporal entities. */
    public Flux<EntityInstance> query(DatasetDefinition dataset, EntityDefinition def, EntityQuery query,
        Instant asOf, Instant knownAt) {
        return RequestContexts.current().flatMapMany(request -> {
            PhysicalQueryPlan plan = queryCompiler.compile(dataset, def, query, dataset.scope().resolve(request),
                timeSlice(dataset, def, asOf, knownAt));
            return readEngine(dataset).executeQuery(plan).map(row -> hydrate(def, row));
        });
    }

    /** Number of entities the query matches within the dataset scope, ignoring its paging. */
    public Mono<Long> count(DatasetDefinition dataset, EntityDefinition def, EntityQuery query) {
        return count(dataset, def, query, null, null);
    }

    /** As {@link #count(DatasetDefinition, EntityDefinition, EntityQuery)}, at a point in time for temporal entities. */
    public Mono<Long> count(DatasetDefinition dataset, EntityDefinition def, EntityQuery query, Instant asOf,
        Instant knownAt) {
        return RequestContexts.current().flatMap(request -> readEngine(dataset)
            .count(queryCompiler.compile(dataset, def, query, dataset.scope().resolve(request),
                timeSlice(dataset, def, asOf, knownAt))));
    }

    /**
     * History of a temporal entity: all its versions with the operations that wrote them. Empty when the entity
     * does not exist or its latest state (a tombstone included) lies outside the dataset scope; refused when the
     * dataset does not allow time travel.
     */
    public Mono<List<Map<String, Object>>> history(DatasetDefinition dataset, EntityDefinition def, Object id) {
        return RequestContexts.current().flatMap(request -> {
            if (!def.temporal) {
                throw notTemporal(def);
            }
            if (!dataset.policy().allowTimeTravel()) {
                throw new ValidationException(List.of(new Violation(null, PlatformErrorCodes.TIME_TRAVEL_NOT_ALLOWED,
                    "Dataset " + dataset.resourceId() + " shows the current state only")));
            }
            Object key;
            try {
                key = def.normalizeId(id);
            } catch (IllegalArgumentException e) {
                throw new ValidationException(List.of(new Violation(def.primaryKey, PlatformErrorCodes.INVALID_VALUE,
                    e.getMessage())));
            }
            java.util.UUID uuid = (java.util.UUID) key;
            StorageEngine engine = storageRegistry.getEngine(dataset.storage().connectionPoolRef());
            String table = queryCompiler.resolveTable(dataset, def);
            Map<String, Object> scope = dataset.scope().resolve(request);
            return temporalStore.history(engine, table, def, uuid).flatMap(entries -> {
                Timeline timeline = Timeline.of(entries.stream().map(TemporalStore.HistoryEntry::version).toList());
                Optional<EntityVersion> latest = timeline.at(clock.instant())
                    .or(() -> timeline.winners().stream().findFirst());
                if (latest.isEmpty() || !withinScope(dataset, def, scope, latest.get().state())) {
                    return Mono.empty();
                }
                return Mono.just(entries.stream().map(TemporalStore.HistoryEntry::describe).toList());
            });
        });
    }

    /** Whether values lie within the resolved scope of the dataset (always true for other entities). */
    static boolean withinScope(DatasetDefinition dataset, EntityDefinition def, Map<String, Object> scope,
        Map<String, Object> values) {
        if (!dataset.isTarget(def.name)) {
            return true;
        }
        for (Map.Entry<String, Object> filter : scope.entrySet()) {
            Object expected = FieldValueCoercer.coerce(def.field(filter.getKey()), filter.getValue(), false);
            if (!sameValue(expected, values.get(filter.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Point of view of a read requested by a caller: the current state unless {@code asOf} or {@code knownAt} is
     * given, which only temporal entities of datasets allowing time travel accept (docs/design/03-dataset.md
     * section 2.5).
     */
    private TimeSlice timeSlice(DatasetDefinition dataset, EntityDefinition def, Instant asOf, Instant knownAt) {
        if (asOf != null || knownAt != null) {
            if (!def.temporal) {
                throw notTemporal(def);
            }
            if (!dataset.policy().allowTimeTravel()) {
                throw new ValidationException(List.of(new Violation(asOf != null ? "asOf" : "knownAt",
                    PlatformErrorCodes.TIME_TRAVEL_NOT_ALLOWED,
                    "Dataset " + dataset.resourceId() + " shows the current state only")));
            }
        }
        return currentSlice(def, asOf, knownAt);
    }

    /** The given point of view, or now; null for entities that are not temporal. */
    TimeSlice currentSlice(EntityDefinition def, Instant asOf, Instant knownAt) {
        return def.temporal ? new TimeSlice(asOf != null ? asOf : clock.instant(), knownAt) : null;
    }

    private StorageEngine readEngine(DatasetDefinition dataset) {
        String pool = dataset.storage().readReplicaRef();
        if (pool == null || pool.isBlank()) {
            pool = dataset.storage().connectionPoolRef();
        }
        return storageRegistry.getEngine(pool);
    }

    private Mono<EntityInstance> findInScope(
        StorageEngine engine, DatasetDefinition dataset, Map<String, Object> scope, EntityDefinition def, Object id
    ) {
        return findInScope(engine, dataset, scope, def, id, currentSlice(def, null, null));
    }

    private Mono<EntityInstance> findInScope(
        StorageEngine engine, DatasetDefinition dataset, Map<String, Object> scope, EntityDefinition def, Object id,
        TimeSlice slice
    ) {
        return Mono.defer(() -> {
            if (id == null) {
                throw new ValidationException(List.of(new Violation(
                    def.primaryKey, "REQUIRED", "Entity id must not be null")));
            }
            EntityQuery query = EntityQuery.builder()
                .where(new QueryPredicate.Eq(def.primaryKey, id))
                .limit(1)
                .build();
            PhysicalQueryPlan plan = queryCompiler.compile(dataset, def, query, scope, slice);
            return engine.executeQuery(plan).next().map(row -> hydrate(def, row));
        });
    }

    // ================= References =================

    /**
     * References to temporal entities hold their UUIDs (the key of {@code entity_registry}); values given as text
     * are converted, invalid ones rejected.
     */
    void normalizeReferences(EntityDefinition def, Map<String, Object> values) {
        List<Violation> violations = new ArrayList<>();
        for (ReferenceDefinition ref : def.references) {
            Object value = values.get(ref.sourceField());
            if (value == null) {
                continue;
            }
            entityRegistry.find(ref.targetEntity()).filter(target -> target.temporal).ifPresent(target -> {
                try {
                    values.put(ref.sourceField(), target.normalizeId(value));
                } catch (IllegalArgumentException e) {
                    violations.add(new Violation(ref.sourceField(), PlatformErrorCodes.INVALID_VALUE, e.getMessage()));
                }
            });
        }
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
    }

    /**
     * Verifies that every reference field among {@code changedFields} points at an existing instance
     * of its target entity. Fields that are not written, or are set to null, are not checked.
     * All missing targets are reported together.
     */
    Mono<Void> verifyReferences(
        EntityDefinition def, Map<String, Object> values, Collection<String> changedFields
    ) {
        return Flux.fromIterable(def.references)
            .filter(ref -> changedFields.contains(ref.sourceField()) && values.get(ref.sourceField()) != null)
            .concatMap(ref -> {
                Object target = values.get(ref.sourceField());
                return referenceExists(ref, target)
                    .filter(exists -> !exists)
                    .map(missing -> new Violation(ref.sourceField(), PlatformErrorCodes.REFERENCE_NOT_FOUND,
                        String.format("%s [ID: %s] referenced by field '%s' does not exist",
                            ref.targetEntity(), target, ref.sourceField()),
                        Map.of("target", ref.targetEntity(), "id", String.valueOf(target))));
            })
            .collectList()
            .flatMap(violations -> violations.isEmpty()
                ? Mono.<Void>empty()
                : Mono.<Void>error(new ValidationException(violations)));
    }

    /** Looks the target up in its default dataset, so its scope and soft delete apply: hidden targets do not count. */
    private Mono<Boolean> referenceExists(ReferenceDefinition ref, Object targetId) {
        return RequestContexts.current().flatMap(request -> {
            EntityDefinition target = entityRegistry.getOrThrow(ref.targetEntity());
            DatasetDefinition targetDataset = datasetFor(target);
            StorageEngine engine = storageRegistry.getEngine(targetDataset.storage().connectionPoolRef());
            return findInScope(engine, targetDataset, targetDataset.scope().resolve(request), target, targetId)
                .hasElement();
        });
    }

    /** Rejects the deletion of an instance that other entities still refer to. */
    Mono<Void> ensureNotReferenced(EntityDefinition def, EntityInstance current) {
        return Flux.fromIterable(entityRegistry.referencesTo(def.name))
            .concatMap(incoming -> isReferenced(incoming, def, current.id())
                .filter(referenced -> referenced)
                .map(referenced -> incoming))
            .next()
            .flatMap(incoming -> Mono.<Void>error(new BusinessRuleViolationException(new Violation(
                null, PlatformErrorCodes.STILL_REFERENCED, String.format(
                    "Cannot delete %s [ID: %s]: still referenced by %s.%s",
                    def.name, current.id(), incoming.source().name, incoming.reference().sourceField()),
                Map.of("entity", def.name, "source", incoming.source().name)))));
    }

    private Mono<Boolean> isReferenced(EntityDefinitionRegistry.IncomingReference incoming, EntityDefinition target, Object id) {
        return Mono.defer(() -> {
            EntityDefinition source = incoming.source();
            DatasetDefinition sourceDataset = datasetFor(source);
            StorageEngine engine = storageRegistry.getEngine(sourceDataset.storage().connectionPoolRef());

            QueryPredicate where = new QueryPredicate.Eq(incoming.reference().sourceField(), id);
            if (source.name.equals(target.name)) {
                // A self-reference must not block the deletion of the instance that holds it.
                where = new QueryPredicate.And(List.of(where, new QueryPredicate.Ne(source.primaryKey, id)));
            }
            EntityQuery query = EntityQuery.builder().where(where).limit(1).build();
            // Referrers outside the caller's scope still block the deletion, so the scope is not applied here.
            Mono<Boolean> now = engine.executeQuery(queryCompiler.compile(sourceDataset, source, query, Map.of(),
                currentSlice(source, null, null))).hasElements();
            if (!source.temporal) {
                return now;
            }
            // A referrer whose scheduled version will refer to the target blocks the deletion as well.
            Object self = source.name.equals(target.name) ? id : null;
            return now.flatMap(referenced -> referenced ? Mono.just(true)
                : temporalStore.referencedLater(engine, queryCompiler.resolveTable(sourceDataset, source), source,
                    incoming.reference().sourceField(), id, self, clock.instant()));
        });
    }

    private DatasetDefinition datasetFor(EntityDefinition def) {
        return datasetRegistry.findForEntity(def.name).orElseThrow(
            () -> new IllegalStateException("No dataset serves entity type " + def.name));
    }

    // ================= Rules =================

    String requireWritable(EntityDefinition def) {
        return def.versionColumn().orElseThrow(() -> new BusinessRuleViolationException(new Violation(
            null, PlatformErrorCodes.ENTITY_READ_ONLY,
            "Entity " + def.name + " declares no Version field and is read-only", Map.of("entity", def.name))));
    }

    /**
     * Datasets whose writes come from processes only (decision D14) refuse the entry points that write what the
     * caller sends: the dataset API and the generic entity processes (422 {@code PROCESS_ONLY_DATASET}).
     */
    public static void rejectDirectWrites(DatasetDefinition dataset) {
        if (dataset.policy().processOnlyWrites()) {
            throw new BusinessRuleViolationException(new Violation(null, PlatformErrorCodes.PROCESS_ONLY_DATASET,
                "Dataset " + dataset.resourceId() + " is written by its processes only",
                Map.of("dataset", dataset.resourceId())));
        }
    }

    private void ensureDatasetWritable(DatasetDefinition dataset) {
        if (dataset.policy().readOnly()) {
            throw new BusinessRuleViolationException(new Violation(null, PlatformErrorCodes.DATASET_READ_ONLY,
                "Write rejected: dataset " + dataset.resourceId() + " is read-only",
                Map.of("dataset", dataset.resourceId())));
        }
    }

    private boolean usesSoftDelete(DatasetDefinition dataset, EntityDefinition def) {
        return dataset.policy().softDelete() && dataset.isTarget(def.name);
    }

    private Map<String, Object> softDeleteAssignments(EntityDefinition def, DatasetPolicy policy) {
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(def.physicalColumn(policy.softDeleteField()), Boolean.TRUE);
        if (policy.softDeleteTimeField() != null) {
            updates.put(def.physicalColumn(policy.softDeleteTimeField()), clock.instant());
        }
        return updates;
    }

    /** The soft-delete fields of the dataset are maintained by deletions only, never by callers. */
    static void rejectSoftDeleteFields(
        DatasetDefinition dataset, EntityDefinition def, Map<String, Object> values, Map<String, Object> current,
        List<Violation> violations
    ) {
        DatasetPolicy policy = dataset.policy();
        if (!policy.softDelete() || !dataset.isTarget(def.name)) {
            return;
        }
        for (String field : new String[] {policy.softDeleteField(), policy.softDeleteTimeField()}) {
            if (field == null || !values.containsKey(field)) {
                continue;
            }
            Object value = values.get(field);
            // "Not deleted" (false or no value) is what every visible row already is, so stating it is harmless.
            boolean notDeleted = value == null || Boolean.FALSE.equals(value);
            if (!notDeleted && !sameValue(value, current.get(field))) {
                violations.add(new Violation(field, PlatformErrorCodes.IMMUTABLE_FIELD,
                    "Field [" + field + "] of " + def.name + " is maintained by deletions only"));
            }
            values.remove(field);
        }
    }

    void verifyIdMatches(EntityDefinition def, EntityInstance instance, Map<String, Object> attrs) {
        if (instance.id() == null) {
            return;
        }
        Object expected = FieldValueCoercer.coerce(def.field(def.primaryKey), instance.id(), false);
        if (!sameValue(expected, attrs.get(def.primaryKey))) {
            throw new ValidationException(List.of(new Violation(def.primaryKey, "ID_MISMATCH",
                "Attribute value differs from the instance id [" + instance.id() + "]")));
        }
    }

    /**
     * Writes to the target entity must lie within the dataset's scope; missing scope values are filled in when
     * {@code fillMissing} is set.
     */
    void enforceScope(
        DatasetDefinition dataset, EntityDefinition def, Map<String, Object> scope, Map<String, Object> attrs,
        boolean fillMissing, List<Violation> violations
    ) {
        if (!dataset.isTarget(def.name)) {
            return;
        }
        for (Map.Entry<String, Object> filter : scope.entrySet()) {
            FieldDefinition field = def.field(filter.getKey());
            Object expected = FieldValueCoercer.coerce(field, filter.getValue(), false);
            if (attrs.containsKey(filter.getKey())) {
                if (!sameValue(expected, attrs.get(filter.getKey()))) {
                    violations.add(new Violation(filter.getKey(), PlatformErrorCodes.OUT_OF_SCOPE, String.format(
                        "Write rejected: field [%s] of %s must be [%s] within dataset %s",
                        filter.getKey(), def.name, expected, dataset.resourceId()),
                        Map.of("expected", String.valueOf(expected))));
                }
            } else if (fillMissing) {
                attrs.put(filter.getKey(), expected);
            }
        }
    }

    /**
     * Determines the state of a new entity and stores it in the attributes; null if the entity has no
     * lifecycle or no valid initial state could be determined (then a violation has been recorded).
     */
    String resolveInitialState(
        EntityDefinition def, Map<String, Object> attrs, String hint, List<Violation> violations
    ) {
        if (def.stateField == null) {
            return null;
        }
        Object provided = attrs.get(def.stateField);
        String state = provided != null ? provided.toString() : hint;
        if (state == null) {
            if (def.initialStates.size() == 1) {
                state = def.initialStates.iterator().next();
            } else if (def.initialStates.isEmpty()) {
                return null;
            } else {
                violations.add(new Violation(def.stateField, PlatformErrorCodes.STATE_REQUIRED,
                    "State is required on insert of " + def.name + "; allowed initial states: " + def.initialStates,
                    Map.of("allowed", String.join(", ", def.initialStates))));
                return null;
            }
        }
        if (!def.initialStates.isEmpty() && !def.initialStates.contains(state)) {
            violations.add(new Violation(def.stateField, PlatformErrorCodes.INVALID_INITIAL_STATE, String.format(
                "Illegal initial state [%s] on %s; allowed: %s", state, def.name, def.initialStates),
                Map.of("state", state, "allowed", String.join(", ", def.initialStates))));
            return null;
        }
        attrs.put(def.stateField, state);
        return state;
    }

    /** Runs the transition guards of {@code from -> to} (from is null on insert) and collects their violations. */
    static void evaluateGuards(
        EntityDefinition def, String from, String to, Map<String, Object> incoming, Map<String, Object> current,
        ValidationContext validation, List<Violation> violations
    ) {
        for (GuardDefinition guard : def.guardsFor(from, to)) {
            try {
                List<Violation> found = guard.guard().check(from, to, current, incoming, validation);
                if (found != null) {
                    violations.addAll(found);
                }
            } catch (RuntimeException e) {
                log.warn("Transition guard {} of {} failed", guard.code(), def.name, e);
                violations.add(new Violation(null, PlatformErrorCodes.GUARD_EVALUATION_FAILED,
                    "Guard " + guard.code() + " could not be evaluated: " + e.getMessage(),
                    Map.of("guard", guard.code())));
            }
        }
    }

    // ================= Dictionaries and unique constraints =================

    /**
     * Enabled codes of the dictionaries behind the supplied Code values that have no fixed values, loaded
     * before validation so that the validator itself stays synchronous.
     */
    Mono<DictionaryLookup> dictionaryLookup(EntityDefinition def, Map<String, Object> raw) {
        List<String> urns = new ArrayList<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            FieldDefinition field = def.fields.get(entry.getKey());
            if (entry.getValue() != null && field != null && field.kind() instanceof SemanticKind.Code code
                && code.allowedValues().isEmpty()) {
                urns.add(code.dictUrn());
            }
        }
        if (urns.isEmpty()) {
            return Mono.just(DictionaryLookup.NONE);
        }
        return dictionaries.enabledCodes(urns).map(codes -> urn -> Optional.ofNullable(codes.get(urn)));
    }

    /**
     * A declared unique constraint becomes a validation error; any other unique index (the primary key of an
     * insert with a caller-supplied id, for example) is a conflict with existing data.
     */
    static Throwable uniqueViolation(EntityDefinition def, UniqueKeyViolationException e) {
        for (UniqueConstraint unique : def.uniqueConstraints) {
            if (unique.name().equalsIgnoreCase(e.constraintName())) {
                return new ValidationException(List.of(new Violation(unique.fields().getFirst(),
                    PlatformErrorCodes.UNIQUE_VIOLATION,
                    "Values of " + unique.fields() + " are already used by another " + def.name,
                    Map.of("constraint", unique.name(), "fields", String.join(", ", unique.fields())))));
            }
        }
        return new ConcurrentUpdateException("Write to " + def.name + " conflicts with existing data ("
            + e.constraintName() + ")");
    }

    static void rejectIfAny(List<Violation> violations) {
        if (!violations.isEmpty()) {
            throw new BusinessRuleViolationException(violations);
        }
    }

    // ================= Helpers =================

    EntityInstance hydrate(EntityDefinition def, Map<String, Object> raw) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (FieldDefinition field : def.fields.values()) {
            if (field.kind() instanceof SemanticKind.Version || !raw.containsKey(field.physicalColumn())) {
                continue;
            }
            attributes.put(field.name(), readValue(field, raw.get(field.physicalColumn())));
        }

        long version = def.versionColumn()
            .map(column -> {
                Object stored = raw.get(column);
                return stored == null ? 0L : (Long) readVersion(stored);
            })
            .orElse(0L);

        String state = def.stateField == null ? null : (String) attributes.get(def.stateField);
        return new EntityInstance(attributes.get(def.primaryKey), def.name, version, state, attributes);
    }

    private Object readValue(FieldDefinition field, Object stored) {
        try {
            return FieldValueCoercer.coerce(field, stored, false);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Stored value of field " + field.name() + " is invalid: " + e.getMessage(), e);
        }
    }

    private Object readVersion(Object stored) {
        try {
            return FieldValueCoercer.coerce(new SemanticKind.Version(), stored, false);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Stored version is invalid: " + e.getMessage(), e);
        }
    }

    private static boolean isSystemRecorded(FieldDefinition field) {
        return field.kind() instanceof SemanticKind.Temporal t && t.role() == TemporalRole.SYSTEM_RECORDED;
    }

    static boolean sameValue(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        return Objects.equals(a, b);
    }

    static EntityNotFoundException notFound(EntityDefinition def, Object id) {
        return new EntityNotFoundException(def.name + " [ID: " + id + "] not found");
    }

    static ValidationException notTemporal(EntityDefinition def) {
        return new ValidationException(List.of(new Violation(null, PlatformErrorCodes.NOT_TEMPORAL,
            def.name + " is not temporal: it has no effective times, schedules or history",
            Map.of("entity", def.name))));
    }

    private static ConcurrentUpdateException conflict(EntityDefinition def, Object id) {
        return new ConcurrentUpdateException(
            "Optimistic lock conflict on " + def.name + " [ID: " + id + "]");
    }
}

