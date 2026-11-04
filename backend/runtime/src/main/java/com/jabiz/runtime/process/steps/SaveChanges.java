package com.jabiz.runtime.process.steps;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.process.ChangeSetCommitter;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Commits the changes registered so far, before the process ends (docs/design/06-process.md section 2.1): later
 * steps then see stored states, generated values and versions in {@code ctx.changes().saved()}. Rarely needed, since
 * the platform commits at the end anyway. Refuses to commit while violations are collected: the process is going
 * to fail, and later steps may rely on the data being valid.
 */
@Component
public class SaveChanges<C extends ProcessContext> implements StepHandler<SaveChanges.Metadata<C>, C> {

    /** @param condition whether to commit at all, decided on the context when the step is reached */
    public record Metadata<C>(Predicate<C> condition) {
        public Metadata {
            Objects.requireNonNull(condition, "condition must not be null");
        }
    }

    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> now() {
        return StepSpec.of(SaveChanges.class, new Metadata<C>(ctx -> true));
    }

    /**
     * Commits only when {@code condition} holds; otherwise the step does nothing and the changes are committed with
     * the process, as usual. For a commit that only some inputs need (a sub-process that reads what this process
     * registered, such as a posting whose source is the row just inserted) on a path whose ordinary case should keep
     * its writes where they are.
     */
    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> when(Predicate<C> condition) {
        return StepSpec.of(SaveChanges.class, new Metadata<>(condition));
    }

    private final ChangeSetCommitter committer;

    public SaveChanges(ChangeSetCommitter committer) {
        this.committer = committer;
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        return Mono.defer(() -> {
            if (!metadata.condition().test(ctx)) {
                return Mono.<Void>empty();
            }
            return ctx.hasViolations()
                ? Mono.error(new BusinessRuleViolationException(List.copyOf(ctx.violations())))
                : committer.commit(ctx);
        });
    }
}
