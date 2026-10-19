package com.jabiz.app.it.external;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.Violation;
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

import java.sql.Connection;
import java.sql.PreparedStatement;
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

    /**
     * @param failAfter whether the process rejects after the step, so that its transaction rolls back
     * @param owner     whose job the {@code requestedBy} process cancels
     */
    public record CancelInput(String jobId, Boolean failAfter, String owner) {}

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
            if (input.label().startsWith("fail-")) {
                throw ExternalJobException.permanent("IT_REFUSED", "refused for the test");
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

    /** Whose jobs a cancelling process may cancel: the actor's own (the default), anyone's, or a named owner's. */
    enum Scope { OWN, OWN_IF_OPEN, ANY, FOR_OWNER }

    static ProcessDefinition<CancelInput, CancelOutput, ProcessContext> canceller(String name, Scope scope) {
        return ProcessDefinition.define(name, 1, CancelInput.class, CancelOutput.class, ProcessContext.class, pb -> {
            CancelExternalJob.Spec<ProcessContext> own = CancelExternalJob.<ProcessContext>of("it.stoppable",
                ctx -> ctx.get("in", CancelInput.class).jobId()).outputKey("cancelled");
            CancelExternalJob.Spec<ProcessContext> cancel = switch (scope) {
                case OWN -> own;
                case OWN_IF_OPEN -> own.ifOpen();
                case ANY -> own.anyRequester();
                case FOR_OWNER -> own.requestedBy(ctx -> ctx.get("in", CancelInput.class).owner());
            };
            pb.permissions("it.job.cancel")
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("in", input);
                    return ctx;
                })
                .outputMapper(ctx -> new CancelOutput(ctx.get("cancelled", Boolean.class)))
                .step("Cancel", cancel)
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
            return canceller("IT_CANCEL_STOPPABLE", Scope.OWN);
        }

        @Bean
        ProcessDefinition<CancelInput, CancelOutput, ProcessContext> cancelAnyStoppable() {
            return canceller("IT_CANCEL_ANY_STOPPABLE", Scope.ANY);
        }

        @Bean
        ProcessDefinition<CancelInput, CancelOutput, ProcessContext> cancelStoppableFor() {
            return canceller("IT_CANCEL_STOPPABLE_FOR", Scope.FOR_OWNER);
        }

        @Bean
        ProcessDefinition<CancelInput, CancelOutput, ProcessContext> cancelStoppableIfOpen() {
            return canceller("IT_CANCEL_STOPPABLE_IF_OPEN", Scope.OWN_IF_OPEN);
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
        Map<String, Object> output = cancelByProcess("IT_CANCEL_ANY_STOPPABLE", jobId, "it-canceller", false)
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
        assertThat(output(cancelByProcess("IT_CANCEL_ANY_STOPPABLE", jobId, "it-canceller", false).expectStatus().isOk()
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

    /** Deny by default (D52 item 2): the step cancels the actor's own jobs; anyone else's is a missing one. */
    @Test
    void anotherRequestersJobIsAnsweredLikeAMissingOneUnlessTheStepSaysOtherwise() {
        String theirs = start("IT_START_STOPPABLE", unique("theirs"), "it-req-owner");
        Map<String, Object> notOwn = cancelByProcess("IT_CANCEL_STOPPABLE", theirs, "it-req-other", false)
            .expectStatus().isNotFound().expectBody(MAP).returnResult().getResponseBody();
        Map<String, Object> missing = cancelByProcess("IT_CANCEL_STOPPABLE", UUID.randomUUID().toString(),
            "it-req-other", false).expectStatus().isNotFound().expectBody(MAP).returnResult().getResponseBody();
        assertThat(withoutInstance(notOwn)).isEqualTo(withoutInstance(missing));
        // Neither ifOpen nor a finished job tells the other requester anything.
        cancelByProcess("IT_CANCEL_STOPPABLE_IF_OPEN", theirs, "it-req-other", false).expectStatus().isNotFound();
        // A process acting for another owner cancels only that owner's jobs.
        cancelByProcess("IT_CANCEL_STOPPABLE_FOR", theirs, "it-req-other", false, "it-req-someone-else")
            .expectStatus().isNotFound();
        assertThat(query("SELECT 1 FROM sys_external_job_cancel WHERE job_id = ?", theirs)).isEmpty();
        assertThat(output(cancelByProcess("IT_CANCEL_STOPPABLE_FOR", theirs, "it-support", false, "it-req-owner")
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody())).containsEntry("cancelled", true);
        assertThat(query("SELECT cancelled_by FROM sys_external_job_cancel WHERE job_id = ?", theirs).getFirst())
            .containsEntry("cancelled_by", "it-support");
        // anyRequester() is explicit, for an operator's process.
        String another = start("IT_START_STOPPABLE", unique("another"), "it-req-owner");
        assertThat(output(cancelByProcess("IT_CANCEL_ANY_STOPPABLE", another, "it-operator", false)
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody())).containsEntry("cancelled", true);
        deliverer.deliverPending().block();
        assertThat(callbacks(theirs)).singleElement().satisfies(callback ->
            assertThat(((ExternalJobCancellation) callback.input()).cancelledBy()).isEqualTo("it-support"));
        assertThat(callbacks(another)).singleElement().satisfies(callback ->
            assertThat(((ExternalJobCancellation) callback.input()).cancelledBy()).isEqualTo("it-operator"));
    }

    /** A cancel that comes after the result was recorded is too late: 422, and the completion stands. */
    @Test
    void aCancelAfterTheResultIsRecordedIs422() throws Exception {
        String label = unique("race");
        GATES.put(label + ":1", new CountDownLatch(1));
        String jobId = start("IT_START_STOPPABLE", label, "it-req-race");
        CompletableFuture<Integer> running = executor.runPending().toFuture();
        assertThat(STARTED.computeIfAbsent(label + ":1", k -> new CountDownLatch(1)).await(20, TimeUnit.SECONDS))
            .isTrue();
        GATES.get(label + ":1").countDown();
        assertThat(running.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        Map<String, Object> late = cancelByProcess("IT_CANCEL_STOPPABLE", jobId, "it-req-race", false)
            .expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody();
        assertThat(violations(late)).singleElement().satisfies(v ->
            assertThat(v).containsEntry("ruleCode", "EXTERNAL_JOB_FINISHED"));
        assertThat(outcome(jobId)).containsEntry("status", "SUCCEEDED");
        deliverer.deliverPending().block();
        assertThat(callbacks(jobId)).singleElement().satisfies(callback ->
            assertThat(callback.process()).isEqualTo("IT_CANCEL_DONE"));
    }

    /** A lease that ran out before the cancel: the cancel records it first, and the job ends at once (Low 2). */
    @Test
    void aCancelRecordsAnExpiredLeaseAndTheCancelledProcessRunsOnce() {
        String jobId = start("IT_START_STOPPABLE", unique("expired"), "it-req-lease");
        insertClaim(jobId, 1, START.minusSeconds(1));
        cancelByProcess("IT_CANCEL_STOPPABLE", jobId, "it-req-lease", false).expectStatus().isOk();
        assertThat(attempts(jobId)).containsExactly("1:LEASE_EXPIRED");
        assertThat(outcome(jobId)).containsEntry("status", "CANCELLED").containsEntry("attempts", 1);
        executor.runPending().block();
        deliverer.deliverPending().block();
        assertThat(attempts(jobId)).containsExactly("1:LEASE_EXPIRED");
        assertThat(callbacks(jobId)).singleElement().satisfies(callback -> {
            ExternalJobCancellation in = (ExternalJobCancellation) callback.input();
            assertThat(in.attempts()).isEqualTo(1);
            assertThat(in.attemptRan()).isTrue();
        });
    }

    /** A lease that runs out after the cancel: the executor records it and ends the job; the process runs once. */
    @Test
    void aLeaseExpiringAfterTheCancelEndsTheJobOnceThroughTheExecutor() {
        String jobId = start("IT_START_STOPPABLE", unique("expiring"), "it-req-lease2");
        insertClaim(jobId, 1, START.plusSeconds(60));
        cancelByProcess("IT_CANCEL_STOPPABLE", jobId, "it-req-lease2", false).expectStatus().isOk();
        // Still "running" by its lease: no outcome yet.
        assertThat(query("SELECT 1 FROM sys_external_job_outcome WHERE job_id = ?", jobId)).isEmpty();
        clock.advance(Duration.ofMinutes(2));
        executor.runPending().block();
        assertThat(attempts(jobId)).containsExactly("1:LEASE_EXPIRED");
        assertThat(outcome(jobId)).containsEntry("status", "CANCELLED").containsEntry("attempts", 1);
        executor.runPending().block();
        deliverer.deliverPending().block();
        deliverer.deliverPending().block();
        assertThat(callbacks(jobId)).singleElement().satisfies(callback ->
            assertThat(callback.process()).isEqualTo("IT_JOB_STOPPED"));
    }

    /** The usage of every attempt reaches the cancellation process: a failed retry's and the cancelled one's. */
    @Test
    void theUsageOfEveryAttemptIsSummed() throws Exception {
        String label = unique("retry-sum");
        GATES.put(label + ":2", new CountDownLatch(1));
        String jobId = start("IT_START_STOPPABLE", label, "it-req-sum");
        assertThat(executor.runPending().block()).isEqualTo(1);
        assertThat(attempts(jobId)).containsExactly("1:FAILED");
        clock.advance(Duration.ofSeconds(11));
        CompletableFuture<Integer> second = executor.runPending().toFuture();
        assertThat(STARTED.computeIfAbsent(label + ":2", k -> new CountDownLatch(1)).await(20, TimeUnit.SECONDS))
            .isTrue();
        cancelByProcess("IT_CANCEL_STOPPABLE", jobId, "it-req-sum", false).expectStatus().isOk();
        GATES.get(label + ":2").countDown();
        assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(outcome(jobId)).containsEntry("status", "CANCELLED").containsEntry("attempts", 2);
        deliverer.deliverPending().block();
        assertThat(callbacks(jobId)).singleElement().satisfies(callback -> {
            ExternalJobCancellation in = (ExternalJobCancellation) callback.input();
            assertThat(in.attempts()).isEqualTo(2);
            assertThat(in.usage()).containsEntry("promptTokens", 14L).containsEntry("completionTokens", 11L)
                .isEqualTo(recordedUsage(jobId));
        });
    }

    /** A running attempt that then fails for good does not turn the cancelled job into a failed one. */
    @Test
    void aRunningAttemptFailingAfterTheCancelLeavesItCancelled() throws Exception {
        String label = unique("fail-after");
        GATES.put(label + ":1", new CountDownLatch(1));
        String jobId = start("IT_START_STOPPABLE", label, "it-req-fail");
        CompletableFuture<Integer> running = executor.runPending().toFuture();
        assertThat(STARTED.computeIfAbsent(label + ":1", k -> new CountDownLatch(1)).await(20, TimeUnit.SECONDS))
            .isTrue();
        cancelByProcess("IT_CANCEL_STOPPABLE", jobId, "it-req-fail", false).expectStatus().isOk();
        GATES.get(label + ":1").countDown();
        assertThat(running.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(attempts(jobId)).containsExactly("1:FAILED");
        assertThat(outcome(jobId)).containsEntry("status", "CANCELLED");
        deliverer.deliverPending().block();
        assertThat(callbacks(jobId)).singleElement().satisfies(callback ->
            assertThat(callback.process()).isEqualTo("IT_JOB_STOPPED"));
    }

    /**
     * A process that cancels inside a long transaction holds the job's row: the executor's claim does not wait for it
     * (SKIP LOCKED, Low 1) and records the expired lease at the next poll.
     */
    @Test
    void theClaimDoesNotWaitForAJobRowAnotherTransactionHolds() throws Exception {
        String jobId = start("IT_START_STOPPABLE", unique("held"), "it-req-held");
        insertClaim(jobId, 1, START.minusSeconds(1));
        try (Connection holder = DB.connect(schema())) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                "SELECT job_id FROM sys_external_job WHERE job_id = ? FOR UPDATE")) {
                lock.setString(1, jobId);
                lock.executeQuery().close();
            }
            CompletableFuture<Integer> poll = executor.runPending().toFuture();
            poll.get(20, TimeUnit.SECONDS);
            assertThat(attempts(jobId)).isEmpty();
            holder.rollback();
        }
        executor.runPending().block();
        assertThat(attempts(jobId)).contains("1:LEASE_EXPIRED");
    }

    // ---------------------------------------------------------------- helpers

    private static void insertClaim(String jobId, int attempt, java.time.Instant leaseUntil) {
        execute("INSERT INTO sys_external_job_claim (job_id, attempt_no, instance_id, claimed_time, lease_until)"
            + " VALUES (?, ?, 'it-crashed', ?, ?)", jobId, attempt,
            java.sql.Timestamp.from(leaseUntil.minusSeconds(60)), java.sql.Timestamp.from(leaseUntil));
    }

    private static List<String> attempts(String jobId) {
        return query("SELECT attempt_no, outcome FROM sys_external_job_attempt WHERE job_id = ? ORDER BY attempt_no",
            jobId).stream().map(row -> row.get("attempt_no") + ":" + row.get("outcome")).toList();
    }

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
        return cancelByProcess(process, jobId, actor, failAfter, null);
    }

    private WebTestClient.ResponseSpec cancelByProcess(String process, String jobId, String actor, boolean failAfter,
        String owner) {
        Map<String, Object> body = new HashMap<>();
        body.put("jobId", jobId);
        body.put("failAfter", failAfter);
        body.put("owner", owner);
        return client.post().uri("/api/processes/" + process + "/latest")
            .header(HttpHeaders.AUTHORIZATION, bearer(actor, "it.job.cancel"))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
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
