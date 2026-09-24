package com.jabiz.process;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Fluent builder used through {@link ProcessDefinition#define}. */
public final class ProcessDefinitionBuilder<I, O, C extends ProcessContext> {

    private final String name;
    private final int version;
    private final Class<I> inputType;
    private final Class<O> outputType;
    private final Class<C> contextType;
    private String description;
    private ProcessDefinition.ContextFactory<I, C> contextFactory;
    private Function<C, O> outputMapper;
    private final List<StepDefinition<?, C>> steps = new ArrayList<>();
    private final Set<String> stepNames = new HashSet<>();

    ProcessDefinitionBuilder(String name, int version, Class<I> inputType, Class<O> outputType, Class<C> contextType) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.version = version;
        this.inputType = inputType;
        this.outputType = outputType;
        this.contextType = contextType;
    }

    public ProcessDefinitionBuilder<I, O, C> description(String description) {
        this.description = description;
        return this;
    }

    public ProcessDefinitionBuilder<I, O, C> contextFactory(ProcessDefinition.ContextFactory<I, C> contextFactory) {
        this.contextFactory = contextFactory;
        return this;
    }

    public ProcessDefinitionBuilder<I, O, C> outputMapper(Function<C, O> outputMapper) {
        this.outputMapper = outputMapper;
        return this;
    }

    /**
     * Appends a step. The compiler checks that the handler accepts exactly the given metadata
     * type and runs against this process's context type.
     */
    public <M> ProcessDefinitionBuilder<I, O, C> step(
        String stepName, Class<? extends StepHandler<M, C>> handlerClass, M metadata
    ) {
        StepDefinition<M, C> step = new StepDefinition<>(stepName, handlerClass, metadata);
        if (!stepNames.add(stepName)) {
            throw new IllegalArgumentException("Process " + name + ": duplicate step name '" + stepName + "'");
        }
        steps.add(step);
        return this;
    }

    ProcessDefinition<I, O, C> build() {
        return new ProcessDefinition<>(
            name, version, description, inputType, outputType, contextType,
            contextFactory, outputMapper, steps);
    }
}
