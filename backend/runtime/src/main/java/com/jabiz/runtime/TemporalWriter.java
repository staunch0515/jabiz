package com.jabiz.runtime;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.CheckDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.EntityValidator;
import com.jabiz.entity.TemporalSpec;
import com.jabiz.entity.UniqueConstraint;
import com.jabiz.entity.ValidationContext;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.query.QueryCompiler;
import com.jabiz.runtime.operation.Operation;
import com.jabiz.runtime.operation.Operations;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.runtime.storage.UniqueKeyViolationException;
import com.jabiz.runtime.temporal.TemporalPermissions;
import com.jabiz.runtime.temporal.VersionAppender;
import com.jabiz.temporal.EntityVersion;
import com.jabiz.temporal.Timeline;
import com.jabiz.temporal.VersionPlanner;
import com.jabiz.temporal.VersionPlanner.PlannedVersion;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Writes of temporal entities (docs/design/04-temporal-append-only.md section 3): every change appends versions,
 * nothing is updated or deleted. The rules of ordinary writes (validation, scope, immutable fields, lifecycle,
 * guards, dictionaries, references) apply unchanged, judged on the state in effect at the change's effective time.
 *
 * <p>The version a caller sends with an update, deletion or cancellation is the number of the version in effect at
 * that time; a different number means the caller worked with a stale state (409). New versions are numbered
 * after the highest number of the entity, so concurrent writers collide on {@code UNIQUE(entity_id, version_no)}.
 */
final class TemporalWriter {

    private final DatasetEntityManager rules;
    private final VersionAppender versions;
    private final QueryCompiler queryCompiler;

    TemporalWriter(DatasetEntityManager rules, VersionAppender versions, QueryCompiler queryCompiler) {
        this.rules = rules;
        this.versions = versions;
        this.queryCompiler = queryCompiler;
    }

    Mono<EntityInstance> apply(StorageEngine engine, DatasetDefinition dataset, Map<String, Object> scope,
        EntityDefinition def, EntityChange change, ValidationContext validation) {
        return Operations.current().flatMap(current -> {
            Operation operation = current.orElseThrow(() -> new IllegalStateException(
                "Writes of temporal entity " + def.name + " need an operation in the Reactor context"));
            Instant effective = effectiveTime(def, change, operation, validation.request());
            String table = queryCompiler.resolveTable(dataset, def);
            Write write = new Write(engine, dataset, scope, def, table, operation, effective, validation,
                change.grant());
            EntityInstance instance = change.instance();
            return switch (change.action()) {
                case INSERT -> insert(write, instance);
                case UPDATE -> update(write, instance);
                case DELETE -> delete(write, instance).then(Mono.<EntityInstance>empty());
                case CANCEL_SCHEDULED -> cancel(write, instance);
            };
        });
    }

    /** What one change works with. */
    private record Write(StorageEngine engine, DatasetDefinition dataset, Map<String, Object> scope,
        EntityDefinition def, String table, Operation operation, Instant effective, ValidationContext validation,
        Object grant) {}

