package com.jabiz.process;

import reactor.core.publisher.Mono;

/**
 * Executes one step of a process. Implementations are singleton Spring beans and must be
 * stateless; all per-execution state lives in the context.
 *
 * @param <M> metadata type specific to the step
 * @param <C> context type the step runs against
 */
public interface StepHandler<M, C extends ProcessContext> {

    /** Runs the step. A failed step signals an error, which aborts the whole process execution. */
    Mono<Void> execute(M metadata, C ctx);
}
