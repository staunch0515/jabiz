package com.jabiz.app.it.fixture;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.task.CloseTasks;
import com.jabiz.runtime.task.CreateTask;
import com.jabiz.runtime.task.TaskSpec;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.Map;

/**
 * Processes of the task tests (docs/design/18-numbering-approvals-tasks.md section 5): one opens a task for a user or
 * the holders of a permission, the other closes the open tasks of a source key.
 */
public final class ItTaskFixtures {

    public static final String PERMISSION = "it.task";

    /** @param user the assignee, or null for {@code permission} */
    public record OpenInput(String user, String permission, @NotBlank String key, @NotBlank String item,
        Instant due) {}

    public record CloseInput(@NotBlank String key, Boolean cancel) {}

    public record Done(String key) {}

    public static final ProcessDefinition<OpenInput, Done, ProcessContext> OPEN =
        ProcessDefinition.define("IT_TASK_OPEN", 1, OpenInput.class, Done.class, ProcessContext.class, pb -> pb
            .permissions(PERMISSION)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put("input", input);
                return ctx;
            })
            .outputMapper(ctx -> new Done(ctx.get("input", OpenInput.class).key()))
            .step("Open the task", CreateTask.of(ctx -> {
                OpenInput input = ctx.get("input", OpenInput.class);
                TaskSpec spec = input.user() != null
                    ? TaskSpec.forUser("it.check", "it.task.check", Map.of("item", input.item()), input.user())
                    : TaskSpec.forPermission("it.check", "it.task.check", Map.of("item", input.item()),
                        input.permission());
                return spec.about("Thing", input.item()).link("/data").due(input.due()).source(input.key());
            })));

    public static final ProcessDefinition<CloseInput, Done, ProcessContext> CLOSE =
        ProcessDefinition.define("IT_TASK_CLOSE", 1, CloseInput.class, Done.class, ProcessContext.class, pb -> pb
            .permissions(PERMISSION)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put("input", input);
                return ctx;
            })
            .outputMapper(ctx -> new Done(ctx.get("input", CloseInput.class).key()))
            .step("Close the tasks", CloseTasks.done(ctx -> ctx.get("input", CloseInput.class).key())));

    private ItTaskFixtures() {}

    @Configuration
    static class Beans {

        @Bean
        ProcessDefinition<OpenInput, Done, ProcessContext> itTaskOpen() {
            return OPEN;
        }

        @Bean
        ProcessDefinition<CloseInput, Done, ProcessContext> itTaskClose() {
            return CLOSE;
        }
    }
}
