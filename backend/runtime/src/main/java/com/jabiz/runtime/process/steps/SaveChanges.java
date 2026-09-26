package com.jabiz.runtime.process.steps;

import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.process.ChangeSetCommitter;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Commits the changes registered so far, before the process ends (docs/design/06-process.md section 2.1): later
 * steps then see stored states, generated values and versions in {@code ctx.changes().saved()}. Rarely needed, since
 * the platform commits at the end anyway. Refuses to commit while violations are collected: the process is going
 * to fail, and later steps may rely on the data being valid.
 */
@Component
public class SaveChanges<C extends ProcessContext> implements StepHandler<NoMetadata, C> {

    public static <C extends ProcessContext> StepSpec<NoMetadata, C> now() {
        return StepSpec.of(SaveChanges.class, NoMetadata.INSTANCE);
    }

    private final ChangeSetCommitter committer;

    public SaveChanges(ChangeSetCommitter committer) {
        this.committer = committer;
    }

    @Override
    public Mono<Void> execute(NoMetadata metadata, C ctx) {
        return Mono.defer(() -> ctx.hasViolations()
            ? Mono.error(new BusinessRuleViolationException(List.copyOf(ctx.violations())))
            : committer.commit(ctx));
    }
}
