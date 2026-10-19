package com.jabiz.app.it.external;

import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.external.ExternalCallException;
import com.jabiz.external.ExternalJobCancellation;
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
import com.jabiz.runtime.event.OutboxDeliverer;
import com.jabiz.runtime.external.ExternalJobExecutor;
import com.jabiz.runtime.external.ExternalJobRecorder;
import com.jabiz.runtime.external.ExternalJobRegistry;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.process.steps.StartExternalJob;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.test.TestTokens;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 * Attempts without recorded usage, and the usage of late attempts (decision D52 item 5), against a real PostgreSQL:
 * the completion, failure and cancellation processes are told how many attempts ended {@code LEASE_EXPIRED} or
 * {@code TIMED_OUT} without any usage ({@code unrecordedAttempts}), so that an application charges those by its own
 * estimate; a {@code FAILED} attempt without usage is not one of them. An instance that comes back after its lease
 * expired has its usage recorded against that attempt, once; its result and files are still dropped and the job's
 * outcome is untouched. Every table stays append-only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "it.sql-log.enabled=true",
    "jabiz.events.delivery.initial-backoff=0s"})
class ExternalJobUnrecordedIT extends PostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    public record JobInput(String label) {}

    public record JobResult(String label, int attempt) {}

    public record StartInput(String label) {}

    public record StartOutput(String jobId) {}

    public record CallbackOutput(String jobId) {}

    record Callback(String process, String jobId, Object input) {}

    static final List<Callback> CALLBACKS = new CopyOnWriteArrayList<>();
    static final Map<String, CountDownLatch> GATES = new ConcurrentHashMap<>();
    static final Map<String, CountDownLatch> STARTED = new ConcurrentHashMap<>();

    /** What every attempt that records usage records. */
    static final long UNITS = 5;

    /**
     * Records {@value #UNITS} units unless its label says {@code silent} (as a handler records its estimate before the
     * call); waits at its gate ({@code label:attempt}) if it has one; fails for good when the label starts with
     * {@code fail}, throws an {@link ExternalCallException} (no answer) when it starts with {@code callfail}, and
     * succeeds otherwise.
     */
    public static class Handler implements ExternalJobHandler<JobInput, JobResult> {
        @Override
        public ExternalJobResult<JobResult> run(JobInput input, ExternalJobContext ctx) {
            String key = input.label() + ":" + ctx.attempt();
            if (!input.label().contains("silent")) {
                ctx.recordUsage("units", UNITS);
            }
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
            if (input.label().startsWith("fail")) {
                throw ExternalJobException.permanent("IT_REFUSED", "refused for the test");
            }
            if (input.label().startsWith("callfail")) {
                // The provider did not answer in time: an EXTERNAL_CALL_FAILED attempt.
                throw new ExternalCallException("it-provider", ExternalCallException.Kind.TIMEOUT, "no answer");
            }
            return ExternalJobResult.of(new JobResult(input.label(), ctx.attempt()));
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

    static ExternalJobType<JobInput, JobResult> type(String name, int attempts, Duration timeout) {
        return ExternalJobType.define(name, JobInput.class, JobResult.class, t -> t
            .handler(Handler.class).timeout(timeout).maxConcurrency(2)
            .retry(attempts, Duration.ofSeconds(10), Duration.ofMinutes(1))
            .onCompleted("IT_U_DONE").onFailed("IT_U_FAILED").onCancelled("IT_U_CANCELLED"));
    }

    @TestConfiguration
    static class Fixtures {

        @Bean
        Handler unrecordedHandler() {
            return new Handler();
        }

        /** Two attempts. */
        @Bean
        ExternalJobType<JobInput, JobResult> itRetryJob() {
            return type("it.u.retry", 2, Duration.ofSeconds(90));
        }

        /** One attempt. */
        @Bean
        ExternalJobType<JobInput, JobResult> itOnceJob() {
            return type("it.u.once", 1, Duration.ofSeconds(90));
        }

        /** One attempt with a timeout of a second. */
        @Bean
        ExternalJobType<JobInput, JobResult> itQuickJob() {
            return type("it.u.quick", 1, Duration.ofSeconds(1));
        }

        /** One attempt with a timeout of five seconds: long enough to expire its lease on the test's clock first. */
        @Bean
        ExternalJobType<JobInput, JobResult> itSlowJob() {
            return type("it.u.slow", 1, Duration.ofSeconds(5));
        }

        @Bean
        ProcessDefinition<StartInput, StartOutput, ProcessContext> startSlow() {
            return starter("IT_U_START_SLOW", "it.u.slow");
        }

        /** One attempt; only the test of the usage report starts jobs of it, so that the report's totals are its own. */
        @Bean
        ExternalJobType<JobInput, JobResult> itReportedJob() {
            return type("it.u.reported", 1, Duration.ofSeconds(90));
        }

        @Bean
        ProcessDefinition<StartInput, StartOutput, ProcessContext> startRetry() {
            return starter("IT_U_START_RETRY", "it.u.retry");
        }

        @Bean
        ProcessDefinition<StartInput, StartOutput, ProcessContext> startOnce() {
            return starter("IT_U_START_ONCE", "it.u.once");
        }

        @Bean
        ProcessDefinition<StartInput, StartOutput, ProcessContext> startQuick() {
            return starter("IT_U_START_QUICK", "it.u.quick");
        }

        @Bean
        ProcessDefinition<StartInput, StartOutput, ProcessContext> startReported() {
            return starter("IT_U_START_REPORTED", "it.u.reported");
        }

        @Bean
        ProcessDefinition<ExternalJobCompletion, CallbackOutput, ProcessContext> itUDone() {
            return callback("IT_U_DONE", ExternalJobCompletion.class);
        }

        @Bean
        ProcessDefinition<ExternalJobFailure, CallbackOutput, ProcessContext> itUFailed() {
            return callback("IT_U_FAILED", ExternalJobFailure.class);
        }

        @Bean
        ProcessDefinition<ExternalJobCancellation, CallbackOutput, ProcessContext> itUCancelled() {
            return callback("IT_U_CANCELLED", ExternalJobCancellation.class);
        }
    }

    @Autowired
    ApplicationContext context;

    @Autowired
    JwtService tokens;

    @Autowired
    ExternalJobExecutor executor;

    @Autowired
    ExternalJobRecorder recorder;

    @Autowired
    OutboxDeliverer deliverer;

    @Autowired
    MeterRegistry meters;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
        SqlStatementLog.clear();
    }

    /** Finishes what a test left open, and checks that nothing was ever updated or deleted. */
    @AfterEach
    void finishLeftovers() {
        GATES.values().forEach(CountDownLatch::countDown);
        for (int round = 0; round < 4; round++) {
            clock.advance(Duration.ofHours(1));
            executor.runPending().block();
        }
        deliverer.deliverPending().block();
        assertThat(query("SELECT j.job_id FROM sys_external_job j WHERE NOT EXISTS (SELECT 1 FROM"
            + " sys_external_job_outcome o WHERE o.job_id = j.job_id)")).as("jobs left open").isEmpty();
        assertThat(SqlStatementLog.STATEMENTS).anyMatch(sql -> sql.contains("sys_external_job"));
        assertThat(SqlStatementLog.STATEMENTS).noneMatch(sql -> sql.matches(
            "(?is).*(UPDATE|DELETE\\s+FROM|TRUNCATE)\\s+sys_external_job\\w*\\b.*"));
    }

    // ---------------------------------------------------------------- counting

    @Test
    void aFailureCountsTheAttemptsWhoseLeaseExpired() {
        String jobId = start("IT_U_START_RETRY", "fail-" + unique("expired"), "it-req-f");
        // An instance claimed the first attempt and died.
        insertClaim(jobId, 1, clock.instant().minusSeconds(1));
        assertThat(executor.runPending().block()).isZero();
        clock.advance(Duration.ofSeconds(11));
        // The second attempt records its usage and fails for good.
        assertThat(executor.runPending().block()).isEqualTo(1);
        assertThat(attempts(jobId)).containsExactly("1:LEASE_EXPIRED", "2:FAILED");
        deliver();
        assertThat(callbacks(jobId)).singleElement().satisfies(callback -> {
            ExternalJobFailure in = (ExternalJobFailure) callback.input();
            assertThat(in.attempts()).isEqualTo(2);
            assertThat(in.unrecordedAttempts()).isEqualTo(1);
            assertThat(in.hasUnrecordedAttempts()).isTrue();
            assertThat(in.usage()).containsExactly(Map.entry("units", UNITS));
        });
        // The operation record shows the count too.
        assertThat(query("SELECT input_summary FROM op_process WHERE process_name = 'IT_U_FAILED'"
            + " AND input_summary::text LIKE ?", "%" + jobId + "%")).singleElement().satisfies(row ->
                assertThat(row.get("input_summary").toString()).contains("\"unrecordedAttempts\": 1"));
    }

    @Test
    void aCompletionCountsAnEarlierExpiredAttempt() {
        String jobId = start("IT_U_START_RETRY", unique("done"), "it-req-c");
        insertClaim(jobId, 1, clock.instant().minusSeconds(1));
        assertThat(executor.runPending().block()).isZero();
        clock.advance(Duration.ofSeconds(11));
        assertThat(executor.runPending().block()).isEqualTo(1);
        assertThat(attempts(jobId)).containsExactly("1:LEASE_EXPIRED", "2:SUCCEEDED");
        deliver();
        assertThat(callbacks(jobId)).singleElement().satisfies(callback -> {
            ExternalJobCompletion in = (ExternalJobCompletion) callback.input();
            assertThat(in.result(JobResult.class).attempt()).isEqualTo(2);
            assertThat(in.unrecordedAttempts()).isEqualTo(1);
            assertThat(in.hasUnrecordedAttempts()).isTrue();
            assertThat(in.usage()).containsExactly(Map.entry("units", UNITS));
        });
    }

    @Test
    void aCancellationCountsTheRunningAttemptWhoseInstanceDied() {
        String jobId = start("IT_U_START_RETRY", unique("died"), "it-req-x");
        // Claimed by an instance that died: running by its lease until it runs out.
        insertClaim(jobId, 1, clock.instant().plusSeconds(60));
        cancel(jobId, "it-req-x");
        assertThat(query("SELECT 1 FROM sys_external_job_outcome WHERE job_id = ?", jobId)).isEmpty();
        clock.advance(Duration.ofMinutes(2));
        executor.runPending().block();
        assertThat(attempts(jobId)).containsExactly("1:LEASE_EXPIRED");
        deliver();
        assertThat(callbacks(jobId)).singleElement().satisfies(callback -> {
            ExternalJobCancellation in = (ExternalJobCancellation) callback.input();
            assertThat(in.attemptRan()).isTrue();
            assertThat(in.attempts()).isEqualTo(1);
            assertThat(in.unrecordedAttempts()).isEqualTo(1);
            assertThat(in.hasUnrecordedAttempts()).isTrue();
            assertThat(in.usage()).isEmpty();
        });
    }

    @Test
    void aCancellationOfAQueuedJobCountsNone() {
        String jobId = start("IT_U_START_RETRY", unique("queued"), "it-req-q");
        cancel(jobId, "it-req-q");
        deliver();
        assertThat(callbacks(jobId)).singleElement().satisfies(callback -> {
            ExternalJobCancellation in = (ExternalJobCancellation) callback.input();
            assertThat(in.attemptRan()).isFalse();
            assertThat(in.unrecordedAttempts()).isZero();
            assertThat(in.hasUnrecordedAttempts()).isFalse();
        });
    }

    @Test
    void aTimedOutAttemptWithoutUsageIsUnrecordedAndOneWithUsageIsNot() {
        String silent = unique("silent-slow");
        String reporting = unique("slow");
        GATES.put(silent + ":1", new CountDownLatch(1));
        GATES.put(reporting + ":1", new CountDownLatch(1));
        String silentJob = start("IT_U_START_QUICK", silent, "it-req-t");
        String reportingJob = start("IT_U_START_QUICK", reporting, "it-req-t");
        assertThat(executor.runPending().block()).isEqualTo(2);
        assertThat(attempts(silentJob)).containsExactly("1:TIMED_OUT");
        assertThat(attempts(reportingJob)).containsExactly("1:TIMED_OUT");
        deliver();
        assertThat(callbacks(silentJob)).singleElement().satisfies(callback -> {
            ExternalJobFailure in = (ExternalJobFailure) callback.input();
            assertThat(in.errorCode()).isEqualTo("TIMEOUT");
            assertThat(in.unrecordedAttempts()).isEqualTo(1);
            assertThat(in.usage()).isEmpty();
        });
        assertThat(callbacks(reportingJob)).singleElement().satisfies(callback -> {
            ExternalJobFailure in = (ExternalJobFailure) callback.input();
            assertThat(in.errorCode()).isEqualTo("TIMEOUT");
            // It reported what it consumed before it ran out of time: recorded, so not counted.
            assertThat(in.unrecordedAttempts()).isZero();
            assertThat(in.usage()).containsExactly(Map.entry("units", UNITS));
        });
    }

    @Test
    void aFailedAttemptWithoutUsageIsNotUnrecorded() {
        String jobId = start("IT_U_START_ONCE", "fail-silent-" + unique("refused"), "it-req-n");
        assertThat(executor.runPending().block()).isEqualTo(1);
        assertThat(attempts(jobId)).containsExactly("1:FAILED");
        deliver();
        assertThat(callbacks(jobId)).singleElement().satisfies(callback -> {
            ExternalJobFailure in = (ExternalJobFailure) callback.input();
            // FAILED is the handler's own outcome: it had the chance to record usage, and recorded none.
            assertThat(in.unrecordedAttempts()).isZero();
            assertThat(in.hasUnrecordedAttempts()).isFalse();
            assertThat(in.usage()).isEmpty();
        });
    }

    /**
     * W-4 as confirmed: a {@code FAILED} attempt is the handler's own outcome, and it had the chance to record usage.
     * One whose provider gave no answer ({@code EXTERNAL_CALL_FAILED}) and that recorded nothing is therefore not
     * counted; a handler that calls a billing provider records its estimate before it rethrows (16w-1 review M1).
     */
    @Test
    void aFailedAttemptWhoseCallGotNoAnswerIsNotUnrecorded() {
        String silent = start("IT_U_START_ONCE", "callfail-silent-" + unique("noanswer"), "it-req-m");
        String estimating = start("IT_U_START_ONCE", "callfail-" + unique("estimate"), "it-req-m");
        executor.runPending().block();
        assertThat(attempts(silent)).containsExactly("1:FAILED");
        assertThat(attempts(estimating)).containsExactly("1:FAILED");
        deliver();
        assertThat(callbacks(silent)).singleElement().satisfies(callback -> {
            ExternalJobFailure in = (ExternalJobFailure) callback.input();
            assertThat(in.errorCode()).isEqualTo("EXTERNAL_CALL_FAILED");
            assertThat(in.unrecordedAttempts()).isZero();
            assertThat(in.usage()).isEmpty();
        });
        assertThat(callbacks(estimating)).singleElement().satisfies(callback -> {
            ExternalJobFailure in = (ExternalJobFailure) callback.input();
            assertThat(in.errorCode()).isEqualTo("EXTERNAL_CALL_FAILED");
            assertThat(in.unrecordedAttempts()).isZero();
            assertThat(in.usage()).containsExactly(Map.entry("units", UNITS));
        });
    }

    // ---------------------------------------------------------------- late attempts

    /**
     * A timed-out attempt whose lease was recorded {@code LEASE_EXPIRED} first is a late one: what it reported before
     * running out of time is recorded against it; one that reported nothing stays unrecorded.
     */
    @Test
    void aLateTimedOutAttemptRecordsWhatItReported() throws Exception {
        String silent = unique("silent-late-timeout");
        String reporting = unique("late-timeout");
        GATES.put(silent + ":1", new CountDownLatch(1));
        GATES.put(reporting + ":1", new CountDownLatch(1));
        String silentJob = start("IT_U_START_SLOW", silent, "it-req-s");
        String reportingJob = start("IT_U_START_SLOW", reporting, "it-req-s");
        CompletableFuture<Integer> late = executor.runPending().toFuture();
        for (String label : List.of(silent, reporting)) {
            assertThat(STARTED.computeIfAbsent(label + ":1", k -> new CountDownLatch(1)).await(20, TimeUnit.SECONDS))
                .isTrue();
        }
        // The lease (5 s and 30 s of margin) runs out on the test's clock before the timeout does in real time.
        ExternalJobExecutor other = otherInstance();
        clock.advance(Duration.ofSeconds(40));
        assertThat(other.runPending().block()).isZero();
        assertThat(attempts(silentJob)).containsExactly("1:LEASE_EXPIRED");
        assertThat(attempts(reportingJob)).containsExactly("1:LEASE_EXPIRED");

        // Both time out, after their leases were recorded.
        assertThat(late.get(30, TimeUnit.SECONDS)).isEqualTo(2);
        assertThat(attempts(silentJob)).containsExactly("1:LEASE_EXPIRED");
        assertThat(usageRows(silentJob)).isEmpty();
        assertThat(usageRows(reportingJob)).containsExactly("1:units:" + UNITS);
        deliver();
        assertThat(callbacks(silentJob)).singleElement().satisfies(callback ->
            assertThat(((ExternalJobFailure) callback.input()).unrecordedAttempts()).isEqualTo(1));
        assertThat(callbacks(reportingJob)).singleElement().satisfies(callback -> {
            ExternalJobFailure in = (ExternalJobFailure) callback.input();
            assertThat(in.unrecordedAttempts()).isZero();
            assertThat(in.usage()).containsExactly(Map.entry("units", UNITS));
        });
    }

    /** A cancel records the expired lease and ends the job; the instance that comes back later still has its usage. */
    @Test
    void aLateAttemptExpiredByACancelRecordsItsUsageAfterTheCancelledProcess() throws Exception {
        String label = unique("late-cancel");
        GATES.put(label + ":1", new CountDownLatch(1));
        String jobId = start("IT_U_START_ONCE", label, "it-req-k");
        CompletableFuture<Integer> late = runUntilStarted(label);
        clock.advance(Duration.ofSeconds(121));
        cancel(jobId, "it-req-k");
        assertThat(attempts(jobId)).containsExactly("1:LEASE_EXPIRED");
        assertThat(outcome(jobId)).containsEntry("status", "CANCELLED");
        deliver();
        assertThat(callbacks(jobId)).singleElement().satisfies(callback -> {
            ExternalJobCancellation in = (ExternalJobCancellation) callback.input();
            assertThat(in.unrecordedAttempts()).isEqualTo(1);
            assertThat(in.usage()).isEmpty();
        });

        GATES.get(label + ":1").countDown();
        assertThat(late.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(usageRows(jobId)).containsExactly("1:units:" + UNITS);
        assertThat(outcome(jobId)).containsEntry("status", "CANCELLED");
        deliver();
        assertThat(callbacks(jobId)).hasSize(1);
    }

    @Test
    void aLateAttemptsUsageIsRecordedAndCountsBeforeTheOutcome() throws Exception {
        String label = unique("late-before");
        GATES.put(label + ":1", new CountDownLatch(1));
        String jobId = start("IT_U_START_ONCE", label, "it-req-l");
        double counted = counted("it.u.once");
        CompletableFuture<Integer> late = runUntilStarted(label);

        // Its lease runs out: another instance records it LEASE_EXPIRED, the job's last attempt, so it fails.
        expireOnAnotherInstance();
        assertThat(attempts(jobId)).containsExactly("1:LEASE_EXPIRED");
        assertThat(outcome(jobId)).containsEntry("status", "FAILED").containsEntry("error_code", "LEASE_EXPIRED");

        // The late instance comes back before the failure process has run.
        GATES.get(label + ":1").countDown();
        assertThat(late.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(usageRows(jobId)).containsExactly("1:units:" + UNITS);
        assertThat(counted("it.u.once") - counted).isEqualTo(UNITS);
        // Nothing else about the job changed.
        assertThat(attempts(jobId)).containsExactly("1:LEASE_EXPIRED");
        assertThat(outcome(jobId)).containsEntry("status", "FAILED").containsEntry("error_code", "LEASE_EXPIRED")
            .containsEntry("result", null);

        deliver();
        assertThat(callbacks(jobId)).singleElement().satisfies(callback -> {
            ExternalJobFailure in = (ExternalJobFailure) callback.input();
            // Its usage is known now: charged as recorded, not by an estimate.
            assertThat(in.unrecordedAttempts()).isZero();
            assertThat(in.usage()).containsExactly(Map.entry("units", UNITS));
        });
    }

    @Test
    void aLateAttemptAfterTheOutcomeAddsToTheReportOnly() throws Exception {
        String label = unique("late-after");
        GATES.put(label + ":1", new CountDownLatch(1));
        String jobId = start("IT_U_START_REPORTED", label, "it-req-a");
        CompletableFuture<Integer> late = runUntilStarted(label);
        expireOnAnotherInstance();
        deliver();
        // The failure process ran first: one attempt without usage, to charge by estimate.
        assertThat(callbacks(jobId)).singleElement().satisfies(callback -> {
            ExternalJobFailure in = (ExternalJobFailure) callback.input();
            assertThat(in.unrecordedAttempts()).isEqualTo(1);
            assertThat(in.usage()).isEmpty();
        });
        assertThat(report("it.u.reported")).isEmpty();

        GATES.get(label + ":1").countDown();
        assertThat(late.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(usageRows(jobId)).containsExactly("1:units:" + UNITS);
        // The report shows what was consumed; no process is called again.
        assertThat(report("it.u.reported")).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("metric", "units");
            assertThat(String.valueOf(row.get("quantity"))).isEqualTo(String.valueOf(UNITS));
            assertThat(String.valueOf(row.get("attempts"))).isEqualTo("1");
        });
        deliver();
        assertThat(callbacks(jobId)).hasSize(1);
        assertThat(query("SELECT 1 FROM op_process WHERE process_name = 'IT_U_FAILED'"
            + " AND input_summary::text LIKE ?", "%" + jobId + "%")).hasSize(1);
        assertThat(outcome(jobId)).containsEntry("status", "FAILED");
    }

    /**
     * {@code insertLateUsage}, called directly (a repeat cannot be brought about through the executor without
     * injecting a database failure): it writes only for an attempt recorded {@code LEASE_EXPIRED} that has no usage
     * yet, so a second call, or a call for an attempt recorded otherwise or not at all, writes nothing.
     */
    @Test
    void aRepeatedLateRecordingWritesTheUsageOnce() throws Exception {
        String jobId = start("IT_U_START_ONCE", unique("repeat"), "it-req-r");
        insertClaim(jobId, 1, clock.instant().minusSeconds(1));
        executor.runPending().block();
        assertThat(attempts(jobId)).containsExactly("1:LEASE_EXPIRED");

        assertThat(insertLateUsage(jobId, 1, Map.of("units", 3L, "calls", 1L)))
            .isEqualTo(Map.of("units", 3L, "calls", 1L));
        assertThat(insertLateUsage(jobId, 1, Map.of("units", 3L, "calls", 1L))).isEmpty();
        assertThat(insertLateUsage(jobId, 1, Map.of("other", 9L))).isEmpty();
        assertThat(usageRows(jobId)).containsExactly("1:calls:1", "1:units:3");

        // An attempt recorded otherwise (FAILED, SUCCEEDED, TIMED_OUT) takes no late usage.
        String failed = start("IT_U_START_ONCE", "fail-silent-" + unique("repeat"), "it-req-r");
        executor.runPending().block();
        assertThat(attempts(failed)).containsExactly("1:FAILED");
        assertThat(insertLateUsage(failed, 1, Map.of("units", 3L))).isEmpty();
        assertThat(usageRows(failed)).isEmpty();
        // Nor one that is not recorded at all.
        assertThat(insertLateUsage(UUID.randomUUID().toString(), 1, Map.of("units", 3L))).isEmpty();
    }

    @Test
    void theLateResultAndFilesAreStillNotHandedOver() throws Exception {
        String label = unique("late-result");
        GATES.put(label + ":1", new CountDownLatch(1));
        String jobId = start("IT_U_START_RETRY", label, "it-req-z");
        CompletableFuture<Integer> late = runUntilStarted(label);
        ExternalJobExecutor other = expireOnAnotherInstance();
        clock.advance(Duration.ofSeconds(11));
        assertThat(other.runPending().block()).isEqualTo(1);
        assertThat(attempts(jobId)).containsExactly("1:LEASE_EXPIRED", "2:SUCCEEDED");

        GATES.get(label + ":1").countDown();
        assertThat(late.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        // The late attempt succeeded too, but the stored result is the second attempt's.
        assertThat(query("SELECT result FROM sys_external_job_outcome WHERE job_id = ?", jobId)).singleElement()
            .satisfies(row -> assertThat(row.get("result").toString()).contains("\"attempt\":2"));
        assertThat(usageRows(jobId)).containsExactly("1:units:" + UNITS, "2:units:" + UNITS);
        deliver();
        assertThat(callbacks(jobId)).singleElement().satisfies(callback -> {
            ExternalJobCompletion in = (ExternalJobCompletion) callback.input();
            assertThat(in.result(JobResult.class).attempt()).isEqualTo(2);
            assertThat(in.files()).isEmpty();
            assertThat(in.attempts()).isEqualTo(2);
            assertThat(in.unrecordedAttempts()).isZero();
            assertThat(in.usage()).containsExactly(Map.entry("units", 2 * UNITS));
        });
        // The output files of a late attempt: ExternalJobFilesIT.theFileOfALateAttemptIsRecordedButNotHandedOver.
    }

    // ---------------------------------------------------------------- helpers

    /** Starts the executor on the job's first attempt, which waits at its gate. */
    private CompletableFuture<Integer> runUntilStarted(String label) throws InterruptedException {
        CompletableFuture<Integer> running = executor.runPending().toFuture();
        assertThat(STARTED.computeIfAbsent(label + ":1", k -> new CountDownLatch(1)).await(20, TimeUnit.SECONDS))
            .isTrue();
        return running;
    }

    private ExternalJobExecutor otherInstance() {
        return new ExternalJobExecutor(context.getBean(ExternalJobRegistry.class), recorder,
            context, context.getBean(ExternalServices.class), context.getBean(JsonMapper.class),
            context.getBean(Clock.class), context.getBean(PlatformObservations.class),
            context.getBeanProvider(MeterRegistry.class), executor.settings().withInstanceId("it-takeover"));
    }

    /** Lets the lease (90 s and 30 s of margin) run out and has another instance record it. */
    private ExternalJobExecutor expireOnAnotherInstance() {
        ExternalJobExecutor other = otherInstance();
        clock.advance(Duration.ofSeconds(121));
        assertThat(other.runPending().block()).isZero();
        return other;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> insertLateUsage(String jobId, int attemptNo, Map<String, Long> usage)
        throws Exception {
        Method method = ExternalJobRecorder.class.getDeclaredMethod("insertLateUsage", String.class, int.class,
            Map.class);
        method.setAccessible(true);
        return ((Mono<Map<String, Long>>) method.invoke(recorder, jobId, attemptNo, usage)).block();
    }

    private double counted(String jobType) {
        Counter counter = meters.find(PlatformObservations.EXTERNAL_USAGE).tags("job_type", jobType, "metric", "units")
            .counter();
        return counter == null ? 0 : counter.count();
    }

    private void deliver() {
        deliverer.deliverPending().block();
        deliverer.deliverPending().block();
    }

    private void cancel(String jobId, String actor) {
        client.post().uri("/api/external-jobs/" + jobId + "/cancel")
            .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(tokens, actor)).exchange().expectStatus().isOk();
    }

    private List<Map<String, Object>> report(String jobType) {
        Map<String, Object> page = client.post().uri("/api/queries/jabiz.external_job_usage")
            .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(tokens, "it-auditor", "external-job.read"))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("params", Map.of("jobType", jobType)))
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        return items;
    }

    private static void insertClaim(String jobId, int attempt, Instant leaseUntil) {
        execute("INSERT INTO sys_external_job_claim (job_id, attempt_no, instance_id, claimed_time, lease_until)"
            + " VALUES (?, ?, 'it-crashed', ?, ?)", jobId, attempt,
            java.sql.Timestamp.from(leaseUntil.minusSeconds(60)), java.sql.Timestamp.from(leaseUntil));
    }

    private static List<String> attempts(String jobId) {
        return query("SELECT attempt_no, outcome FROM sys_external_job_attempt WHERE job_id = ? ORDER BY attempt_no",
            jobId).stream().map(row -> row.get("attempt_no") + ":" + row.get("outcome")).toList();
    }

    private static List<String> usageRows(String jobId) {
        return query("SELECT attempt_no, metric, quantity FROM sys_external_job_usage WHERE job_id = ?"
            + " ORDER BY attempt_no, metric", jobId).stream()
            .map(row -> row.get("attempt_no") + ":" + row.get("metric") + ":" + row.get("quantity")).toList();
    }

    private static Map<String, Object> outcome(String jobId) {
        List<Map<String, Object>> rows = query("SELECT status, result, error_code, attempts"
            + " FROM sys_external_job_outcome WHERE job_id = ?", jobId);
        assertThat(rows).as("outcome of " + jobId).hasSize(1);
        return new HashMap<>(rows.getFirst());
    }

    private static List<Callback> callbacks(String jobId) {
        return CALLBACKS.stream().filter(callback -> callback.jobId().equals(jobId)).toList();
    }

    private static final AtomicInteger SEQ = new AtomicInteger();

    private static String unique(String prefix) {
        return prefix + "-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    private String start(String process, String label, String actor) {
        Map<String, Object> result = client.post().uri("/api/processes/" + process + "/latest")
            .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(tokens, actor, "it.job.start"))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("label", label)).exchange()
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.get("output");
        return output.get("jobId").toString();
    }
}
