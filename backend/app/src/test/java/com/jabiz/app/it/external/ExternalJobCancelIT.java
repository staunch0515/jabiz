package com.jabiz.app.it.external;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.Violation;
import com.jabiz.external.ExternalJobCancellation;
import com.jabiz.external.ExternalJobCompletion;
import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobFailure;
import com.jabiz.external.ExternalJobHandler;
import com.jabiz.external.ExternalJobResult;
import com.jabiz.external.ExternalJobType;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.event.OutboxDeliverer;
import com.jabiz.runtime.external.ExternalJobExecutor;
import com.jabiz.runtime.process.steps.CancelExternalJob;
import com.jabiz.runtime.process.steps.StartExternalJob;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outcome of a cancellation and cancelling from a business process (decision D52 item 2), against a real
 * PostgreSQL: a type that declares {@code onCancelled} has that process run once, as the system, when a cancelled job
 * has ended, with who cancelled it and the usage of every attempt; the platform step {@code CancelExternalJob} cancels
 * jobs of its own type only, as the process's actor, in the process's transaction. Types without {@code onCancelled}
 * behave as before (decision D38: nothing is called).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.events.delivery.initial-backoff=0s"})
class ExternalJobCancelIT extends PostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    public record JobInput(String label) {}

    public record JobResult(String label) {}

    public record StartInput(String label) {}

    public record StartOutput(String jobId) {}

    /** @param failAfter whether the process rejects after the step, so that its transaction rolls back */
    public record CancelInput(String jobId, Boolean failAfter) {}

    public record CancelOutput(Boolean cancelled) {}

    public record CallbackOutput(String jobId) {}

    record Callback(String process, String jobId, String actor, Object input) {}

    static final List<Callback> CALLBACKS = new CopyOnWriteArrayList<>();
    static final List<String> RUNS = new CopyOnWriteArrayList<>();
    static final Map<String, CountDownLatch> GATES = new ConcurrentHashMap<>();
    static final Map<String, CountDownLatch> STARTED = new ConcurrentHashMap<>();

    /** Records usage as it goes, waits at its gate if it has one, and succeeds. */
    public static class Handler implements ExternalJobHandler<JobInput, JobResult> {
        @Override
        public ExternalJobResult<JobResult> run(JobInput input, ExternalJobContext ctx) {
            String key = input.label() + ":" + ctx.attempt();
            RUNS.add(key);
            ctx.recordUsage("promptTokens", 7);
            STARTED.computeIfAbsent(key, k -> new CountDownLatch(1)).countDown();
            CountDownLatch gate = GATES.get(key);
            if (gate != null) {
                try {
                    gate.await(25, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted");
                }
            }
            return ExternalJobResult.of(new JobResult(input.label())).usage("completionTokens", 11);
        }
    }

    static ProcessDefinition<StartInput, StartOutput, ProcessContext> starter(String name, String jobType) {
        return ProcessDefinition.define(name, 1, StartInput.class, StartOutput.class, ProcessContext.class, pb -> pb
            .permissions("it.job.start")
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put("in", input);
                return ctx;
            })
            .outputMapper(ctx -> new StartOutput(ctx.get("jobId", String.class)))
            .step("Start", StartExternalJob.<ProcessContext>of(jobType,
                    ctx -> new JobInput(ctx.get("in", StartInput.class).label()))
                .outputKey("jobId")));
    }

    static ProcessDefinition<CancelInput, CancelOutput, ProcessContext> canceller(String name, boolean ifOpen) {
        return ProcessDefinition.define(name, 1, CancelInput.class, CancelOutput.class, ProcessContext.class, pb -> {
            CancelExternalJob.Spec<ProcessContext> cancel = CancelExternalJob.<ProcessContext>of("it.stoppable",
                ctx -> ctx.get("in", CancelInput.class).jobId()).outputKey("cancelled");
            pb.permissions("it.job.cancel")
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("in", input);
                    return ctx;
                })
                .outputMapper(ctx -> new CancelOutput(ctx.get("cancelled", Boolean.class)))
                .step("Cancel", ifOpen ? cancel.ifOpen() : cancel)
                .compute("Maybe fail", (metadata, ctx) -> {
                    if (Boolean.TRUE.equals(ctx.get("in", CancelInput.class).failAfter())) {
                        ctx.reject(new Violation("jobId", "IT_FAIL_AFTER", "fails after the job was cancelled"));
                    }
                });
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
                CALLBACKS.add(new Callback(name, jobId(in), ctx.request().actorId(), in));
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

    static ExternalJobType<JobInput, JobResult> type(String name, boolean withCancelled) {
        return ExternalJobType.define(name, JobInput.class, JobResult.class, t -> {
            t.handler(Handler.class).timeout(Duration.ofSeconds(90))
                .retry(3, Duration.ofSeconds(10), Duration.ofMinutes(1))
                .onCompleted("IT_CANCEL_DONE").onFailed("IT_CANCEL_FAILED");
            if (withCancelled) {
                t.onCancelled("IT_JOB_STOPPED");
            }
        });
    }

    @TestConfiguration
    static class Fixtures {

        @Bean
        Handler cancelHandler() {
            return new Handler();
        }

        @Bean
        ExternalJobType<JobInput, JobResult> itStoppableJob() {
            return type("it.stoppable", true);
        }

        /** Of another type, with its own cancellation process: the step of it.stoppable must not reach it. */
        @Bean
        ExternalJobType<JobInput, JobResult> itOtherJob() {
            return type("it.other", true);
        }

        /** As decision D38 had it: no cancellation process. */
        @Bean
        ExternalJobType<JobInput, JobResult> itPlainJob() {
            return type("it.plain", false);
        }

        @Bean
        ProcessDefinition<StartInput, StartOutput, ProcessContext> startStoppable() {
            return starter("IT_START_STOPPABLE", "it.stoppable");
        }

        @Bean
        ProcessDefinition<StartInput, StartOutput, ProcessContext> startOther() {
            return starter("IT_START_OTHER", "it.other");
        }

        @Bean
        ProcessDefinition<StartInput, StartOutput, ProcessContext> startPlain() {
            return starter("IT_START_PLAIN", "it.plain");
        }

        @Bean
        ProcessDefinition<CancelInput, CancelOutput, ProcessContext> cancelStoppable() {
            return canceller("IT_CANCEL_STOPPABLE", false);
        }

        @Bean
        ProcessDefinition<CancelInput, CancelOutput, ProcessContext> cancelStoppableIfOpen() {
            return canceller("IT_CANCEL_STOPPABLE_IF_OPEN", true);
        }

        @Bean
        ProcessDefinition<ExternalJobCompletion, CallbackOutput, ProcessContext> itCancelDone() {
            return callback("IT_CANCEL_DONE", ExternalJobCompletion.class);
        }

        @Bean
        ProcessDefinition<ExternalJobFailure, CallbackOutput, ProcessContext> itCancelFailed() {
            return callback("IT_CANCEL_FAILED", ExternalJobFailure.class);
        }

        @Bean
        ProcessDefinition<ExternalJobCancellation, CallbackOutput, ProcessContext> itJobStopped() {
            return callback("IT_JOB_STOPPED", ExternalJobCancellation.class);
        }
    }

    @Autowired
    ApplicationContext context;

    @Autowired
    JwtService tokens;

    @Autowired
    ExternalJobExecutor executor;

    @Autowired
    OutboxDeliverer deliverer;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
    }

    /** Finishes what a test left open, so that the next test's counts are its own. */
    @AfterEach
    void finishLeftovers() {
        GATES.values().forEach(CountDownLatch::countDown);
        for (int round = 0; round < 6; round++) {
            clock.advance(Duration.ofHours(1));
            executor.runPending().block();
        }
        deliverer.deliverPending().block();
        assertThat(query("SELECT j.job_id FROM sys_external_job j WHERE NOT EXISTS (SELECT 1 FROM"
            + " sys_external_job_outcome o WHERE o.job_id = j.job_id)")).as("jobs left open").isEmpty();
    }

    @Test
    void aQueuedJobCancelledByAProcessIsNeverRunAndItsCancelledProcessRunsOnce() {
        String label = unique("queued");
        String jobId = start("IT_START_STOPPABLE", label, "it-req-q");
        Map<String, Object> output = cancelByProcess("IT_CANCEL_STOPPABLE", jobId, "it-canceller", false)
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(output(output)).containsEntry("cancelled", true);
        assertThat(query("SELECT cancelled_by FROM sys_external_job_cancel WHERE job_id = ?", jobId).getFirst())
            .containsEntry("cancelled_by", "it-canceller");
        assertThat(outcome(jobId)).containsEntry("status", "CANCELLED").containsEntry("attempts", 0);
        assertThat(executor.runPending().block()).isZero();
        assertThat(RUNS).noneMatch(run -> run.startsWith(label + ":"));

        deliverer.deliverPending().block();
        deliverer.deliverPending().block();
        assertThat(callbacks(jobId)).singleElement().satisfies(callback -> {
            assertThat(callback.process()).isEqualTo("IT_JOB_STOPPED");
            assertThat(callback.actor()).isEqualTo(RequestContext.SYSTEM_ACTOR);
            ExternalJobCancellation in = (ExternalJobCancellation) callback.input();
            assertThat(in.jobType()).isEqualTo("it.stoppable");
            assertThat(in.requestedBy()).isEqualTo("it-req-q");
            assertThat(in.cancelledBy()).isEqualTo("it-canceller");
            assertThat(in.cancelledTime()).isEqualTo(START);
            assertThat(in.finishedTime()).isEqualTo(START);
            assertThat(in.attempts()).isZero();
            assertThat(in.attemptRan()).isFalse();
            assertThat(in.usage()).isEmpty();
        });
        // The cancellation process runs as a sub-process of the platform's EXTERNAL_JOB_COMPLETE, as the system.
        assertThat(query("SELECT actor_id FROM op_process WHERE process_name = 'IT_JOB_STOPPED'"
            + " AND input_summary::text LIKE ?", "%" + jobId + "%")).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("actor_id", RequestContext.SYSTEM_ACTOR));
    }

    @Test
    void aRunningJobCancelledByAProcessGivesItsRecordedUsageToTheCancelledProcess() throws Exception {
        String label = unique("running");
        GATES.put(label + ":1", new CountDownLatch(1));
        String jobId = start("IT_START_STOPPABLE", label, "it-req-r");
        CompletableFuture<Integer> running = executor.runPending().toFuture();
        assertThat(STARTED.computeIfAbsent(label + ":1", k -> new CountDownLatch(1)).await(20, TimeUnit.SECONDS))
            .isTrue();
        assertThat(output(cancelByProcess("IT_CANCEL_STOPPABLE", jobId, "it-canceller", false).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody())).containsEntry("cancelled", true);
        // Not ended yet: the attempt runs on, and nothing is called.
        assertThat(query("SELECT 1 FROM sys_external_job_outcome WHERE job_id = ?", jobId)).isEmpty();
        deliverer.deliverPending().block();
        assertThat(callbacks(jobId)).isEmpty();

        GATES.get(label + ":1").countDown();
        assertThat(running.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(outcome(jobId)).containsEntry("status", "CANCELLED").containsEntry("result", null)
            .containsEntry("attempts", 1);
        deliverer.deliverPending().block();
        assertThat(callbacks(jobId)).singleElement().satisfies(callback -> {
            ExternalJobCancellation in = (ExternalJobCancellation) callback.input();
            assertThat(in.attemptRan()).isTrue();
            assertThat(in.attempts()).isEqualTo(1);
            assertThat(in.cancelledBy()).isEqualTo("it-canceller");
            // What the provider may bill: everything the attempt recorded, its result dropped.
            assertThat(in.usage()).isEqualTo(recordedUsage(jobId)).containsEntry("promptTokens", 7L)
                .containsEntry("completionTokens", 11L);
        });
        assertThat(callbacks(jobId)).noneMatch(callback -> callback.process().equals("IT_CANCEL_DONE"));
    }

    @Test
    void anOperatorsCancelThroughTheEndpointCallsTheCancelledProcessWithWhoCancelled() {
        String jobId = start("IT_START_STOPPABLE", unique("operator"), "it-req-o");
        client.post().uri("/api/external-jobs/" + jobId + "/cancel")
            .header(HttpHeaders.AUTHORIZATION, bearer("it-operator", "external-job.cancel")).exchange()
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        deliverer.deliverPending().block();
        assertThat(callbacks(jobId)).singleElement().satisfies(callback -> {
            ExternalJobCancellation in = (ExternalJobCancellation) callback.input();
            assertThat(in.cancelledBy()).isEqualTo("it-operator");
            assertThat(in.requestedBy()).isEqualTo("it-req-o");
            assertThat(callback.actor()).isEqualTo(RequestContext.SYSTEM_ACTOR);
        });
        // The requester's own cancellation, through the endpoint, as well.
        String own = start("IT_START_STOPPABLE", unique("own"), "it-req-o");
        client.post().uri("/api/external-jobs/" + own + "/cancel")
            .header(HttpHeaders.AUTHORIZATION, bearer("it-req-o")).exchange().expectStatus().isOk();
        deliverer.deliverPending().block();
        assertThat(callbacks(own)).singleElement().satisfies(callback ->
            assertThat(((ExternalJobCancellation) callback.input()).cancelledBy()).isEqualTo("it-req-o"));
    }

    @Test
    void aTypeWithoutOnCancelledCallsNothingAsBefore() {
        String jobId = start("IT_START_PLAIN", unique("plain"), "it-req-p");
        client.post().uri("/api/external-jobs/" + jobId + "/cancel")
            .header(HttpHeaders.AUTHORIZATION, bearer("it-req-p")).exchange().expectStatus().isOk();
        assertThat(outcome(jobId)).containsEntry("status", "CANCELLED");
        deliverer.deliverPending().block();
        assertThat(callbacks(jobId)).isEmpty();
        // The event is consumed all the same, calling nothing.
        assertThat(query("SELECT 1 FROM op_process WHERE process_name = 'EXTERNAL_JOB_COMPLETE'"
            + " AND input_summary::text LIKE ?", "%" + jobId + "%")).hasSize(1);
        assertThat(query("SELECT 1 FROM op_process WHERE process_name = 'IT_JOB_STOPPED'"
            + " AND input_summary::text LIKE ?", "%" + jobId + "%")).isEmpty();
    }

    @Test
    void theStepAnswersJobsOfOtherTypesLikeMissingOnes() {
        String other = start("IT_START_OTHER", unique("other"), "it-req-x");
        Map<String, Object> ofOtherType = cancelByProcess("IT_CANCEL_STOPPABLE", other, "it-req-x", false)
            .expectStatus().isNotFound().expectBody(MAP).returnResult().getResponseBody();
        Map<String, Object> missing = cancelByProcess("IT_CANCEL_STOPPABLE", UUID.randomUUID().toString(), "it-req-x",
            false).expectStatus().isNotFound().expectBody(MAP).returnResult().getResponseBody();
        assertThat(withoutInstance(ofOtherType)).isEqualTo(withoutInstance(missing));
        // ifOpen does not turn the refusal into a no-op: the job is not one the step may see.
        cancelByProcess("IT_CANCEL_STOPPABLE_IF_OPEN", other, "it-req-x", false).expectStatus().isNotFound();
        assertThat(query("SELECT 1 FROM sys_external_job_cancel WHERE job_id = ?", other)).isEmpty();
        assertThat(executor.runPending().block()).isEqualTo(1);
        assertThat(outcome(other)).containsEntry("status", "SUCCEEDED");
    }

    @Test
    void aFinishedJobIs422UnlessIfOpen() {
        String cancelled = start("IT_START_STOPPABLE", unique("twice"), "it-req-f");
        cancelByProcess("IT_CANCEL_STOPPABLE", cancelled, "it-req-f", false).expectStatus().isOk();
        Map<String, Object> again = cancelByProcess("IT_CANCEL_STOPPABLE", cancelled, "it-req-f", false)
            .expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody();
        assertThat(violations(again)).singleElement().satisfies(v ->
            assertThat(v).containsEntry("ruleCode", "EXTERNAL_JOB_FINISHED"));
        assertThat(output(cancelByProcess("IT_CANCEL_STOPPABLE_IF_OPEN", cancelled, "it-req-f", false)
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody())).containsEntry("cancelled", false);
        assertThat(query("SELECT 1 FROM sys_external_job_cancel WHERE job_id = ?", cancelled)).hasSize(1);

        String succeeded = start("IT_START_STOPPABLE", unique("done"), "it-req-f");
        assertThat(executor.runPending().block()).isEqualTo(1);
        assertThat(outcome(succeeded)).containsEntry("status", "SUCCEEDED");
        cancelByProcess("IT_CANCEL_STOPPABLE", succeeded, "it-req-f", false).expectStatus().isEqualTo(422);
        assertThat(output(cancelByProcess("IT_CANCEL_STOPPABLE_IF_OPEN", succeeded, "it-req-f", false)
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody())).containsEntry("cancelled", false);
        assertThat(query("SELECT 1 FROM sys_external_job_cancel WHERE job_id = ?", succeeded)).isEmpty();
        deliverer.deliverPending().block();
        assertThat(callbacks(cancelled)).hasSize(1);
        assertThat(callbacks(succeeded)).singleElement().satisfies(callback ->
            assertThat(callback.process()).isEqualTo("IT_CANCEL_DONE"));
    }

    @Test
    void aProcessThatRollsBackLeavesTheJobOpen() {
        String jobId = start("IT_START_STOPPABLE", unique("rollback"), "it-req-b");
        Map<String, Object> refused = cancelByProcess("IT_CANCEL_STOPPABLE", jobId, "it-req-b", true)
            .expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody();
        assertThat(violations(refused)).singleElement().satisfies(v ->
            assertThat(v).containsEntry("ruleCode", "IT_FAIL_AFTER"));
        assertThat(query("SELECT 1 FROM sys_external_job_cancel WHERE job_id = ?", jobId)).isEmpty();
        assertThat(query("SELECT 1 FROM sys_external_job_outcome WHERE job_id = ?", jobId)).isEmpty();
        assertThat(query("SELECT 1 FROM sys_outbox_event WHERE entity_id = ?", jobId)).isEmpty();
        assertThat(executor.runPending().block()).isEqualTo(1);
        assertThat(outcome(jobId)).containsEntry("status", "SUCCEEDED");
        deliverer.deliverPending().block();
        assertThat(callbacks(jobId)).singleElement().satisfies(callback ->
            assertThat(callback.process()).isEqualTo("IT_CANCEL_DONE"));
    }

    @Test
    void theCancelledProcessCannotBeRunThroughTheProcessApi() {
        String jobId = start("IT_START_STOPPABLE", unique("forge"), "it-req");
        Map<String, Object> forged = new HashMap<>();
        forged.put("jobId", jobId);
        forged.put("jobType", "it.stoppable");
        forged.put("requestedBy", "it-req");
        forged.put("cancelledBy", "it-req");
        forged.put("attempts", 0);
        forged.put("attemptRan", false);
        Map<String, Object> refused = client.post().uri("/api/processes/IT_JOB_STOPPED/latest")
            .header(HttpHeaders.AUTHORIZATION, bearer("it-admin", "*")).contentType(MediaType.APPLICATION_JSON)
            .bodyValue(forged).exchange().expectStatus().isForbidden().expectBody(MAP).returnResult()
            .getResponseBody();
        assertThat(violations(refused)).singleElement().satisfies(v ->
            assertThat(v).containsEntry("ruleCode", "PROCESS_INTERNAL"));
        assertThat(callbacks(jobId)).isEmpty();
        assertThat(query("SELECT 1 FROM sys_external_job_cancel WHERE job_id = ?", jobId)).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private static final AtomicInteger SEQ = new AtomicInteger();

    private static String unique(String prefix) {
        return prefix + "-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    private String bearer(String actor, String... permissions) {
        return TestTokens.bearer(tokens, actor, permissions);
    }

    private String start(String process, String label, String actor) {
        Map<String, Object> result = client.post().uri("/api/processes/" + process + "/latest")
            .header(HttpHeaders.AUTHORIZATION, bearer(actor, "it.job.start"))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("label", label)).exchange()
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return output(result).get("jobId").toString();
    }

    private WebTestClient.ResponseSpec cancelByProcess(String process, String jobId, String actor, boolean failAfter) {
        return client.post().uri("/api/processes/" + process + "/latest")
            .header(HttpHeaders.AUTHORIZATION, bearer(actor, "it.job.cancel"))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("jobId", jobId, "failAfter", failAfter))
            .exchange();
    }

    private static Map<String, Object> outcome(String jobId) {
        List<Map<String, Object>> rows = query("SELECT status, result, attempts FROM sys_external_job_outcome"
            + " WHERE job_id = ?", jobId);
        assertThat(rows).as("outcome of " + jobId).hasSize(1);
        return rows.getFirst();
    }

    private static Map<String, Long> recordedUsage(String jobId) {
        Map<String, Long> usage = new HashMap<>();
        query("SELECT metric, quantity FROM sys_external_job_usage WHERE job_id = ?", jobId)
            .forEach(row -> usage.merge(String.valueOf(row.get("metric")), ((Number) row.get("quantity")).longValue(),
                Long::sum));
        return usage;
    }

    private static List<Callback> callbacks(String jobId) {
        return CALLBACKS.stream().filter(callback -> callback.jobId().equals(jobId)).toList();
    }

    private static Map<String, Object> withoutInstance(Map<String, Object> problem) {
        Map<String, Object> copy = new HashMap<>(problem);
        copy.remove("instance");
        copy.remove("requestId");
        copy.remove("traceId");
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> output(Map<String, Object> result) {
        return (Map<String, Object>) result.get("output");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> violations(Map<String, Object> problem) {
        return (List<Map<String, Object>>) problem.get("violations");
    }
}
