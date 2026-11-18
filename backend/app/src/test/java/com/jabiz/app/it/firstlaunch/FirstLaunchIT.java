package com.jabiz.app.it.firstlaunch;

import com.jabiz.context.RequestContext;
import com.jabiz.firstlaunch.FirstLaunchConfig;
import com.jabiz.firstlaunch.FirstLaunchHandler;
import com.jabiz.firstlaunch.FirstLaunchProblem;
import com.jabiz.firstlaunch.FirstLaunchStep;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.firstlaunch.FirstLaunchExit;
import com.jabiz.runtime.firstlaunch.FirstLaunchGate;
import com.jabiz.runtime.firstlaunch.FirstLaunchProcesses;
import com.jabiz.runtime.firstlaunch.FirstLaunchRun;
import com.jabiz.runtime.firstlaunch.FirstLaunchRuns;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The first launch on a database that never had a user (docs/design/23-first-launch.md): the runner begins the run
 * once the application is ready, keeps the application closed (503) while the content phase runs, runs the
 * handler's steps one {@code FIRST_LAUNCH_STEP} each as the system, waits on a waiting step, records the phase as
 * done and opens.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "jabiz.first-launch.await-interval=50ms")
@Import(FirstLaunchIT.Handler.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FirstLaunchIT extends PostgresIntegrationTest {

    /** Holds the waiting step until a test lets it go. */
    static final CountDownLatch RELEASE = new CountDownLatch(1);
    static final AtomicInteger READY_CALLS = new AtomicInteger();
    static final List<Integer> EXITS = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void firstLaunch(DynamicPropertyRegistry registry) {
        registry.add("jabiz.first-launch.config", () -> configFile("version: 1\nsite:\n  language: en\nnote: hello\n"));
    }

    static String configFile(String content) {
        try {
            Path directory = Files.createTempDirectory("first-launch");
            Path main = directory.resolve("first-launch.yml");
            Files.writeString(main, content);
            return main.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public record Echo(String text) {}

    public record Ask() {}

    @TestConfiguration
    static class Handler {

        @Bean
        ProcessDefinition<Echo, Echo, ProcessContext> firstLaunchItEcho() {
            return ProcessDefinition.define("FL_IT_ECHO", 1, Echo.class, Echo.class, ProcessContext.class, pb -> pb
                .permissions("test.first-launch")
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("in", input);
                    return ctx;
                })
                .compute("Echo", (metadata, ctx) -> ctx.put("out", new Echo(ctx.get("in", Echo.class).text() + "!")))
                .outputMapper(ctx -> ctx.get("out", Echo.class)));
        }

        @Bean
        ProcessDefinition<Ask, Boolean, ProcessContext> firstLaunchItReady() {
            return ProcessDefinition.define("FL_IT_READY", 1, Ask.class, Boolean.class, ProcessContext.class, pb -> pb
                .permissions("test.first-launch")
                .contextFactory((start, input) -> new ProcessContext(start))
                .compute("Ask", (metadata, ctx) -> {
                    READY_CALLS.incrementAndGet();
                    ctx.put("ready", RELEASE.getCount() == 0);
                })
                .outputMapper(ctx -> ctx.get("ready", Boolean.class)));
        }

        @Bean
        FirstLaunchHandler firstLaunchItHandler() {
            return new FirstLaunchHandler() {
                @Override
                public String name() {
                    return "it";
                }

                @Override
                public List<FirstLaunchProblem> validate(FirstLaunchConfig config) {
                    return config.document().get("note") instanceof String ? List.of()
                        : List.of(new FirstLaunchProblem("note", "is required", "write note: some text"));
                }

                @Override
                public List<FirstLaunchStep> plan(FirstLaunchConfig config) {
                    String note = (String) config.document().get("note");
                    return List.of(
                        FirstLaunchStep.run("echo", "FL_IT_ECHO", results -> new Echo(note)),
                        FirstLaunchStep.await("ready", "FL_IT_READY", results -> new Ask()),
                        FirstLaunchStep.run("echo-again", "FL_IT_ECHO",
                            results -> new Echo(results.output("echo", Echo.class).text())));
                }
            };
        }

        @Bean
        FirstLaunchExit firstLaunchItExit() {
            return EXITS::add;
        }
    }

    @Autowired
    ApplicationContext context;

    @Autowired
    FirstLaunchGate gate;

    @Autowired
    FirstLaunchRuns runs;

    @Autowired
    ProcessExecutor processes;

    private static final RequestContext SYSTEM = RequestContext.system(Locale.ENGLISH, "it");

    private WebTestClient client() {
        return WebTestClient.bindToApplicationContext(context).build();
    }

    private static void within(Duration limit, BooleanSupplier condition) {
        long end = System.nanoTime() + limit.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("Condition not met within " + limit);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private FirstLaunchRun run() {
        return runs.current().contextWrite(view -> RequestContexts.put(view, SYSTEM)).block().orElseThrow();
    }

    @Test
    @Order(1)
    void whileTheContentPhaseRunsEveryRequestIsAnswered503() {
        within(Duration.ofSeconds(30), () -> READY_CALLS.get() > 0);

        assertThat(gate.isOpen()).isFalse();
        assertThat(run().state()).isEqualTo(FirstLaunchRun.State.RUNNING);
        client().get().uri("/api/datasets").header(HttpHeaders.ACCEPT_LANGUAGE, "zh").exchange()
            .expectStatus().isEqualTo(503)
            .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "30")
            .expectBody()
            .jsonPath("$.violations[0].ruleCode").isEqualTo(PlatformErrorCodes.FIRST_LAUNCH_IN_PROGRESS)
            .jsonPath("$.detail").value(detail -> assertThat((String) detail).contains("首发"));
        // The waiting step is asked again and again until it answers true.
        within(Duration.ofSeconds(10), () -> READY_CALLS.get() > 2);
        // Another instance started without the configuration still stays closed while the run goes on.
        FirstLaunchGate other = new FirstLaunchGate();
        new com.jabiz.runtime.firstlaunch.FirstLaunchRunner(new org.springframework.mock.env.MockEnvironment(), runs,
            other, processes, context.getBeanProvider(FirstLaunchHandler.class),
            context.getBeanProvider(com.jabiz.runtime.firstlaunch.FirstLaunchPlatformSteps.class),
            context.getBean(com.jabiz.i18n.MessageCatalog.class), context.getBean(java.time.Clock.class),
            context.getBeanProvider(FirstLaunchExit.class),
            context.getBean(tools.jackson.databind.json.JsonMapper.class),
            context.getBean(com.jabiz.runtime.firstlaunch.FirstLaunchPowers.class)).afterSingletonsInstantiated();
        assertThat(other.isOpen()).isFalse();
    }

    @Test
    @Order(2)
    void onceTheWaitingStepIsDoneThePhaseIsRecordedAndTheApplicationServes() {
        RELEASE.countDown();
        within(Duration.ofSeconds(30), gate::isOpen);

        FirstLaunchRun run = run();
        assertThat(run.state()).isEqualTo(FirstLaunchRun.State.CONTENT_DONE);
        assertThat(run.configHash()).hasSize(64);
        assertThat(EXITS).isEmpty();
        client().get().uri("/api/datasets").exchange().expectStatus().value(status -> assertThat(status).isNotEqualTo(503));
        // Every step is a FIRST_LAUNCH_STEP with its process as a sub-process, told apart by the request id.
        List<Map<String, Object>> steps = query("SELECT p.request_id, c.process_name FROM op_process p"
            + " JOIN op_process c ON c.parent_seq_id = p.process_seq_id WHERE p.process_name = 'FIRST_LAUNCH_STEP'"
            + " ORDER BY p.process_seq_id");
        assertThat(steps).extracting(row -> row.get("process_name")).startsWith("FL_IT_ECHO", "FL_IT_READY")
            .endsWith("FL_IT_ECHO");
        assertThat(steps).allSatisfy(row -> assertThat((String) row.get("request_id"))
            .startsWith("first-launch-" + run.runId() + "-"));
        assertThat(steps.getLast().get("request_id")).isEqualTo("first-launch-" + run.runId() + "-echo-again");
        // The later step read the earlier one's output.
        assertThat(query("SELECT c.input_summary::text AS input FROM op_process p JOIN op_process c"
            + " ON c.parent_seq_id = p.process_seq_id WHERE p.request_id = ?",
            "first-launch-" + run.runId() + "-echo-again").getFirst().get("input").toString()).contains("hello!");
    }

    @Test
    @Order(3)
    void thereIsOneRunAtMost() {
        FirstLaunchProcesses.StartOutput again = processes.execute(FirstLaunchProcesses.START_PROCESS,
                new FirstLaunchProcesses.StartInput("another"))
            .contextWrite(view -> RequestContexts.put(view, SYSTEM)).block();

        assertThat(again.started()).isFalse();
        assertThat(query("SELECT count(*) AS runs FROM sys_first_launch_run").getFirst().get("runs")).isEqualTo(1L);
    }

    @Test
    @Order(4)
    void theContentPhaseIsOverForGood() {
        FirstLaunchRun run = run();

        // Neither a failure nor another step after CONTENT_DONE.
        assertThatThrownBy(() -> processes.execute(FirstLaunchProcesses.RECORD_PROCESS,
                new FirstLaunchProcesses.RecordInput(run.runId().toString(), "FAILED", null, null, "late", "h", null))
            .contextWrite(view -> RequestContexts.put(view, SYSTEM)).block())
            .hasMessageContaining("CONTENT_DONE");
        assertThatThrownBy(() -> processes.execute(FirstLaunchProcesses.STEP_PROCESS,
                new FirstLaunchProcesses.StepInput(run.runId().toString(), "late", "FL_IT_ECHO", new Echo("x")))
            .contextWrite(view -> RequestContexts.put(view, SYSTEM)).block())
            .hasMessageContaining("cannot be started here");
    }

    @Test
    @Order(5)
    void theRecordIsAppendOnly() {
        assertThatThrownBy(() -> execute("UPDATE sys_first_launch_run SET config_hash = 'x'"))
            .hasMessageContaining("append-only");
        assertThatThrownBy(() -> execute("DELETE FROM sys_first_launch_event"))
            .hasMessageContaining("append-only");
    }
}
