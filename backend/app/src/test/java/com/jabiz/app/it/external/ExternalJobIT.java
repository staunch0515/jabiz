package com.jabiz.app.it.external;

import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.app.commerce.ModerateText;
import com.jabiz.app.commerce.ProductCopy;
import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.app.it.fixture.StubAiServer;
import com.jabiz.context.RequestContext;
import com.jabiz.entity.Violation;
import com.jabiz.external.ExternalHttp;
import com.jabiz.external.ExternalJobCompletion;
import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobException;
import com.jabiz.external.ExternalJobFailure;
import com.jabiz.external.ExternalJobHandler;
import com.jabiz.external.ExternalJobResult;
import com.jabiz.external.ExternalJobType;
import com.jabiz.external.ExternalServices;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.event.OutboxDeliverer;
import com.jabiz.runtime.external.ExternalJobExecutor;
import com.jabiz.runtime.external.ExternalJobRecorder;
import com.jabiz.runtime.external.ExternalJobRegistry;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.process.steps.StartExternalJob;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.test.StubHttpServer;
import com.jabiz.runtime.test.TestTokens;
import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asynchronous external jobs (decision D38 items 3–5) against a real PostgreSQL and a local stub of {@code demo-ai}:
 * a process starts a job and answers at once; the executor runs the handler outside any transaction, once per attempt
 * across instances, within its type's concurrency, timeout and backoff, with the job's idempotency key on every
 * attempt; a result, a failure for good or a cancellation is recorded once, and the completion or failure process
 * runs once, as the system. Usage is recorded per attempt; requesters see their job's status and cancel it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "it.sql-log.enabled=true",
    "jabiz.events.delivery.initial-backoff=0s"})
