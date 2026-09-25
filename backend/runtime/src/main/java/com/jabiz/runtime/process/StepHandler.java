package com.jabiz.runtime.process;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepImplementation;
import reactor.core.publisher.Mono;

/**
 * Executes one platform I/O step of a process. Implementations are singleton Spring beans and must be
 * stateless; all per-execution state lives in the context.
 *
 * <p>Platform-internal only: business code implements the synchronous step types instead
 * (docs/design/01-core-vs-runtime.md section 4), so it never handles {@code Mono}.
 *
 * @param <M> metadata type specific to the step
 * @param <C> context type the step runs against
 */
public interface StepHandler<M, C extends ProcessContext> extends StepImplementation<M, C> {

    /** Runs the step. A failed step signals an error, which aborts the whole process execution. */
    Mono<Void> execute(M metadata, C ctx);
}
