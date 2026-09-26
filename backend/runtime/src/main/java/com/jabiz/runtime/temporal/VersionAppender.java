package com.jabiz.runtime.temporal;

import com.jabiz.entity.CheckDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.ValidationContext;
import com.jabiz.entity.Violation;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.entity.ValidationException;
import com.jabiz.runtime.RebaseConflictException;
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

    public VersionAppender(TemporalStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
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
                    .concatMap(version -> store.append(engine, table, def, id, version, operation)))
                .then(Mono.just(plan.versions()));
        });
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
