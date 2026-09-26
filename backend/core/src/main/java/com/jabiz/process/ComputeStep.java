package com.jabiz.process;

/**
 * A business step that only computes: it reads the context, registers changes in {@link ProcessContext#changes()}
 * and reports broken rules in {@link ProcessContext#violations()}. It runs synchronously on the calling (reactive)
 * thread, so it must not perform any I/O (docs/design/06-process.md section 2). Implementations are singleton
 * beans and keep no per-execution state.
 *
 * @param <M> metadata type the step accepts
 * @param <C> context type the step runs against
 */
@FunctionalInterface
public interface ComputeStep<M, C extends ProcessContext> extends StepImplementation<M, C> {

    /** Runs the step. Throwing aborts the process; rule violations belong in {@link ProcessContext#violations()}. */
    void compute(M metadata, C ctx);
}
