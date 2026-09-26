package com.jabiz.runtime.process;

import com.jabiz.process.BlockingStep;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.process.StepImplementation;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.EventPublisher;
import com.jabiz.runtime.process.steps.PublishEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class ProcessChecksTest {

    record Out(long seq) {}

    /** Implements no execution method: no executor supports it. */
    static final class UnsupportedStep implements StepImplementation<NoMetadata, ProcessContext> {}

    static final class MarkStep implements StepHandler<NoMetadata, ProcessContext> {
        @Override
        public Mono<Void> execute(NoMetadata metadata, ProcessContext ctx) {
            return Mono.empty();
        }
    }

    static final class SleepStep implements BlockingStep<NoMetadata, ProcessContext> {
        @Override
        public void run(NoMetadata metadata, ProcessContext ctx) {}
    }

    private static ProcessDefinition<String, Out, ProcessContext> process(String name, int version,
        Consumer<ProcessDefinitionBuilder<String, Out, ProcessContext>> steps) {
        return ProcessDefinition.define(name, version, String.class, Out.class, ProcessContext.class, pb -> {
            pb.contextFactory((start, in) -> new ProcessContext(start))
                .outputMapper(ctx -> new Out(ctx.processSeqId()));
            steps.accept(pb);
        });
    }

    private static List<CheckProblem> check(boolean development, ProcessDefinition<?, ?, ?>... definitions) {
        StaticListableBeanFactory declared = new StaticListableBeanFactory();
        for (int i = 0; i < definitions.length; i++) {
            declared.addBean("process" + i, definitions[i]);
        }
        ProcessRegistry registry = new ProcessRegistry(
            declared.getBeanProvider(ResolvableType.forClass(ProcessDefinition.class)));
        StaticApplicationContext beans = new StaticApplicationContext();
        beans.registerSingleton("mark", MarkStep.class);
        beans.getBeanFactory().registerSingleton("call", new CallProcess<>(registry, null));
        beans.refresh();
        beans.getBeanFactory().registerSingleton("publish",
            new PublishEvent<>(beans.getBeanProvider(EventPublisher.class)));
        MockEnvironment environment = new MockEnvironment();
        if (development) {
            environment.setActiveProfiles("dev");
        }
        return new ProcessChecks(registry, beans, environment).check();
    }

    @Test
    void wellFormedProcessesPass() {
        ProcessDefinition<String, Out, ProcessContext> child = process("CHILD", 1, pb -> pb
            .permissions("child.run")
            .step("Mark", MarkStep.class, NoMetadata.INSTANCE));
        ProcessDefinition<String, Out, ProcessContext> parent = process("PARENT", 1, pb -> pb
            .permissions("parent.run")
            .compute("Inline", (metadata, ctx) -> {})
            .step("Call", CallProcess.latest("CHILD", ctx -> "x", "child")));

        assertThat(check(false, child, parent)).isEmpty();
    }

    @Test
    void unsupportedStepTypesAndMissingBeansAreErrors() {
        ProcessDefinition<String, Out, ProcessContext> definition = process("P", 1, pb -> pb
            .permissions("p.run")
            .step("Unsupported", UnsupportedStep.class, NoMetadata.INSTANCE)
            .step("Sleep", SleepStep.class, NoMetadata.INSTANCE));

        assertThat(check(false, definition)).extracting(CheckProblem::location, CheckProblem::message)
            .containsExactly(
                tuple("P@1 step 'Unsupported'",
                    UnsupportedStep.class.getName() + " is neither a StepHandler, a ComputeStep nor a BlockingStep"),
                tuple("P@1 step 'Sleep'",
                    "expected exactly one bean of type " + SleepStep.class.getName() + " but found 0"));
    }

    @Test
    void missingPermissionsAreErrorsExceptInDevelopment() {
        ProcessDefinition<String, Out, ProcessContext> definition = process("OPEN", 1, pb -> pb
            .step("Mark", MarkStep.class, NoMetadata.INSTANCE));

        assertThat(check(false, definition)).singleElement().satisfies(problem -> {
            assertThat(problem.severity()).isEqualTo(CheckProblem.Severity.ERROR);
            assertThat(problem.message()).isEqualTo("declares no permissions");
        });
        assertThat(check(true, definition)).singleElement()
            .extracting(CheckProblem::severity).isEqualTo(CheckProblem.Severity.WARNING);
    }

    @Test
    void callCyclesAndUnknownTargetsAreErrors() {
        ProcessDefinition<String, Out, ProcessContext> a = process("A", 1, pb -> pb
            .permissions("a").step("Call B", CallProcess.of("B", 1, ctx -> "x", null)));
        ProcessDefinition<String, Out, ProcessContext> b = process("B", 1, pb -> pb
            .permissions("b").step("Call A", CallProcess.latest("A", ctx -> "x", null)));
        ProcessDefinition<String, Out, ProcessContext> c = process("C", 1, pb -> pb
            .permissions("c").step("Call nothing", CallProcess.of("NOPE", 3, ctx -> "x", null)));

        assertThat(check(false, a, b, c)).extracting(CheckProblem::message).containsExactlyInAnyOrder(
            "calls unknown process NOPE@3",
            "call cycle: A@1 -> B@1 -> A@1");
    }

    @Test
    void selfCallIsACycle() {
        ProcessDefinition<String, Out, ProcessContext> loop = process("LOOP", 1, pb -> pb
            .permissions("l").step("Again", CallProcess.of("LOOP", 1, ctx -> "x", null)));

        assertThat(check(false, loop)).extracting(CheckProblem::message).containsExactly("call cycle: LOOP@1 -> LOOP@1");
    }

    @Test
    void callingADeprecatedVersionIsAWarning() {
        ProcessDefinition<String, Out, ProcessContext> old = process("OLD", 1, pb -> pb
            .permissions("o").deprecated().step("Mark", MarkStep.class, NoMetadata.INSTANCE));
        ProcessDefinition<String, Out, ProcessContext> caller = process("CALLER", 1, pb -> pb
            .permissions("c").step("Call old", CallProcess.of("OLD", 1, ctx -> "x", null)));

        assertThat(check(false, old, caller)).singleElement().satisfies(problem -> {
            assertThat(problem.severity()).isEqualTo(CheckProblem.Severity.WARNING);
            assertThat(problem.message()).isEqualTo("calls deprecated process OLD@1");
        });
    }

    @Test
    void publishingNeedsAnEventPublisher() {
        ProcessDefinition<String, Out, ProcessContext> definition = process("EMIT", 1, pb -> pb
            .permissions("e").step("Emit", PublishEvent.of("order.created", ctx -> "payload")));

        assertThat(check(false, definition)).extracting(CheckProblem::message).containsExactly(
            "publishes order.created but no EventPublisher is configured (outbox: phase 9)");
    }
}
