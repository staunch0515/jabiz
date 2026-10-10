package com.jabiz.runtime.approval;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ChangeSet;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.LoadEntity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Four-eyes changes of the controls themselves (docs/design/18-numbering-approvals-tasks.md section 3.5): approval
 * rules, approver limits, SoD rules and controlled business parameters (decision D40) change only through a
 * {@code SysControlChange} that one person proposes and another publishes. What can be changed, and how a change is
 * checked and written, is up to the {@link ControlTarget} of the entity.
 * <ul>
 *   <li>{@code CONTROL_CHANGE_PROPOSE}: a new instance, a change of some fields, or a deletion; optionally effective
 *       at a later time (scheduled). The target checks the values at once;</li>
 *   <li>{@code CONTROL_CHANGE_PUBLISH}: by anybody but the proposer ({@code CONTROL_SAME_PERSON}); the target checks
 *       the change again against the state it is based on now and writes it with the proposed effective time;</li>
 *   <li>{@code CONTROL_CHANGE_WITHDRAW}: the proposer takes a proposal back.</li>
 * </ul>
 */
@Configuration
public class ControlChanges {

    public static final String PROPOSE = "CONTROL_CHANGE_PROPOSE";
    public static final String PUBLISH = "CONTROL_CHANGE_PUBLISH";
    public static final String WITHDRAW = "CONTROL_CHANGE_WITHDRAW";

    /** Actions of a change. */
    public static final String UPSERT = "UPSERT";
    public static final String DELETE = "DELETE";

    /**
     * @param targetId      the instance to change or delete; empty to create one
     * @param delete        true to delete the target
     * @param values        the fields to set (for an approval rule, {@code condition} and {@code levels} as JSON
     *                      objects or their text)
     * @param effectiveTime when the change takes effect; empty: when it is published
     */
    public record ProposeInput(@NotBlank String targetEntity, UUID targetId, Boolean delete,
        Map<String, Object> values, Instant effectiveTime, @NotBlank @Size(max = 500) String reason) {}

    public record ChangeInput(@NotNull UUID changeId) {}

    public record ChangeOutput(String changeId, String status, String targetId) {}

    static final String CHANGE_ID = "changeId";
    private static final String INPUT = "input";
    private static final String CHANGE = "change";
    private static final String REQUEST = "request";
    private static final String TARGET = "target";
    private static final String OUTPUT = "output";

