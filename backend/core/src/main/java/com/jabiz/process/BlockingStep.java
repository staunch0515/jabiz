package com.jabiz.process;

/**
 * A business step that calls a service only reachable through a blocking client. The platform runs it on a
 * virtual thread (Reactor's {@code boundedElastic}), never on an event loop thread.
 *
 * <p>It must not access the platform's database: it writes its results to the context, and platform steps persist
 * them. A step with external side effects must run {@link StepPhase#AFTER_COMMIT} unless the external call is
 * idempotent, since a rollback cannot undo it (docs/design/06-process.md section 4).
 *
 * @param <M> metadata type the step accepts
 * @param <C> context type the step runs against
 */
@FunctionalInterface
public interface BlockingStep<M, C extends ProcessContext> extends StepImplementation<M, C> {

    void run(M metadata, C ctx) throws Exception;
}
