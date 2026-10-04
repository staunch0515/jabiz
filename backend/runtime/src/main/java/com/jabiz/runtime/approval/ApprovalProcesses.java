package com.jabiz.runtime.approval;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code APPROVAL_DECIDE} (docs/design/18-numbering-approvals-tasks.md section 3.4): approves or rejects the current
 * level of a pending request. The approver
 * <ul>
 *   <li>holds the level's permission (403 otherwise);</li>
 *   <li>is not the preparer ({@code APPROVAL_OWN_REQUEST}) and has not decided another level of the request
 *       ({@code APPROVAL_ALREADY_DECIDED});</li>
 *   <li>to approve a level with a limit fact, has an approval limit for the subject covering the fact's value
 *       ({@code APPROVAL_LIMIT_EXCEEDED}); a rejection needs a reason.</li>
 * </ul>
 * Approving the last level approves the request and publishes {@value #APPROVED_EVENT}; a rejection rejects it and
 * publishes {@value #REJECTED_EVENT}. The application subscribes to them to continue (or stop) its own process,
 * which calls {@link RequireApproval} again: the approval counts only while the content hashes the same. The request's
 * task is closed and, while levels remain, the next level's opened ({@link ApprovalTasks}).
 */
@Configuration
public class ApprovalProcesses {

    public static final String DECIDE = "APPROVAL_DECIDE";
    public static final String APPROVED_EVENT = "jabiz.approval.approved";
    public static final String REJECTED_EVENT = "jabiz.approval.rejected";

    public static final String APPROVE = "APPROVE";
    public static final String REJECT = "REJECT";

    public enum Decision { APPROVE, REJECT }

    public record DecideInput(@NotNull UUID requestId, @NotNull Decision decision, @Size(max = 500) String reason) {}

    public record DecideOutput(String requestId, String status, int currentLevel) {}

    /**
     * Payload of the approved and rejected events.
     *
     * @param approverId who decided the last level (approved) or rejected
     */
    public record Decided(String requestId, String subject, String entityId, String preparerId, String contentHash,
        String status, String approverId, String reason) {}

    private static final String REQUEST_ID = "requestId";
    private static final String REQUEST = "request";
    private static final String DECISIONS = "decisions";
    private static final String LIMITS = "limits";
    private static final String OUTPUT = "output";
    private static final String DECIDED = "decided";
    private static final String PASSED = "passed";

    public static ProcessDefinition<DecideInput, DecideOutput, ProcessContext> decide() {
        return ProcessDefinition.define(DECIDE, 1, DecideInput.class, DecideOutput.class, ProcessContext.class,
            pb -> pb
                .description("Approves or rejects the current level of a pending approval request.")
                .permissions(ApprovalPermissions.DECIDE)
                .actsOn(ApprovalEntities.REQUEST, REQUEST_ID, a -> a.whenField("status", ApprovalEntities.PENDING))
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("input", input);
                    ctx.put(REQUEST_ID, input.requestId());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, DecideOutput.class))
                .step("Load the request", LoadEntity.by(ApprovalEntities.REQUEST_DATASET, REQUEST_ID, REQUEST))
                .step("Load its decisions", QueryEntities.of(ApprovalEntities.DECISION_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("requestId", ctx.get(REQUEST_ID)))
                        .limit(ApprovalEntities.MAX_ROWS).build(), DECISIONS))
                .step("Load the approver's limit", QueryEntities.of(ApprovalEntities.LIMIT_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                        userIs(ctx.request().actorId()),
                        new QueryPredicate.Eq("subject", ctx.get(REQUEST, EntityInstance.class).get("subject")))))
                        .limit(1).build(), LIMITS))
                .compute("Decide", (metadata, ctx) -> decide(ctx))
                .step("Pass the task on", ApprovalTasks.passOn(PASSED))
                .step("Announce the approval", PublishEvent.when(ctx -> isDecided(ctx, ApprovalEntities.APPROVED),
                    APPROVED_EVENT, ctx -> ctx.get(DECIDED)))
                .step("Announce the rejection", PublishEvent.when(ctx -> isDecided(ctx, ApprovalEntities.REJECTED),
                    REJECTED_EVENT, ctx -> ctx.get(DECIDED))));
    }

    /** Users are temporal entities with UUID keys; any other actor (the system, a dev header) has no limit. */
    private static QueryPredicate userIs(String actorId) {
        try {
            return new QueryPredicate.Eq("userId", UUID.fromString(actorId));
        } catch (IllegalArgumentException e) {
            return new QueryPredicate.In("userId", List.of());
        }
    }

    private static boolean isDecided(ProcessContext ctx, String status) {
        return ctx.contains(DECIDED) && ctx.get(DECIDED, Decided.class).status().equals(status);
    }

    @SuppressWarnings("unchecked")
    private static void decide(ProcessContext ctx) {
        DecideInput input = ctx.get("input", DecideInput.class);
        EntityInstance request = ctx.get(REQUEST, EntityInstance.class);
        String requestId = String.valueOf(request.id());
        String actor = ctx.request().actorId();
        Map<String, Object> params = Map.of("request", requestId);
        if (!ApprovalEntities.PENDING.equals(request.get("status"))) {
            ctx.reject(new Violation(REQUEST_ID, PlatformErrorCodes.APPROVAL_NOT_PENDING,
                "Approval request " + requestId + " is " + request.get("status") + ", not pending",
                Map.of("request", requestId, "status", String.valueOf(request.<Object>get("status")))));
            return;
        }
        List<Map<String, Object>> levels = (List<Map<String, Object>>) ApprovalJson.read(request.get("levels"));
        int current = ((BigDecimal) request.get("currentLevel")).intValueExact();
        Map<String, Object> level = levels.get(current - 1);
        String permission = String.valueOf(level.get("permission"));
        if (!ctx.request().hasPermission(permission)) {
            throw new PermissionDeniedException(permission, "Level " + current + " of approval request " + requestId
                + " requires permission " + permission);
        }
        if (actor.equals(request.get("preparerId"))) {
            ctx.reject(new Violation(REQUEST_ID, PlatformErrorCodes.APPROVAL_OWN_REQUEST,
                "The preparer of approval request " + requestId + " cannot decide it", params));
        }
        List<EntityInstance> decisions = (List<EntityInstance>) ctx.get(DECISIONS);
        if (decisions.stream().anyMatch(decision -> actor.equals(decision.get("approverId")))) {
            ctx.reject(new Violation(REQUEST_ID, PlatformErrorCodes.APPROVAL_ALREADY_DECIDED,
                "You have already decided a level of approval request " + requestId, params));
        }
        String reason = input.reason() == null || input.reason().isBlank() ? null : input.reason().strip();
        if (input.decision() == Decision.REJECT && reason == null) {
            ctx.reject(new Violation("reason", PlatformErrorCodes.REASON_REQUIRED, "A rejection needs a reason"));
        }
        Object limitFact = level.get("limitFact");
        if (input.decision() == Decision.APPROVE && limitFact != null) {
            Map<String, Object> facts = (Map<String, Object>) ApprovalJson.read(request.get("facts"));
            BigDecimal value = facts.get(limitFact) == null ? BigDecimal.ZERO
                : new BigDecimal(String.valueOf(facts.get(limitFact)));
            List<EntityInstance> limits = (List<EntityInstance>) ctx.get(LIMITS);
            BigDecimal max = limits.isEmpty() ? null : limits.getFirst().get("maxValue");
            if (max == null || max.compareTo(value) < 0) {
                ctx.reject(new Violation(REQUEST_ID, PlatformErrorCodes.APPROVAL_LIMIT_EXCEEDED,
                    "Your approval limit does not cover " + limitFact + " " + value.toPlainString(),
                    Map.of("fact", String.valueOf(limitFact), "value", value.toPlainString(),
                        "limit", max == null ? "-" : max.toPlainString())));
            }
        }
        if (ctx.hasViolations()) {
            return;
        }
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("requestId", request.id());
        decision.put("levelNo", BigDecimal.valueOf(current));
        decision.put("approverId", actor);
        decision.put("decision", input.decision().name());
        decision.put("reason", reason);
        decision.put("decidedTime", ctx.opTime());
        decision.put("processSeqId", BigDecimal.valueOf(ctx.processSeqId()));
        ctx.changes().insert(ApprovalEntities.DECISION, decision);

        String status = input.decision() == Decision.REJECT ? ApprovalEntities.REJECTED
            : current == levels.size() ? ApprovalEntities.APPROVED : ApprovalEntities.PENDING;
        int next = status.equals(ApprovalEntities.PENDING) ? current + 1 : current;
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("status", status);
        changes.put("currentLevel", BigDecimal.valueOf(next));
        ctx.changes().update(ApprovalEntities.REQUEST, request.id(), request.version(), changes);
        if (!status.equals(ApprovalEntities.PENDING)) {
            ctx.put(DECIDED, new Decided(requestId, request.get("subject"), request.get("entityId"),
                request.get("preparerId"), request.get("contentHash"), status, actor, reason));
        }
        ctx.put(OUTPUT, new DecideOutput(requestId, status, next));
        ctx.put(PASSED, new ApprovalTasks.Passed(requestId, request.get("subject"), request.get("entityId"),
            request.get("reference"), next,
            status.equals(ApprovalEntities.PENDING) ? String.valueOf(levels.get(next - 1).get("permission")) : null));
    }

    @Bean
    ProcessDefinition<DecideInput, DecideOutput, ProcessContext> approvalDecideProcess() {
        return decide();
    }
}