    public static ProcessDefinition<ProposeInput, ChangeOutput, ProcessContext> propose(ControlTargets targets) {
        return ProcessDefinition.define(PROPOSE, 1, ProposeInput.class, ChangeOutput.class, ProcessContext.class,
            pb -> pb
                .description("Proposes a change of an approval rule, approver limit, SoD rule or controlled"
                    + " business parameter.")
                .permissions(ApprovalPermissions.CONTROL_PROPOSE)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, ChangeOutput.class))
                .step("Load the target", LoadControlTarget.of(ctx -> {
                    ProposeInput input = ctx.get(INPUT, ProposeInput.class);
                    return new ControlTarget.Request(input.targetEntity(),
                        input.targetId() == null ? null : input.targetId().toString(),
                        Boolean.TRUE.equals(input.delete()), input.values(), input.effectiveTime());
                }, REQUEST, TARGET))
                .compute("Record the proposal", (metadata, ctx) -> propose(ctx, targets)));
    }

    public static ProcessDefinition<ChangeInput, ChangeOutput, ProcessContext> publish(ControlTargets targets) {
        return ProcessDefinition.define(PUBLISH, 1, ChangeInput.class, ChangeOutput.class, ProcessContext.class,
            pb -> pb
                .description("Publishes a change of a control that another person proposed.")
                .permissions(ApprovalPermissions.CONTROL_PUBLISH)
                .requiresMfa(com.jabiz.security.MfaRequirement.ADMINISTRATION)
                .actsOn(ApprovalEntities.CONTROL_CHANGE, CHANGE_ID,
                    a -> a.whenField("status", ApprovalEntities.PROPOSED))
                .contextFactory(ControlChanges::start)
                .outputMapper(ctx -> ctx.get(OUTPUT, ChangeOutput.class))
                .step("Load the change", LoadEntity.by(ApprovalEntities.CONTROL_CHANGE_DATASET, CHANGE_ID, CHANGE))
                .step("Load the target", LoadControlTarget.of(ctx -> recorded(ctx.get(CHANGE, EntityInstance.class)),
                    REQUEST, TARGET))
                .compute("Publish", (metadata, ctx) -> publish(ctx, targets)));
    }

    public static ProcessDefinition<ChangeInput, ChangeOutput, ProcessContext> withdraw() {
        return ProcessDefinition.define(WITHDRAW, 1, ChangeInput.class, ChangeOutput.class, ProcessContext.class,
            pb -> pb
                .description("Withdraws one's own proposed change of a control.")
                .permissions(ApprovalPermissions.CONTROL_PROPOSE)
                .actsOn(ApprovalEntities.CONTROL_CHANGE, CHANGE_ID,
                    a -> a.whenField("status", ApprovalEntities.PROPOSED))
                .contextFactory(ControlChanges::start)
                .outputMapper(ctx -> ctx.get(OUTPUT, ChangeOutput.class))
                .step("Load the change", LoadEntity.by(ApprovalEntities.CONTROL_CHANGE_DATASET, CHANGE_ID, CHANGE))
                .compute("Withdraw", (metadata, ctx) -> withdraw(ctx)));
    }

    private static ProcessContext start(com.jabiz.process.ProcessStart start, ChangeInput input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(CHANGE_ID, input.changeId());
        return ctx;
    }

    /** The change as recorded; its values are read from their JSON once, here. */
    @SuppressWarnings("unchecked")
    private static ControlTarget.Request recorded(EntityInstance change) {
        Object json = change.get("changeValues");
        return new ControlTarget.Request(change.get("targetEntity"), change.get("targetId"),
            DELETE.equals(change.get("changeAction")),
            json == null ? Map.of() : (Map<String, Object>) ApprovalJson.read((String) json),
            change.get("effectiveTime"));
    }

    private static Object loaded(ProcessContext ctx) {
        return ctx.contains(TARGET) ? ctx.get(TARGET) : null;
    }

    private static void propose(ProcessContext ctx, ControlTargets targets) {
        ProposeInput input = ctx.get(INPUT, ProposeInput.class);
        Optional<ControlTarget> target = targets.find(input.targetEntity());
        if (target.isEmpty()) {
            ctx.reject(invalid("targetEntity", "changes are proposed for " + String.join(", ", targets.entities())
                + ", not " + input.targetEntity()));
            return;
        }
        ControlTarget.Proposal proposal = target.get().propose(ctx.get(REQUEST, ControlTarget.Request.class),
            loaded(ctx), ctx);
        if (proposal == null || ctx.hasViolations()) {
            return;
        }
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("targetEntity", input.targetEntity());
        change.put("targetId", proposal.targetId());
        change.put("changeAction", Boolean.TRUE.equals(input.delete()) ? DELETE : UPSERT);
        change.put("changeValues", proposal.values() == null ? null : ApprovalJson.write(proposal.values()));
        change.put("effectiveTime", input.effectiveTime());
        change.put("reason", input.reason().strip());
        change.put("status", ApprovalEntities.PROPOSED);
        change.put("proposedBy", ctx.request().actorId());
        Object id = ctx.changes().insert(ApprovalEntities.CONTROL_CHANGE, change);
        ctx.put(OUTPUT, new ChangeOutput(String.valueOf(id), ApprovalEntities.PROPOSED, proposal.targetId()));
    }

    private static void publish(ProcessContext ctx, ControlTargets targets) {
        EntityInstance change = ctx.get(CHANGE, EntityInstance.class);
        String changeId = String.valueOf(change.id());
        if (!ApprovalEntities.PROPOSED.equals(change.get("status"))) {
            ctx.reject(new Violation(CHANGE_ID, PlatformErrorCodes.CONTROL_CHANGE_NOT_PROPOSED,
                "Change " + changeId + " is " + change.get("status") + ", not proposed", Map.of("change", changeId)));
            return;
        }
        if (ctx.request().actorId().equals(change.get("proposedBy"))) {
            ctx.reject(new Violation(CHANGE_ID, PlatformErrorCodes.CONTROL_SAME_PERSON,
                "The proposer of change " + changeId + " cannot publish it", Map.of("change", changeId)));
            return;
        }
        ControlTarget.Request request = ctx.get(REQUEST, ControlTarget.Request.class);
        ControlTarget target = targets.find(request.entity()).orElseThrow(() -> new IllegalStateException(
            "Change " + changeId + " is for " + request.entity() + ", which no control target changes"));
        ChangeSet.Target writes = ctx.changes().in(target.dataset(request.entity()));
        if (request.effectiveTime() != null) {
            writes = writes.effectiveAt(request.effectiveTime());
        }
        Object targetId = target.publish(request, loaded(ctx), ctx, writes);
        if (targetId == null || ctx.hasViolations()) {
            return;
        }
        Map<String, Object> published = new LinkedHashMap<>();
        published.put("status", ApprovalEntities.PUBLISHED);
        published.put("publishedBy", ctx.request().actorId());
        ctx.changes().update(ApprovalEntities.CONTROL_CHANGE, change.id(), change.version(), published);
        ctx.put(OUTPUT, new ChangeOutput(changeId, ApprovalEntities.PUBLISHED, String.valueOf(targetId)));
    }

    private static void withdraw(ProcessContext ctx) {
        EntityInstance change = ctx.get(CHANGE, EntityInstance.class);
        String changeId = String.valueOf(change.id());
        if (!ApprovalEntities.PROPOSED.equals(change.get("status"))) {
            ctx.reject(new Violation(CHANGE_ID, PlatformErrorCodes.CONTROL_CHANGE_NOT_PROPOSED,
                "Change " + changeId + " is " + change.get("status") + ", not proposed", Map.of("change", changeId)));
            return;
        }
        if (!ctx.request().actorId().equals(change.get("proposedBy"))) {
            ctx.reject(invalid(CHANGE_ID, "only the proposer withdraws a proposal"));
            return;
        }
        ctx.changes().update(ApprovalEntities.CONTROL_CHANGE, change.id(), change.version(),
            Map.of("status", ApprovalEntities.WITHDRAWN));
        ctx.put(OUTPUT, new ChangeOutput(changeId, ApprovalEntities.WITHDRAWN, change.get("targetId")));
    }

    /** A problem of a change (422 {@code CONTROL_CHANGE_INVALID}), for the targets as well. */
    public static Violation invalid(String field, String detail) {
        return new Violation(field, PlatformErrorCodes.CONTROL_CHANGE_INVALID, "Invalid change: " + detail,
            Map.of("detail", detail));
    }

    @Bean
    ProcessDefinition<ProposeInput, ChangeOutput, ProcessContext> controlChangeProposeProcess(ControlTargets targets) {
        return propose(targets);
    }

    @Bean
    ProcessDefinition<ChangeInput, ChangeOutput, ProcessContext> controlChangePublishProcess(ControlTargets targets) {
        return publish(targets);
    }

    @Bean
    ProcessDefinition<ChangeInput, ChangeOutput, ProcessContext> controlChangeWithdrawProcess() {
        return withdraw();
    }
}
