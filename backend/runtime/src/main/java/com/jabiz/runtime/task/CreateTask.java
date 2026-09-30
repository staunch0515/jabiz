package com.jabiz.runtime.task;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.process.steps.CheckedStep;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Creates a task in the process's transaction (docs/design/18-numbering-approvals-tasks.md section 5.1):
 * {@code .step("Ask for the invoice", CreateTask.of(ctx -> TaskSpec.forUser("invoice", "task.invoice", params, owner)
 * .source("order:" + id)))}. Its creation is published; with mail on, the assignees are notified after the commit.
 */
@Component
public class CreateTask<C extends ProcessContext> implements StepHandler<CreateTask.Metadata<C>, C>,
    CheckedStep<CreateTask.Metadata<C>> {

    /** @param when whether to create it at all (null: always) */
    public record Metadata<C>(Function<C, TaskSpec> task, Predicate<C> when) {
        public Metadata {
            Objects.requireNonNull(task, "task must not be null");
        }
    }

    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> of(Function<C, TaskSpec> task) {
        return StepSpec.of(CreateTask.class, new Metadata<>(task, null));
    }

    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> when(Predicate<C> condition,
        Function<C, TaskSpec> task) {
        return StepSpec.of(CreateTask.class, new Metadata<>(task, Objects.requireNonNull(condition)));
    }

    private final TaskWriter tasks;

    public CreateTask(TaskWriter tasks) {
        this.tasks = tasks;
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        return Mono.defer(() -> metadata.when() != null && !metadata.when().test(ctx) ? Mono.empty()
            : tasks.create(ctx, Objects.requireNonNull(metadata.task().apply(ctx), "task must not be null")));
    }

    @Override
    public List<String> problems(Metadata<C> metadata) {
        return tasks.publishes() ? List.of() : List.of("creates tasks but no EventPublisher is configured");
    }
}
