package com.jabiz.runtime.approval;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.process.ProcessInputs;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.task.TaskEntities;
import com.jabiz.runtime.task.TaskWriter;
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
 * The step of {@code APPROVAL_SCOPE_MOVE} ({@link ApprovalScopeMove}, decision D76). For each case, in the order of
 * subject and id, it takes the case's lock and reads its open requests (pending, or approved and not yet used) whole, whatever the caller's scope (as {@link RequireApproval} does); a request
 * of another scope name, or of none, refuses the whole move (422 {@code APPROVAL_SCOPE_MISMATCH}). Requests and tasks
 * are appended a version with the new value against the version read, so a decision taken meanwhile fails the move
 * (409) instead of being overwritten.
 */
@Component
class MoveApprovalScope implements StepHandler<NoMetadata, ProcessContext> {

    /** Whether an open request's scope moves: what {@link ApprovalStore#openRequests} reads as open. */
    static final java.util.Set<String> OPEN = java.util.Set.of(ApprovalEntities.PENDING, ApprovalEntities.APPROVED);

    private static final java.util.Comparator<ApprovalScopeMove.Case> LOCK_ORDER =
        java.util.Comparator.comparing(ApprovalScopeMove.Case::subject).thenComparing(ApprovalScopeMove.Case::entityId);

    private final ApprovalStore store;
    private final TaskWriter tasks;
    private final ProcessInputs inputs;

    MoveApprovalScope(ApprovalStore store, TaskWriter tasks, ProcessInputs inputs) {
        this.store = store;
        this.tasks = tasks;
        this.inputs = inputs;
    }

    @Override
    public Mono<Void> execute(NoMetadata metadata, ProcessContext ctx) {
        return Mono.defer(() -> {
            // Sub-processes are not checked against their permissions at the entry; moving approvals is a right of
            // its own, besides whatever the application's process asked for. Checked first: an unauthorised caller
            // learns nothing about the input.
            if (!ctx.request().hasPermission(ApprovalPermissions.SCOPE_MOVE)) {
                return Mono.error(new PermissionDeniedException(ApprovalPermissions.SCOPE_MOVE,
                    ApprovalScopeMove.MOVE + " requires permission " + ApprovalPermissions.SCOPE_MOVE));
            }
            ApprovalScopeMove.MoveInput input = ctx.get(ApprovalScopeMove.INPUT, ApprovalScopeMove.MoveInput.class);
            // A sub-process's input is not checked at an entry: the step checks its constraints itself (400).
            inputs.validate(input);
            // A scoped session writes its own scope's rows only (decision D64): it cannot hand them to another value.
            if (ctx.request().entryScope() != null) {
                return Mono.error(new BusinessRuleViolationException(new Violation("toValue",
                    PlatformErrorCodes.OUT_OF_SCOPE, "A session of an entry scope cannot move approvals to another "
                    + "scope value", Map.of("expected", ctx.request().entryScope().value()))));
            }
            List<String> taskKeys = input.taskSourceKeys() == null ? List.of() : input.taskSourceKeys();
            List<Violation> approvalKeys = taskKeys.stream()
                .filter(key -> key.startsWith(ApprovalTasks.sourceKey(""))).distinct()
                .map(key -> new Violation("taskSourceKeys", PlatformErrorCodes.INVALID_VALUE, "Source key " + key
                    + " is an approval's task: it moves with its request, named in requests", Map.of("value", key)))
                .toList();
            if (!approvalKeys.isEmpty()) {
                return Mono.error(new BusinessRuleViolationException(approvalKeys));
            }
            Moved moved = new Moved();
            // In one order, so that two moves never wait for each other's case locks.
            List<ApprovalScopeMove.Case> cases = new LinkedHashSet<>(input.requests()).stream().sorted(LOCK_ORDER)
                .toList();
            Set<String> keys = new LinkedHashSet<>();
            return Flux.fromIterable(cases)
                .concatMap(found -> moveRequest(ctx, input, found, moved, keys))
                .then(Mono.defer(() -> {
                    keys.addAll(taskKeys);
                    return Flux.fromIterable(keys).concatMap(key -> moveTasks(ctx, input, key, moved)).then();
                }))
                .then(Mono.fromRunnable(() -> ctx.put(ApprovalScopeMove.OUTPUT, new ApprovalScopeMove.MoveOutput(
                    List.copyOf(moved.requestIds), List.copyOf(moved.taskIds), List.copyOf(moved.skipped)))));
        });
    }

    /** What the move did so far. */
    private static final class Moved {
        final List<String> requestIds = new ArrayList<>();
        final Set<String> taskIds = new LinkedHashSet<>();
        final List<ApprovalScopeMove.Skipped> skipped = new ArrayList<>();
    }

    /**
     * Every open request of the case: pending, and approved but not yet used up or superseded - an approved request
     * left in the old scope would keep the new one from superseding or withdrawing the case (APPROVAL_OTHER_SCOPE).
     */
    private Mono<Void> moveRequest(ProcessContext ctx, ApprovalScopeMove.MoveInput input, ApprovalScopeMove.Case found,
        Moved moved, Set<String> keys) {
        return store.lockCase(found.subject(), found.entityId())
            .then(store.openRequests(found.subject(), found.entityId()))
            .doOnNext(all -> {
                List<EntityInstance> open = all.stream().filter(request -> OPEN.contains(request.get("status")))
                    .toList();
                if (open.isEmpty()) {
                    moved.skipped.add(new ApprovalScopeMove.Skipped(found.subject(), found.entityId(),
                        ApprovalScopeMove.NOT_OPEN));
                }
                for (EntityInstance request : open) {
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

    /** The open tasks of {@code sourceKey}, whatever the caller's scope ({@link TaskWriter#openTasks}). */
    private Mono<Void> moveTasks(ProcessContext ctx, ApprovalScopeMove.MoveInput input, String sourceKey,
        Moved moved) {
        return tasks.openTasks(sourceKey)
            .doOnNext(task -> {
                String id = String.valueOf(task.id());
                if (!input.scopeName().equals(task.get("scopeName"))) {
                    ctx.reject(mismatch(id, input.scopeName()));
                    return;
                }
                if (Objects.equals(input.toValue(), task.get("scopeValue")) || !moved.taskIds.add(id)) {
                    return;
                }
                ctx.changes().in(TaskEntities.TASK_DATASET)
                    .granted(new ApprovalScopeGrant(TaskEntities.TASK, id, input.scopeName(), input.toValue(),
                        ctx.processSeqId()))
                    .update(TaskEntities.TASK, task.id(), task.version(), Map.of("scopeValue", input.toValue()));
            })
            .then();
    }

    private static Violation mismatch(String id, String scopeName) {
        return new Violation("scopeName", PlatformErrorCodes.APPROVAL_SCOPE_MISMATCH, id + " does not belong to an "
            + "entry scope named " + scopeName, Map.of("id", id, "scopeName", scopeName));
    }
}
