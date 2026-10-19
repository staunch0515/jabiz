package com.jabiz.app.it.external;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jabiz.context.RequestContext;
import com.jabiz.external.ExternalJobCancellation;
import com.jabiz.external.ExternalJobCompletion;
import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobException;
import com.jabiz.external.ExternalJobFailure;
import com.jabiz.external.ExternalJobHandler;
import com.jabiz.external.ExternalJobResult;
import com.jabiz.external.ExternalJobType;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.event.OutboxDeliverer;
import com.jabiz.external.ExternalServices;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.external.ExternalJobExecutor;
import com.jabiz.runtime.external.ExternalJobRecorder;
import com.jabiz.runtime.external.ExternalJobRegistry;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.param.ParamService;
import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;
import com.jabiz.runtime.external.ExternalJobTypeHeldException;
import com.jabiz.runtime.param.ParamProcesses;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.steps.StartExternalJob;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.test.TestTokens;
import com.jabiz.runtime.test.scenario.Scenario;
import com.jabiz.runtime.test.scenario.ScenarioRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pausing a job type by a business parameter (decision D52 item 4), against a real PostgreSQL: a type that declares
 * {@code enabledBy} is claimed only while its parameter's value in effect now is boolean {@code true}. While it is
 * held, queued and retry-waiting jobs wait without using an attempt, a running attempt runs to its end, expired leases
 * are still recorded and held jobs can be cancelled; jobs can still be started; other types are not held.
 *
 * <p>Each test has gated types of its own, with a parameter key of its own: parameters are temporal and cannot be
 * cleaned up between tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.events.delivery.initial-backoff=0s"})
