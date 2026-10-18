package com.jabiz.runtime.approval;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Runs the {@link ApprovalDecisionCheck}s covering the subject of the request being decided ({@code APPROVAL_DECIDE});
 * a request that is not pending is left to the decision itself, which refuses it.
 */
@Component
class CheckApprovalDecision implements StepHandler<CheckApprovalDecision.Metadata, ProcessContext> {

    record Metadata(String requestKey, String inputKey) {}

    static StepSpec<Metadata, ProcessContext> of(String requestKey, String inputKey) {
        return StepSpec.of(CheckApprovalDecision.class, new Metadata(requestKey, inputKey));
    }

    private final List<ApprovalDecisionCheck> checks;

    CheckApprovalDecision(ObjectProvider<ApprovalDecisionCheck> checks) {
        this.checks = checks.orderedStream().toList();
    }

    @Override
    public Mono<Void> execute(Metadata metadata, ProcessContext ctx) {
        return Mono.defer(() -> {
            EntityInstance request = ctx.get(metadata.requestKey(), EntityInstance.class);
            if (checks.isEmpty() || !ApprovalEntities.PENDING.equals(request.get("status"))) {
                return Mono.empty();
            }
            ApprovalProcesses.DecideInput input = ctx.get(metadata.inputKey(), ApprovalProcesses.DecideInput.class);
            boolean approve = input.decision() == ApprovalProcesses.Decision.APPROVE;
            String subject = request.get("subject");
            return Flux.fromIterable(checks).filter(check -> check.covers(subject))
                .concatMap(check -> check.check(request, approve, ctx)).then();
        });
    }
}
