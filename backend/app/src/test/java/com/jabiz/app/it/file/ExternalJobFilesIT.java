package com.jabiz.app.it.file;

import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.app.commerce.CommerceFiles;
import com.jabiz.app.commerce.ProductImage;
import com.jabiz.app.it.fixture.ItFileFixtures;
import com.jabiz.app.it.fixture.StubAiServer;
import com.jabiz.context.RequestContext;
import com.jabiz.external.ExternalJobCompletion;
import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobFailure;
import com.jabiz.external.ExternalJobFile;
import com.jabiz.external.ExternalJobHandler;
import com.jabiz.external.ExternalJobResult;
import com.jabiz.external.ExternalJobType;
import com.jabiz.file.FilePolicy;
import com.jabiz.job.JobDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.event.OutboxDeliverer;
import com.jabiz.runtime.external.ExternalJobExecutor;
import com.jabiz.runtime.file.FileKeys;
import com.jabiz.runtime.file.FileProcesses;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.process.steps.StartExternalJob;
import com.jabiz.runtime.test.FileSamples;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * External jobs with files (decision D50) against a real PostgreSQL, the local file storage and a stub of
 * {@code demo-ai}: the input limit per type; files given to a job only when the requester may read them, read by its
 * handler only during the attempt and only those; files a handler returns stored through the upload pipeline (type by
 * content, images re-encoded, the policy's limits, a random id for private metadata) as the requester's uploads, with
 * the job recorded; refused or excess files failing the job for good; what a failed or cancelled job stored swept as
 * orphans. Handlers run on the executor's virtual threads under BlockHound, which the tests always install.
 */
class ExternalJobFilesIT extends FileItSupport {

    static final StubAiServer STUB = StubAiServer.start();

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

    public record FileInput(String label) {}

    /** @param digests SHA-256 of each file the handler read, in order */
    public record FileResult(List<String> digests) {}

    public record StartInput(String label, List<UUID> files) {}

    public record StartOutput(String jobId) {}

    public record CallbackOutput(String jobId) {}

    /** The completions and failures the callbacks saw, by job. */
    static final Map<String, Object> CALLBACKS = new ConcurrentHashMap<>();
    /** The files each job's handler was handed, kept past the attempt. */
    static final Map<String, List<ExternalJobFile>> SEEN = new ConcurrentHashMap<>();
    static final Map<String, CountDownLatch> GATES = new ConcurrentHashMap<>();
    static final Map<String, CountDownLatch> STARTED = new ConcurrentHashMap<>();

    static final byte[] POLYGLOT_SCRIPT = "<script>alert(document.cookie)</script>".getBytes(StandardCharsets.UTF_8);

    /**
     * Reads every file it was given and returns their digests, then produces files as its label says
     * ({@code out:<what>}); {@code other:<fileId>} asks for a file the job was not given; {@code gate} waits.
     */
    public static class FileHandler implements ExternalJobHandler<FileInput, FileResult> {
        @Override
        public ExternalJobResult<FileResult> run(FileInput input, ExternalJobContext ctx) {
            String label = input.label();
            SEEN.put(ctx.jobId(), ctx.files());
            STARTED.computeIfAbsent(label, k -> new CountDownLatch(1)).countDown();
            CountDownLatch gate = GATES.get(label);
            if (gate != null) {
                try {
                    gate.await(25, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted");
                }
            }
            if (label.startsWith("other:")) {
                ctx.file(UUID.fromString(label.substring("other:".length())));
            }
            List<String> digests = new ArrayList<>();
            for (ExternalJobFile file : ctx.files()) {
                digests.add(sha256(file.bytes()));
            }
            ExternalJobResult<FileResult> result = ExternalJobResult.of(new FileResult(digests));
            String out = label.contains("out:") ? label.substring(label.indexOf("out:") + 4) : "";
            return switch (out) {
                case "" -> result;
                case "jpeg" -> result.file("image", "IMG_0001.jpg", FileSamples.jpegWithExif(640, 480, 1));
                case "two" -> result.file("a", "a.jpg", FileSamples.jpeg(100, 80))
                    .file("b", "b.png", FileSamples.pngWithText(90, 60));
                case "three" -> result.file("a", null, FileSamples.jpeg(10, 10)).file("b", null,
                    FileSamples.jpeg(10, 10)).file("c", null, FileSamples.jpeg(10, 10));
                case "big" -> result.file("image", null, padded(FileSamples.jpeg(10, 10), (int) FilePolicy.MB + 1));
                case "svg" -> result.file("image", "logo.svg", FileSamples.svg());
                case "html" -> result.file("image", "page.html", FileSamples.html());
                case "pdf" -> result.file("image", "doc.pdf", FileSamples.pdf());
                case "polyglot" -> result.file("image", "x.jpg", concat(FileSamples.jpeg(120, 90), POLYGLOT_SCRIPT));
                case "broken" -> result.file("image", "x.jpg", concat(new byte[] {(byte) 0xFF, (byte) 0xD8,
                    (byte) 0xFF, (byte) 0xE0}, "not really a picture".repeat(20).getBytes(StandardCharsets.UTF_8)));
                case "then-svg" -> result.file("a", "a.jpg", FileSamples.jpeg(100, 80)).file("b", "b.svg",
                    FileSamples.svg());
                default -> throw new IllegalArgumentException(out);
            };
        }
    }

    static void requireSystem(ProcessContext ctx) {
        if (!RequestContext.SYSTEM_ACTOR.equals(ctx.request().actorId())) {
            throw new PermissionDeniedException("it.files.callback", "Callbacks come from the platform only");
        }
    }

    static ProcessDefinition<StartInput, StartOutput, ProcessContext> starter(String name, String jobType,
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
                ? StartExternalJob.<ProcessContext>of(jobType, ctx -> new FileInput(ctx.get("in", StartInput.class)
                    .label())).files(ctx -> ctx.get("in", StartInput.class).files()).outputKey("jobId")
                : StartExternalJob.<ProcessContext>of(jobType, ctx -> new FileInput(ctx.get("in", StartInput.class)
                    .label())).outputKey("jobId")));
    }

    static <I> ProcessDefinition<I, CallbackOutput, ProcessContext> callback(String name, Class<I> type) {
        return ProcessDefinition.define(name, 1, type, CallbackOutput.class, ProcessContext.class, pb -> pb
            .permissions("it.files.callback")
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put("in", input);
                return ctx;
            })
            .outputMapper(ctx -> new CallbackOutput(jobId(ctx.get("in"))))
            .compute("Refuse others", (metadata, ctx) -> requireSystem(ctx))
            .compute("Record", (metadata, ctx) -> CALLBACKS.put(jobId(ctx.get("in")), ctx.get("in"))));
    }

    static String jobId(Object input) {
        return input instanceof ExternalJobCompletion c ? c.jobId() : ((ExternalJobFailure) input).jobId();
    }

    static ExternalJobType<FileInput, FileResult> type(String name,
        java.util.function.Consumer<ExternalJobType.Builder<FileInput, FileResult>> files) {
        return ExternalJobType.define(name, FileInput.class, FileResult.class, t -> {
            t.handler(FileHandler.class).timeout(Duration.ofSeconds(40)).retry(2, Duration.ofSeconds(10),
                Duration.ofMinutes(1)).onCompleted("IT_FILES_DONE").onFailed("IT_FILES_FAILED");
            files.accept(t);
        });
    }

    @TestConfiguration
    static class Fixtures {

        @Bean
        FileHandler itFileHandler() {
            return new FileHandler();
        }

        /** Reads up to two photos (product photos or private ones) of up to 1 MB. */
        @Bean
        ExternalJobType<FileInput, FileResult> itFilesRead() {
            return type("it.files.read", t -> t.inputFiles(2, FilePolicy.MB, CommerceFiles.IMAGE,
                ItFileFixtures.PRIVATE));
        }

        /** Reads one photo of up to 1 KB. */
        @Bean
        ExternalJobType<FileInput, FileResult> itFilesSmall() {
            return type("it.files.small", t -> t.inputFiles(1, FilePolicy.KB, CommerceFiles.IMAGE));
        }

        /** Produces up to two product photos of up to 1 MB. */
        @Bean
        ExternalJobType<FileInput, FileResult> itFilesMake() {
            return type("it.files.make", t -> t.outputFiles(CommerceFiles.IMAGE, 2, FilePolicy.MB));
        }

        /** Produces one file of the policy that keeps its metadata private. */
        @Bean
        ExternalJobType<FileInput, FileResult> itFilesPrivate() {
            return type("it.files.private", t -> t.outputFiles(ItFileFixtures.PRIVATE, 1, FilePolicy.MB));
        }

        /** No files; an input of up to 200 KB. */
        @Bean
        ExternalJobType<FileInput, FileResult> itFilesLarge() {
            return type("it.files.large", t -> t.maxInputSize(200 * 1024));
        }

        /** No files, the default input limit. */
        @Bean
        ExternalJobType<FileInput, FileResult> itFilesPlain() {
            return type("it.files.plain", t -> { });
        }

        @Bean
        ProcessDefinition<StartInput, StartOutput, ProcessContext> itFilesStartRead() {
            return starter("IT_FILES_READ", "it.files.read", true);
        }

        @Bean
        ProcessDefinition<StartInput, StartOutput, ProcessContext> itFilesStartSmall() {
            return starter("IT_FILES_SMALL", "it.files.small", true);
        }

        @Bean
        ProcessDefinition<StartInput, StartOutput, ProcessContext> itFilesStartMake() {
            return starter("IT_FILES_MAKE", "it.files.make", false);
        }

        @Bean
        ProcessDefinition<StartInput, StartOutput, ProcessContext> itFilesStartPrivate() {
            return starter("IT_FILES_PRIVATE", "it.files.private", false);
        }

        @Bean
        ProcessDefinition<StartInput, StartOutput, ProcessContext> itFilesStartLarge() {
            return starter("IT_FILES_LARGE", "it.files.large", false);
        }

        @Bean
        ProcessDefinition<StartInput, StartOutput, ProcessContext> itFilesStartPlain() {
            return starter("IT_FILES_PLAIN", "it.files.plain", false);
        }

        @Bean
        ProcessDefinition<ExternalJobCompletion, CallbackOutput, ProcessContext> itFilesDone() {
            return callback("IT_FILES_DONE", ExternalJobCompletion.class);
        }

        @Bean
        ProcessDefinition<ExternalJobFailure, CallbackOutput, ProcessContext> itFilesFailed() {
            return callback("IT_FILES_FAILED", ExternalJobFailure.class);
        }
    }

    @Autowired
    ExternalJobExecutor executor;

    @Autowired
    OutboxDeliverer deliverer;

    @Autowired
    JobRunner jobs;

    @Autowired
    @Qualifier("fileSweepJob")
    JobDefinition<FileProcesses.PurgeInput> sweep;

    @BeforeEach
    void reset() {
        STUB.reset();
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
    }

    /** Finishes what a test left open, and waits for the storage half of any sweep it ran. */
    @AfterEach
    void finishLeftovers() {
        GATES.values().forEach(CountDownLatch::countDown);
        for (int round = 0; round < 3; round++) {
            clock.advance(Duration.ofHours(1));
            executor.runPending().block();
        }
        deliverer.deliverPending().block();
        assertThat(query("SELECT j.job_id FROM sys_external_job j WHERE NOT EXISTS (SELECT 1 FROM"
            + " sys_external_job_outcome o WHERE o.job_id = j.job_id)")).as("jobs left open").isEmpty();
        await().atMost(Duration.ofSeconds(30)).until(() -> query("SELECT 1 FROM op_process p "
            + "WHERE p.process_name = 'FILE_PURGE_ORPHANS' AND NOT EXISTS (SELECT 1 FROM op_process_after_commit a "
            + "WHERE a.process_seq_id = p.process_seq_id AND a.succeeded)").isEmpty());
    }

    // ---------------------------------------------------------------- P1: the input limit per type

    @Test
    void theInputLimitIsDeclaredPerType() {
        String medium = "x".repeat(100 * 1024);
        String jobId = start("IT_FILES_LARGE", medium, List.of(), "it-large", "it.job.start");
        assertThat(query("SELECT octet_length(input) AS n FROM sys_external_job WHERE job_id = ?", jobId).getFirst()
            .get("n")).isEqualTo(100 * 1024 + "{\"label\":\"\"}".length());
        Map<String, Object> plain = process("IT_FILES_PLAIN", medium, List.of(), bearer("it-plain", "it.job.start"))
            .expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody();
        assertThat(violation(plain)).containsEntry("ruleCode", "EXTERNAL_JOB_INPUT_TOO_LARGE");
        assertThat(digits(violation(plain).get("message"))).contains("65536");
        Map<String, Object> large = process("IT_FILES_LARGE", "x".repeat(210 * 1024), List.of(),
            bearer("it-large", "it.job.start")).expectStatus().isEqualTo(422).expectBody(MAP).returnResult()
            .getResponseBody();
        assertThat(violation(large)).containsEntry("ruleCode", "EXTERNAL_JOB_INPUT_TOO_LARGE");
        assertThat(digits(violation(large).get("message"))).contains("204800");
        assertThat(query("SELECT 1 FROM sys_external_job WHERE requested_by = 'it-plain'")).isEmpty();
    }

    // ---------------------------------------------------------------- P2: files as input

    @Test
    void aJobReadsTheFilesItsRequesterMayReadAndNothingElse() {
        String photo = photo("it-files-owner");
        String other = photo("it-files-owner");
        // A reader of product photos, who did not upload it, may give it to the job.
        String jobId = start("IT_FILES_READ", "read", List.of(UUID.fromString(photo), UUID.fromString(photo)),
            "it-files-reader", "it.job.start", "commerce.media.read");
        assertThat(query("SELECT direction, position, file_id, policy FROM sys_external_job_file WHERE job_id = ?"
            + " ORDER BY position", jobId)).containsExactly(Map.of("direction", "INPUT", "position", 0,
            "file_id", photo, "policy", CommerceFiles.IMAGE));
        assertThat(executor.runPending().block()).isEqualTo(1);
        deliverer.deliverPending().block();
        ExternalJobCompletion done = (ExternalJobCompletion) CALLBACKS.get(jobId);
        assertThat(done.result(FileResult.class).digests()).containsExactly(digestOf(photo));

        // The copies are gone with the attempt, and what the handler kept of them no longer opens.
        assertThat(uploadLeftovers()).isEmpty();
        assertThat(SEEN.get(jobId)).singleElement().satisfies(file -> {
            assertThat(file.contentType()).isEqualTo("image/jpeg");
            assertThatThrownBy(file::open).isInstanceOf(IllegalStateException.class);
        });

        // A file the job was not given cannot be asked for: the attempt fails, it is not handed over.
        String nosy = start("IT_FILES_READ", "other:" + other, List.of(UUID.fromString(photo)), "it-files-reader",
            "it.job.start", "commerce.media.read");
        executor.runPending().block();
        assertThat(attempts(nosy)).containsExactly("1:FAILED:true:HANDLER_ERROR");
        assertThat(uploadLeftovers()).isEmpty();
    }

    @Test
    void filesTheRequesterMayNotReadAreRefusedLikeMissingOnes() {
        String photo = photo("it-files-owner");
        String contract = (String) uploaded(CommerceFiles.DOCUMENT, FileSamples.pdf(), "contract.pdf",
            "application/pdf", bearer("it-buyer", "commerce.document.upload", "commerce.document.read"))
            .get("fileId");
        // No read permission for product photos; a file that does not exist; a file of a policy the job does not
        // read, though the requester may read it: one answer for all three.
        List<Map<String, Object>> refusals = new ArrayList<>();
        refusals.add(refused("IT_FILES_READ", List.of(UUID.fromString(photo)), "it.job.start"));
        refusals.add(refused("IT_FILES_READ", List.of(UUID.randomUUID()), "it.job.start", "commerce.media.read"));
        refusals.add(refused("IT_FILES_READ", List.of(UUID.fromString(contract)), "it.job.start",
            "commerce.media.read", "commerce.document.read"));
        assertThat(refusals).allSatisfy(violation -> {
            assertThat(violation).containsEntry("ruleCode", "EXTERNAL_JOB_INPUT_FILE");
            assertThat(violation.get("message").toString()).contains("does not exist, is not a file this job reads,"
                + " or you may not read it");
        });
        assertThat(query("SELECT 1 FROM sys_external_job WHERE requested_by = 'it-files-stranger'")).isEmpty();
        // file.read reads every file.
        start("IT_FILES_READ", "read", List.of(UUID.fromString(photo)), "it-files-auditor", "it.job.start",
            "file.read");
        // Too many files, or too large a one.
        String second = photo("it-files-owner");
        String third = photo("it-files-owner");
        assertThat(refused("IT_FILES_READ", List.of(UUID.fromString(photo), UUID.fromString(second),
            UUID.fromString(third)), "it.job.start", "commerce.media.read"))
            .containsEntry("ruleCode", "EXTERNAL_JOB_INPUT_FILE_LIMIT");
        assertThat(refused("IT_FILES_SMALL", List.of(UUID.fromString(photo)), "it.job.start", "commerce.media.read"))
            .containsEntry("ruleCode", "EXTERNAL_JOB_INPUT_FILE_LIMIT");
    }

    @Test
    void aFileDeletedBeforeTheJobRunsFailsItForGood() {
        String photo = photo("it-files-owner");
        String jobId = start("IT_FILES_READ", "read", List.of(UUID.fromString(photo)), "it-files-reader",
            "it.job.start", "commerce.media.read");
        post("/api/processes/" + FileProcesses.DELETE + "/latest", bearer("it-files-admin", "file.delete"),
            Map.of("fileId", photo)).expectStatus().isOk();
        executor.runPending().block();
        assertThat(attempts(jobId)).containsExactly("1:FAILED:false:INPUT_FILE_MISSING");
        assertThat(uploadLeftovers()).isEmpty();
    }

    // ---------------------------------------------------------------- P3: files as output

    @Test
    void producedFilesAreStoredLikeUploadsOfTheRequester() {
        String jobId = start("IT_FILES_MAKE", "out:jpeg", List.of(), "it-maker", "it.job.start",
            "commerce.media.upload");
        executor.runPending().block();
        deliverer.deliverPending().block();
        ExternalJobCompletion done = (ExternalJobCompletion) CALLBACKS.get(jobId);
        UUID fileId = done.file("image");
        assertThat(fileId.version()).isEqualTo(7);

        Map<String, Object> row = query("SELECT policy, content_type, uploaded_by, original_name, width, height,"
            + " variants FROM sys_file WHERE file_id = ?", fileId).getFirst();
        assertThat(row).containsEntry("policy", CommerceFiles.IMAGE).containsEntry("content_type", "image/jpeg")
            .containsEntry("uploaded_by", "it-maker").containsEntry("original_name", "IMG_0001.jpg")
            .containsEntry("variants", "w160");
        // Re-encoded: no EXIF, no GPS, in the original and its variant.
        String prefix = FileKeys.prefix(fileId);
        List<String> keys = storedKeys().stream().filter(key -> key.startsWith(prefix)).toList();
        assertThat(keys).containsExactly(prefix + "/original", prefix + "/w160");
        for (String key : keys) {
            assertThat(FileSamples.contains(stored(key), FileSamples.SECRET_MARKER.getBytes(StandardCharsets.UTF_8)))
                .as("marker in %s", key).isFalse();
            assertThat(FileSamples.contains(stored(key), "Exif".getBytes(StandardCharsets.US_ASCII)))
                .as("EXIF in %s", key).isFalse();
        }
        // Registered by FILE_REGISTER as the requester, with the job named, and audited; the job records it.
        Map<String, Object> register = query("SELECT actor_id, input_summary::text AS summary FROM op_process"
            + " WHERE process_name = 'FILE_REGISTER' AND input_summary::text LIKE ?", "%" + fileId + "%").getFirst();
        assertThat(register).containsEntry("actor_id", "it-maker");
        assertThat((String) register.get("summary")).contains(jobId).doesNotContain("IMG_0001");
        assertThat(query("SELECT actor_id FROM sys_audit_record WHERE entity_type = 'SysFile' AND entity_id = ?",
            fileId.toString())).containsExactly(Map.of("actor_id", "it-maker"));
        assertThat(query("SELECT direction, attempt_no, output_key, policy FROM sys_external_job_file"
            + " WHERE job_id = ? AND file_id = ?", jobId, fileId.toString()))
            .containsExactly(Map.of("direction", "OUTPUT", "attempt_no", 1, "output_key", "image",
                "policy", CommerceFiles.IMAGE));
        // The requester reads it as an upload of theirs; the completion's operation record names the file only.
        get("/api/files/" + fileId, bearer("it-maker", "commerce.media.read")).expectStatus().isOk()
            .expectBody(MAP).value(meta -> assertThat(meta).containsEntry("uploadedBy", "it-maker"));
        assertThat(query("SELECT input_summary::text AS s FROM op_process WHERE process_name = 'IT_FILES_DONE'"
            + " AND input_summary::text LIKE ?", "%" + jobId + "%").getFirst().get("s").toString())
            .contains(fileId.toString());
    }

    @Test
    void aPrivatePolicyGivesTheFileARandomIdAndHidesItsName() {
        String jobId = start("IT_FILES_PRIVATE", "out:jpeg", List.of(), "it-private-maker", "it.job.start",
            "it.private.upload");
        executor.runPending().block();
        deliverer.deliverPending().block();
        UUID fileId = ((ExternalJobCompletion) CALLBACKS.get(jobId)).file("image");
        assertThat(fileId.version()).isEqualTo(4);
        get("/api/files/" + fileId, bearer("it-private-reader", "it.private.read")).expectStatus().isOk()
            .expectBody(MAP).value(meta -> assertThat(meta).containsEntry("originalName", null)
                .containsEntry("uploadedBy", null));
    }

    @Test
    void onlySomeoneWhoMayUploadUnderTheOutputPolicyStartsTheJob() {
        process("IT_FILES_MAKE", "out:jpeg", List.of(), bearer("it-no-upload", "it.job.start", "commerce.media.read"))
            .expectStatus().isForbidden();
        assertThat(query("SELECT 1 FROM sys_external_job WHERE requested_by = 'it-no-upload'")).isEmpty();
    }

    @Test
    void unsafeOrExcessFilesFailTheJobForGood() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("out:svg", "1:FAILED:false:OUTPUT_FILE_REJECTED");
        expected.put("out:html", "1:FAILED:false:OUTPUT_FILE_REJECTED");
        expected.put("out:pdf", "1:FAILED:false:OUTPUT_FILE_REJECTED");
        expected.put("out:broken", "1:FAILED:false:OUTPUT_FILE_REJECTED");
        expected.put("out:three", "1:FAILED:false:OUTPUT_FILE_LIMIT");
        expected.put("out:big", "1:FAILED:false:OUTPUT_FILE_TOO_LARGE");
        Map<String, String> jobs = new LinkedHashMap<>();
        expected.keySet().forEach(label -> jobs.put(label, start("IT_FILES_MAKE", label, List.of(), "it-unsafe",
            "it.job.start", "commerce.media.upload")));
        // A type that declares no output files gets none.
        String undeclared = start("IT_FILES_PLAIN", "out:jpeg", List.of(), "it-unsafe", "it.job.start");
        // Four at a time of a type.
        executor.runPending().block();
        executor.runPending().block();
        deliverer.deliverPending().block();
        expected.forEach((label, attempt) -> assertThat(attempts(jobs.get(label))).as(label)
            .containsExactly(attempt));
        assertThat(attempts(undeclared)).containsExactly("1:FAILED:false:BAD_RESULT");
        assertThat(((ExternalJobFailure) CALLBACKS.get(jobs.get("out:svg"))).errorMessage())
            .contains("FILE_TYPE_NOT_ALLOWED");
        assertThat(((ExternalJobFailure) CALLBACKS.get(jobs.get("out:broken"))).errorMessage())
            .contains("FILE_INVALID");
        // Nothing was stored for any of them.
        assertThat(query("SELECT 1 FROM sys_file WHERE uploaded_by = 'it-unsafe'")).isEmpty();
        assertThat(uploadLeftovers()).isEmpty();
    }

    @Test
    void aPolyglotIsStoredOnlyAsTheImageItAlsoIs() {
        String jobId = start("IT_FILES_MAKE", "out:polyglot", List.of(), "it-polyglot", "it.job.start",
            "commerce.media.upload");
        executor.runPending().block();
        deliverer.deliverPending().block();
        UUID fileId = ((ExternalJobCompletion) CALLBACKS.get(jobId)).file("image");
        byte[] original = stored(FileKeys.key(fileId, FileKeys.ORIGINAL));
        assertThat(FileSamples.contains(original, POLYGLOT_SCRIPT)).isFalse();
        assertThat(original[0]).isEqualTo((byte) 0xFF);
        assertThat(original[1]).isEqualTo((byte) 0xD8);
    }

    @Test
    void whatAFailedJobStoredIsSweptAsAnOrphan() {
        String jobId = start("IT_FILES_MAKE", "out:then-svg", List.of(), "it-orphans", "it.job.start",
            "commerce.media.upload");
        executor.runPending().block();
        deliverer.deliverPending().block();
        assertThat(attempts(jobId)).containsExactly("1:FAILED:false:OUTPUT_FILE_REJECTED");
        // The first file went in before the second was refused: the job records it, nothing refers to it.
        List<Map<String, Object>> outputs = query("SELECT output_key, file_id FROM sys_external_job_file"
            + " WHERE job_id = ? AND direction = 'OUTPUT'", jobId);
        assertThat(outputs).singleElement().satisfies(row -> assertThat(row).containsEntry("output_key", "a"));
        UUID orphan = UUID.fromString((String) outputs.getFirst().get("file_id"));
        assertThat(query("SELECT 1 FROM sys_file WHERE file_id = ?", orphan)).hasSize(1);
        assertThat(((ExternalJobFailure) CALLBACKS.get(jobId)).errorCode()).isEqualTo("OUTPUT_FILE_REJECTED");

        clock.advance(Duration.ofHours(25));
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        assertThat(jobs.run(sweep, clock.instant())).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(query("SELECT 1 FROM sys_file WHERE file_id = ?", orphan)).isEmpty();
        String prefix = FileKeys.prefix(orphan) + "/";
        await().atMost(Duration.ofSeconds(10)).until(() -> storedKeys().stream().noneMatch(k -> k.startsWith(prefix)));
        // The record of which job produced it stays.
        assertThat(query("SELECT 1 FROM sys_external_job_file WHERE file_id = ?", orphan.toString())).hasSize(1);
    }

    @Test
    void aJobCancelledWhileRunningStoresNothing() throws Exception {
        String label = "gate-" + UUID.randomUUID() + " out:jpeg";
        GATES.put(label, new CountDownLatch(1));
        String jobId = start("IT_FILES_MAKE", label, List.of(), "it-cancel", "it.job.start",
            "commerce.media.upload");
        CompletableFuture<Integer> running = executor.runPending().toFuture();
        assertThat(STARTED.computeIfAbsent(label, k -> new CountDownLatch(1)).await(20, TimeUnit.SECONDS)).isTrue();
        client.post().uri("/api/external-jobs/" + jobId + "/cancel")
            .header(HttpHeaders.AUTHORIZATION, bearer("it-cancel")).exchange().expectStatus().isOk();
        GATES.get(label).countDown();
        assertThat(running.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(query("SELECT status FROM sys_external_job_outcome WHERE job_id = ?", jobId))
            .containsExactly(Map.of("status", "CANCELLED"));
        assertThat(query("SELECT 1 FROM sys_file WHERE uploaded_by = 'it-cancel'")).isEmpty();
        assertThat(query("SELECT 1 FROM sys_external_job_file WHERE job_id = ?", jobId)).isEmpty();
    }

    @Test
    void severalFilesComeBackByTheirKeys() {
        String jobId = start("IT_FILES_MAKE", "out:two", List.of(), "it-two", "it.job.start",
            "commerce.media.upload");
        executor.runPending().block();
        deliverer.deliverPending().block();
        Map<String, UUID> files = ((ExternalJobCompletion) CALLBACKS.get(jobId)).files();
        assertThat(files).containsOnlyKeys("a", "b");
        assertThat(query("SELECT content_type FROM sys_file WHERE file_id = ?", files.get("b")))
            .containsExactly(Map.of("content_type", "image/png"));
        assertThat(FileSamples.contains(stored(FileKeys.key(files.get("b"), FileKeys.ORIGINAL)),
            FileSamples.SECRET_MARKER.getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    // ---------------------------------------------------------------- the demo: a product photo redone

    @Test
    void theDemoRedoesAProductPhotoThroughTheAiService() {
        String photo = photo("it-photographer");
        String sku = "IMG" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(java.util.Locale.ROOT);
        String productId = product(sku, photo);
        String editor = bearer("it-image-editor", "commerce.product.write", "commerce.product.read",
            "commerce.media.read", "commerce.media.upload");
        Map<String, Object> started = client.post().uri("/api/processes/" + ProductImage.REQUEST + "/latest")
            .header(HttpHeaders.AUTHORIZATION, editor).contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("productId", productId, "style", "white")).exchange().expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        @SuppressWarnings("unchecked")
        String jobId = ((Map<String, Object>) started.get("output")).get("jobId").toString();
        executor.runPending().block();
        deliverer.deliverPending().block();

        // The service got the photo, all of it.
        long size = ((Number) query("SELECT size_bytes FROM sys_file WHERE file_id = ?", UUID.fromString(photo))
            .getFirst().get("size_bytes")).longValue();
        assertThat(query("SELECT quantity FROM sys_external_job_usage WHERE job_id = ? AND metric = 'receivedBytes'",
            jobId)).containsExactly(Map.of("quantity", size));
        assertThat(STUB.requests("/v1/images")).singleElement().satisfies(request ->
            assertThat(request.body()).contains("\"style\":\"white\"", "\"contentType\":\"image/jpeg\""));
        // The product shows the new photo: the editor's upload, re-encoded without the GPS of the service's answer.
        UUID fresh = UUID.fromString(productPhoto(sku));
        assertThat(fresh.toString()).isNotEqualTo(photo);
        assertThat(query("SELECT uploaded_by, policy FROM sys_file WHERE file_id = ?", fresh))
            .containsExactly(Map.of("uploaded_by", "it-image-editor", "policy", CommerceFiles.IMAGE));
        assertThat(FileSamples.contains(stored(FileKeys.key(fresh, FileKeys.ORIGINAL)),
            FileSamples.SECRET_MARKER.getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(query("SELECT DISTINCT title_key FROM sys_task_version WHERE source_key = ?",
            "product-image:" + jobId)).containsExactly(Map.of("title_key", "task.commerce.product-image.ready"));

        // A service that answers with markup instead of an image does not get it onto the product.
        STUB.script("/v1/images", 200, StubAiServer.imageAnswer(FileSamples.svg(), 0), Duration.ZERO);
        client.post().uri("/api/processes/" + ProductImage.REQUEST + "/latest")
            .header(HttpHeaders.AUTHORIZATION, editor).contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("productId", productId)).exchange().expectStatus().isOk();
        executor.runPending().block();
        deliverer.deliverPending().block();
        assertThat(productPhoto(sku)).isEqualTo(fresh.toString());
        assertThat(query("SELECT DISTINCT title_key FROM sys_task_version WHERE source_key LIKE 'product-image:%'"
            + " AND title_key LIKE '%failed'")).hasSize(1);

        // No photo, nothing to redo; no permission to upload photos, no job.
        String bare = product("NOIMG" + UUID.randomUUID().toString().substring(0, 4).toUpperCase(java.util.Locale.ROOT),
            null);
        client.post().uri("/api/processes/" + ProductImage.REQUEST + "/latest")
            .header(HttpHeaders.AUTHORIZATION, editor).contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("productId", bare)).exchange().expectStatus().isEqualTo(422);
        client.post().uri("/api/processes/" + ProductImage.REQUEST + "/latest")
            .header(HttpHeaders.AUTHORIZATION, bearer("it-copy-only", "commerce.product.write",
                "commerce.media.read")).contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("productId", productId)).exchange().expectStatus().isForbidden();
    }

    // ---------------------------------------------------------------- helpers

    /** A product photo uploaded by {@code actor}; returns its id. */
    private String photo(String actor) {
        return (String) uploaded(CommerceFiles.IMAGE, FileSamples.jpeg(200, 150), "photo.jpg", "image/jpeg",
            bearer(actor, "commerce.media.upload", "commerce.media.read")).get("fileId");
    }

    private static String digestOf(String fileId) {
        return (String) query("SELECT sha256 FROM sys_file WHERE file_id = ?", UUID.fromString(fileId)).getFirst()
            .get("sha256");
    }

    private WebTestClient.ResponseSpec process(String name, String label, List<UUID> files, String authorization) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("label", label);
        input.put("files", files.stream().map(UUID::toString).toList());
        return post("/api/processes/" + name + "/latest", authorization, input);
    }

    private String start(String name, String label, List<UUID> files, String actor, String... permissions) {
        Map<String, Object> result = process(name, label, files, bearer(actor, permissions)).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.get("output");
        return output.get("jobId").toString();
    }

    private Map<String, Object> refused(String name, List<UUID> files, String... permissions) {
        return violation(process(name, "read", files, bearer("it-files-stranger", permissions))
            .expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody());
    }

    /** The digits of a message: numbers in it may be grouped by the locale. */
    private static String digits(Object message) {
        return String.valueOf(message).replaceAll("[^0-9]", "");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> violation(Map<String, Object> problem) {
        return ((List<Map<String, Object>>) problem.get("violations")).getFirst();
    }

    private static List<String> attempts(String jobId) {
        return query("SELECT attempt_no, outcome, retryable, error_code FROM sys_external_job_attempt"
            + " WHERE job_id = ? ORDER BY attempt_no", jobId).stream()
            .map(row -> row.get("attempt_no") + ":" + row.get("outcome") + ":" + row.get("retryable") + ":"
                + row.get("error_code"))
            .toList();
    }

    private String product(String sku, String photo) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("sku", sku);
        attributes.put("productName", "Product " + sku);
        attributes.put("unitPrice", 100);
        attributes.put("active", true);
        attributes.put("imageFileId", photo);
        post("/api/datasets/" + CommerceEntities.PRODUCT_DATASET + "/commit", bearer("it-admin", "*"),
            Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", attributes)))).expectStatus().isOk();
        return String.valueOf(products(sku).getFirst().get("id"));
    }

    @SuppressWarnings("unchecked")
    private String productPhoto(String sku) {
        return (String) ((Map<String, Object>) products(sku).getFirst().get("attributes")).get("imageFileId");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> products(String sku) {
        Map<String, Object> page = post("/api/datasets/" + CommerceEntities.PRODUCT_DATASET + "/query",
            bearer("it-admin", "*"), Map.of("filters", List.of(Map.of("field", "sku", "op", "eq", "value", sku))))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return (List<Map<String, Object>>) page.get("items");
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] concat(byte[] first, byte[] second) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(first);
        out.writeBytes(second);
        return out.toByteArray();
    }

    /** {@code content} followed by zeros up to {@code size} bytes. */
    static byte[] padded(byte[] content, int size) {
        byte[] padded = new byte[size];
        System.arraycopy(content, 0, padded, 0, content.length);
        return padded;
    }
}
