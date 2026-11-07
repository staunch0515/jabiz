package com.jabiz.runtime.approval;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.process.ProcessInputs;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.task.TaskEntities;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The step of {@code APPROVAL_SCOPE_MOVE} ({@link ApprovalScopeMove}, decision D76). For each case it takes the case's
 * lock and reads its pending request whole, whatever the caller's scope (as {@link RequireApproval} does); a request
 * of another scope name, or of none, refuses the whole move (422 {@code APPROVAL_SCOPE_MISMATCH}). Requests and tasks
 * are appended a version with the new value against the version read, so a decision taken meanwhile fails the move
 * (409) instead of being overwritten.
 */
@Component
class MoveApprovalScope implements StepHandler<NoMetadata, ProcessContext> {

    private final ApprovalStore store;
    private final DatasetEntityManager entities;
    private final DatasetRegistry datasets;
    private final ProcessInputs inputs;

    MoveApprovalScope(ApprovalStore store, DatasetEntityManager entities, DatasetRegistry datasets,
        ProcessInputs inputs) {
        this.store = store;
        this.entities = entities;
        this.datasets = datasets;
        this.inputs = inputs;
    }

    @Override
    public Mono<Void> execute(NoMetadata metadata, ProcessContext ctx) {
        return Mono.defer(() -> {
            ApprovalScopeMove.MoveInput input = ctx.get(ApprovalScopeMove.INPUT, ApprovalScopeMove.MoveInput.class);
            // A sub-process's input is not checked at an entry: the step checks its constraints itself (400).
            inputs.validate(input);
            // Sub-processes are not checked against their permissions at the entry; moving approvals is a right of
            // its own, besides whatever the application's process asked for.
            if (!ctx.request().hasPermission(ApprovalPermissions.SCOPE_MOVE)) {
                return Mono.error(new PermissionDeniedException(ApprovalPermissions.SCOPE_MOVE,
                    ApprovalScopeMove.MOVE + " requires permission " + ApprovalPermissions.SCOPE_MOVE));
            }
            // A scoped session writes its own scope's rows only (decision D64): it cannot hand them to another value.
            if (ctx.request().entryScope() != null) {
                return Mono.error(new BusinessRuleViolationException(new Violation("toValue",
                    PlatformErrorCodes.OUT_OF_SCOPE, "A session of an entry scope cannot move approvals to another "
                    + "scope value", Map.of("expected", ctx.request().entryScope().value()))));
            }
            Moved moved = new Moved();
            Set<ApprovalScopeMove.Case> cases = new LinkedHashSet<>(input.requests());
            Set<String> keys = new LinkedHashSet<>();
            return Flux.fromIterable(cases)
                .concatMap(found -> moveRequest(ctx, input, found, moved, keys))
                .then(Mono.defer(() -> {
                    if (input.taskSourceKeys() != null) {
                        keys.addAll(input.taskSourceKeys());
                    }
                    return Flux.fromIterable(keys).concatMap(key -> moveTasks(ctx, input, key, moved)).then();
                }))
                .then(Mono.fromRunnable(() -> ctx.put(ApprovalScopeMove.OUTPUT, new ApprovalScopeMove.MoveOutput(
                    List.copyOf(moved.requestIds), List.copyOf(moved.taskIds), List.copyOf(moved.skipped)))));
        });
    }

    /** What the move did so far. */
    private static final class Moved {
        final List<String> requestIds = new ArrayList<>();
        final List<String> taskIds = new ArrayList<>();
        final List<ApprovalScopeMove.Skipped> skipped = new ArrayList<>();
    }

    private Mono<Void> moveRequest(ProcessContext ctx, ApprovalScopeMove.MoveInput input, ApprovalScopeMove.Case found,
        Moved moved, Set<String> keys) {
        return store.lockCase(found.subject(), found.entityId())
            .then(store.openRequests(found.subject(), found.entityId()))
            .doOnNext(open -> {
                List<EntityInstance> pending = open.stream()
                    .filter(request -> ApprovalEntities.PENDING.equals(request.get("status"))).toList();
                if (pending.isEmpty()) {
                    moved.skipped.add(new ApprovalScopeMove.Skipped(found.subject(), found.entityId(),
                        ApprovalScopeMove.NOT_PENDING));
                }
                for (EntityInstance request : pending) {
                    String id = String.valueOf(request.id());
                    if (!input.scopeName().equals(request.get("scopeName"))) {
                        ctx.reject(mismatch(id, input.scopeName()));
                        continue;
                    }
                    // Its open tasks follow it, even when the request itself is there already.
                    keys.add(ApprovalTasks.sourceKey(id));
                    if (input.toValue().equals(request.get("scopeValue"))) {
                        moved.skipped.add(new ApprovalScopeMove.Skipped(found.subject(), found.entityId(),
                            ApprovalScopeMove.ALREADY_THERE));
                        continue;
                    }
                    ctx.changes().in(ApprovalEntities.REQUEST_DATASET)
                        .granted(new ApprovalScopeGrant(ApprovalEntities.REQUEST, id, input.scopeName(),
                            input.toValue(), ctx.processSeqId()))
                        .update(ApprovalEntities.REQUEST, request.id(), request.version(),
                            Map.of("scopeValue", input.toValue()));
                    moved.requestIds.add(id);
                }
            })
            .then();
    }

    /** The open tasks of {@code sourceKey}, whatever the caller's scope (as {@code TaskWriter#close} reads them). */
    private Mono<Void> moveTasks(ProcessContext ctx, ApprovalScopeMove.MoveInput input, String sourceKey,
        Moved moved) {
        DatasetDefinition dataset = datasets.findById(TaskEntities.TASK_DATASET).orElseThrow();
        EntityQuery query = EntityQuery.builder().where(new QueryPredicate.And(List.of(
            new QueryPredicate.Eq("sourceKey", sourceKey),
            new QueryPredicate.Eq("status", TaskEntities.OPEN)))).limit(ApprovalEntities.MAX_ROWS).build();
        return RequestContexts.withoutEntryScope(entities.queryAll(dataset, TaskEntities.SYS_TASK, query))
            .doOnNext(task -> {
                String id = String.valueOf(task.id());
                if (!input.scopeName().equals(task.get("scopeName"))) {
                    ctx.reject(mismatch(id, input.scopeName()));
                    return;
                }
                if (Objects.equals(input.toValue(), task.get("scopeValue")) || moved.taskIds.contains(id)) {
                    return;
                }
                ctx.changes().in(TaskEntities.TASK_DATASET)
                    .granted(new ApprovalScopeGrant(TaskEntities.TASK, id, input.scopeName(), input.toValue(),
                        ctx.processSeqId()))
                    .update(TaskEntities.TASK, task.id(), task.version(), Map.of("scopeValue", input.toValue()));
                moved.taskIds.add(id);
            })
            .then();
    }

    private static Violation mismatch(String id, String scopeName) {
        return new Violation("scopeName", PlatformErrorCodes.APPROVAL_SCOPE_MISMATCH, id + " does not belong to an "
            + "entry scope named " + scopeName, Map.of("id", id, "scopeName", scopeName));
    }
}
