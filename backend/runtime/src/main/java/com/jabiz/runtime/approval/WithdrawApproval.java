package com.jabiz.runtime.approval;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.process.steps.CheckedStep;
import com.jabiz.runtime.task.TaskEntities;
import com.jabiz.runtime.task.TaskWriter;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Withdraws the pending approval request of a case, for example when the document is cancelled
 * (docs/design/18-numbering-approvals-tasks.md section 3.3). An approved request stays approved: it is bound to its
 * content, and a changed document supersedes it the next time {@link RequireApproval} evaluates it. The request's task
 * is cancelled.
 */
@Component
public class WithdrawApproval<C extends ProcessContext> implements StepHandler<WithdrawApproval.Metadata<C>, C>,
    CheckedStep<WithdrawApproval.Metadata<C>> {

    /** @param when whether to withdraw at all, decided when the step is reached (null: always) */
    public record Metadata<C>(String subject, Function<C, ?> entityId, Predicate<C> when) {
        public Metadata {
            Objects.requireNonNull(subject, "subject must not be null");
            Objects.requireNonNull(entityId, "entityId must not be null");
        }

        public Metadata(String subject, Function<C, ?> entityId) {
            this(subject, entityId, null);
        }
    }

    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> of(String subject, Function<C, ?> entityId) {
        return StepSpec.of(WithdrawApproval.class, new Metadata<>(subject, entityId));
    }

    /**
     * As {@link #of} when {@code condition(ctx)} holds; otherwise the step does nothing (written like
     * {@code CallProcess.when} and {@code RequireApproval.when}).
     */
    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> when(Predicate<C> condition, String subject,
        Function<C, ?> entityId) {
        return StepSpec.of(WithdrawApproval.class, new Metadata<>(subject, entityId,
            Objects.requireNonNull(condition, "condition must not be null")));
    }

    private final ApprovalSubjectRegistry subjects;
    private final ApprovalStore store;
    private final TaskWriter tasks;

    public WithdrawApproval(ApprovalSubjectRegistry subjects, ApprovalStore store, TaskWriter tasks) {
        this.subjects = subjects;
        this.store = store;
        this.tasks = tasks;
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        return Mono.defer(() -> {
            if (metadata.when() != null && !metadata.when().test(ctx)) {
                return Mono.empty();
            }
            String entityId = String.valueOf(Objects.requireNonNull(metadata.entityId().apply(ctx),
                "entityId must not be null"));
            return store.lockCase(metadata.subject(), entityId)
                .then(store.openRequests(metadata.subject(), entityId))
                .flatMapMany(open -> {
                    List<EntityInstance> pending = open.stream()
                        .filter(request -> ApprovalEntities.PENDING.equals(request.get("status"))).toList();
                    ApprovalStore.requireOwnScope(ctx, pending);
                    return Flux.fromIterable(pending);
                })
                .concatMap(request -> {
                    ctx.changes().update(ApprovalEntities.REQUEST, request.id(), request.version(),
                        Map.of("status", ApprovalEntities.WITHDRAWN));
                    return tasks.close(ctx, ApprovalTasks.sourceKey(String.valueOf(request.id())),
                        TaskEntities.CANCELLED);
                })
                .then();
        });
    }

    @Override
    public List<String> problems(Metadata<C> metadata) {
        return subjects.find(metadata.subject()).isPresent() ? List.of()
            : List.of("approval subject " + metadata.subject() + " is not declared");
    }
}
