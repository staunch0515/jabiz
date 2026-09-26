package com.jabiz.process;

import java.util.Objects;

/**
 * One step of a process. The handler type and the metadata type are bound to each other
 * through {@code M}, so a step cannot be declared with metadata its handler does not accept.
 *
 * @param handlerClass handler bean type (a class reference, not a string)
 * @param metadata     step-specific configuration passed to the handler on every execution
 * @param phase        whether the step runs inside the transaction or after it has committed
 * @param retryPolicy  attempts of an {@link StepPhase#AFTER_COMMIT} step; {@link RetryPolicy#NONE} otherwise
 * @param inline       an implementation declared in place (a lambda) rather than as a bean; null for bean steps
 */
public record StepDefinition<M, C extends ProcessContext>(
    String stepName,
    Class<? extends StepImplementation<M, C>> handlerClass,
    M metadata,
    StepPhase phase,
    RetryPolicy retryPolicy,
    StepImplementation<M, C> inline
) {
    public StepDefinition {
        Objects.requireNonNull(stepName, "stepName must not be null");
        Objects.requireNonNull(handlerClass, "handlerClass must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
        Objects.requireNonNull(phase, "phase must not be null");
        Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
        if (phase == StepPhase.IN_TX && retryPolicy.maxAttempts() != 1) {
            throw new IllegalArgumentException("Step '" + stepName + "': only after-commit steps are retried");
        }
    }

    /** An in-transaction bean step. */
    public StepDefinition(String stepName, Class<? extends StepImplementation<M, C>> handlerClass, M metadata) {
        this(stepName, handlerClass, metadata, StepPhase.IN_TX, RetryPolicy.NONE, null);
    }

    public boolean isInline() {
        return inline != null;
    }
}
