package com.jabiz.runtime.process;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.StepDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runs process definitions: creates the context from the input, executes the steps strictly
 * one after another, and maps the final context to the process output. The first failing step
 * aborts the execution and its error is propagated unchanged, so domain exceptions keep their
 * type.
 *
 * At startup (as a {@link PlatformCheck}) the executor verifies that every step handler of every registered process is
 * available as exactly one bean.
 */
@Component
public class ProcessExecutor implements PlatformCheck {

    private static final Logger log = LoggerFactory.getLogger(ProcessExecutor.class);

    private final ProcessRegistry registry;
    private final ApplicationContext beans;
    private final ProcessSequence sequence;

    public ProcessExecutor(ProcessRegistry registry, ApplicationContext beans, ProcessSequence sequence) {
        this.registry = registry;
        this.beans = beans;
        this.sequence = sequence;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        for (ProcessDefinition<?, ?, ?> definition : registry.all()) {
            for (StepDefinition<?, ?> step : definition.steps()) {
                String location = definition.name() + " v" + definition.version() + " step '" + step.stepName() + "'";
                if (!StepHandler.class.isAssignableFrom(step.handlerClass())) {
                    problems.add(CheckProblem.error("PROCESS", location, step.handlerClass().getName()
                        + " is not a " + StepHandler.class.getSimpleName() + "; no executor supports this step type"));
                    continue;
                }
                int count = beans.getBeanNamesForType(step.handlerClass()).length;
                if (count != 1) {
                    problems.add(CheckProblem.error("PROCESS", location, "expected exactly one bean of type "
                        + step.handlerClass().getName() + " but found " + count));
                }
            }
        }
        return problems;
    }

    public <I, O, C extends ProcessContext> Mono<O> execute(ProcessDefinition<I, O, C> definition, I input) {
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(input, "input must not be null");
        return sequence.next().flatMap(seq -> Mono.defer(() -> {
            C ctx = definition.contextFactory().create(seq, input);
            return Flux.fromIterable(definition.steps())
                .concatMap(step -> runStep(definition, step, ctx))
                .then(Mono.fromSupplier(() -> Objects.requireNonNull(
                    definition.outputMapper().apply(ctx),
                    "Process " + definition.name() + ": output mapper returned null")));
        }));
    }

    private <M, C extends ProcessContext> Mono<Void> runStep(
        ProcessDefinition<?, ?, C> definition, StepDefinition<M, C> step, C ctx
    ) {
        return Mono.defer(() -> {
            // Checked at startup: every step implementation is a StepHandler.
            @SuppressWarnings("unchecked")
            StepHandler<M, C> handler = (StepHandler<M, C>) beans.getBean(step.handlerClass());
            return handler.execute(step.metadata(), ctx);
        }).doOnError(error -> log.warn("Process {} v{} (seq {}) failed at step '{}': {}",
            definition.name(), definition.version(), ctx.processSeqId(), step.stepName(), error.toString()));
    }
}