class ExternalJobGateIT extends PostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private static final RequestContext ADMIN = new RequestContext("it-admin", null, Locale.ENGLISH, "it-gate",
        Set.of(), Set.of("*"));
    private static final Map<String, Object> BOOL = Map.of("type", "bool");

    public record JobInput(String label) {}

    public record JobResult(String label) {}

    public record StartInput(String type, String label) {}

    public record StartOutput(String jobId) {}

    public record CallbackOutput(String jobId) {}

    record Callback(String process, String jobId, Object input) {}

    static final List<Callback> CALLBACKS = new CopyOnWriteArrayList<>();
    static final List<String> RUNS = new CopyOnWriteArrayList<>();
    static final Map<String, CountDownLatch> GATES = new ConcurrentHashMap<>();
    static final Map<String, CountDownLatch> STARTED = new ConcurrentHashMap<>();

    /** The types of the tests, each held by a parameter of its own (by the type's name), and one never held. */
    static final List<String> GATED = List.of("it.held.queued", "it.held.retry", "it.held.running", "it.held.resume",
        "it.held.missing", "it.held.text", "it.held.scheduled", "it.held.cancel", "it.held.other", "it.held.lease",
        "it.held.simulated", "it.held.scenario", "it.held.broken", "it.held.slot", "it.held.exhausted");
    static final String FREE = "it.free";
    /** Whether reading it.held.broken's parameter fails. */
    static final AtomicBoolean BROKEN = new AtomicBoolean();

    /** Records its run, waits at its gate if it has one; a "retry-" job fails its first attempt. */
    public static class Handler implements ExternalJobHandler<JobInput, JobResult> {
        @Override
        public ExternalJobResult<JobResult> run(JobInput input, ExternalJobContext ctx) {
            String key = input.label() + ":" + ctx.attempt();
            RUNS.add(key);
            ctx.recordUsage("promptTokens", 5);
            STARTED.computeIfAbsent(key, k -> new CountDownLatch(1)).countDown();
            if (input.label().startsWith("retry-") && ctx.attempt() == 1) {
                throw ExternalJobException.retryable("IT_BUSY", "busy for the test");
            }
            CountDownLatch gate = GATES.get(key);
            if (gate != null) {
                try {
                    gate.await(25, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted");
                }
            }
            return ExternalJobResult.of(new JobResult(input.label()));
        }
    }

    static String param(String type) {
        return type + ".enabled";
    }

    static ExternalJobType<JobInput, JobResult> type(String name, boolean gated) {
        return ExternalJobType.define(name, JobInput.class, JobResult.class, t -> {
            t.handler(Handler.class).timeout(Duration.ofSeconds(90))
                .retry(3, Duration.ofSeconds(10), Duration.ofMinutes(1))
                .onCompleted("IT_HELD_DONE").onFailed("IT_HELD_FAILED").onCancelled("IT_HELD_STOPPED");
            if (gated) {
                t.enabledBy(param(name));
            }
        });
    }

    static <I> ProcessDefinition<I, CallbackOutput, ProcessContext> callback(String name, Class<I> type) {
        return ProcessDefinition.define(name, 1, type, CallbackOutput.class, ProcessContext.class, pb -> pb
            .permissions("it.job.callback")
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put("in", input);
                return ctx;
            })
            .outputMapper(ctx -> new CallbackOutput(jobId(ctx.get("in"))))
            .compute("Record", (metadata, ctx) -> {
                Object in = ctx.get("in");
                CALLBACKS.add(new Callback(name, jobId(in), in));
            }));
    }

    static String jobId(Object input) {
        return switch (input) {
            case ExternalJobCompletion c -> c.jobId();
            case ExternalJobFailure f -> f.jobId();
            case ExternalJobCancellation x -> x.jobId();
            default -> throw new IllegalArgumentException(String.valueOf(input));
        };
    }

    @TestConfiguration
    static class Fixtures {

        @Bean
        Handler heldHandler() {
            return new Handler();
        }

        @Bean
        ExternalJobType<JobInput, JobResult> heldQueued() {
            return type("it.held.queued", true);
        }

        @Bean
        ExternalJobType<JobInput, JobResult> heldRetry() {
            return type("it.held.retry", true);
        }

        @Bean
        ExternalJobType<JobInput, JobResult> heldRunning() {
            return type("it.held.running", true);
        }

        @Bean
        ExternalJobType<JobInput, JobResult> heldResume() {
            return type("it.held.resume", true);
        }

        @Bean
        ExternalJobType<JobInput, JobResult> heldMissing() {
            return type("it.held.missing", true);
        }

        @Bean
        ExternalJobType<JobInput, JobResult> heldText() {
            return type("it.held.text", true);
        }

        @Bean
        ExternalJobType<JobInput, JobResult> heldScheduled() {
            return type("it.held.scheduled", true);
        }

        @Bean
        ExternalJobType<JobInput, JobResult> heldCancel() {
            return type("it.held.cancel", true);
        }

        @Bean
        ExternalJobType<JobInput, JobResult> heldOther() {
            return type("it.held.other", true);
        }

        @Bean
        ExternalJobType<JobInput, JobResult> heldLease() {
            return type("it.held.lease", true);
        }

        @Bean
        ExternalJobType<JobInput, JobResult> heldSimulated() {
            return type("it.held.simulated", true);
        }

        @Bean
        ExternalJobType<JobInput, JobResult> heldScenario() {
            return type("it.held.scenario", true);
        }

        @Bean
        ExternalJobType<JobInput, JobResult> heldBroken() {
            return type("it.held.broken", true);
        }

        @Bean
        ExternalJobType<JobInput, JobResult> heldSlot() {
            return type("it.held.slot", true);
        }

        @Bean
        ExternalJobType<JobInput, JobResult> heldExhausted() {
            return type("it.held.exhausted", true);
        }

        /** The parameters as the platform reads them, except that {@link #BROKEN} cannot be read while it is set. */
        @Bean
        @Primary
        ParamService failingParams(DatasetRegistry datasets, DatasetEntityManager entities) {
            return new ParamService(datasets, entities) {
                @Override
                public Mono<Object> get(String key, Instant asOf) {
                    if (BROKEN.get() && key.equals(param("it.held.broken"))) {
                        return Mono.error(new IllegalStateException("the database is down for the test"));
                    }
                    return super.get(key, asOf);
                }
            };
        }

        @Bean
        ExternalJobType<JobInput, JobResult> freeType() {
            return type(FREE, false);
        }

        /** Starts a job of any of the test types: one process, so that the tests need not declare one each. */
        @Bean
        ProcessDefinition<StartInput, StartOutput, ProcessContext> startHeld() {
            return ProcessDefinition.define("IT_START_HELD", 1, StartInput.class, StartOutput.class,
                ProcessContext.class, pb -> {
                    pb.permissions("it.job.start")
                        .contextFactory((start, input) -> {
                            ProcessContext ctx = new ProcessContext(start);
                            ctx.put("in", input);
                            return ctx;
                        })
                        .outputMapper(ctx -> new StartOutput(ctx.get("jobId", String.class)));
                    for (String name : allTypes()) {
                        pb.step("Start " + name, StartExternalJob.<ProcessContext>of(name,
                                ctx -> new JobInput(ctx.get("in", StartInput.class).label()))
                            .when(ctx -> ctx.get("in", StartInput.class).type().equals(name))
                            .outputKey("jobId"));
                    }
                });
        }

        @Bean
        ProcessDefinition<ExternalJobCompletion, CallbackOutput, ProcessContext> itHeldDone() {
            return callback("IT_HELD_DONE", ExternalJobCompletion.class);
        }

        @Bean
        ProcessDefinition<ExternalJobFailure, CallbackOutput, ProcessContext> itHeldFailed() {
            return callback("IT_HELD_FAILED", ExternalJobFailure.class);
        }

        @Bean
        ProcessDefinition<ExternalJobCancellation, CallbackOutput, ProcessContext> itHeldStopped() {
            return callback("IT_HELD_STOPPED", ExternalJobCancellation.class);
        }
    }

    static List<String> allTypes() {
        List<String> all = new java.util.ArrayList<>(GATED);
        all.add(FREE);
        return all;
    }

    @Autowired
    ApplicationContext context;

    @Autowired
    JwtService tokens;

    @Autowired
    ExternalJobExecutor executor;

    @Autowired
    OutboxDeliverer deliverer;

    @Autowired
    ProcessExecutor processes;

    WebTestClient client;
    /** For the whole class: a type is logged when it changes, which may be in an earlier test. */
    static final ListAppender<ILoggingEvent> LOGS = new ListAppender<>();

    @BeforeAll
    static void watchTheLog() {
        LOGS.setName("it-external-job-gate");
        LOGS.start();
    }

    @AfterAll
    static void stopWatching() {
        gateLogger().detachAppender(LOGS);
    }

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
        // Attached once the context (and its logging system) is up; the level is the test's, not the configuration's.
        Logger logger = gateLogger();
        logger.setLevel(Level.INFO);
        if (logger.getAppender(LOGS.getName()) == null) {
            logger.addAppender(LOGS);
        }
    }

    @AfterEach
    void tearDown() {
        GATES.values().forEach(CountDownLatch::countDown);
        BROKEN.set(false);
    }

    /** One poll of the shared executor; tests look at their own jobs, not at how many it ran. */
    private void poll() {
        executor.runPending().block();
    }

    private static Logger gateLogger() {
        return (Logger) LoggerFactory.getLogger("com.jabiz.runtime.external.ExternalJobGate");
    }

    @Test
    void whileTheParameterIsOffQueuedJobsWaitAndUseNoAttempt() {
        String type = "it.held.queued";
        create(type, false);
        // Starting still registers the job (V-3).
        String label = unique("queued");
        String jobId = start(type, label, "it-req");
        for (int poll = 0; poll < 3; poll++) {
            poll();
            clock.advance(Duration.ofMinutes(10));
        }
        assertThat(attempts(jobId)).isEmpty();
        assertThat(query("SELECT 1 FROM sys_external_job_claim WHERE job_id = ?", jobId)).isEmpty();
        assertThat(query("SELECT 1 FROM sys_external_job_outcome WHERE job_id = ?", jobId)).isEmpty();
        assertThat(RUNS).noneMatch(run -> run.startsWith(label + ":"));
        String paused = "External job type it.held.queued is paused: parameter " + param(type)
            + " is false; its jobs wait";
        // Logged once, not at every poll.
        assertThat(messages()).filteredOn(paused::equals).hasSize(1);
    }

    @Test
    void retriesWaitToo() {
        String type = "it.held.retry";
        create(type, true);
        String jobId = start(type, "retry-" + unique("r"), "it-req");
        poll();
        assertThat(attempts(jobId)).containsExactly("1:FAILED");
        set(type, false);
        clock.advance(Duration.ofMinutes(5));
        executor.runPending().block();
        clock.advance(Duration.ofMinutes(30));
        executor.runPending().block();
        assertThat(attempts(jobId)).containsExactly("1:FAILED");
        assertThat(query("SELECT attempt_no FROM sys_external_job_claim WHERE job_id = ?", jobId)).hasSize(1);
        assertThat(query("SELECT 1 FROM sys_external_job_outcome WHERE job_id = ?", jobId)).isEmpty();
        set(type, true);
        executor.runPending().block();
        assertThat(attempts(jobId)).containsExactly("1:FAILED", "2:SUCCEEDED");
        assertThat(outcome(jobId)).containsEntry("status", "SUCCEEDED").containsEntry("attempts", 2);
    }

    @Test
    void aRunningAttemptIsNotInterrupted() throws Exception {
        String type = "it.held.running";
        create(type, true);
        String label = unique("running");
        GATES.put(label + ":1", new CountDownLatch(1));
        String jobId = start(type, label, "it-req");
        CompletableFuture<Integer> running = executor.runPending().toFuture();
        assertThat(STARTED.computeIfAbsent(label + ":1", k -> new CountDownLatch(1)).await(20, TimeUnit.SECONDS))
            .isTrue();
        String next = start(type, unique("next"), "it-req");
        set(type, false);
        // Held now: the next job is not claimed, while the running attempt goes on.
        poll();
        GATES.get(label + ":1").countDown();
        running.get(30, TimeUnit.SECONDS);
        assertThat(outcome(jobId)).containsEntry("status", "SUCCEEDED").containsEntry("attempts", 1);
        poll();
        assertThat(attempts(next)).isEmpty();
    }

    @Test
    void switchingOnResumesAtTheNextPoll() {
        String type = "it.held.resume";
        create(type, false);
        String jobId = start(type, unique("resume"), "it-req");
        poll();
        set(type, true);
        poll();
        assertThat(outcome(jobId)).containsEntry("status", "SUCCEEDED").containsEntry("attempts", 1);
        assertThat(messages()).filteredOn(message -> message.contains(type)).containsSubsequence(
            "External job type it.held.resume is paused: parameter " + param(type) + " is false; its jobs wait",
            "External job type it.held.resume resumes: parameter " + param(type) + " is true");
    }

    @Test
    void aMissingOrNonBooleanParameterHolds() {
        String missing = "it.held.missing";
        String text = "it.held.text";
        run(ParamProcesses.CREATE, new ParamProcesses.CreateInput(param(text),
            Map.of("type", "text", "maxLength", 10), "true", null));
        String missingJob = start(missing, unique("missing"), "it-req");
        String textJob = start(text, unique("text"), "it-req");
        poll();
        assertThat(attempts(missingJob)).isEmpty();
        assertThat(attempts(textJob)).isEmpty();
        assertThat(messages()).contains(
            "External job type it.held.missing is paused: parameter " + param(missing)
                + " has no value in effect; its jobs wait",
            "External job type it.held.text is paused: parameter " + param(text) + " is not a boolean; its jobs wait");
        create(missing, true);
        poll();
        assertThat(outcome(missingJob)).containsEntry("status", "SUCCEEDED");
        assertThat(attempts(textJob)).isEmpty();
    }

    @Test
    void aScheduledChangeTakesEffectAtItsTime() {
        String type = "it.held.scheduled";
        create(type, false);
        Instant at = clock.instant().plus(Duration.ofHours(1));
        run(ParamProcesses.SCHEDULE, new ParamProcesses.ScheduleInput(param(type), true, at));
        String jobId = start(type, unique("scheduled"), "it-req");
        poll();
        clock.advance(Duration.ofMinutes(59));
        poll();
        assertThat(attempts(jobId)).isEmpty();
        clock.advance(Duration.ofMinutes(1));
        poll();
        assertThat(outcome(jobId)).containsEntry("status", "SUCCEEDED");
        // And the switch back off, scheduled as well.
        run(ParamProcesses.SCHEDULE, new ParamProcesses.ScheduleInput(param(type), false,
            clock.instant().plus(Duration.ofHours(1))));
        String later = start(type, unique("later"), "it-req-s");
        clock.advance(Duration.ofHours(1));
        poll();
        assertThat(attempts(later)).isEmpty();
        // Not left for a test whose clock passes the hour this type was open.
        client.post().uri("/api/external-jobs/" + later + "/cancel")
            .header(HttpHeaders.AUTHORIZATION, bearer("it-req-s")).exchange().expectStatus().isOk();
    }

    @Test
    void heldJobsCanBeCancelledAndGetTheirOutcome() {
        String type = "it.held.cancel";
        create(type, false);
        String jobId = start(type, unique("cancel"), "it-req-c");
        poll();
        client.post().uri("/api/external-jobs/" + jobId + "/cancel")
            .header(HttpHeaders.AUTHORIZATION, bearer("it-req-c")).exchange().expectStatus().isOk();
        assertThat(outcome(jobId)).containsEntry("status", "CANCELLED").containsEntry("attempts", 0);
        executor.runPending().block();
        deliverer.deliverPending().block();
        deliverer.deliverPending().block();
        assertThat(callbacks(jobId)).singleElement().satisfies(callback -> {
            assertThat(callback.process()).isEqualTo("IT_HELD_STOPPED");
            ExternalJobCancellation in = (ExternalJobCancellation) callback.input();
            assertThat(in.attempts()).isZero();
            assertThat(in.attemptRan()).isFalse();
            assertThat(in.cancelledBy()).isEqualTo("it-req-c");
        });
    }

    @Test
    void otherTypesAreNotHeld() {
        String type = "it.held.other";
        create(type, false);
        String held = start(type, unique("held"), "it-req");
        String free = start(FREE, unique("free"), "it-req");
        poll();
        assertThat(outcome(free)).containsEntry("status", "SUCCEEDED");
        assertThat(attempts(held)).isEmpty();
    }

    @Test
    void expiredLeasesAreStillRecordedWhileHeld() {
        String type = "it.held.lease";
        create(type, false);
        String jobId = start(type, unique("lease"), "it-req");
        String last = start(type, unique("last"), "it-req");
        insertClaim(jobId, 1, START.minusSeconds(1));
        // The last attempt allowed (3 of 3): its expiry fails the job for good, held or not.
        insertAttempt(last, 1);
        insertAttempt(last, 2);
        insertClaim(last, 3, START.minusSeconds(1));
        poll();
        assertThat(attempts(jobId)).containsExactly("1:LEASE_EXPIRED");
        assertThat(query("SELECT 1 FROM sys_external_job_outcome WHERE job_id = ?", jobId)).isEmpty();
        assertThat(query("SELECT attempt_no FROM sys_external_job_claim WHERE job_id = ?", jobId)).hasSize(1);
        assertThat(outcome(last)).containsEntry("status", "FAILED");
        // The failure process hears of the last expiry, held or not.
        deliverer.deliverPending().block();
        deliverer.deliverPending().block();
        assertThat(callbacks(last)).singleElement().satisfies(callback -> {
            assertThat(callback.process()).isEqualTo("IT_HELD_FAILED");
            assertThat(((ExternalJobFailure) callback.input()).errorCode()).isEqualTo("LEASE_EXPIRED");
        });
        // Past the backoff of the expired attempt: only the parameter holds the job now.
        clock.advance(Duration.ofSeconds(11));
        poll();
        assertThat(attempts(jobId)).containsExactly("1:LEASE_EXPIRED");
        set(type, true);
        poll();
        assertThat(attempts(jobId)).containsExactly("1:LEASE_EXPIRED", "2:SUCCEEDED");
    }

    /** Scenario replay's completeExternalJob obeys the hold as the executor does. */
    @Test
    void aSimulatedResultWhileHeldIsRefusedWithTheReason() {
        String type = "it.held.simulated";
        create(type, false);
        String jobId = start(type, unique("simulated"), "it-req");
        assertThatThrownBy(() -> executor.completeWithoutHandler(type, null, "SUCCEEDED", Map.of("label", "x"),
            Map.of(), null, null).block())
            .isInstanceOfSatisfying(ExternalJobTypeHeldException.class, held -> {
                assertThat(held.jobType()).isEqualTo(type);
                assertThat(held.reason()).isEqualTo("parameter " + param(type) + " is false");
            });
        assertThat(attempts(jobId)).isEmpty();
        set(type, true);
        assertThat(executor.completeWithoutHandler(type, null, "SUCCEEDED", Map.of("label", "x"), Map.of(), null,
            null).block()).isEqualTo(ExternalJobExecutor.Outcome.SUCCEEDED);
    }

    /** Scenario replay: runExternalJobs leaves a held type's jobs waiting, completeExternalJob fails with the reason. */
    @Test
    void aScenarioObeysTheHold() {
        String type = "it.held.scenario";
        create(type, false);
        Scenario scenario = Scenario.parse("""
            name: Held external jobs
            clock: 2026-01-31T09:00:00Z
            actor: { id: it-scenario, permissions: ["it.job.start"] }
            steps:
              - process: IT_START_HELD@latest
                input: { type: it.held.scenario, label: from-a-scenario }
              - runExternalJobs: true
              - completeExternalJob:
                  jobType: it.held.scenario
                  result: { label: from-a-scenario }
            """, "held.yml");
        assertThatThrownBy(() -> new ScenarioRunner(context).run(scenario))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("step 3: external job type it.held.scenario is paused: parameter "
                + param(type) + " is false");
        assertThat(query("SELECT j.job_id FROM sys_external_job j WHERE j.job_type = ? AND NOT EXISTS (SELECT 1"
            + " FROM sys_external_job_attempt a WHERE a.job_id = j.job_id)", type)).hasSize(1);
        assertThat(RUNS).noneMatch(run -> run.startsWith("from-a-scenario:"));
    }

    /** A parameter that cannot be read holds the type, with one WARN, and the type resumes once it can (L1, L2). */
    @Test
    void aParameterThatCannotBeReadHolds() {
        String type = "it.held.broken";
        create(type, true);
        BROKEN.set(true);
        String jobId = start(type, unique("broken"), "it-req");
        for (int round = 0; round < 3; round++) {
            poll();
        }
        assertThat(attempts(jobId)).isEmpty();
        assertThat(LOGS.list).filteredOn(event -> event.getFormattedMessage().contains(param(type) + " cannot be read"))
            .singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("parameter " + param(type)
                    + " cannot be read (IllegalStateException)");
            });
        BROKEN.set(false);
        poll();
        assertThat(outcome(jobId)).containsEntry("status", "SUCCEEDED");
    }

    /**
     * An instance with one slot: a held type takes none of it, whichever comes first in the turn, so an open type's
     * job is claimed at the first poll.
     */
    @Test
    void aHeldTypeTakesNoSlotFromAnOpenOne() {
        String type = "it.held.slot";
        create(type, false);
        String held = start(type, unique("slot"), "it-req");
        String free = start(FREE, unique("free-slot"), "it-req");
        ExternalJobExecutor single = new ExternalJobExecutor(context.getBean(ExternalJobRegistry.class),
            context.getBean(ExternalJobRecorder.class), context, context.getBean(ExternalServices.class),
            context.getBean(JsonMapper.class), context.getBean(Clock.class),
            context.getBean(PlatformObservations.class), context.getBeanProvider(MeterRegistry.class),
            executor.settings().withInstanceId("it-single").withMaxInFlight(1));
        for (int round = 0; round < allTypes().size(); round++) {
            single.runPending().block();
            assertThat(outcome(free)).as("claimed at the first poll").containsEntry("status", "SUCCEEDED");
            assertThat(attempts(held)).isEmpty();
        }
    }

    /** A job whose attempts are used up gets its outcome while held: the settling is not a claim. */
    @Test
    void aJobWithItsAttemptsUsedUpIsSettledWhileHeld() {
        String type = "it.held.exhausted";
        create(type, false);
        String jobId = start(type, unique("exhausted"), "it-req");
        insertAttempt(jobId, 1);
        insertAttempt(jobId, 2);
        insertAttempt(jobId, 3);
        poll();
        assertThat(query("SELECT status, error_code FROM sys_external_job_outcome WHERE job_id = ?", jobId))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("status", "FAILED")
                .containsEntry("error_code", "ATTEMPTS_EXHAUSTED"));
        deliverer.deliverPending().block();
        deliverer.deliverPending().block();
        assertThat(callbacks(jobId)).singleElement().satisfies(callback ->
            assertThat(callback.process()).isEqualTo("IT_HELD_FAILED"));
    }

    // ---------------------------------------------------------------- helpers

    private void create(String type, boolean value) {
        run(ParamProcesses.CREATE, new ParamProcesses.CreateInput(param(type), BOOL, value, null));
    }

    private void set(String type, boolean value) {
        run(ParamProcesses.SET, new ParamProcesses.SetInput(param(type), value));
    }

    private <I, O> O run(ProcessDefinition<I, O, ProcessContext> definition, I input) {
        return asRequest(ADMIN, processes.execute(definition, input)).block();
    }

    private List<String> messages() {
        return LOGS.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static void insertClaim(String jobId, int attempt, Instant leaseUntil) {
        execute("INSERT INTO sys_external_job_claim (job_id, attempt_no, instance_id, claimed_time, lease_until)"
            + " VALUES (?, ?, 'it-crashed', ?, ?)", jobId, attempt,
            java.sql.Timestamp.from(leaseUntil.minusSeconds(60)), java.sql.Timestamp.from(leaseUntil));
    }

    private static void insertAttempt(String jobId, int attempt) {
        insertClaim(jobId, attempt, START.minusSeconds(600));
        execute("INSERT INTO sys_external_job_attempt (job_id, attempt_no, attempt_id, outcome, retryable, error_code,"
            + " finished_time) VALUES (?, ?, ?, 'FAILED', true, 'IT_BUSY', ?)", jobId, attempt,
            UUID.randomUUID().toString(), java.sql.Timestamp.from(START.minusSeconds(600)));
    }

    private static List<String> attempts(String jobId) {
        return query("SELECT attempt_no, outcome FROM sys_external_job_attempt WHERE job_id = ? ORDER BY attempt_no",
            jobId).stream().map(row -> row.get("attempt_no") + ":" + row.get("outcome")).toList();
    }

    private static Map<String, Object> outcome(String jobId) {
        List<Map<String, Object>> rows = query("SELECT status, attempts FROM sys_external_job_outcome"
            + " WHERE job_id = ?", jobId);
        assertThat(rows).as("outcome of " + jobId).hasSize(1);
        return rows.getFirst();
    }

    private static final AtomicInteger SEQ = new AtomicInteger();

    private static String unique(String prefix) {
        return prefix + "-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    private String bearer(String actor, String... permissions) {
        return TestTokens.bearer(tokens, actor, permissions);
    }

    private String start(String type, String label, String actor) {
        Map<String, Object> result = client.post().uri("/api/processes/IT_START_HELD/latest")
            .header(HttpHeaders.AUTHORIZATION, bearer(actor, "it.job.start"))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("type", type, "label", label)).exchange()
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.get("output");
        return output.get("jobId").toString();
    }

    private static List<Callback> callbacks(String jobId) {
        return CALLBACKS.stream().filter(callback -> callback.jobId().equals(jobId)).toList();
    }
}
