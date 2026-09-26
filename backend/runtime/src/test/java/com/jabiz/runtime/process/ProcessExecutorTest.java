package com.jabiz.runtime.process;

import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.StepImplementation;
import com.jabiz.runtime.check.PlatformCheckRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.support.StaticApplicationContext;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessExecutorTest {

    record Out(long seq, boolean ran) {}

    /** A step type no executor supports yet (synchronous steps arrive in ROADMAP phase 6). */
    static final class UnsupportedStep implements StepImplementation<NoMetadata, ProcessContext> {}

    static final class MarkStep implements StepHandler<NoMetadata, ProcessContext> {
        @Override
        public Mono<Void> execute(NoMetadata metadata, ProcessContext ctx) {
            return Mono.fromRunnable(() -> ctx.put("ran", true));
        }
    }

    private static ProcessDefinition<String, Out, ProcessContext> process(
        Class<? extends StepImplementation<NoMetadata, ProcessContext>> step
    ) {
        return ProcessDefinition.define("PROBE", 1, String.class, Out.class, ProcessContext.class, pb -> pb
            .contextFactory((seq, in) -> new ProcessContext(seq))
            .outputMapper(ctx -> new Out(ctx.processSeqId(), ctx.contains("ran")))
            .step("Only", step, NoMetadata.INSTANCE));
    }

    private static ProcessExecutor executor(ProcessDefinition<?, ?, ?> definition) {
        StaticListableBeanFactory definitions = new StaticListableBeanFactory();
        definitions.addBean("process", definition);
        StaticApplicationContext beans = new StaticApplicationContext();
        beans.registerSingleton("mark", MarkStep.class);
        beans.refresh();
        AtomicLong counter = new AtomicLong(41);
        ProcessSequence sequence = () -> Mono.fromSupplier(counter::incrementAndGet);
        return new ProcessExecutor(new ProcessRegistry(definitions.getBeanProvider(
            org.springframework.core.ResolvableType.forClass(ProcessDefinition.class))), beans, sequence);
    }

    @Test
    void stepHandlersRunWithTheSequenceNumber() {
        ProcessDefinition<String, Out, ProcessContext> definition = process(MarkStep.class);
        ProcessExecutor executor = executor(definition);
        PlatformCheckRunner.verify(executor);

        assertThat(executor.execute(definition, "in").block()).isEqualTo(new Out(42, true));
    }

    @Test
    void stepTypesWithoutExecutorFailAtStartup() {
        ProcessExecutor executor = executor(process(UnsupportedStep.class));

        assertThatThrownBy(() -> PlatformCheckRunner.verify(executor))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("UnsupportedStep is not a StepHandler");
    }
}
