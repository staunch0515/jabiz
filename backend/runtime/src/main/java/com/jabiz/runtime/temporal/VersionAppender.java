package com.jabiz.runtime.temporal;

import com.jabiz.entity.CheckDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.ValidationContext;
import com.jabiz.entity.Violation;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.entity.ValidationException;
import com.jabiz.runtime.RebaseConflictException;
import com.jabiz.runtime.audit.AuditRecorder;
import com.jabiz.runtime.event.Outbox;
import com.jabiz.runtime.operation.Operation;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.temporal.EntityVersion;
import com.jabiz.temporal.Timeline;
import com.jabiz.temporal.VersionPlanner;
import com.jabiz.temporal.VersionPlanner.PlannedVersion;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Appends one write to a temporal entity: plans its versions and rebased copies (decision D1), evaluates the
 * entity's checks on every version it will insert (docs/design/02-metamodel.md section 4.1: a rebased copy or a
 * reverted state combines values no single write supplied), checks uniqueness (decision D6) and inserts versions and
 * operation items. Shared by the writes of the dataset API and by reverts.
 */
@Component
public class VersionAppender {

    private final TemporalStore store;
    private final Clock clock;
    private final Outbox outbox;

    private final AuditRecorder audit;

    public VersionAppender(TemporalStore store, Clock clock, Outbox outbox, AuditRecorder audit) {
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
    }

    /**
     * @param timeline    the versions of the instance as stored now
     * @param checkUnique whether the write can change the values of a unique constraint
     * @return the versions inserted, the written one first
     * @throws RebaseConflictException (as error signal) if later versions changed the same fields
     * @throws BusinessRuleViolationException (as error signal) if a version to insert breaks an entity check
     * @throws ValidationException     (as error signal) with {@code UNIQUE_VIOLATION}s
     */
    public Mono<List<PlannedVersion>> append(StorageEngine engine, String table, EntityDefinition def, UUID id,
        Timeline timeline, VersionPlanner.Write write, Operation operation, boolean checkUnique) {
        return RequestContexts.current().flatMap(request -> {
            VersionPlanner.Plan plan = VersionPlanner.plan(timeline, write);
            if (plan.hasConflicts()) {
                return Mono.error(conflict(def, id, write, plan));
            }
            if (!def.checks.isEmpty()) {
                ValidationContext validation = new ValidationContext(clock, request);
                List<Violation> violations = new ArrayList<>();
                for (PlannedVersion version : plan.versions()) {
                    if (!version.deleted()) {
                        CheckDefinition.evaluate(def, version.state(), validation, violations);
                    }
                }
                if (!violations.isEmpty()) {
                    return Mono.error(new BusinessRuleViolationException(List.copyOf(new LinkedHashSet<>(violations))));
                }
            }
            Mono<Void> unique = checkUnique
                ? store.checkUnique(engine, table, def, id, statesToCheck(timeline, plan, operation), operation.opTime())
                    .flatMap(violations -> violations.isEmpty()
                        ? Mono.<Void>empty()
                        : Mono.<Void>error(new ValidationException(violations)))
                : Mono.empty();
            return unique
                .thenMany(Flux.fromIterable(plan.versions())
                    .concatMap(version -> store.append(engine, table, def, id, version, operation)
                        .then(Mono.defer(() -> audited(engine, def, id, timeline, plan.versions(), version)))))
                .then(Mono.defer(() -> plan.versions().isEmpty()
                    ? Mono.<Void>empty()
                    : published(engine, def, id, plan.versions().getFirst())))
                .then(Mono.just(plan.versions()));
        });
    }

    /**
     * The audit record of a version: its state against the state of the version it is based on (none for an
     * insertion); a tombstone has no "after".
     */
    private Mono<Void> audited(StorageEngine engine, EntityDefinition def, UUID id, Timeline timeline,
        List<PlannedVersion> planned, PlannedVersion version) {
        Long base = version.baseVersionNo();
        Map<String, Object> before = null;
        if (base != null) {
            // A rebased copy may be based on a version this same write plans.
            before = planned.stream().filter(p -> p.versionNo() == base).findFirst()
                .map(p -> p.deleted() ? null : p.state())
                .orElseGet(() -> timeline.version(base).filter(v -> !v.deleted()).map(EntityVersion::state)
                    .orElse(null));
        }
        return audit.record(engine, def, id, version.action().name(), version.versionNo(), version.effectiveFrom(),
            before, version.deleted() ? null : version.state());
    }

    /**
     * The change event of the written version (the first planned one); rebased copies only carry later versions
     * over and publish nothing of their own.
     */
    private Mono<Void> published(StorageEngine engine, EntityDefinition def, UUID id, PlannedVersion written) {
        return outbox.entityChanged(engine, def, id, written.action().name(), written.versionNo(),
            written.effectiveFrom(), written.changedFields());
    }

    /**
     * States of the new versions that will be current at the time of the operation or scheduled after it; versions
     * already superseded by then cannot collide with anything.
     */
    private static List<Map<String, Object>> statesToCheck(Timeline timeline, VersionPlanner.Plan plan,
        Operation operation) {
        List<EntityVersion> all = new ArrayList<>(timeline.versions());
        for (PlannedVersion planned : plan.versions()) {
            all.add(new EntityVersion(planned.versionNo(), planned.effectiveFrom(), operation.opTime(),
                planned.deleted(), planned.state(), planned.changedFields(), planned.action(),
                planned.baseVersionNo(), operation.processSeqId()));
        }
        Timeline after = Timeline.of(all);
        long firstNew = timeline.maxVersionNo() + 1;
        Instant now = operation.opTime();
        List<EntityVersion> relevant = new ArrayList<>();
        after.at(now).ifPresent(relevant::add);
        relevant.addAll(after.after(now));
        return relevant.stream()
            .filter(v -> v.versionNo() >= firstNew && !v.deleted())
            .map(EntityVersion::state)
            .toList();
    }

    private static RebaseConflictException conflict(EntityDefinition def, UUID id, VersionPlanner.Write write,
        VersionPlanner.Plan plan) {
        List<RebaseConflictException.Conflict> conflicts = plan.conflicts().stream()
            .map(c -> new RebaseConflictException.Conflict(def.name, id, c.versionNo(), c.effectiveFrom(),
                c.processSeqId(), c.fields()))
            .toList();
        return new RebaseConflictException(String.format(
            "%s [ID: %s]: the change effective at %s conflicts with %d later version(s); cancel or change them first",
            def.name, id, write.effectiveFrom(), conflicts.size()), conflicts);
    }
}
