package com.jabiz.runtime.task;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.function.Function;

/**
 * Closes the open tasks of a source key in the process's transaction (docs/design/18-numbering-approvals-tasks.md
 * section 5.1): {@code CloseTasks.done(ctx -> "order:" + id)} when the work is done, {@code CloseTasks.cancel(...)}
 * when it is no longer needed. Nothing open: nothing to do.
 */
@Component
public class CloseTasks<C extends ProcessContext> implements StepHandler<CloseTasks.Metadata<C>, C> {

    public record Metadata<C>(Function<C, String> sourceKey, String status) {
        public Metadata {
            Objects.requireNonNull(sourceKey, "sourceKey must not be null");
            Objects.requireNonNull(status, "status must not be null");
        }
    }

    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> done(Function<C, String> sourceKey) {
        return StepSpec.of(CloseTasks.class, new Metadata<>(sourceKey, TaskEntities.DONE));
    }

    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> cancel(Function<C, String> sourceKey) {
        return StepSpec.of(CloseTasks.class, new Metadata<>(sourceKey, TaskEntities.CANCELLED));
    }

    private final TaskWriter tasks;

    public CloseTasks(TaskWriter tasks) {
        this.tasks = tasks;
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        return Mono.defer(() -> tasks.close(ctx, Objects.requireNonNull(metadata.sourceKey().apply(ctx),
            "sourceKey must not be null"), metadata.status()));
    }
}
