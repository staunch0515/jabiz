package com.jabiz.runtime.approval;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.task.TaskEntities;
import com.jabiz.runtime.task.TaskSpec;
import com.jabiz.runtime.task.TaskWriter;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * The tasks of approval requests (docs/design/18-numbering-approvals-tasks.md section 5.2): one open task per pending
 * request, for the holders of the current level's permission, done in {@code /tasks}. A decided level closes it and,
 * while levels remain, opens the next level's; a superseded or withdrawn request cancels it. As a step of
 * {@code APPROVAL_DECIDE} it passes the task on after the decision.
 */
@Component
class ApprovalTasks<C extends ProcessContext> implements StepHandler<ApprovalTasks.Metadata, C> {

    /** Type of the tasks of approval requests. */
    static final String TYPE = "approval";
    static final String TITLE = "task.approval";
    static final String LINK = "/tasks";

    /** What {@code APPROVAL_DECIDE} decided: {@code nextPermission} null when no level remains. */
    record Passed(String requestId, String subject, String entityId, int nextLevel, String nextPermission) {}

    record Metadata(String passedKey) {}

    static <C extends ProcessContext> StepSpec<Metadata, C> passOn(String passedKey) {
        return StepSpec.of(ApprovalTasks.class, new Metadata(passedKey));
    }

    static String sourceKey(String requestId) {
        return "approval:" + requestId;
    }

    static TaskSpec task(String subject, String entityId, String requestId, int level, String permission) {
        return TaskSpec.forPermission(TYPE, TITLE, Map.of("subject", subject, "entity", entityId, "level",
                String.valueOf(level)), permission)
            .about(ApprovalEntities.REQUEST, requestId).link(LINK).source(sourceKey(requestId));
    }

    private final TaskWriter tasks;

    ApprovalTasks(TaskWriter tasks) {
        this.tasks = tasks;
    }

    @Override
    public Mono<Void> execute(Metadata metadata, C ctx) {
        return Mono.defer(() -> {
            if (!ctx.contains(metadata.passedKey())) {
                return Mono.empty();
            }
            Passed passed = ctx.get(metadata.passedKey(), Passed.class);
            Mono<Void> closed = tasks.close(ctx, sourceKey(passed.requestId()), TaskEntities.DONE);
            return passed.nextPermission() == null ? closed : closed.then(tasks.create(ctx,
                task(passed.subject(), passed.entityId(), passed.requestId(), passed.nextLevel(),
                    passed.nextPermission())));
        });
    }
}
