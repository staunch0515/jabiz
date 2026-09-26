package com.jabiz.runtime.process.steps;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.ProcessRegistry;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Calls another process as a sub-process (docs/design/06-process.md section 5): same transaction, its own
 * {@code process_seq_id} with {@code parent_seq_id} pointing at the caller, the caller's operation time. A failing
 * sub-process fails the caller, and everything is rolled back. The call graph must be acyclic (startup check).
 */
@Component
public class CallProcess<C extends ProcessContext> implements StepHandler<CallProcess.Metadata<C>, C>,
    CheckedStep<CallProcess.Metadata<C>> {

    /**
     * @param version   version to call; null for the latest one
     * @param input     builds the sub-process input from the caller's context
     * @param outputKey context key receiving the sub-process output; null to discard it
     * @param condition whether to call at all, decided on the caller's context when the step is reached (for
     *                  example: only when there is something to post); always, if null
     */
    public record Metadata<C>(String processName, Integer version, Function<C, ?> input, String outputKey,
        Predicate<C> condition) {
        public Metadata {
            Objects.requireNonNull(processName, "processName must not be null");
            Objects.requireNonNull(input, "input must not be null");
            condition = condition == null ? ctx -> true : condition;
        }

        public Metadata(String processName, Integer version, Function<C, ?> input, String outputKey) {
            this(processName, version, input, outputKey, null);
        }

        public String target() {
            return processName + "@" + (version == null ? "latest" : version);
        }
    }

    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> of(String processName, int version,
        Function<C, ?> input, String outputKey) {
        return StepSpec.of(CallProcess.class, new Metadata<>(processName, version, input, outputKey));
    }

    /** Calls the process only when {@code condition} holds on the caller's context; otherwise the step does nothing. */
    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> when(Predicate<C> condition, String processName,
        int version, Function<C, ?> input, String outputKey) {
        return StepSpec.of(CallProcess.class, new Metadata<>(processName, version, input, outputKey,
            Objects.requireNonNull(condition, "condition must not be null")));
    }

    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> latest(String processName,
        Function<C, ?> input, String outputKey) {
        return StepSpec.of(CallProcess.class, new Metadata<>(processName, null, input, outputKey));
    }

    private final ProcessRegistry registry;
    private final ProcessExecutor executor;

    public CallProcess(ProcessRegistry registry, ProcessExecutor executor) {
        this.registry = registry;
        this.executor = executor;
    }

    /** The process a use of this step calls, resolved as it would be now. */
    public static Optional<ProcessDefinition<?, ?, ?>> resolve(ProcessRegistry registry, Metadata<?> metadata) {
        return metadata.version() == null
            ? registry.findLatest(metadata.processName())
            : registry.find(metadata.processName(), metadata.version());
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        return Mono.defer(() -> {
            if (!metadata.condition().test(ctx)) {
                return Mono.empty();
            }
            ProcessDefinition<?, ?, ?> target = resolve(registry, metadata).orElseThrow(
                () -> new IllegalStateException("Unknown process " + metadata.target()));
            return call(target, metadata.input().apply(ctx))
                .doOnNext(output -> {
                    if (metadata.outputKey() != null) {
                        ctx.put(metadata.outputKey(), output);
                    }
                })
                .then();
        });
    }

    private <I, O, D extends ProcessContext> Mono<O> call(ProcessDefinition<I, O, D> target, Object input) {
        if (!target.inputType().isInstance(input)) {
            return Mono.error(new IllegalStateException("Process " + target.name() + " v" + target.version()
                + " takes " + target.inputType().getName() + " but was given "
                + (input == null ? "null" : input.getClass().getName())));
        }
        return executor.executeChild(target, target.inputType().cast(input));
    }

    @Override
    public List<String> problems(Metadata<C> metadata) {
        return resolve(registry, metadata).isPresent()
            ? List.of()
            : List.of("calls unknown process " + metadata.target());
    }
}
