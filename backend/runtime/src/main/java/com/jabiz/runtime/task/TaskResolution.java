package com.jabiz.runtime.task;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.LoadEntity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A person closes a task by hand (docs/design/18-numbering-approvals-tasks.md section 5.3a, phase 16y): work that no
 * process will ever close, such as a reconciliation check that found nothing to do. {@code TASK_RESOLVE} closes an
 * open task the caller can see (assigned to them, or to a permission they hold; {@code *} holds every one) as
 * {@code DONE} or {@code CANCELLED}, with a note, and records who closed it; the audit records the change. Another
 * person's task, or none, is the same 404. An approval's task is closed only by deciding the approval.
 */
@Configuration
public class TaskResolution {

    public static final String RESOLVE = "TASK_RESOLVE";

    /** The longest note. */
    public static final int NOTE_LENGTH = 1000;

    /**
     * @param outcome {@code DONE} (the work is done) or {@code CANCELLED} (it is not needed)
     * @param note    why, for whoever reads the task later
     */
    public record ResolveInput(@NotNull UUID taskId, @NotNull @Pattern(regexp = "DONE|CANCELLED") String outcome,
        @NotBlank @Size(max = NOTE_LENGTH) String note) {}

    public record ResolveOutput(String taskId, String status) {}

    private static final String TASK_ID = "taskId";
    private static final String TASK = "task";
    private static final String INPUT = "input";

    static ProcessDefinition<ResolveInput, ResolveOutput, ProcessContext> resolve() {
        return ProcessDefinition.define(RESOLVE, 1, ResolveInput.class, ResolveOutput.class, ProcessContext.class,
            pb -> pb
                .description("Closes an open task the caller can see, with a note.")
                .permissions(TaskPermissions.RESOLVE)
                // No actsOn row action: the generic data view cannot tell an approval's or another person's task;
                // the page /tasks offers it on the caller's own open tasks.
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    ctx.put(TASK_ID, input.taskId());
                    return ctx;
                })
                .outputMapper(ctx -> {
                    EntityInstance task = ctx.get(TASK, EntityInstance.class);
                    return new ResolveOutput(String.valueOf(task.id()), ctx.get(INPUT, ResolveInput.class).outcome());
                })
                .step("Load the task", LoadEntity.by(TaskEntities.TASK_DATASET, TASK_ID, TASK))
                .compute("Resolve it", (metadata, ctx) -> resolve(ctx)));
    }

    static void resolve(ProcessContext ctx) {
        ResolveInput input = ctx.get(INPUT, ResolveInput.class);
        EntityInstance task = ctx.get(TASK, EntityInstance.class);
        if (!visible(task, ctx.request())) {
            // As GET /api/tasks/mine: another person's work is not said to exist.
            throw new EntityNotFoundException(TaskEntities.TASK + " " + input.taskId() + " not found");
        }
        if (TaskTitles.APPROVAL_TYPE.equals(task.get("taskType"))) {
            ctx.reject(new Violation("taskId", PlatformErrorCodes.TASK_CLOSED_BY_ITS_PROCESS, "An approval's task "
                + "closes when the approval is decided", Map.of()));
            return;
        }
        if (!TaskEntities.OPEN.equals(task.get("status"))) {
            ctx.reject(new Violation("taskId", PlatformErrorCodes.TASK_NOT_OPEN, "The task is closed already",
                Map.of("status", String.valueOf((Object) task.get("status")))));
            return;
        }
        Map<String, Object> closed = new LinkedHashMap<>();
        closed.put("status", input.outcome());
        closed.put("closedBy", ctx.request().actorId());
        closed.put("closeNote", input.note().strip());
        ctx.changes().update(TaskEntities.TASK, task.id(), task.version(), closed);
    }

    /** Whether the caller sees the task among their own: the rule of {@code GET /api/tasks/mine}. */
    static boolean visible(EntityInstance task, RequestContext request) {
        String user = task.get("assigneeUserId");
        String permission = task.get("assigneePermission");
        return (user != null && user.equals(request.actorId()))
            || (permission != null && request.hasPermission(permission));
    }

    @Bean
    ProcessDefinition<ResolveInput, ResolveOutput, ProcessContext> taskResolveProcess() {
        return resolve();
    }
}