class ExternalJobIT extends PostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    static final StubAiServer STUB = StubAiServer.start();
    static final List<Map<String, String>> OBSERVED = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void demoAi(DynamicPropertyRegistry registry) {
        registry.add("jabiz.external-services.demo-ai.base-url", STUB::baseUrl);
        registry.add("jabiz.external-services.demo-ai.allowed-hosts", () -> "127.0.0.1");
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    // ---------------------------------------------------------------- fixtures

    public record ItInput(String label) {}

    public record ItResult(String label, int attempt) {}

    /** The name says it is a secret; the declaration does not: only the step catches it. */
    public record LeakyInput(String label, String accessToken) {}

    public record StartInput(String label, String reference, Boolean failAfter, String accessToken) {}

    public record StartOutput(String jobId) {}

    public record CallbackOutput(String jobId) {}

    /** A callback as the test's completion and failure processes saw it, and who ran them. */
    record Callback(String process, String jobId, String actor, Object payload) {}

    static final List<Callback> CALLBACKS = new CopyOnWriteArrayList<>();
    /** {@code label:attempt} of every handler run. */
    static final List<String> RUNS = new CopyOnWriteArrayList<>();
    /** Gates by {@code label:attempt}: the handler waits until the test opens it. */
    static final Map<String, CountDownLatch> GATES = new ConcurrentHashMap<>();
    /** Latches by {@code label:attempt} counted down when the handler starts. */
    static final Map<String, CountDownLatch> STARTED = new ConcurrentHashMap<>();
    /** What the handler of label {@code fail-*} does per attempt: "permanent", "retry", "throw", or nothing. */
    static final Map<String, List<String>> BEHAVIOUR = new ConcurrentHashMap<>();

    /** Waits at its gate, if any; fails as told; calls the stub's path when the label says so. */
    public static class GateHandler implements ExternalJobHandler<ItInput, ItResult> {
        @Override
        public ExternalJobResult<ItResult> run(ItInput input, ExternalJobContext ctx) {
            String key = input.label() + ":" + ctx.attempt();
            RUNS.add(key);
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
            ctx.recordUsage("units", 1);
            if (input.label().startsWith("call-")) {
                ctx.http(ModerateText.SERVICE).send(ExternalHttp.Request.post("/v1/" + input.label().substring(5))
                    .json("{}").idempotencyKey(ctx.idempotencyKey()));
            }
            List<String> behaviour = BEHAVIOUR.getOrDefault(input.label(), List.of());
            String now = behaviour.size() >= ctx.attempt() ? behaviour.get(ctx.attempt() - 1) : "";
            switch (now) {
                case "permanent" -> throw ExternalJobException.permanent("IT_REFUSED", "Refused for the test");
                case "retry" -> throw ExternalJobException.retryable("IT_BUSY", "Busy for the test");
                case "throw" -> throw new IllegalStateException("secret detail that must not be stored");
                default -> {
                    // succeeds
                }
            }
            return ExternalJobResult.of(new ItResult(input.label(), ctx.attempt())).usage("units", 2);
        }
    }

    public static class LeakyHandler implements ExternalJobHandler<LeakyInput, ItResult> {
        @Override
        public ExternalJobResult<ItResult> run(LeakyInput input, ExternalJobContext ctx) {
            return ExternalJobResult.of(new ItResult(input.label(), ctx.attempt()));
        }
    }

    static void requireSystem(ProcessContext ctx) {
        if (!RequestContext.SYSTEM_ACTOR.equals(ctx.request().actorId())) {
            throw new PermissionDeniedException("it.job.callback", "Callbacks come from the platform only");
        }
    }

    static ProcessDefinition<StartInput, StartOutput, ProcessContext> starter(String name, String jobType,
        boolean leaky) {
        return ProcessDefinition.define(name, 1, StartInput.class, StartOutput.class, ProcessContext.class, pb -> pb
            .permissions("it.job.start")
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put("in", input);
                return ctx;
            })
            .outputMapper(ctx -> new StartOutput(ctx.get("jobId", String.class)))
            .step("Start", StartExternalJob.<ProcessContext>of(jobType, ctx -> {
                    StartInput in = ctx.get("in", StartInput.class);
                    return leaky ? new LeakyInput(in.label(), in.accessToken()) : new ItInput(in.label());
                })
                .reference(ctx -> ctx.get("in", StartInput.class).reference())
                .outputKey("jobId"))
            .compute("Maybe fail", (metadata, ctx) -> {
                if (Boolean.TRUE.equals(ctx.get("in", StartInput.class).failAfter())) {
                    ctx.reject(new Violation("label", "IT_FAIL_AFTER", "fails after the job was started"));
                }
            }));
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
            .compute("Refuse others", (metadata, ctx) -> requireSystem(ctx))
            .compute("Record", (metadata, ctx) -> {
                Object in = ctx.get("in");
                CALLBACKS.add(new Callback(name, jobId(in), ctx.request().actorId(), in));
            }));
    }

    static String jobId(Object input) {
        return input instanceof ExternalJobCompletion c ? c.jobId() : ((ExternalJobFailure) input).jobId();
    }

    static ExternalJobType<ItInput, ItResult> gateType(String name, int maxConcurrency, Duration timeout,
        int attempts) {
        return ExternalJobType.define(name, ItInput.class, ItResult.class, t -> t
            .handler(GateHandler.class).services(ModerateText.SERVICE).timeout(timeout)
            .retry(attempts, Duration.ofSeconds(10), Duration.ofMinutes(1)).maxConcurrency(maxConcurrency)
            .onCompleted("IT_JOB_DONE").onFailed("IT_JOB_FAILED"));
    }

    @TestConfiguration
    static class Fixtures {

        @Bean
        GateHandler gateHandler() {
            return new GateHandler();
        }

        @Bean
        LeakyHandler leakyHandler() {
            return new LeakyHandler();
        }

        /** Two at a time, three attempts, a timeout long enough for the gates. */
        @Bean
        ExternalJobType<ItInput, ItResult> itGateJob() {
            return gateType("it.gate", 2, Duration.ofSeconds(30), 3);
        }

        /** One at a time across instances, one attempt. */
        @Bean
        ExternalJobType<ItInput, ItResult> itSerialJob() {
            return gateType("it.serial", 1, Duration.ofSeconds(30), 1);
        }

        /** A timeout of a second. */
        @Bean
        ExternalJobType<ItInput, ItResult> itQuickJob() {
            return gateType("it.quick", 2, Duration.ofSeconds(1), 3);
        }

        @Bean
        ExternalJobType<LeakyInput, ItResult> itLeakyJob() {
            return ExternalJobType.define("it.leaky", LeakyInput.class, ItResult.class, t -> t
                .handler(LeakyHandler.class).onCompleted("IT_JOB_DONE").onFailed("IT_JOB_FAILED"));
        }

        @Bean
        ProcessDefinition<StartInput, StartOutput, ProcessContext> startGate() {
            return starter("IT_START_GATE", "it.gate", false);
        }

        @Bean
        ProcessDefinition<StartInput, StartOutput, ProcessContext> startSerial() {
            return starter("IT_START_SERIAL", "it.serial", false);
        }

        @Bean
        ProcessDefinition<StartInput, StartOutput, ProcessContext> startQuick() {
            return starter("IT_START_QUICK", "it.quick", false);
        }

        @Bean
        ProcessDefinition<StartInput, StartOutput, ProcessContext> startLeaky() {
            return starter("IT_START_LEAKY", "it.leaky", true);
        }

        @Bean
        ProcessDefinition<ExternalJobCompletion, CallbackOutput, ProcessContext> itJobDone() {
            return callback("IT_JOB_DONE", ExternalJobCompletion.class);
        }

        @Bean
        ProcessDefinition<ExternalJobFailure, CallbackOutput, ProcessContext> itJobFailed() {
            return callback("IT_JOB_FAILED", ExternalJobFailure.class);
        }

        @Bean
        ObservationHandler<Observation.Context> externalJobObservations() {
            return new ObservationHandler<>() {
                @Override
                public void onStop(Observation.Context context) {
                    if (context.getName().equals(PlatformObservations.EXTERNAL_JOB)) {
                        Map<String, String> tags = new TreeMap<>();
                        for (KeyValue kv : context.getLowCardinalityKeyValues()) {
                            tags.put(kv.getKey(), kv.getValue());
                        }
                        OBSERVED.add(tags);
                    }
                }

                @Override
                public boolean supportsContext(Observation.Context context) {
                    return true;
                }
            };
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
        STUB.reset();
        OBSERVED.clear();
    }

    /**
     * Finishes what a test left open (jobs it started but did not run, retries not yet due), so that the counts of
     * the next test's runs are its own: the tests share one database.
     */
    @AfterEach
    void finishLeftovers() {
        GATES.values().forEach(CountDownLatch::countDown);
        STUB.reset();
        for (int round = 0; round < 6; round++) {
            clock.advance(Duration.ofHours(1));
            executor.runPending().block();
        }
        deliverer.deliverPending().block();
        assertThat(query("SELECT j.job_id FROM sys_external_job j WHERE NOT EXISTS (SELECT 1 FROM"
            + " sys_external_job_outcome o WHERE o.job_id = j.job_id)")).as("jobs left open").isEmpty();
    }

    // ---------------------------------------------------------------- starting

    @Test
    void aProcessStartsAJobAndAnswersAtOnce() {
        String ref = unique("start");
        String jobId = start("IT_START_GATE", "start-" + ref, ref, "it-req-start");
        assertThat(jobId).isNotBlank();
        assertThat(RUNS).noneMatch(run -> run.startsWith("start-" + ref));
        Map<String, Object> job = query("SELECT * FROM sys_external_job WHERE job_id = ?", jobId).getFirst();
        assertThat(job).containsEntry("job_type", "it.gate").containsEntry("reference", ref)
            .containsEntry("requested_by", "it-req-start").containsEntry("input", "{\"label\":\"start-" + ref + "\"}");
        assertThat(query("SELECT process_name FROM op_process WHERE process_seq_id = ?", job.get("process_seq_id"))
            .getFirst()).containsEntry("process_name", "IT_START_GATE");
        assertThat(status(jobId, "it-req-start").get("status")).isEqualTo("QUEUED");
    }

    @Test
    void aProcessThatFailsAfterStartingLeavesNoJob() {
        String ref = unique("rollback");
        process("IT_START_GATE", Map.of("label", "rb", "reference", ref, "failAfter", true), "it-req")
            .expectStatus().isEqualTo(422);
        assertThat(query("SELECT 1 FROM sys_external_job WHERE reference = ?", ref)).isEmpty();
    }

    @Test
    void aReplayedRequestReturnsTheSameJob() {
        String ref = unique("replay");
        String key = "it-replay-" + ref;
        String first = output(client.post().uri("/api/processes/IT_START_GATE/latest")
            .header(HttpHeaders.AUTHORIZATION, bearer("it-req", "it.job.start")).header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("label", "replay", "reference", ref))
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody()).get("jobId").toString();
        String second = output(client.post().uri("/api/processes/IT_START_GATE/latest")
            .header(HttpHeaders.AUTHORIZATION, bearer("it-req", "it.job.start")).header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("label", "replay", "reference", ref))
            .exchange().expectStatus().isOk().expectHeader().valueEquals("Idempotency-Replayed", "true")
            .expectBody(MAP).returnResult().getResponseBody()).get("jobId").toString();
        assertThat(second).isEqualTo(first);
        assertThat(query("SELECT 1 FROM sys_external_job WHERE reference = ?", ref)).hasSize(1);
    }

    @Test
    void inputsWithSecretsOrTooLargeAreRefused() {
        String ref = unique("leak");
        Map<String, Object> leaked = process("IT_START_LEAKY", Map.of("label", "x", "reference", ref,
            "accessToken", "tok-it-very-secret"), "it-req").expectStatus().isEqualTo(422).expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(violations(leaked)).singleElement().satisfies(v -> {
            assertThat(v).containsEntry("ruleCode", "EXTERNAL_JOB_INPUT_SECRET").containsEntry("field", "accessToken");
            assertThat(String.valueOf(v)).doesNotContain("tok-it-very-secret");
        });
        // Without the secret the same input is fine.
        process("IT_START_LEAKY", Map.of("label", "x", "reference", ref), "it-req").expectStatus().isOk();

        Map<String, Object> large = process("IT_START_GATE", Map.of("label", "x".repeat(70_000),
            "reference", unique("large")), "it-req").expectStatus().isEqualTo(422).expectBody(MAP).returnResult()
            .getResponseBody();
        assertThat(violations(large)).singleElement().satisfies(v ->
            assertThat(v).containsEntry("ruleCode", "EXTERNAL_JOB_INPUT_TOO_LARGE"));
        assertThat(query("SELECT input FROM sys_external_job WHERE reference = ?", ref)).singleElement()
            .satisfies(row -> assertThat(row.get("input").toString()).contains("\"accessToken\":null"));
    }

    // ---------------------------------------------------------------- the demo: marketing copy

    @Test
    void theCopyJobRunsOnceAndItsCompletionProcessRunsOnceAsTheSystem() {
        String sku = sku();
        String productId = product(sku, "Blue mug");
        Map<String, Object> started = client.post().uri("/api/processes/" + ProductCopy.REQUEST + "/latest")
            .header(HttpHeaders.AUTHORIZATION, bearer("it-copywriter", "commerce.product.write"))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("productId", productId, "language", "en"))
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        String jobId = output(started).get("jobId").toString();
        assertThat(STUB.requests("/v1/generate")).isEmpty();

        assertThat(executor.runPending().block()).isEqualTo(1);
        var call = STUB.requests("/v1/generate");
        assertThat(call).singleElement().satisfies(request -> {
            assertThat(request.header("Idempotency-Key")).isEqualTo("jabiz-job-" + jobId);
            assertThat(request.header("Authorization")).isEqualTo("Bearer test-only-demo-ai-key");
            assertThat(request.body()).contains("Blue mug", sku, "\"language\":\"en\"");
        });
        assertThat(outcome(jobId)).containsEntry("status", "SUCCEEDED").containsEntry("attempts", 1);
        assertThat(usage(jobId)).containsExactly(
            Map.entry("1:completionTokens", StubAiServer.COMPLETION_TOKENS),
            Map.entry("1:promptTokens", StubAiServer.PROMPT_TOKENS));
        assertThat(query("SELECT payload FROM sys_outbox_event WHERE event_type = 'jabiz.external-job.finished'"
            + " AND entity_id = ?", jobId)).singleElement().satisfies(row -> assertThat(row.get("payload").toString())
            .contains(jobId, "SUCCEEDED", ProductCopy.JOB_TYPE).doesNotContain("Discover"));
        assertThat(OBSERVED).contains(Map.of("job_type", ProductCopy.JOB_TYPE, "outcome", "SUCCEEDED"));
        assertThat(marketingCopy(sku)).isNull();

        deliverer.deliverPending().block();
        assertThat(marketingCopy(sku)).isEqualTo(StubAiServer.GENERATED_PREFIX + "Blue mug.");
        assertThat(query("SELECT actor_id FROM op_process WHERE process_name = ? AND input_summary::text LIKE ?",
            ProductCopy.APPLY, "%" + jobId + "%")).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("actor_id", RequestContext.SYSTEM_ACTOR));
        assertThat(query("SELECT title_key FROM sys_task_version WHERE assignee_user_id = 'it-copywriter'"
            + " AND task_type = ? AND subject_id = ?", ProductCopy.TASK, productId)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("title_key", "task.commerce.product-copy.ready"));

        // Nothing is due any more, and the event is consumed: neither runs again.
        assertThat(executor.runPending().block()).isZero();
        deliverer.deliverPending().block();
        assertThat(STUB.requests("/v1/generate")).hasSize(1);
        assertThat(query("SELECT 1 FROM op_process WHERE process_name = ? AND input_summary::text LIKE ?",
            ProductCopy.APPLY, "%" + jobId + "%")).hasSize(1);
        Map<String, Object> status = status(jobId, "it-copywriter");
        assertThat(status).containsEntry("status", "SUCCEEDED").containsEntry("attempts", 1)
            .containsEntry("reference", productId).doesNotContainKeys("input", "result");
        assertThat(status.get("usage")).isEqualTo(Map.of("promptTokens", 12, "completionTokens", 34));
    }

    @Test
    void failedAttemptsAreRetriedAfterTheirBackoffWithTheSameKeyAndTheirUsageKept() {
        String sku = sku();
        String productId = product(sku, "Green mug");
        String busy = "{\"error\":\"busy\",\"usage\":{\"promptTokens\":5}}";
        STUB.script("/v1/generate", 503, busy, null);
        STUB.script("/v1/generate", 503, busy, null);
        String jobId = copy(productId);

        assertThat(executor.runPending().block()).isEqualTo(1);
        assertThat(status(jobId, "it-copywriter")).containsEntry("status", "WAITING_RETRY")
            .containsEntry("errorCode", "PROVIDER_UNAVAILABLE");
        // The backoff is ten seconds after the first failure, twenty after the second.
        assertThat(executor.runPending().block()).isZero();
        clock.advance(Duration.ofSeconds(10));
        assertThat(executor.runPending().block()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(19));
        assertThat(executor.runPending().block()).isZero();
        clock.advance(Duration.ofSeconds(1));
        assertThat(executor.runPending().block()).isEqualTo(1);

        assertThat(STUB.requests("/v1/generate")).hasSize(3)
            .extracting(request -> request.header("Idempotency-Key")).containsOnly("jabiz-job-" + jobId);
        assertThat(attempts(jobId)).containsExactly("1:FAILED:true:PROVIDER_UNAVAILABLE", "2:FAILED:true:PROVIDER_UNAVAILABLE",
            "3:SUCCEEDED:false:null");
        assertThat(usage(jobId)).containsEntry("1:promptTokens", 5L).containsEntry("2:promptTokens", 5L)
            .containsEntry("3:promptTokens", 12L).containsEntry("3:completionTokens", 34L);
        assertThat(outcome(jobId)).containsEntry("status", "SUCCEEDED").containsEntry("attempts", 3);
        assertThat(query("SELECT claimed_time FROM sys_external_job_claim WHERE job_id = ? ORDER BY attempt_no",
            jobId)).hasSize(3);
    }

    @Test
    void aPermanentFailureIsNotRetriedAndTheFailureProcessTellsTheRequester() {
        String sku = sku();
        String productId = product(sku, "Red mug");
        STUB.script("/v1/generate", 400, "{\"error\":\"policy\"}", null);
        String jobId = copy(productId);
        assertThat(executor.runPending().block()).isEqualTo(1);
        clock.advance(Duration.ofHours(1));
        assertThat(executor.runPending().block()).isZero();
        assertThat(outcome(jobId)).containsEntry("status", "FAILED").containsEntry("error_code", "GENERATION_REFUSED")
            .containsEntry("result", null);
        deliverer.deliverPending().block();
        deliverer.deliverPending().block();
        assertThat(query("SELECT title_key, title_params FROM sys_task_version WHERE assignee_user_id = 'it-copywriter'"
            + " AND subject_id = ?", productId)).singleElement().satisfies(row -> {
                assertThat(row).containsEntry("title_key", "task.commerce.product-copy.failed");
                assertThat(row.get("title_params").toString()).contains("GENERATION_REFUSED", sku);
            });
        assertThat(query("SELECT 1 FROM op_process WHERE process_name = ? AND input_summary::text LIKE ?",
            ProductCopy.FAILED, "%" + jobId + "%")).hasSize(1);
        assertThat(marketingCopy(sku)).isNull();
    }

    @Test
    void whenTheAttemptsAreUsedUpTheFailureProcessRunsOnce() {
        String label = "fail-" + unique("used");
        BEHAVIOUR.put(label, List.of("retry", "throw", "retry"));
        String jobId = start("IT_START_GATE", label, unique("ref"), "it-req");
        assertThat(executor.runPending().block()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(10));
        assertThat(executor.runPending().block()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(20));
        assertThat(executor.runPending().block()).isEqualTo(1);
        clock.advance(Duration.ofHours(1));
        assertThat(executor.runPending().block()).isZero();
        assertThat(attempts(jobId)).containsExactly("1:FAILED:true:IT_BUSY", "2:FAILED:true:HANDLER_ERROR",
            "3:FAILED:true:IT_BUSY");
        // An arbitrary exception's message is not kept: it may repeat a request or a secret.
        assertThat(query("SELECT error_detail FROM sys_external_job_attempt WHERE job_id = ? AND attempt_no = 2",
            jobId).getFirst()).containsEntry("error_detail", "IllegalStateException");
        assertThat(outcome(jobId)).containsEntry("status", "FAILED").containsEntry("error_code", "IT_BUSY");
        deliverer.deliverPending().block();
        deliverer.deliverPending().block();
        assertThat(callbacks(jobId)).singleElement().satisfies(callback -> {
            assertThat(callback.process()).isEqualTo("IT_JOB_FAILED");
            assertThat(callback.actor()).isEqualTo(RequestContext.SYSTEM_ACTOR);
            ExternalJobFailure failure = (ExternalJobFailure) callback.payload();
            assertThat(failure.errorCode()).isEqualTo("IT_BUSY");
            assertThat(failure.errorMessage()).isEqualTo("Busy for the test");
            assertThat(failure.attempts()).isEqualTo(3);
            assertThat(failure.requestedBy()).isEqualTo("it-req");
            assertThat(failure.usage()).containsEntry("units", 3L);
        });
    }

    @Test
    void aPermanentFailureOfTheHandlerEndsTheJobAtOnce() {
        String label = "fail-" + unique("perm");
        BEHAVIOUR.put(label, List.of("permanent"));
        String jobId = start("IT_START_GATE", label, unique("ref"), "it-req");
        assertThat(executor.runPending().block()).isEqualTo(1);
        assertThat(attempts(jobId)).containsExactly("1:FAILED:false:IT_REFUSED");
        assertThat(outcome(jobId)).containsEntry("status", "FAILED").containsEntry("error_code", "IT_REFUSED");
        deliverer.deliverPending().block();
        assertThat(callbacks(jobId)).singleElement().satisfies(callback ->
            assertThat(((ExternalJobFailure) callback.payload()).errorCode()).isEqualTo("IT_REFUSED"));
    }

    @Test
    void aCompletionProcessGetsTheTypedResultUsageAndRequester() {
        String label = unique("typed");
        String ref = unique("ref");
        String jobId = start("IT_START_GATE", label, ref, "it-req-typed");
        executor.runPending().block();
        deliverer.deliverPending().block();
        assertThat(callbacks(jobId)).singleElement().satisfies(callback -> {
            assertThat(callback.process()).isEqualTo("IT_JOB_DONE");
            assertThat(callback.actor()).isEqualTo(RequestContext.SYSTEM_ACTOR);
            ExternalJobCompletion completion = (ExternalJobCompletion) callback.payload();
            assertThat(completion.result(ItResult.class)).isEqualTo(new ItResult(label, 1));
            assertThat(completion.requestedBy()).isEqualTo("it-req-typed");
            assertThat(completion.reference()).isEqualTo(ref);
            assertThat(completion.jobType()).isEqualTo("it.gate");
            assertThat(completion.attempts()).isEqualTo(1);
            assertThat(completion.usage()).containsEntry("units", 3L);
            assertThat(completion.finishedTime()).isEqualTo(START);
        });
    }

    @Test
    void anAttemptPastItsTimeoutIsTimedOutAndRetried() {
        STUB.script(StubHttpServer.Answer.json(200, "{}").after(Duration.ofSeconds(4)), "/v1/slow");
        String jobId = start("IT_START_QUICK", "call-slow", unique("ref"), "it-req");
        long began = System.nanoTime();
        assertThat(executor.runPending().block()).isEqualTo(1);
        assertThat(Duration.ofNanos(System.nanoTime() - began)).isLessThan(Duration.ofSeconds(4));
        assertThat(attempts(jobId)).containsExactly("1:TIMED_OUT:true:TIMEOUT");
        assertThat(OBSERVED).contains(Map.of("job_type", "it.quick", "outcome", "WAITING_RETRY"));
        clock.advance(Duration.ofSeconds(10));
        assertThat(executor.runPending().block()).isEqualTo(1);
        assertThat(attempts(jobId)).containsExactly("1:TIMED_OUT:true:TIMEOUT", "2:SUCCEEDED:false:null");
        assertThat(STUB.requests("/v1/slow")).hasSize(2)
            .extracting(request -> request.header("Idempotency-Key")).containsOnly("jabiz-job-" + jobId);
    }

    // ---------------------------------------------------------------- several instances

    @Test
    void twoInstancesRunAtMostMaxConcurrencyOfATypeAndEachJobOnce() throws Exception {
        ExternalJobExecutor second = secondInstance("it-second");
        List<String> jobs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            STUB.script(StubHttpServer.Answer.json(200, "{}").after(Duration.ofMillis(300)), "/v1/serial");
            jobs.add(start("IT_START_SERIAL", "call-serial", unique("serial"), "it-req"));
        }
        CompletableFuture<Void> a = CompletableFuture.runAsync(() -> drain(executor, jobs));
        CompletableFuture<Void> b = CompletableFuture.runAsync(() -> drain(second, jobs));
        CompletableFuture.allOf(a, b).get(60, TimeUnit.SECONDS);

        assertThat(STUB.maxConcurrent()).isEqualTo(1);
        assertThat(STUB.requests("/v1/serial")).hasSize(5);
        for (String job : jobs) {
            assertThat(outcome(job)).containsEntry("status", "SUCCEEDED").containsEntry("attempts", 1);
            assertThat(query("SELECT instance_id FROM sys_external_job_claim WHERE job_id = ?", job)).hasSize(1);
        }
    }

    @Test
    void twoInstancesRacingForOneJobClaimItOnce() throws Exception {
        ExternalJobExecutor second = secondInstance("it-racer");
        String label = unique("race");
        GATES.put(label + ":1", new CountDownLatch(1));
        String jobId = start("IT_START_GATE", label, unique("ref"), "it-req");
        CountDownLatch go = new CountDownLatch(1);
        CompletableFuture<Integer> a = CompletableFuture.supplyAsync(() -> await(go, executor));
        CompletableFuture<Integer> b = CompletableFuture.supplyAsync(() -> await(go, second));
        go.countDown();
        assertThat(STARTED.computeIfAbsent(label + ":1", k -> new CountDownLatch(1)).await(20, TimeUnit.SECONDS))
            .isTrue();
        GATES.get(label + ":1").countDown();
        assertThat(a.get(30, TimeUnit.SECONDS) + b.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(RUNS).filteredOn(run -> run.startsWith(label + ":")).containsExactly(label + ":1");
        assertThat(query("SELECT attempt_no FROM sys_external_job_claim WHERE job_id = ?", jobId)).hasSize(1);
        assertThat(outcome(jobId)).containsEntry("status", "SUCCEEDED");
    }

    @Test
    void anExpiredLeaseIsRecordedAndTheJobTakenAgain() {
        String label = unique("crash");
        String jobId = start("IT_START_GATE", label, unique("ref"), "it-req");
        // An instance claimed the first attempt and stopped: its lease ended a minute ago.
        execute("INSERT INTO sys_external_job_claim (job_id, attempt_no, instance_id, claimed_time, lease_until)"
            + " VALUES (?, 1, 'it-crashed', ?, ?)", jobId, java.sql.Timestamp.from(START.minusSeconds(120)),
            java.sql.Timestamp.from(START.minusSeconds(60)));
        assertThat(status(jobId, "it-req").get("status")).isEqualTo("WAITING_RETRY");
        assertThat(executor.runPending().block()).isZero();
        assertThat(attempts(jobId)).containsExactly("1:LEASE_EXPIRED:true:LEASE_EXPIRED");
        clock.advance(Duration.ofSeconds(10));
        assertThat(executor.runPending().block()).isEqualTo(1);
        assertThat(attempts(jobId)).containsExactly("1:LEASE_EXPIRED:true:LEASE_EXPIRED", "2:SUCCEEDED:false:null");
        assertThat(RUNS).filteredOn(run -> run.startsWith(label + ":")).containsExactly(label + ":2");
    }

    @Test
    void aLateInstanceDropsItsResultAndTheCompletionRunsOnce() throws Exception {
        String label = unique("zombie");
        GATES.put(label + ":1", new CountDownLatch(1));
        String jobId = start("IT_START_GATE", label, unique("ref"), "it-req");
        CompletableFuture<Integer> zombie = executor.runPending().toFuture();
        assertThat(STARTED.computeIfAbsent(label + ":1", k -> new CountDownLatch(1)).await(20, TimeUnit.SECONDS))
            .isTrue();
        assertThat(status(jobId, "it-req").get("status")).isEqualTo("RUNNING");

        // The lease (timeout 30 s and 30 s of margin) runs out: another instance takes the job over.
        ExternalJobExecutor other = secondInstance("it-takeover");
        clock.advance(Duration.ofSeconds(61));
        assertThat(other.runPending().block()).isZero();
        clock.advance(Duration.ofSeconds(10));
        assertThat(other.runPending().block()).isEqualTo(1);
        assertThat(outcome(jobId)).containsEntry("status", "SUCCEEDED").containsEntry("attempts", 2);

        GATES.get(label + ":1").countDown();
        assertThat(zombie.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(attempts(jobId)).containsExactly("1:LEASE_EXPIRED:true:LEASE_EXPIRED", "2:SUCCEEDED:false:null");
        assertThat(query("SELECT result FROM sys_external_job_outcome WHERE job_id = ?", jobId)).singleElement()
            .satisfies(row -> assertThat(row.get("result").toString()).contains("\"attempt\":2"));
        assertThat(OBSERVED).contains(Map.of("job_type", "it.gate", "outcome", "DISCARDED"));
        deliverer.deliverPending().block();
        deliverer.deliverPending().block();
        assertThat(callbacks(jobId)).singleElement().satisfies(callback ->
            assertThat(((ExternalJobCompletion) callback.payload()).result(ItResult.class).attempt()).isEqualTo(2));
    }

    /**
     * While a handler runs, the application holds no transaction open: no connection of it is idle in a transaction
     * that touched the jobs' tables.
     */
    @Test
    void noTransactionIsHeldWhileAHandlerRuns() throws Exception {
        String label = unique("idle");
        GATES.put(label + ":1", new CountDownLatch(1));
        String jobId = start("IT_START_GATE", label, unique("ref"), "it-req");
        CompletableFuture<Integer> running = executor.runPending().toFuture();
        assertThat(STARTED.computeIfAbsent(label + ":1", k -> new CountDownLatch(1)).await(20, TimeUnit.SECONDS))
            .isTrue();
        // Give a transaction that would be left open the time to show.
        Thread.sleep(200);
        assertThat(query("SELECT pid, state, query FROM pg_stat_activity WHERE datname = current_database()"
            + " AND state LIKE 'idle in transaction%' AND query ILIKE '%external_job%'")).isEmpty();
        assertThat(query("SELECT pid FROM pg_stat_activity WHERE datname = current_database()"
            + " AND state = 'active' AND query ILIKE '%sys_external_job%' AND pid <> pg_backend_pid()")).isEmpty();
        GATES.get(label + ":1").countDown();
        assertThat(running.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(outcome(jobId)).containsEntry("status", "SUCCEEDED");
    }

    // ---------------------------------------------------------------- cancel and status

    @Test
    void aQueuedJobCancelledIsNeverRunNorCalledBack() {
        String label = unique("cancel-queued");
        String jobId = start("IT_START_GATE", label, unique("ref"), "it-req-c");
        Map<String, Object> cancelled = cancel(jobId, bearer("it-req-c")).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(cancelled).containsEntry("status", "CANCELLED").containsEntry("attempts", 0);
        assertThat(outcome(jobId)).containsEntry("status", "CANCELLED");
        assertThat(query("SELECT cancelled_by FROM sys_external_job_cancel WHERE job_id = ?", jobId).getFirst())
            .containsEntry("cancelled_by", "it-req-c");
        assertThat(executor.runPending().block()).isZero();
        assertThat(RUNS).noneMatch(run -> run.startsWith(label + ":"));
        deliverer.deliverPending().block();
        assertThat(callbacks(jobId)).isEmpty();
        // Finished: cancelling again is refused.
        Map<String, Object> again = cancel(jobId, bearer("it-req-c")).expectStatus().isEqualTo(422).expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(violations(again)).singleElement().satisfies(v ->
            assertThat(v).containsEntry("ruleCode", "EXTERNAL_JOB_FINISHED"));
    }

    @Test
    void aRunningJobCancelledDropsItsResult() throws Exception {
        String label = unique("cancel-running");
        GATES.put(label + ":1", new CountDownLatch(1));
        String jobId = start("IT_START_GATE", label, unique("ref"), "it-req-r");
        CompletableFuture<Integer> running = executor.runPending().toFuture();
        assertThat(STARTED.computeIfAbsent(label + ":1", k -> new CountDownLatch(1)).await(20, TimeUnit.SECONDS))
            .isTrue();
        assertThat(cancel(jobId, bearer("it-req-r")).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody()).containsEntry("status", "CANCELLED");
        assertThat(query("SELECT 1 FROM sys_external_job_outcome WHERE job_id = ?", jobId)).isEmpty();
        GATES.get(label + ":1").countDown();
        assertThat(running.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(outcome(jobId)).containsEntry("status", "CANCELLED").containsEntry("result", null);
        assertThat(attempts(jobId)).containsExactly("1:SUCCEEDED:false:null");
        assertThat(OBSERVED).contains(Map.of("job_type", "it.gate", "outcome", "CANCELLED"));
        deliverer.deliverPending().block();
        assertThat(callbacks(jobId)).isEmpty();
    }

    @Test
    void onlyTheRequesterOrHoldersOfThePermissionsSeeOrCancelAJob() {
        String jobId = start("IT_START_GATE", unique("private"), unique("ref"), "it-owner");
        get("/api/external-jobs/" + jobId, bearer("it-owner")).expectStatus().isOk();
        get("/api/external-jobs/" + jobId, bearer("it-stranger")).expectStatus().isNotFound();
        get("/api/external-jobs/" + jobId, bearer("it-stranger", "external-job.cancel")).expectStatus().isNotFound();
        get("/api/external-jobs/" + jobId, bearer("it-auditor", "external-job.read")).expectStatus().isOk()
            .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store");
        get("/api/external-jobs/" + UUID.randomUUID(), bearer("it-auditor", "external-job.read"))
            .expectStatus().isNotFound();
        cancel(jobId, bearer("it-stranger")).expectStatus().isNotFound();
        cancel(jobId, bearer("it-auditor", "external-job.read")).expectStatus().isNotFound();
        assertThat(query("SELECT 1 FROM sys_external_job_cancel WHERE job_id = ?", jobId)).isEmpty();
        cancel(jobId, bearer("it-operator", "external-job.cancel")).expectStatus().isOk();
        assertThat(query("SELECT cancelled_by FROM sys_external_job_cancel WHERE job_id = ?", jobId).getFirst())
            .containsEntry("cancelled_by", "it-operator");
        get("/api/external-jobs/" + jobId, null).expectStatus().isUnauthorized();
    }

    @Test
    void completionProcessesCannotBeRunThroughTheProcessApi() {
        String jobId = start("IT_START_GATE", unique("forge"), unique("ref"), "it-req");
        Map<String, Object> forged = Map.of("jobId", jobId, "jobType", "it.gate", "requestedBy", "it-req",
            "result", Map.of("label", "forged", "attempt", 1), "attempts", 1);
        for (String process : List.of("IT_JOB_DONE", ProductCopy.APPLY)) {
            client.post().uri("/api/processes/" + process + "/latest").header(HttpHeaders.AUTHORIZATION,
                    bearer("it-admin", "*")).contentType(MediaType.APPLICATION_JSON).bodyValue(forged).exchange()
                .expectStatus().isForbidden();
        }
        client.post().uri("/api/processes/EXTERNAL_JOB_COMPLETE/latest").header(HttpHeaders.AUTHORIZATION,
                bearer("it-admin", "*")).contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("jobId", jobId))
            .exchange().expectStatus().isForbidden();
        assertThat(callbacks(jobId)).isEmpty();
    }

    @Test
    void usageIsReportedByTypeMetricAndMonth() {
        String jobId = start("IT_START_GATE", unique("usage"), unique("ref"), "it-req");
        executor.runPending().block();
        assertThat(outcome(jobId)).containsEntry("status", "SUCCEEDED");
        Map<String, Object> page = client.post().uri("/api/queries/jabiz.external_job_usage")
            .header(HttpHeaders.AUTHORIZATION, bearer("it-auditor", "external-job.read"))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("params", Map.of("jobType", "it.gate")))
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(String.valueOf(page)).contains("it.gate", "units", "2026-01");
        client.post().uri("/api/queries/jabiz.external_job_usage")
            .header(HttpHeaders.AUTHORIZATION, bearer("it-stranger", "it.job.start"))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("params", Map.of()))
            .exchange().expectStatus().isForbidden();
    }

    @Test
    void theJobTablesAreNeverUpdatedOrDeleted() {
        SqlStatementLog.STATEMENTS.clear();
        String label = "fail-" + unique("append");
        BEHAVIOUR.put(label, List.of("retry"));
        String jobId = start("IT_START_GATE", label, unique("ref"), "it-req");
        executor.runPending().block();
        clock.advance(Duration.ofSeconds(10));
        executor.runPending().block();
        String cancelled = start("IT_START_GATE", unique("append-cancel"), unique("ref"), "it-req");
        cancel(cancelled, bearer("it-req")).expectStatus().isOk();
        deliverer.deliverPending().block();
        assertThat(outcome(jobId)).containsEntry("status", "SUCCEEDED");
        assertThat(SqlStatementLog.STATEMENTS).anyMatch(sql -> sql.contains("sys_external_job_outcome"));
        assertThat(SqlStatementLog.STATEMENTS).noneMatch(sql -> sql.matches(
            "(?is).*(UPDATE|DELETE\\s+FROM|TRUNCATE)\\s+sys_external_job\\w*\\b.*"));
        for (String table : List.of("sys_external_job", "sys_external_job_claim", "sys_external_job_attempt",
            "sys_external_job_usage", "sys_external_job_cancel", "sys_external_job_outcome")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> execute("DELETE FROM " + table))
                .hasMessageContaining("append-only");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> execute("UPDATE " + table
                + " SET job_id = job_id")).hasMessageContaining("append-only");
        }
    }

    // ---------------------------------------------------------------- helpers

    private ExternalJobExecutor secondInstance(String id) {
        return new ExternalJobExecutor(context.getBean(ExternalJobRegistry.class),
            context.getBean(ExternalJobRecorder.class), context, context.getBean(ExternalServices.class),
            context.getBean(JsonMapper.class), context.getBean(Clock.class),
            context.getBean(PlatformObservations.class), context.getBeanProvider(MeterRegistry.class),
            executor.settings().withInstanceId(id));
    }

    private static void drain(ExternalJobExecutor instance, List<String> jobs) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (System.nanoTime() < until) {
            instance.runPending().block();
            if (query("SELECT count(*) AS n FROM sys_external_job_outcome WHERE job_id = ANY(?)",
                (Object) jobs.toArray(String[]::new)).getFirst().get("n").equals(5L)) {
                return;
            }
        }
    }

    private static int await(CountDownLatch go, ExternalJobExecutor instance) {
        try {
            go.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return instance.runPending().block();
    }

    private static final AtomicInteger SEQ = new AtomicInteger();

    private static String unique(String prefix) {
        return prefix + "-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    private static String sku() {
        return "J" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
    }

    private String bearer(String actor, String... permissions) {
        return TestTokens.bearer(tokens, actor, permissions);
    }

    private WebTestClient.ResponseSpec process(String name, Map<String, Object> input, String actor) {
        return client.post().uri("/api/processes/" + name + "/latest")
            .header(HttpHeaders.AUTHORIZATION, bearer(actor, "it.job.start"))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(input).exchange();
    }

    private String start(String process, String label, String reference, String actor) {
        Map<String, Object> result = process(process, Map.of("label", label, "reference", reference), actor)
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return output(result).get("jobId").toString();
    }

    private String copy(String productId) {
        Map<String, Object> started = client.post().uri("/api/processes/" + ProductCopy.REQUEST + "/latest")
            .header(HttpHeaders.AUTHORIZATION, bearer("it-copywriter", "commerce.product.write"))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("productId", productId))
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return output(started).get("jobId").toString();
    }

    private WebTestClient.ResponseSpec get(String path, String authorization) {
        var request = client.get().uri(path);
        if (authorization != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return request.exchange();
    }

    private WebTestClient.ResponseSpec cancel(String jobId, String authorization) {
        return client.post().uri("/api/external-jobs/" + jobId + "/cancel")
            .header(HttpHeaders.AUTHORIZATION, authorization).exchange();
    }

    private Map<String, Object> status(String jobId, String actor) {
        return get("/api/external-jobs/" + jobId, bearer(actor)).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
    }

    private static Map<String, Object> outcome(String jobId) {
        List<Map<String, Object>> rows = query("SELECT status, result, error_code, attempts"
            + " FROM sys_external_job_outcome WHERE job_id = ?", jobId);
        assertThat(rows).as("outcome of " + jobId).hasSize(1);
        return rows.getFirst();
    }

    private static List<String> attempts(String jobId) {
        return query("SELECT attempt_no, outcome, retryable, error_code FROM sys_external_job_attempt"
            + " WHERE job_id = ? ORDER BY attempt_no", jobId).stream()
            .map(row -> row.get("attempt_no") + ":" + row.get("outcome") + ":" + row.get("retryable") + ":"
                + row.get("error_code"))
            .toList();
    }

    private static Map<String, Long> usage(String jobId) {
        Map<String, Long> usage = new TreeMap<>();
        query("SELECT attempt_no, metric, quantity FROM sys_external_job_usage WHERE job_id = ?", jobId)
            .forEach(row -> usage.put(row.get("attempt_no") + ":" + row.get("metric"),
                ((Number) row.get("quantity")).longValue()));
        return usage;
    }

    private static List<Callback> callbacks(String jobId) {
        return CALLBACKS.stream().filter(callback -> callback.jobId().equals(jobId)).toList();
    }

    private String product(String sku, String name) {
        client.post().uri("/api/datasets/" + CommerceEntities.PRODUCT_DATASET + "/commit")
            .header(HttpHeaders.AUTHORIZATION, bearer("it-admin", "*")).contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", Map.of("sku", sku,
                "productName", name, "unitPrice", 100, "active", true))))).exchange().expectStatus().isOk();
        return String.valueOf(products(sku).getFirst().get("id"));
    }

    private String marketingCopy(String sku) {
        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = (Map<String, Object>) products(sku).getFirst().get("attributes");
        return (String) attributes.get("marketingCopy");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> products(String sku) {
        Map<String, Object> page = client.post().uri("/api/datasets/" + CommerceEntities.PRODUCT_DATASET + "/query")
            .header(HttpHeaders.AUTHORIZATION, bearer("it-admin", "*")).contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("filters", List.of(Map.of("field", "sku", "op", "eq", "value", sku))))
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return (List<Map<String, Object>>) page.get("items");
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
