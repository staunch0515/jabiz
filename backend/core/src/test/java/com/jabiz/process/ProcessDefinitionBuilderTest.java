package com.jabiz.process;

import com.jabiz.context.RequestContext;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessDefinitionBuilderTest {

    record In(String value) {}
    record Out(long seq) {}
    record Tag(String name) {}

    static final class NoopStep implements StepImplementation<NoMetadata, ProcessContext> {}

    static final class TagStep implements StepImplementation<Tag, ProcessContext> {}

    private static ProcessDefinition<In, Out, ProcessContext> define(
        java.util.function.Consumer<ProcessDefinitionBuilder<In, Out, ProcessContext>> block) {
        return ProcessDefinition.define("TEST_PROCESS", 1, In.class, Out.class, ProcessContext.class, block);
    }

    private static ProcessDefinitionBuilder<In, Out, ProcessContext> complete(
        ProcessDefinitionBuilder<In, Out, ProcessContext> pb) {
        return pb.contextFactory((start, in) -> new ProcessContext(start))
            .outputMapper(ctx -> new Out(ctx.processSeqId()));
    }

    @Test
    void buildsDefinitionWithStepsInDeclarationOrder() {
        ProcessDefinition<In, Out, ProcessContext> def = define(pb -> complete(pb)
            .description("does things")
            .step("First", NoopStep.class, NoMetadata.INSTANCE)
            .step("Second", TagStep.class, new Tag("seen")));

        assertThat(def.name()).isEqualTo("TEST_PROCESS");
        assertThat(def.version()).isEqualTo(1);
        assertThat(def.description()).isEqualTo("does things");
        assertThat(def.inputType()).isEqualTo(In.class);
        assertThat(def.outputType()).isEqualTo(Out.class);
        assertThat(def.contextType()).isEqualTo(ProcessContext.class);
        assertThat(def.steps()).extracting(StepDefinition::stepName).containsExactly("First", "Second");
        assertThat(def.steps().get(1).handlerClass()).isEqualTo(TagStep.class);
        assertThat(def.steps().get(1).metadata()).isEqualTo(new Tag("seen"));
        assertThat(def.contextFactory().create(start(7L), new In("x")).processSeqId()).isEqualTo(7L);
        assertThat(def.outputMapper().apply(new ProcessContext(start(9L)))).isEqualTo(new Out(9L));
    }

    @Test
    void stepsAreImmutable() {
        ProcessDefinition<In, Out, ProcessContext> def = define(pb -> complete(pb)
            .step("Only", NoopStep.class, NoMetadata.INSTANCE));

        assertThatThrownBy(() -> def.steps().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void duplicateStepNameIsRejected() {
        assertThatThrownBy(() -> define(pb -> complete(pb)
            .step("Same", NoopStep.class, NoMetadata.INSTANCE)
            .step("Same", TagStep.class, new Tag("t"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("duplicate step name 'Same'");
    }

    @Test
    void processNeedsAtLeastOneStep() {
        assertThatThrownBy(() -> define(ProcessDefinitionBuilderTest::complete))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("needs at least one step");
    }

    @Test
    void versionMustBePositive() {
        assertThatThrownBy(() -> ProcessDefinition.define("P", 0, In.class, Out.class, ProcessContext.class,
            pb -> complete(pb).step("S", NoopStep.class, NoMetadata.INSTANCE)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("version must be positive");
    }

    @Test
    void nameIsRequired() {
        assertThatThrownBy(() -> ProcessDefinition.define(null, 1, In.class, Out.class, ProcessContext.class, pb -> {}))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("name");
    }

    @Test
    void contextFactoryAndOutputMapperAreRequired() {
        assertThatThrownBy(() -> define(pb -> pb
            .outputMapper(ctx -> new Out(0))
            .step("S", NoopStep.class, NoMetadata.INSTANCE)))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("contextFactory");
        assertThatThrownBy(() -> define(pb -> pb
            .contextFactory((start, in) -> new ProcessContext(start))
            .step("S", NoopStep.class, NoMetadata.INSTANCE)))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("outputMapper");
    }

    @Test
    void stepDefinitionRejectsNulls() {
        assertThatThrownBy(() -> define(pb -> complete(pb).step("S", NoopStep.class, null)))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("metadata");
        assertThatThrownBy(() -> new StepDefinition<>(null, NoopStep.class, NoMetadata.INSTANCE))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StepDefinition<NoMetadata, ProcessContext>("S", null, NoMetadata.INSTANCE))
            .isInstanceOf(NullPointerException.class);
    }

    static ProcessStart start(long seq) {
        return new ProcessStart(seq, Instant.parse("2026-01-01T00:00:00Z"),
            RequestContext.system(Locale.ENGLISH, "test"), IdAssigner.NONE);
    }

    @Test
    void stepsDefaultToTheTransactionAndAfterCommitStepsAreRetried() {
        RetryPolicy once = new RetryPolicy(1, Duration.ZERO);
        ProcessDefinition<In, Out, ProcessContext> def = define(pb -> complete(pb)
            .step("In", NoopStep.class, NoMetadata.INSTANCE)
            .afterCommit("Notify", TagStep.class, new Tag("n"))
            .afterCommit("Notify once", TagStep.class, new Tag("o"), once)
            .afterCommit("Spec", StepSpec.of(NoopStep.class, NoMetadata.INSTANCE)));

        assertThat(def.steps()).extracting(StepDefinition::phase).containsExactly(
            StepPhase.IN_TX, StepPhase.AFTER_COMMIT, StepPhase.AFTER_COMMIT, StepPhase.AFTER_COMMIT);
        assertThat(def.steps()).extracting(StepDefinition::retryPolicy).containsExactly(
            RetryPolicy.NONE, RetryPolicy.DEFAULT, once, RetryPolicy.DEFAULT);
        assertThat(def.steps()).allSatisfy(step -> assertThat(step.isInline()).isFalse());
    }

    @Test
    void inTransactionStepsAreNotRetried() {
        assertThatThrownBy(() -> new StepDefinition<>("S", NoopStep.class, NoMetadata.INSTANCE, StepPhase.IN_TX,
            RetryPolicy.DEFAULT, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("only after-commit steps are retried");
    }

    @Test
    void specStepsAndInlineComputations() {
        ComputeStep<NoMetadata, ProcessContext> body = (metadata, ctx) -> ctx.put("done", true);
        ProcessDefinition<In, Out, ProcessContext> def = define(pb -> complete(pb)
            .step("Spec", StepSpec.of(TagStep.class, new Tag("t")))
            .compute("Inline", body));

        assertThat(def.steps().get(0).handlerClass()).isEqualTo(TagStep.class);
        assertThat(def.steps().get(0).metadata()).isEqualTo(new Tag("t"));
        StepDefinition<?, ProcessContext> inline = def.steps().get(1);
        assertThat(inline.isInline()).isTrue();
        assertThat(inline.inline()).isSameAs(body);
        assertThat(inline.handlerClass()).isEqualTo(ComputeStep.class);
    }

    @Test
    void permissionsAndDeprecation() {
        ProcessDefinition<In, Out, ProcessContext> def = define(pb -> complete(pb)
            .permissions("a.run", "b.run")
            .deprecated()
            .step("S", NoopStep.class, NoMetadata.INSTANCE));

        assertThat(def.permissions()).containsExactlyInAnyOrder("a.run", "b.run");
        assertThat(def.deprecated()).isTrue();
        assertThat(def.withPermissions("c.run").permissions()).containsExactly("c.run");
        assertThat(def.withPermissions("c.run").deprecated()).isTrue();
        assertThatThrownBy(() -> def.withPermissions(" "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not be blank");
        assertThat(define(pb -> complete(pb).step("S", NoopStep.class, NoMetadata.INSTANCE)).permissions()).isEmpty();
    }

    @Test
    void internalProcessesAreMarkedAndKeepTheMarkWithNewPermissions() {
        ProcessDefinition<In, Out, ProcessContext> plain = define(pb -> complete(pb)
            .permissions("a.run").step("S", NoopStep.class, NoMetadata.INSTANCE));
        ProcessDefinition<In, Out, ProcessContext> internal = define(pb -> complete(pb)
            .permissions("a.run").internal().step("S", NoopStep.class, NoMetadata.INSTANCE));

        assertThat(plain.internal()).isFalse();
        assertThat(internal.internal()).isTrue();
        assertThat(internal.withPermissions("b.run").internal()).isTrue();
        assertThat(plain.asInternal().internal()).isTrue();
        assertThat(plain.asInternal().permissions()).containsExactly("a.run");
    }

    @Test
    void singleProcessComputesItsOutputFromInputAndContext() {
        ProcessDefinition<In, Out, ProcessContext> def = ProcessDefinition.single("ONE", 2, In.class, Out.class,
            (in, ctx) -> {
                ctx.changes().update("Thing", in.value(), 1L, java.util.Map.of("x", 1));
                return new Out(ctx.processSeqId());
            }).withPermissions("one.run");

        assertThat(def.name()).isEqualTo("ONE");
        assertThat(def.version()).isEqualTo(2);
        assertThat(def.contextType()).isEqualTo(ProcessContext.class);
        assertThat(def.permissions()).containsExactly("one.run");
        assertThat(def.steps()).singleElement().satisfies(step -> assertThat(step.isInline()).isTrue());

        ProcessContext ctx = def.contextFactory().create(start(5L), new In("t-1"));
        @SuppressWarnings("unchecked")
        ComputeStep<NoMetadata, ProcessContext> step =
            (ComputeStep<NoMetadata, ProcessContext>) def.steps().getFirst().inline();
        step.compute(NoMetadata.INSTANCE, ctx);
        assertThat(def.outputMapper().apply(ctx)).isEqualTo(new Out(5L));
        assertThat(ctx.changes().pending()).singleElement()
            .extracting(ChangeSet.Change::id).isEqualTo("t-1");
    }

    @Test
    void retryPolicyBacksOffExponentially() {
        RetryPolicy policy = new RetryPolicy(4, Duration.ofMillis(100));
        assertThat(policy.backoffBefore(1)).isEqualTo(Duration.ZERO);
        assertThat(policy.backoffBefore(2)).isEqualTo(Duration.ofMillis(100));
        assertThat(policy.backoffBefore(3)).isEqualTo(Duration.ofMillis(200));
        assertThat(policy.backoffBefore(4)).isEqualTo(Duration.ofMillis(400));
        assertThatThrownBy(() -> new RetryPolicy(0, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(1, Duration.ofMillis(-1))).isInstanceOf(IllegalArgumentException.class);
    }
}
