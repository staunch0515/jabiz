package com.jabiz.app.it.file;

import com.jabiz.app.commerce.CommerceFiles;
import com.jabiz.context.RequestContext;
import com.jabiz.external.ExternalJobCompletion;
import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobFailure;
import com.jabiz.external.ExternalJobFile;
import com.jabiz.external.ExternalJobHandler;
import com.jabiz.external.ExternalJobResult;
import com.jabiz.external.ExternalJobType;
import com.jabiz.file.FilePolicy;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.event.OutboxDeliverer;
import com.jabiz.runtime.external.ExternalJobExecutor;
import com.jabiz.runtime.file.FileProperties;
import com.jabiz.runtime.file.LocalFileStore;
import com.jabiz.runtime.process.steps.StartExternalJob;
import com.jabiz.runtime.test.FileSamples;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.buffer.DataBuffer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * External jobs whose files meet a failing or slow storage (decision D50): copying the input past its time, storing
 * the output past its time, a store that fails once and is tried again with the same bytes (the provider is not
 * called again), and one that keeps failing (a retryable failure: the next attempt calls the handler again). The
 * storage is the platform's own with faults put in front of it; the times are the shortest the settings allow.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.external-jobs.files.work-minimum=1s",
    "jabiz.external-jobs.files.work-per-mib=0ms",
    "jabiz.external-jobs.files.store-retries=1"})
class ExternalJobFileFailuresIT extends FileItSupport {

    public record In(String label) {}

    public record Out(String label) {}

    public record StartInput(String label, List<UUID> files) {}

    public record StartOutput(String jobId) {}

    /** Every handler run, by job: a storage retry must not run the handler again. */
    static final List<String> RUNS = new CopyOnWriteArrayList<>();
    static final Map<String, Object> CALLBACKS = new ConcurrentHashMap<>();

    /** The platform's local storage, slowed down or failing as a test says. */
    static final class FaultyStore extends LocalFileStore {
        static volatile Duration readDelay = Duration.ZERO;
        static volatile Duration writeDelay = Duration.ZERO;
        static final AtomicInteger FAILING_WRITES = new AtomicInteger();

        FaultyStore(FileProperties properties) {
            super(properties);
        }

        // The delays wait on boundedElastic, where the store's own reads and writes run anyway.
        @Override
        public Flux<DataBuffer> read(String key, long offset, long length) {
            Duration delay = readDelay;
            return delay.isZero() ? super.read(key, offset, length)
                : Mono.delay(delay, Schedulers.boundedElastic()).thenMany(super.read(key, offset, length));
        }

        @Override
        public Mono<Void> write(String key, Flux<DataBuffer> content) {
            if (FAILING_WRITES.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                return Mono.error(new IOException("the disk is unwell"));
            }
            Duration delay = writeDelay;
            return delay.isZero() ? super.write(key, content)
                : Mono.delay(delay, Schedulers.boundedElastic()).then(super.write(key, content));
        }
    }

    public static class Handler implements ExternalJobHandler<In, Out> {
        @Override
        public ExternalJobResult<Out> run(In input, ExternalJobContext ctx) {
            RUNS.add(ctx.jobId());
            for (ExternalJobFile file : ctx.files()) {
                file.bytes();
            }
            ExternalJobResult<Out> result = ExternalJobResult.of(new Out(input.label()));
            return input.label().startsWith("out") ? result.file("image", "made.jpg", FileSamples.jpeg(120, 90))
                : result;
        }
    }

    @TestConfiguration
    static class Fixtures {

        @Bean
        @Primary
        FaultyStore faultyStore(FileProperties properties) {
            return new FaultyStore(properties);
        }

        @Bean
        Handler itFaultHandler() {
            return new Handler();
        }

        @Bean
        ExternalJobType<In, Out> itFaultRead() {
            return ExternalJobType.define("it.fault.read", In.class, Out.class, t -> t.handler(Handler.class)
                .inputFiles(1, FilePolicy.MB, CommerceFiles.IMAGE).retry(2, Duration.ofSeconds(10),
                    Duration.ofMinutes(1)).onCompleted("IT_FAULT_DONE").onFailed("IT_FAULT_FAILED"));
        }

