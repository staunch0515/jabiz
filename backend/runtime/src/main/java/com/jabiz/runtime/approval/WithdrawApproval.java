package com.jabiz.runtime.approval;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.process.steps.CheckedStep;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Withdraws the pending approval request of a case, for example when the document is cancelled
 * (docs/design/18-numbering-approvals-tasks.md section 3.3). An approved request stays approved: it is bound to its
 * content, and a changed document supersedes it the next time {@link RequireApproval} evaluates it.
 */
@Component
public class WithdrawApproval<C extends ProcessContext> implements StepHandler<WithdrawApproval.Metadata<C>, C>,
    CheckedStep<WithdrawApproval.Metadata<C>> {

    public record Metadata<C>(String subject, Function<C, ?> entityId) {
        public Metadata {
            Objects.requireNonNull(subject, "subject must not be null");
            Objects.requireNonNull(entityId, "entityId must not be null");
        }
    }

    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> of(String subject, Function<C, ?> entityId) {
        return StepSpec.of(WithdrawApproval.class, new Metadata<>(subject, entityId));
    }

    private final ApprovalSubjectRegistry subjects;
    private final ApprovalStore store;

    public WithdrawApproval(ApprovalSubjectRegistry subjects, ApprovalStore store) {
        this.subjects = subjects;
        this.store = store;
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        return Mono.defer(() -> {
            String entityId = String.valueOf(Objects.requireNonNull(metadata.entityId().apply(ctx),
                "entityId must not be null"));
            return store.lockCase(metadata.subject(), entityId)
                .then(store.openRequests(metadata.subject(), entityId))
                .doOnNext(open -> open.stream()
                    .filter(request -> ApprovalEntities.PENDING.equals(request.get("status")))
                    .forEach(request -> ctx.changes().update(ApprovalEntities.REQUEST, request.id(),
                        request.version(), Map.of("status", ApprovalEntities.WITHDRAWN))))
                .then();
        });
    }

    @Override
    public List<String> problems(Metadata<C> metadata) {
        return subjects.find(metadata.subject()).isPresent() ? List.of()
            : List.of("approval subject " + metadata.subject() + " is not declared");
    }
}
