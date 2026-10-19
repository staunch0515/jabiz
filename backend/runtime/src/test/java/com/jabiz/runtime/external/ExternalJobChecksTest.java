package com.jabiz.runtime.external;

import com.jabiz.external.ExternalJobCancellation;
import com.jabiz.external.ExternalJobCompletion;
import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobFailure;
import com.jabiz.external.ExternalJobHandler;
import com.jabiz.external.ExternalJobResult;
import com.jabiz.external.ExternalJobType;
import com.jabiz.external.ExternalService;
import com.jabiz.file.FilePolicy;
import com.jabiz.file.MediaTypes;
import com.jabiz.param.ControlledParams;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.param.ParamService;
import com.jabiz.runtime.process.ProcessRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
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
            .timeout(Duration.ofSeconds(60)).maxConcurrency(concurrency).onCompleted(completed).onFailed(failed));
    }

    private static List<String> problems(ExternalJobType<?, ?>... types) {
        return problems(null, types);
    }

    /** With {@code params} (and a clock at {@link ExternalJobGateTest#NOW}) when given: the parameters of held types. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<String> problems(ExternalJobGateTest.Params params, ExternalJobType<?, ?>... types) {
        GenericApplicationContext beans = new GenericApplicationContext();
        if (params != null) {
            beans.registerBean(ParamService.class, () -> params);
            beans.registerBean(Clock.class, () -> Clock.fixed(ExternalJobGateTest.NOW, ZoneOffset.UTC));
        }
        beans.registerBean("handler", Handler.class, Handler::new);
        beans.registerBean("done", ProcessDefinition.class, () -> process("DONE", ExternalJobCompletion.class, true));
        beans.registerBean("failed", ProcessDefinition.class, () -> process("FAILED", ExternalJobFailure.class, true));
        beans.registerBean("public", ProcessDefinition.class, () -> process("PUBLIC", ExternalJobCompletion.class,
            false));
        beans.registerBean("wrong", ProcessDefinition.class, () -> process("WRONG", In.class, true));
        beans.registerBean("stopped", ProcessDefinition.class, () -> process("STOPPED", ExternalJobCancellation.class,
            true));
        beans.registerBean("publicStopped", ProcessDefinition.class, () -> process("PUBLIC_STOPPED",
            ExternalJobCancellation.class, false));
        beans.registerBean("controlled", ControlledParams.class, () -> ControlledParams.of("it.gate.controlled"));
        beans.registerBean("image", FilePolicy.class, () -> FilePolicy.define("it.image").allow(MediaTypes.PNG)
            .maxBytes(FilePolicy.MB).permissions("it.upload", "it.read").build());
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

    /** An attempt must outlast one request to its services, or a slow request makes it time out for nothing. */
    @Test
    void anAttemptMustOutlastItsServicesRequests() {
        ExternalJobType<In, Out> quick = ExternalJobType.define("it.quick", In.class, Out.class, t -> t
            .handler(Handler.class).services("demo-ai").timeout(Duration.ofSeconds(30))
            .onCompleted("DONE").onFailed("FAILED"));
        assertThat(problems(quick)).containsExactly("EXTERNAL | job it.quick | timeout: PT30S must be longer than the "
            + "request timeout PT30S of service demo-ai");
    }

    /** The files of a job are of declared policies, and the output policy takes files of the promised size (D50). */
    @Test
    void filePoliciesAreDeclaredAndTakeTheOutputFiles() {
        ExternalJobType<In, Out> good = ExternalJobType.define("it.files", In.class, Out.class, t -> t
            .handler(Handler.class).inputFiles(2, 5 * FilePolicy.MB, "it.image").outputFiles("it.image", 1,
                FilePolicy.MB).onCompleted("DONE").onFailed("FAILED"));
        assertThat(problems(good)).isEmpty();
        ExternalJobType<In, Out> bad = ExternalJobType.define("it.badfiles", In.class, Out.class, t -> t
            .handler(Handler.class).inputFiles(1, 10, "it.image", "it.ghost").outputFiles("it.none", 1, 10)
            .onCompleted("DONE").onFailed("FAILED"));
        ExternalJobType<In, Out> tooLarge = ExternalJobType.define("it.large", In.class, Out.class, t -> t
            .handler(Handler.class).outputFiles("it.image", 1, 2 * FilePolicy.MB)
            .onCompleted("DONE").onFailed("FAILED"));
        assertThat(problems(bad, tooLarge)).containsExactly(
            "EXTERNAL | job it.badfiles | inputFiles: no file policy it.ghost is declared",
            "EXTERNAL | job it.badfiles | outputFiles: no file policy it.none is declared",
            "EXTERNAL | job it.large | outputFiles: files of up to 2097152 bytes, but policy it.image takes at most "
                + "1048576");
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

    /** A cancellation process (decision D52 item 2) exists, is internal() and takes ExternalJobCancellation. */
    @Test
    void aCancellationProcessIsCheckedLikeTheOthers() {
        ExternalJobType<In, Out> good = ExternalJobType.define("it.stops", In.class, Out.class, t -> t
            .handler(Handler.class).onCompleted("DONE").onFailed("FAILED").onCancelled("STOPPED"));
        assertThat(problems(good)).isEmpty();
        ExternalJobType<In, Out> open = ExternalJobType.define("it.open", In.class, Out.class, t -> t
            .handler(Handler.class).onCompleted("DONE").onFailed("FAILED").onCancelled("PUBLIC_STOPPED"));
        ExternalJobType<In, Out> wrong = ExternalJobType.define("it.wrong", In.class, Out.class, t -> t
            .handler(Handler.class).onCompleted("DONE").onFailed("FAILED").onCancelled("FAILED"));
        ExternalJobType<In, Out> ghost = ExternalJobType.define("it.ghost", In.class, Out.class, t -> t
            .handler(Handler.class).onCompleted("DONE").onFailed("FAILED").onCancelled("GHOST"));
        assertThat(problems(open, wrong, ghost)).containsExactly(
            "EXTERNAL | job it.open | onCancelled: process PUBLIC_STOPPED must be internal(); anyone could run it "
                + "with a made-up result otherwise",
            "EXTERNAL | job it.wrong | onCancelled: process FAILED must take ExternalJobCancellation, not "
                + ExternalJobFailure.class.getName(),
            "EXTERNAL | job it.ghost | onCancelled: no process GHOST is registered");
    }

    /** Decision D52 item 4: a malformed key is an error, a key no ControlledParams declares a warning. */
    @Test
    void theParameterThatHoldsATypeIsWellFormedAndShouldBeControlled() {
        ExternalJobType<In, Out> controlled = ExternalJobType.define("it.controlled", In.class, Out.class, t -> t
            .handler(Handler.class).onCompleted("DONE").onFailed("FAILED").enabledBy("it.gate.controlled"));
        ExternalJobType<In, Out> loose = ExternalJobType.define("it.loose", In.class, Out.class, t -> t
            .handler(Handler.class).onCompleted("DONE").onFailed("FAILED").enabledBy("it.gate.loose"));
        ExternalJobType<In, Out> malformed = ExternalJobType.define("it.malformed", In.class, Out.class, t -> t
            .handler(Handler.class).onCompleted("DONE").onFailed("FAILED").enabledBy("It Gate"));
        assertThat(problems(controlled)).isEmpty();
        assertThat(problems(loose)).containsExactly("WARNING EXTERNAL | job it.loose | enabledBy: parameter "
            + "it.gate.loose is not declared in any ControlledParams bean; one person holding platform.param.write "
            + "can pause or resume the type alone (declared, both pausing and resuming take four eyes)");
        assertThat(problems(malformed)).containsExactly("EXTERNAL | job it.malformed | enabledBy: 'It Gate' is not "
            + "a parameter key (lower case words of letters and digits joined by '.', '-' or '_', at most 200 "
            + "characters)");
    }

    /**
     * A held type whose parameter has no value in effect at startup (typically not created before the deployment) is
     * a warning, as is one that cannot be read; a false one is a decision and passes (review L1).
     */
    @Test
    void aParameterWithoutAValueAtStartupIsAWarning() {
        ExternalJobType<In, Out> unset = ExternalJobType.define("it.unset", In.class, Out.class, t -> t
            .handler(Handler.class).onCompleted("DONE").onFailed("FAILED").enabledBy("it.gate.controlled"));
        ExternalJobGateTest.Params params = new ExternalJobGateTest.Params();
        assertThat(problems(params, unset)).containsExactly("WARNING EXTERNAL | job it.unset | enabledBy: parameter "
            + "it.gate.controlled has no value in effect now; the type is paused and its jobs wait until it is true");
        params.values.put("it.gate.controlled", false);
        assertThat(problems(params, unset)).isEmpty();
        params.values.put("it.gate.controlled", true);
        assertThat(problems(params, unset)).isEmpty();
        params.failure = new IllegalStateException("database down");
        assertThat(problems(params, unset)).containsExactly("WARNING EXTERNAL | job it.unset | enabledBy: parameter "
            + "it.gate.controlled cannot be read (IllegalStateException) now; the type is paused and its jobs wait "
            + "until it is true");
        // Without parameters in the context (the platform check of a context without a database) nothing is read.
        assertThat(problems(unset)).isEmpty();
    }
}
