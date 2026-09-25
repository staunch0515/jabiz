package com.jabiz.process;

import java.util.Objects;

/**
 * One step of a process. The handler type and the metadata type are bound to each other
 * through {@code M}, so a step cannot be declared with metadata its handler does not accept.
 *
 * @param handlerClass handler bean type (a class reference, not a string)
 * @param metadata     step-specific configuration passed to the handler on every execution
 */
public record StepDefinition<M, C extends ProcessContext>(
    String stepName,
    Class<? extends StepImplementation<M, C>> handlerClass,
    M metadata
) {
    public StepDefinition {
        Objects.requireNonNull(stepName, "stepName must not be null");
        Objects.requireNonNull(handlerClass, "handlerClass must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
    }
}
