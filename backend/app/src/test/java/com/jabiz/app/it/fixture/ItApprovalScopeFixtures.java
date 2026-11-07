package com.jabiz.app.it.fixture;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.approval.ApprovalScopeMove;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.task.TaskEntities;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * Processes of the scope move test (decision D76): an application's reassignment process calling
 * {@code APPROVAL_SCOPE_MOVE} as its sub-process, and a process that tries to change a task's scope itself.
 */
public final class ItApprovalScopeFixtures {

    public static final String REASSIGN = "IT_REASSIGN";
    public static final String RESCOPE_TASK = "IT_TASK_RESCOPE";
    public static final String PERMISSION = "it.reassign";

    public record RescopeInput(@NotBlank String sourceKey, @NotBlank String scopeValue) {}

    public record RescopeOutput(String taskId) {}

    /** Passes its input on to {@code APPROVAL_SCOPE_MOVE}; the application's own checks would come first. */
    public static final ProcessDefinition<ApprovalScopeMove.MoveInput, ApprovalScopeMove.MoveOutput, ProcessContext>
        REASSIGN_PROCESS = ProcessDefinition.define(REASSIGN, 1, ApprovalScopeMove.MoveInput.class,
        ApprovalScopeMove.MoveOutput.class, ProcessContext.class, pb -> pb
            .permissions(PERMISSION)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put("input", input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get("moved", ApprovalScopeMove.MoveOutput.class))
            .step("Move the approvals", CallProcess.of(ApprovalScopeMove.MOVE, 1, ctx -> ctx.get("input"),
                "moved")));

    /** Changes the scope value of the open task of a source key without the move's grant. */
    @SuppressWarnings("unchecked")
    public static final ProcessDefinition<RescopeInput, RescopeOutput, ProcessContext> RESCOPE_PROCESS =
        ProcessDefinition.define(RESCOPE_TASK, 1, RescopeInput.class, RescopeOutput.class, ProcessContext.class,
            pb -> pb
                .permissions(PERMISSION)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("input", input);
                    return ctx;
                })
                .outputMapper(ctx -> new RescopeOutput(String.valueOf(ctx.get("taskId"))))
                .step("Load the task", QueryEntities.<ProcessContext>of(TaskEntities.TASK_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("sourceKey", ctx.get("input", RescopeInput.class).sourceKey()),
                        new QueryPredicate.Eq("status", TaskEntities.OPEN)))).limit(1).build(), "tasks"))
                .compute("Change its scope", (metadata, ctx) -> {
                    EntityInstance task = ((List<EntityInstance>) ctx.get("tasks")).getFirst();
                    ctx.put("taskId", task.id());
                    ctx.changes().update(TaskEntities.TASK, task.id(), task.version(),
                        Map.of("scopeValue", ctx.get("input", RescopeInput.class).scopeValue()));
                }));

    private ItApprovalScopeFixtures() {}

    @Configuration
    static class Beans {

        @Bean
        ProcessDefinition<ApprovalScopeMove.MoveInput, ApprovalScopeMove.MoveOutput, ProcessContext> itReassign() {
            return REASSIGN_PROCESS;
        }

        @Bean
        ProcessDefinition<RescopeInput, RescopeOutput, ProcessContext> itTaskRescope() {
            return RESCOPE_PROCESS;
        }
    }
}