        @Bean
        ExternalJobType<In, Out> itFaultMake() {
            return ExternalJobType.define("it.fault.make", In.class, Out.class, t -> t.handler(Handler.class)
                .outputFiles(CommerceFiles.IMAGE, 1, FilePolicy.MB).retry(2, Duration.ofSeconds(10),
                    Duration.ofMinutes(1)).onCompleted("IT_FAULT_DONE").onFailed("IT_FAULT_FAILED"));
        }

        @Bean
        ProcessDefinition<StartInput, StartOutput, ProcessContext> itFaultStartRead() {
            return starter("IT_FAULT_READ", "it.fault.read", true);
        }

        @Bean
        ProcessDefinition<StartInput, StartOutput, ProcessContext> itFaultStartMake() {
            return starter("IT_FAULT_MAKE", "it.fault.make", false);
        }

        @Bean
        ProcessDefinition<ExternalJobCompletion, StartOutput, ProcessContext> itFaultDone() {
            return callback("IT_FAULT_DONE", ExternalJobCompletion.class);
        }

        @Bean
        ProcessDefinition<ExternalJobFailure, StartOutput, ProcessContext> itFaultFailed() {
            return callback("IT_FAULT_FAILED", ExternalJobFailure.class);
        }
    }

    static ProcessDefinition<StartInput, StartOutput, ProcessContext> starter(String name, String type,
        boolean files) {
        return ProcessDefinition.define(name, 1, StartInput.class, StartOutput.class, ProcessContext.class, pb -> pb
            .permissions("it.job.start")
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put("in", input);
                return ctx;
            })
            .outputMapper(ctx -> new StartOutput(ctx.get("jobId", String.class)))
            .step("Start", files
                ? StartExternalJob.<ProcessContext>of(type, ctx -> new In(ctx.get("in", StartInput.class).label()))
                    .files(ctx -> ctx.get("in", StartInput.class).files()).outputKey("jobId")
                : StartExternalJob.<ProcessContext>of(type, ctx -> new In(ctx.get("in", StartInput.class).label()))
                    .outputKey("jobId")));
    }

    static <I> ProcessDefinition<I, StartOutput, ProcessContext> callback(String name, Class<I> type) {
        return ProcessDefinition.define(name, 1, type, StartOutput.class, ProcessContext.class, pb -> pb
            .permissions("it.fault.callback")
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put("in", input);
                return ctx;
            })
            .outputMapper(ctx -> new StartOutput(jobId(ctx.get("in"))))
            .compute("Record", (metadata, ctx) -> {
                if (!RequestContext.SYSTEM_ACTOR.equals(ctx.request().actorId())) {
                    throw new PermissionDeniedException("it.fault.callback", "platform only");
                }
                CALLBACKS.put(jobId(ctx.get("in")), ctx.get("in"));
            }));
    }

    static String jobId(Object input) {
        return input instanceof ExternalJobCompletion c ? c.jobId() : ((ExternalJobFailure) input).jobId();
    }

    @Autowired
    ExternalJobExecutor executor;

    @Autowired
    OutboxDeliverer deliverer;

    @BeforeEach
    @AfterEach
    void healTheStore() {
        FaultyStore.readDelay = Duration.ZERO;
        FaultyStore.writeDelay = Duration.ZERO;
        FaultyStore.FAILING_WRITES.set(0);
    }

    @Test
    void copyingTheInputPastItsTimeIsARetryableFailureAndLeavesNoCopy() {
        String photo = photo();
        String jobId = start("IT_FAULT_READ", "slow-read", List.of(UUID.fromString(photo)));
        FaultyStore.readDelay = Duration.ofSeconds(3);
        executor.runPending().block();
        assertThat(attempts(jobId)).containsExactly("1:FAILED:true:INPUT_FILE_UNREADABLE");
        assertThat(RUNS).doesNotContain(jobId);
        assertThat(uploadLeftovers()).isEmpty();
        // Once the storage answers in time, the next attempt reads the file.
        FaultyStore.readDelay = Duration.ZERO;
        clock.advance(Duration.ofSeconds(11));
        executor.runPending().block();
        assertThat(attempts(jobId)).containsExactly("1:FAILED:true:INPUT_FILE_UNREADABLE", "2:SUCCEEDED:false:null");
    }

    @Test
    void anInputFileWhoseStoredContentIsGoneCannotBeRead() throws IOException {
        String photo = photo();
        String jobId = start("IT_FAULT_READ", "gone", List.of(UUID.fromString(photo)));
        String prefix = com.jabiz.runtime.file.FileKeys.prefix(UUID.fromString(photo));
        for (String key : storedKeys()) {
            if (key.startsWith(prefix)) {
                java.nio.file.Files.delete(filesRoot().resolve(key));
            }
        }
        executor.runPending().block();
        assertThat(attempts(jobId)).containsExactly("1:FAILED:true:INPUT_FILE_UNREADABLE");
        assertThat(uploadLeftovers()).isEmpty();
        clock.advance(Duration.ofSeconds(11));
        executor.runPending().block();
        deliverer.deliverPending().block();
        assertThat(((ExternalJobFailure) CALLBACKS.get(jobId)).errorCode()).isEqualTo("INPUT_FILE_UNREADABLE");
    }

    @Test
    void storingTheOutputPastItsTimeFailsTheAttemptForAnotherTry() {
        String jobId = start("IT_FAULT_MAKE", "out-slow", List.of());
        FaultyStore.writeDelay = Duration.ofSeconds(5);
        executor.runPending().block();
        assertThat(attempts(jobId)).containsExactly("1:FAILED:true:OUTPUT_FILE_FAILED");
        assertThat(query("SELECT error_detail FROM sys_external_job_attempt WHERE job_id = ?", jobId).getFirst()
            .get("error_detail").toString()).contains("calls the service again");
    }

    @Test
    void aStorageThatFailsOnceIsTriedAgainWithoutTheHandler() {
        String jobId = start("IT_FAULT_MAKE", "out-once", List.of());
        FaultyStore.FAILING_WRITES.set(1);
        executor.runPending().block();
        deliverer.deliverPending().block();
        assertThat(attempts(jobId)).containsExactly("1:SUCCEEDED:false:null");
        assertThat(RUNS.stream().filter(jobId::equals)).hasSize(1);
        assertThat(((ExternalJobCompletion) CALLBACKS.get(jobId)).files()).containsOnlyKeys("image");
    }

    @Test
    void aStorageThatKeepsFailingRunsTheHandlerAgainOnTheNextAttempt() {
        String jobId = start("IT_FAULT_MAKE", "out-failing", List.of());
        FaultyStore.FAILING_WRITES.set(2);
        executor.runPending().block();
        assertThat(attempts(jobId)).containsExactly("1:FAILED:true:OUTPUT_FILE_FAILED");
        clock.advance(Duration.ofSeconds(11));
        executor.runPending().block();
        assertThat(attempts(jobId)).containsExactly("1:FAILED:true:OUTPUT_FILE_FAILED", "2:SUCCEEDED:false:null");
        assertThat(RUNS.stream().filter(jobId::equals)).hasSize(2);
    }

    private String photo() {
        return (String) uploaded(CommerceFiles.IMAGE, FileSamples.jpeg(200, 150), "photo.jpg", "image/jpeg",
            bearer("it-fault-owner", "commerce.media.upload", "commerce.media.read")).get("fileId");
    }

    private String start(String process, String label, List<UUID> files) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("label", label);
        input.put("files", files.stream().map(UUID::toString).toList());
        Map<String, Object> result = post("/api/processes/" + process + "/latest", bearer("it-fault-requester",
            "it.job.start", "commerce.media.read", "commerce.media.upload"), input).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.get("output");
        return output.get("jobId").toString();
    }

    private static List<String> attempts(String jobId) {
        return query("SELECT attempt_no, outcome, retryable, error_code FROM sys_external_job_attempt"
            + " WHERE job_id = ? ORDER BY attempt_no", jobId).stream()
            .map(row -> row.get("attempt_no") + ":" + row.get("outcome") + ":" + row.get("retryable") + ":"
                + row.get("error_code"))
            .toList();
    }
}
