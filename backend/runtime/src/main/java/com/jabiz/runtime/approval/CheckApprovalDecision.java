package com.jabiz.runtime.approval;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.security.Permissions;
import com.jabiz.runtime.sod.SodGuard;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Runs the {@link ApprovalDecisionCheck}s covering the subject of the request being decided ({@code APPROVAL_DECIDE});
 * a request that is not pending is left to the decision itself, which refuses it. First, whatever its status, the
 * decider must hold the permission deciding the subject needs (decision D51): a covering check's
 * {@link ApprovalDecisionCheck#decidePermission}, otherwise {@code approval.decide} (403); and segregation of duties is
 * checked for the permissions the decision actually uses - that one and the current level's ({@link SodGuard}; the
 * entry point leaves processes with alternative permissions to this, review M3).
 */
@Component
class CheckApprovalDecision implements StepHandler<CheckApprovalDecision.Metadata, ProcessContext> {

    record Metadata(String requestKey, String inputKey) {}

    static StepSpec<Metadata, ProcessContext> of(String requestKey, String inputKey) {
        return StepSpec.of(CheckApprovalDecision.class, new Metadata(requestKey, inputKey));
    }

    private final List<ApprovalDecisionCheck> checks;
    private final SodGuard sod;

    CheckApprovalDecision(ObjectProvider<ApprovalDecisionCheck> checks, SodGuard sod) {
        this.checks = checks.orderedStream().toList();
        this.sod = sod;
    }

    /** The permission of the request's current level, when it has one. */
    @SuppressWarnings("unchecked")
    private static Optional<String> levelPermission(EntityInstance request) {
        Object levels = request.get("levels");
        Object current = request.get("currentLevel");
        if (levels == null || !(current instanceof Number number)) {
            return Optional.empty();
        }
        List<Map<String, Object>> all = (List<Map<String, Object>>) ApprovalJson.read(String.valueOf(levels));
        int index = number.intValue() - 1;
        return index < 0 || index >= all.size() || all.get(index).get("permission") == null ? Optional.empty()
            : Optional.of(String.valueOf(all.get(index).get("permission")));
    }

    @Override
    public Mono<Void> execute(Metadata metadata, ProcessContext ctx) {
        return Mono.defer(() -> {
            EntityInstance request = ctx.get(metadata.requestKey(), EntityInstance.class);
            String subject = request.get("subject");
            String needed = checks.stream().filter(check -> check.covers(subject))
                .map(ApprovalDecisionCheck::decidePermission).filter(java.util.Objects::nonNull).findFirst()
                .orElse(ApprovalPermissions.DECIDE);
            Permissions.require(ctx.request(), needed, "Deciding approval request " + request.id() + " ("
                + subject + ")");
            Set<String> used = new LinkedHashSet<>(List.of(needed));
            levelPermission(request).ifPresent(used::add);
            Mono<Void> segregated = sod.check(ctx.request(), used);
            if (checks.isEmpty() || !ApprovalEntities.PENDING.equals(request.get("status"))) {
                return segregated;
            }
            ApprovalProcesses.DecideInput input = ctx.get(metadata.inputKey(), ApprovalProcesses.DecideInput.class);
            boolean approve = input.decision() == ApprovalProcesses.Decision.APPROVE;
            return segregated.then(Flux.fromIterable(checks).filter(check -> check.covers(subject))
                .concatMap(check -> check.check(request, approve, ctx)).then());
        });
    }
}
