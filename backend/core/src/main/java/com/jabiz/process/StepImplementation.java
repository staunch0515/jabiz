package com.jabiz.process;

/**
 * Marker for the implementation of a process step, referenced by class from a {@link StepDefinition}.
 *
 * <p>It declares no methods so that the core stays independent of how steps are executed: the
 * runtime's reactive platform steps and the synchronous business steps (see
 * docs/design/06-process.md) each extend it with their own execution method.
 *
 * @param <M> metadata type the step accepts
 * @param <C> context type the step runs against
 */
public interface StepImplementation<M, C extends ProcessContext> {}
