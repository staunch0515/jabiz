package com.jabiz.process;

import java.util.Objects;

/**
 * A step implementation together with its metadata, as returned by the factories of platform steps (for example
 * {@code LoadEntity.by(...)}), so that a generic step can be added without spelling out its type parameters.
 */
public record StepSpec<M, C extends ProcessContext>(Class<? extends StepImplementation<M, C>> handlerClass, M metadata) {

    public StepSpec {
        Objects.requireNonNull(handlerClass, "handlerClass must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
    }

    /**
     * Spec of a generic step class. The cast is unchecked by nature: a generic class literal has no type arguments.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <M, C extends ProcessContext> StepSpec<M, C> of(Class<? extends StepImplementation> handlerClass,
        M metadata) {
        return new StepSpec<>((Class) handlerClass, metadata);
    }
}