    /**
     * The effective time of a change, checked against the time of the operation: later times need an entity that
     * allows scheduling, earlier times (corrections) need {@link TemporalPermissions#BACKDATE} and a reason.
     */
    private static Instant effectiveTime(EntityDefinition def, EntityChange change, Operation operation,
        RequestContext request) {
        Instant opTime = operation.opTime();
        Instant effective = change.effectiveTime() == null
            ? opTime
            : change.effectiveTime().truncatedTo(ChronoUnit.MICROS);
        // What PostgreSQL cannot store is the caller's 400, not a database error (decision D51 item 3).
        List<Violation> range = new java.util.ArrayList<>();
        com.jabiz.runtime.query.TimeTravel.check("effectiveTime", effective, range);
        if (!range.isEmpty()) {
            throw new ValidationException(range);
        }
        if (change.action() == EntityAction.CANCEL_SCHEDULED && !effective.isAfter(opTime)) {
            throw new BusinessRuleViolationException(new Violation("effectiveTime", PlatformErrorCodes.NOT_SCHEDULED,
                "Only versions that take effect after " + opTime + " can be cancelled",
                Map.of("time", String.valueOf(effective))));
        }
        if (effective.isAfter(opTime) && !def.temporalSpec.allowScheduled()) {
            throw new BusinessRuleViolationException(new Violation("effectiveTime",
                PlatformErrorCodes.SCHEDULING_NOT_ALLOWED, def.name + " does not allow scheduled changes",
                Map.of("entity", def.name)));
        }
        if (effective.isBefore(opTime)) {
            if (!request.hasPermission(TemporalPermissions.BACKDATE)) {
                throw new PermissionDeniedException(TemporalPermissions.BACKDATE,
                    "Changes effective before the time of the operation need permission "
                        + TemporalPermissions.BACKDATE);
            }
            if (operation.reason() == null || operation.reason().isBlank()) {
                throw new ValidationException(List.of(new Violation("reason", PlatformErrorCodes.REASON_REQUIRED,
                    "A correction of the past needs a reason")));
            }
        }
        return effective;
    }

    private Mono<EntityInstance> insert(Write w, EntityInstance instance) {
        EntityDefinition def = w.def();
        Map<String, Object> raw = new LinkedHashMap<>(instance.attributes());
        raw.putIfAbsent(def.primaryKey, instance.id());
        rules.fillProcessOnly(w.dataset(), def, w.scope(), raw, instance.state());
        return rules.dictionaryLookup(def, raw).flatMap(lookup -> Mono.defer(() -> {
            Map<String, Object> attrs = new LinkedHashMap<>(
                EntityValidator.requireValid(def, raw, w.validation(), true, lookup));
            rules.normalizeReferences(def, attrs);
            rules.verifyIdMatches(def, instance, attrs);
            UUID id = id(def, attrs.get(def.primaryKey));
            attrs.put(def.primaryKey, id);

            List<Violation> violations = new ArrayList<>();
            rules.enforceScope(w.dataset(), def, w.scope(), attrs, true, violations);
            String state = rules.resolveInitialState(def, attrs, instance.state(), violations);
            if (state != null) {
                DatasetEntityManager.evaluateGuards(def, null, state, attrs, Map.of(), w.validation(), violations);
            }
            CheckDefinition.evaluate(def, attrs, w.validation(), violations);
            DatasetEntityManager.rejectIfAny(violations);

            Map<String, Object> newState = new LinkedHashMap<>();
            for (String field : def.stateFields()) {
                newState.put(field, attrs.get(field));
            }
            // A new instance has no versions to read (16p, T3): an id that exists already is refused by the database,
            // by the key of entity_registry (and the key index of a write-once entity), with the same 409 as before.
            Timeline none = Timeline.of(List.of());
            VersionPlanner.Write write = VersionPlanner.Write.insert(w.effective(), newState,
                Set.copyOf(def.changeableFields()));
            return rules.verifyReferences(def, id, attrs, attrs.keySet())
                .then(Mono.defer(() -> append(w, id, none, write, true)))
                .contextWrite(context -> StorageEngine.withInsertConflicts(context,
                    (conflict, table, record) -> insertConflict(def, id, conflict)))
                .flatMap(written -> Mono.deferContextual(view -> {
                    // Known to exist from now on, for the references of later writes (decision D46 rule 3).
                    if (!w.effective().isAfter(w.operation().opTime())) {
                        w.engine().transactionState(view).ifPresent(known -> known.inserted(def.name, id,
                            written.attributes()));
                    }
                    return Mono.just(written);
                }));
        }));
    }

    /**
     * A unique key an insertion breaks: a declared unique constraint is a validation error, any other key (the
     * instance's, its first version's) means the instance exists already (409).
     */
    private static Throwable insertConflict(EntityDefinition def, UUID id, UniqueKeyViolationException conflict) {
        for (UniqueConstraint unique : def.uniqueConstraints) {
            if (unique.name().equalsIgnoreCase(conflict.constraintName())) {
                return DatasetEntityManager.uniqueViolation(def, conflict);
            }
        }
        return new ConcurrentUpdateException(def.name + " [ID: " + id + "] already exists", conflict);
    }

