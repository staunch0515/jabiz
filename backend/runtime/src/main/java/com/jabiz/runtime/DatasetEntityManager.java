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
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.dictionary.DictionaryRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.runtime.storage.UniqueKeyViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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

    public DatasetEntityManager(
        StorageAdapterRegistry storageRegistry,
        EntityDefinitionRegistry entityRegistry,
        DatasetRegistry datasetRegistry,
        QueryCompiler queryCompiler,
        DictionaryRegistry dictionaries,
        Clock clock
    ) {
        this.storageRegistry = Objects.requireNonNull(storageRegistry, "StorageAdapterRegistry cannot be null");
        this.entityRegistry = Objects.requireNonNull(entityRegistry, "EntityDefinitionRegistry cannot be null");
        this.datasetRegistry = Objects.requireNonNull(datasetRegistry, "DatasetRegistry cannot be null");
        this.queryCompiler = Objects.requireNonNull(queryCompiler, "QueryCompiler cannot be null");
        this.dictionaries = Objects.requireNonNull(dictionaries, "DictionaryRegistry cannot be null");
        this.clock = Objects.requireNonNull(clock, "Clock cannot be null");
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

            Mono<List<EntityInstance>> work = Flux.fromIterable(ordered)
                .concatMap(change -> applyChange(engine, dataset, scope, change, validation))
                .collectList()
                .map(Collections::unmodifiableList);

            return engine.inTransaction(work)
                .doOnSuccess(committed -> log.debug("Committed {} change(s) through dataset {}",
                    ordered.size(), dataset.resourceId()));
        });
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

            Mono<EntityInstance> result = switch (change.action()) {
                case INSERT -> insert(engine, dataset, scope, def, instance, validation);
                case UPDATE -> update(engine, dataset, scope, def, instance, validation);
                case DELETE -> delete(engine, dataset, scope, def, instance).then(Mono.<EntityInstance>empty());
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

            verifyIdMatches(def, instance, attrs);
            List<Violation> violations = new ArrayList<>();
            rejectSoftDeleteFields(dataset, def, attrs, Map.of(), violations);
            enforceScope(dataset, def, scope, attrs, true, violations);
            String state = resolveInitialState(def, attrs, instance.state(), violations);
            if (state != null) {
                evaluateGuards(def, null, state, attrs, Map.of(), validation, violations);
            }
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
                .then(Mono.defer(() -> engine.insert(table, row).thenReturn(created)));
        }));
    }

    private Mono<EntityInstance> update(
        StorageEngine engine, DatasetDefinition dataset, Map<String, Object> scope, EntityDefinition def,
        EntityInstance instance, ValidationContext validation
    ) {
        return dictionaryLookup(def, instance.attributes()).flatMap(lookup -> Mono.defer(() -> {
            String versionColumn = requireWritable(def);

            Map<String, Object> incoming = EntityValidator.requireValid(
                def, instance.attributes(), validation, false, lookup);
            List<Violation> scopeViolations = new ArrayList<>();
            enforceScope(dataset, def, scope, incoming, false, scopeViolations);

            String table = queryCompiler.resolveTable(dataset, def);
            return findInScope(engine, dataset, scope, def, instance.id())
                .switchIfEmpty(Mono.error(() -> notFound(def, instance.id())))
                .flatMap(current -> applyUpdate(engine, dataset, def, table, versionColumn, instance, current,
                    incoming, scopeViolations, validation));
        }));
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

        // Keep only real changes; attempts to change immutable fields are collected and rejected together
        // with every other rule this change breaks.
        List<Violation> violations = new ArrayList<>(scopeViolations);
        Map<String, Object> changes = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : incoming.entrySet()) {
            String fieldName = entry.getKey();
            if (sameValue(current.attributes().get(fieldName), entry.getValue())) {
                continue;
            }
            if (def.field(fieldName).immutable() || fieldName.equals(def.primaryKey)) {
                violations.add(new Violation(fieldName, PlatformErrorCodes.IMMUTABLE_FIELD, String.format(
                    "Immutability violation on %s: field [%s] cannot be altered", def.name, fieldName)));
                continue;
            }
            changes.put(fieldName, entry.getValue());
        }
        rejectSoftDeleteFields(dataset, def, changes, current.attributes(), violations);
        if (changes.isEmpty() && violations.isEmpty()) {
            return Mono.just(current);
        }

        String nextState = current.state();
        if (def.stateField != null && changes.containsKey(def.stateField)) {
            Object candidate = changes.get(def.stateField);
            if (candidate == null) {
                violations.add(new Violation(def.stateField, PlatformErrorCodes.STATE_CLEARED,
                    "The state of " + def.name + " [ID: " + instance.id() + "] cannot be cleared"));
            } else {
                String candidateState = candidate.toString();
                if (!def.allowsTransition(current.state(), candidateState)) {
                    violations.add(new Violation(def.stateField, PlatformErrorCodes.ILLEGAL_TRANSITION,
                        String.format("Illegal transition from [%s] to [%s] on %s [ID: %s]",
                            current.state(), candidateState, def.name, instance.id()),
                        Map.of("from", String.valueOf(current.state()), "to", candidateState)));
                } else {
                    evaluateGuards(def, current.state(), candidateState, changes, current.attributes(),
                        validation, violations);
                    nextState = candidateState;
                }
            }
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
                    ? Mono.just(updated)
                    : Mono.<EntityInstance>error(conflict(def, instance.id())))));
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
                            ? Mono.<Void>empty()
                            : Mono.<Void>error(conflict(def, instance.id())));
                    }));
                });
        });
    }

    // ================= Reads =================

    /** Finds an entity by id within the dataset scope, reading from the primary engine. */
    public Mono<EntityInstance> findById(DatasetDefinition dataset, EntityDefinition def, Object id) {
        return RequestContexts.current().flatMap(request -> findInScope(
            storageRegistry.getEngine(dataset.storage().connectionPoolRef()), dataset,
            dataset.scope().resolve(request), def, id));
    }

    /** Runs a query within the dataset scope, reading from the read replica when one is configured. */
    public Flux<EntityInstance> query(DatasetDefinition dataset, EntityDefinition def, EntityQuery query) {
        return RequestContexts.current().flatMapMany(request -> {
            PhysicalQueryPlan plan = queryCompiler.compile(dataset, def, query, dataset.scope().resolve(request));
            return readEngine(dataset).executeQuery(plan).map(row -> hydrate(def, row));
        });
    }

    /** Number of entities the query matches within the dataset scope, ignoring its paging. */
    public Mono<Long> count(DatasetDefinition dataset, EntityDefinition def, EntityQuery query) {
        return RequestContexts.current().flatMap(request -> readEngine(dataset)
            .count(queryCompiler.compile(dataset, def, query, dataset.scope().resolve(request))));
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
        return Mono.defer(() -> {
            if (id == null) {
                throw new ValidationException(List.of(new Violation(
                    def.primaryKey, "REQUIRED", "Entity id must not be null")));
            }
            EntityQuery query = EntityQuery.builder()
                .where(new QueryPredicate.Eq(def.primaryKey, id))
                .limit(1)
                .build();
            PhysicalQueryPlan plan = queryCompiler.compile(dataset, def, query, scope);
            return engine.executeQuery(plan).next().map(row -> hydrate(def, row));
        });
    }

    // ================= References =================

    /**
     * Verifies that every reference field among {@code changedFields} points at an existing instance
     * of its target entity. Fields that are not written, or are set to null, are not checked.
     * All missing targets are reported together.
     */
    private Mono<Void> verifyReferences(
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
    private Mono<Void> ensureNotReferenced(EntityDefinition def, EntityInstance current) {
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
            return engine.executeQuery(queryCompiler.compile(sourceDataset, source, query, Map.of())).hasElements();
        });
    }

    private DatasetDefinition datasetFor(EntityDefinition def) {
        return datasetRegistry.findForEntity(def.name).orElseThrow(
            () -> new IllegalStateException("No dataset serves entity type " + def.name));
    }

    // ================= Rules =================

    private String requireWritable(EntityDefinition def) {
        return def.versionColumn().orElseThrow(() -> new BusinessRuleViolationException(new Violation(
            null, PlatformErrorCodes.ENTITY_READ_ONLY,
            "Entity " + def.name + " declares no Version field and is read-only", Map.of("entity", def.name))));
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
    private static void rejectSoftDeleteFields(
        DatasetDefinition dataset, EntityDefinition def, Map<String, Object> values, Map<String, Object> current,
        List<Violation> violations
    ) {
        DatasetPolicy policy = dataset.policy();
        if (!policy.softDelete() || !dataset.isTarget(def.name)) {
            return;
        }
        for (String field : new String[] {policy.softDeleteField(), policy.softDeleteTimeField()}) {
            if (field != null && values.containsKey(field) && !sameValue(values.get(field), current.get(field))) {
                values.remove(field);
                violations.add(new Violation(field, PlatformErrorCodes.IMMUTABLE_FIELD,
                    "Field [" + field + "] of " + def.name + " is maintained by deletions only"));
            }
        }
    }

    private void verifyIdMatches(EntityDefinition def, EntityInstance instance, Map<String, Object> attrs) {
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
    private void enforceScope(
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
    private String resolveInitialState(
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
    private static void evaluateGuards(
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
    private Mono<DictionaryLookup> dictionaryLookup(EntityDefinition def, Map<String, Object> raw) {
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

    /** A declared unique constraint becomes a validation error; any other index violation stays an error. */
    private static Throwable uniqueViolation(EntityDefinition def, UniqueKeyViolationException e) {
        for (UniqueConstraint unique : def.uniqueConstraints) {
            if (unique.name().equalsIgnoreCase(e.constraintName())) {
                return new ValidationException(List.of(new Violation(unique.fields().getFirst(),
                    PlatformErrorCodes.UNIQUE_VIOLATION,
                    "Values of " + unique.fields() + " are already used by another " + def.name,
                    Map.of("constraint", unique.name(), "fields", String.join(", ", unique.fields())))));
            }
        }
        return e;
    }

    private static void rejectIfAny(List<Violation> violations) {
        if (!violations.isEmpty()) {
            throw new BusinessRuleViolationException(violations);
        }
    }

    // ================= Helpers =================

    private EntityInstance hydrate(EntityDefinition def, Map<String, Object> raw) {
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

    private static boolean sameValue(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        return Objects.equals(a, b);
    }

    private static EntityNotFoundException notFound(EntityDefinition def, Object id) {
        return new EntityNotFoundException(def.name + " [ID: " + id + "] not found");
    }

    private static ConcurrentUpdateException conflict(EntityDefinition def, Object id) {
        return new ConcurrentUpdateException(
            "Optimistic lock conflict on " + def.name + " [ID: " + id + "]");
    }
}

