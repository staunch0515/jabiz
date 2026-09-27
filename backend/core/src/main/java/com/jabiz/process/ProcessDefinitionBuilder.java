package com.jabiz.process;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
    private final Set<String> permissions = new LinkedHashSet<>();
    private boolean deprecated;
    private boolean internal;
    private ActsOn actsOn;

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

    /** The entity the process acts on; see {@link ProcessDefinition#actsOn(String, String, java.util.function.Consumer)}. */
    public ProcessDefinitionBuilder<I, O, C> actsOn(String entity, String input,
        java.util.function.Consumer<ActsOn.Builder> condition) {
        this.actsOn = ActsOn.of(entity, input, condition);
        return this;
    }

    /** The entity the process acts on, whose primary key is the input component {@code input}. */
    public ProcessDefinitionBuilder<I, O, C> actsOn(String entity, String input) {
        return actsOn(entity, input, null);
    }

    /** Permission codes the caller needs, all of them. A process without any fails startup outside development. */
    public ProcessDefinitionBuilder<I, O, C> permissions(String... codes) {
        Collections.addAll(permissions, codes);
        return this;
    }

    /**
     * Marks the process as run by the platform or a dedicated entry point only: it is left out of the process
     * catalog clients build forms from (decision D15). Its permissions are checked as usual.
     */
    public ProcessDefinitionBuilder<I, O, C> internal() {
        this.internal = true;
        return this;
    }

    /** Marks this version as superseded: it still runs, but processes calling it are reported at startup. */
    public ProcessDefinitionBuilder<I, O, C> deprecated() {
        this.deprecated = true;
        return this;
    }

    /**
     * Appends an in-transaction step. The compiler checks that the handler accepts exactly the given metadata
     * type and runs against this process's context type.
     */
    public <M> ProcessDefinitionBuilder<I, O, C> step(
        String stepName, Class<? extends StepImplementation<M, C>> handlerClass, M metadata
    ) {
        return add(new StepDefinition<>(stepName, handlerClass, metadata));
    }

    /** Appends an in-transaction platform step, as returned by the step's factory (for example {@code LoadEntity.by}). */
    public <M> ProcessDefinitionBuilder<I, O, C> step(String stepName, StepSpec<M, C> spec) {
        return add(new StepDefinition<>(stepName, spec.handlerClass(), spec.metadata()));
    }

    /** Appends a step that runs once the transaction has committed, retried as {@link RetryPolicy#DEFAULT}. */
    public <M> ProcessDefinitionBuilder<I, O, C> afterCommit(
        String stepName, Class<? extends StepImplementation<M, C>> handlerClass, M metadata
    ) {
        return afterCommit(stepName, handlerClass, metadata, RetryPolicy.DEFAULT);
    }

    public <M> ProcessDefinitionBuilder<I, O, C> afterCommit(
        String stepName, Class<? extends StepImplementation<M, C>> handlerClass, M metadata, RetryPolicy retryPolicy
    ) {
        return add(new StepDefinition<>(stepName, handlerClass, metadata, StepPhase.AFTER_COMMIT, retryPolicy, null));
    }

    public <M> ProcessDefinitionBuilder<I, O, C> afterCommit(String stepName, StepSpec<M, C> spec) {
        return afterCommit(stepName, spec.handlerClass(), spec.metadata(), RetryPolicy.DEFAULT);
    }

    /** Appends an in-transaction computation declared in place; it needs no bean. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ProcessDefinitionBuilder<I, O, C> compute(String stepName, ComputeStep<NoMetadata, C> computation) {
        Objects.requireNonNull(computation, "computation must not be null");
        Class<? extends StepImplementation<NoMetadata, C>> type = (Class) ComputeStep.class;
        return add(new StepDefinition<>(stepName, type, NoMetadata.INSTANCE, StepPhase.IN_TX, RetryPolicy.NONE,
            computation));
    }

    private ProcessDefinitionBuilder<I, O, C> add(StepDefinition<?, C> step) {
        if (!stepNames.add(step.stepName())) {
            throw new IllegalArgumentException("Process " + name + ": duplicate step name '" + step.stepName() + "'");
        }
        steps.add(step);
        return this;
    }

    ProcessDefinition<I, O, C> build() {
        return new ProcessDefinition<>(
            name, version, description, inputType, outputType, contextType,
            contextFactory, outputMapper, steps, permissions, deprecated, internal, actsOn);
    }
}