    private Mono<EntityInstance> update(Write w, EntityInstance instance) {
        EntityDefinition def = w.def();
        return Mono.defer(() -> {
            rules.requireWritable(def);
            rules.requireChangeable(def, instance.id());
            UUID id = id(def, instance.id());
            Map<String, Object> incoming = new LinkedHashMap<>(
                EntityValidator.requireValid(def, instance.attributes(), w.validation(), false));
            rules.normalizeReferences(def, incoming);
            List<Violation> scopeViolations = new ArrayList<>();
            rules.enforceScope(w.dataset(), def, w.scope(), incoming, false, scopeViolations);

            return timeline(w, id).flatMap(timeline -> {
                EntityVersion base = requireBase(w, timeline, id, instance.version());
                EntityInstance current = snapshot(def, base, base.processSeqId(), base.recordedAt());
                return rules.verifyChangedCodes(def, incoming, current, w.validation()).then(Mono.defer(() -> {
                    List<Violation> violations = new ArrayList<>(scopeViolations);
                    Map<String, Object> changes = new LinkedHashMap<>();
                    rules.evaluateUpdate(w.dataset(), def, id, base.state(), current.state(), incoming, changes,
                        violations, w.validation());
                    if (changes.isEmpty() && violations.isEmpty()) {
                        return Mono.just(current);
                    }
                    if (!changes.isEmpty()) {
                        Map<String, Object> candidate = new LinkedHashMap<>(base.state());
                        candidate.putAll(changes);
                        CheckDefinition.evaluate(def, candidate, w.validation(), violations);
                    }
                    DatasetEntityManager.rejectIfAny(violations);
                    VersionPlanner.Write write = VersionPlanner.Write.update(base, w.effective(), changes);
                    return rules.verifyReferences(def, id, changes, changes.keySet())
                        .then(Mono.defer(() -> append(w, id, timeline, write, touchesUnique(def, changes.keySet()))));
                }));
            });
        });
    }

    private Mono<Void> delete(Write w, EntityInstance instance) {
        EntityDefinition def = w.def();
        return Mono.defer(() -> {
            rules.requireWritable(def);
            rules.requireChangeable(def, instance.id());
            UUID id = id(def, instance.id());
            return timeline(w, id).flatMap(timeline -> {
                EntityVersion base = requireBase(w, timeline, id, instance.version());
                EntityInstance current = snapshot(def, base, base.processSeqId(), base.recordedAt());
                VersionPlanner.Write write = VersionPlanner.Write.delete(base, w.effective(),
                    Set.copyOf(def.changeableFields()));
                return rules.ensureNotReferenced(def, current)
                    .then(rules.ensureDeletable(w.engine(), def, current))
                    .then(Mono.defer(() -> append(w, id, timeline, write, false)))
                    .then();
            });
        });
    }

    /** Cancels the version scheduled at the change's effective time (docs/design/04 section 4.1). */
    private Mono<EntityInstance> cancel(Write w, EntityInstance instance) {
        EntityDefinition def = w.def();
        return Mono.defer(() -> {
            rules.requireWritable(def);
            rules.requireChangeable(def, instance.id());
            UUID id = id(def, instance.id());
            return timeline(w, id).flatMap(timeline -> {
                // Something must still take effect then: a cancelled schedule cannot be cancelled again.
                EntityVersion scheduled = timeline.effectiveExactlyAt(w.effective())
                    .filter(version -> timeline.hasChangeAt(w.effective()) && inScope(w, version.state()))
                    .orElseThrow(() -> new BusinessRuleViolationException(new Violation("effectiveTime",
                        PlatformErrorCodes.NOT_SCHEDULED,
                        def.name + " [ID: " + id + "] has no version scheduled at " + w.effective(),
                        Map.of("time", String.valueOf(w.effective())))));
                if (scheduled.versionNo() != instance.version()) {
                    throw stale(def, id, instance.version(), scheduled.versionNo());
                }
                return append(w, id, timeline, VersionPlanner.Write.cancel(timeline, scheduled), true)
                    .filter(written -> !Boolean.TRUE.equals(written.attributes().get(TemporalSpec.DELETED)));
            });
        });
    }

