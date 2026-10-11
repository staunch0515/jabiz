package com.jabiz.runtime.external;

import com.jabiz.external.ExternalJobCompletion;
import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobFailure;
import com.jabiz.external.ExternalJobHandler;
import com.jabiz.external.ExternalJobResult;
import com.jabiz.external.ExternalJobType;
import com.jabiz.external.ExternalService;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.process.ProcessRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The startup check {@code EXTERNAL} of external job types reports every problem at once. */
class ExternalJobChecksTest {

    record In(String id) {}

    record Out(String text) {}

    static final class Handler implements ExternalJobHandler<In, Out> {
        @Override
        public ExternalJobResult<Out> run(In input, ExternalJobContext ctx) {
            return ExternalJobResult.of(new Out(input.id()));
        }
    }

    /** No bean of it is registered. */
    static final class Orphan implements ExternalJobHandler<In, Out> {
        @Override
        public ExternalJobResult<Out> run(In input, ExternalJobContext ctx) {
            return ExternalJobResult.of(new Out(input.id()));
        }
    }

    private static <I> ProcessDefinition<I, Out, ProcessContext> process(String name, Class<I> input,
        boolean internal) {
        return ProcessDefinition.define(name, 1, input, Out.class, ProcessContext.class, pb -> {
            pb.permissions("it.callback").contextFactory((start, in) -> new ProcessContext(start))
                .outputMapper(ctx -> new Out(name)).compute("Nothing", (metadata, ctx) -> { });
            if (internal) {
                pb.internal();
            }
        });
    }

    private static ExternalJobType<In, Out> type(String name, String completed, String failed, int concurrency,
        Class<? extends ExternalJobHandler<In, Out>> handler, String... services) {
        return ExternalJobType.define(name, In.class, Out.class, t -> t.handler(handler).services(services)
            .timeout(Duration.ofSeconds(30)).maxConcurrency(concurrency).onCompleted(completed).onFailed(failed));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<String> problems(ExternalJobType<?, ?>... types) {
        GenericApplicationContext beans = new GenericApplicationContext();
        beans.registerBean("handler", Handler.class, Handler::new);
        beans.registerBean("done", ProcessDefinition.class, () -> process("DONE", ExternalJobCompletion.class, true));
        beans.registerBean("failed", ProcessDefinition.class, () -> process("FAILED", ExternalJobFailure.class, true));
        beans.registerBean("public", ProcessDefinition.class, () -> process("PUBLIC", ExternalJobCompletion.class,
            false));
        beans.registerBean("wrong", ProcessDefinition.class, () -> process("WRONG", In.class, true));
        beans.refresh();
        ProcessRegistry processes = new ProcessRegistry((ObjectProvider) beans.getBeanProvider(ProcessDefinition.class));
        ExternalServiceRegistry services = new ExternalServiceRegistry(List.of(ExternalService.define("demo-ai",
            s -> s.baseUrl("https://ai.example.com/v1").allowedHosts("ai.example.com"))), new MockEnvironment());
        List<String> found = new ExternalJobChecks(new ExternalJobRegistry(List.of(types)), services, processes, beans)
            .check().stream().map(CheckProblem::format).toList();
        beans.close();
        return found;
    }

    @Test
    void aWellDeclaredTypePasses() {
        assertThat(problems(type("it.good", "DONE", "FAILED", 2, Handler.class, "demo-ai"))).isEmpty();
    }

    @Test
    void everyProblemIsReportedAtOnce() {
        assertThat(problems(
            type("it.good", "DONE", "FAILED", 2, Handler.class, "demo-ai"),
            type("it.good", "DONE", "FAILED", 2, Handler.class),
            type("it.bad", "PUBLIC", "WRONG", 0, Orphan.class, "nowhere"),
            type("it.missing", "GHOST", "FAILED", 1, Handler.class))).containsExactly(
                "EXTERNAL | job it.good | declared twice",
                "EXTERNAL | job it.bad | maxConcurrency: must be between 1 and 1000",
                "EXTERNAL | job it.bad | handler: expected exactly one bean of type "
                    + Orphan.class.getName() + ", found 0",
                "EXTERNAL | job it.bad | services: no external service nowhere is declared",
                "EXTERNAL | job it.bad | onCompleted: process PUBLIC must be internal(); anyone could run it with a "
                    + "made-up result otherwise",
                "EXTERNAL | job it.bad | onFailed: process WRONG must take ExternalJobFailure, not "
                    + In.class.getName(),
                "EXTERNAL | job it.missing | onCompleted: no process GHOST is registered");
    }

    @Test
    void theRegistryFindsTypesByName() {
        ExternalJobType<In, Out> good = type("it.good", "DONE", "FAILED", 2, Handler.class);
        ExternalJobRegistry registry = new ExternalJobRegistry(List.of(good,
            type("it.good", "OTHER", "FAILED", 2, Handler.class)));
        assertThat(registry.find("it.good")).containsSame(good);
        assertThat(registry.find("it.none")).isEmpty();
        assertThat(registry.find(null)).isEmpty();
        assertThat(registry.names()).containsExactly("it.good");
        assertThat(registry.declared()).hasSize(2);
        assertThat(registry.toString()).contains("it.good");
    }
}
