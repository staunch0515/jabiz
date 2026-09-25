package com.jabiz.process;

import org.junit.jupiter.api.Test;

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
        return pb.contextFactory((seq, in) -> new ProcessContext(seq))
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
        assertThat(def.contextFactory().create(7L, new In("x")).processSeqId()).isEqualTo(7L);
        assertThat(def.outputMapper().apply(new ProcessContext(9L))).isEqualTo(new Out(9L));
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
            .contextFactory((seq, in) -> new ProcessContext(seq))
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

    @Test
    void processContextStoresTypedValues() {
        ProcessContext ctx = new ProcessContext(1L);
        ctx.put("n", 5);
        assertThat(ctx.get("n", Integer.class)).isEqualTo(5);
        assertThat(ctx.contains("n")).isTrue();
        assertThatThrownBy(() -> ctx.get("n", String.class)).isInstanceOf(IllegalStateException.class);
        ctx.put("n", null);
        assertThat(ctx.get("n")).isNull();
        assertThat(ctx.get("n", String.class)).isNull();
    }
}
