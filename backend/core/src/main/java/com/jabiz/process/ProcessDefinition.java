package com.jabiz.process;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Immutable description of a process: its identity, its typed input and output, how the
 * execution context is created from the input, how the output is derived from the final
 * context, and its ordered steps.
 *
 * Usage:
 * <pre>
 * ProcessDefinition.define("SIGN_IN", 1, In.class, Out.class, Ctx.class, pb -&gt; pb
 *     .description("...")
 *     .contextFactory((seq, in) -&gt; new Ctx(seq))
 *     .outputMapper(ctx -&gt; new Out(...))
 *     .step("Authenticate", AuthHandler.class, new AuthMetadata(...)));
 * </pre>
 *
 * @param <I> process input type
 * @param <O> process output type
 * @param <C> context type shared by all steps
 */
public record ProcessDefinition<I, O, C extends ProcessContext>(
    String name,
    int version,
    String description,
    Class<I> inputType,
    Class<O> outputType,
    Class<C> contextType,
    ContextFactory<I, C> contextFactory,
    Function<C, O> outputMapper,
    List<StepDefinition<?, C>> steps
) {

    /** Creates the execution context for one run from the process input. */
    @FunctionalInterface
    public interface ContextFactory<I, C extends ProcessContext> {
        C create(long processSeqId, I input);
    }

    public ProcessDefinition {
        Objects.requireNonNull(name, "name must not be null");
        if (version <= 0) {
            throw new IllegalArgumentException("Process " + name + ": version must be positive");
        }
        Objects.requireNonNull(inputType, "inputType must not be null");
        Objects.requireNonNull(outputType, "outputType must not be null");
        Objects.requireNonNull(contextType, "contextType must not be null");
        Objects.requireNonNull(contextFactory, "contextFactory must not be null");
        Objects.requireNonNull(outputMapper, "outputMapper must not be null");
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("Process " + name + " needs at least one step");
        }
        steps = List.copyOf(steps);
    }

    public static <I, O, C extends ProcessContext> ProcessDefinition<I, O, C> define(
        String name,
        int version,
        Class<I> inputType,
        Class<O> outputType,
        Class<C> contextType,
        Consumer<ProcessDefinitionBuilder<I, O, C>> block
    ) {
        ProcessDefinitionBuilder<I, O, C> builder =
            new ProcessDefinitionBuilder<>(name, version, inputType, outputType, contextType);
        block.accept(builder);
        return builder.build();
    }
}