    private Mono<Timeline> timeline(Write w, UUID id) {
        return rules.temporalStore().load(w.engine(), w.table(), w.def(), id).map(Timeline::of);
    }

    private Mono<EntityInstance> append(Write w, UUID id, Timeline timeline, VersionPlanner.Write write,
        boolean checkUnique) {
        return versions.append(w.engine(), w.table(), w.def(), id, timeline, write, w.operation(), w.grant(),
                checkUnique)
            .map(written -> {
                PlannedVersion first = written.getFirst();
                return snapshot(w.def(), new EntityVersion(first.versionNo(), first.effectiveFrom(),
                    w.operation().opTime(), first.deleted(), first.state(), first.changedFields(), first.action(),
                    first.baseVersionNo(), w.operation().processSeqId()),
                    w.operation().processSeqId(), w.operation().opTime());
            });
    }

    /**
     * The version in effect at the effective time, which the caller must have read: it has to exist, not be a
     * tombstone, lie within the dataset scope and carry the caller's version number.
     */
    private EntityVersion requireBase(Write w, Timeline timeline, UUID id, long callerVersion) {
        EntityVersion base = timeline.at(w.effective())
            .filter(version -> !version.deleted() && inScope(w, version.state()))
            .orElseThrow(() -> new EntityNotFoundException(
                w.def().name + " [ID: " + id + "] not found at " + w.effective()));
        if (base.versionNo() != callerVersion) {
            throw stale(w.def(), id, callerVersion, base.versionNo());
        }
        return base;
    }

    private static ConcurrentUpdateException stale(EntityDefinition def, UUID id, long expected, long actual) {
        return ConcurrentUpdateException.stale(String.format(
            "Version conflict on %s [ID: %s]: expected version [%d], version in effect [%d]",
            def.name, id, expected, actual));
    }

    private static boolean inScope(Write w, Map<String, Object> state) {
        return DatasetEntityManager.withinScope(w.dataset(), w.def(), w.scope(), state);
    }

    private static boolean touchesUnique(EntityDefinition def, Set<String> fields) {
        for (UniqueConstraint unique : def.uniqueConstraints) {
            if (unique.fields().stream().anyMatch(fields::contains)) {
                return true;
            }
        }
        return false;
    }

    private static UUID id(EntityDefinition def, Object raw) {
        if (raw == null) {
            throw new ValidationException(List.of(new Violation(def.primaryKey, PlatformErrorCodes.REQUIRED,
                "Entity id must not be null")));
        }
        try {
            return (UUID) def.normalizeId(raw);
        } catch (IllegalArgumentException e) {
            throw new ValidationException(List.of(new Violation(def.primaryKey, PlatformErrorCodes.INVALID_VALUE,
                e.getMessage())));
        }
    }

    /** A version as the snapshot returned to callers: its state plus the system fields. */
    static EntityInstance snapshot(EntityDefinition def, EntityVersion version, long processSeqId, Instant recordedAt) {
        Map<String, Object> attributes = new LinkedHashMap<>(version.state());
        attributes.put(TemporalSpec.EFFECT_START_TIME, version.effectiveFrom());
        attributes.put(TemporalSpec.CREATED_TIME, recordedAt);
        attributes.put(TemporalSpec.PROCESS_SEQ_ID, BigDecimal.valueOf(processSeqId));
        attributes.put(TemporalSpec.DELETED, version.deleted());
        String state = def.stateField == null ? null : (String) version.state().get(def.stateField);
        return new EntityInstance(version.state().get(def.primaryKey), def.name, version.versionNo(), state, attributes);
    }
}
