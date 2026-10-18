package com.jabiz.runtime.external;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.external.ExternalJobException;
import com.jabiz.external.ExternalJobFile;
import com.jabiz.external.ExternalJobType;
import com.jabiz.external.GeneratedFile;
import com.jabiz.file.FilePolicy;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.runtime.PayloadTooLargeException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.file.FileKeys;
import com.jabiz.runtime.file.FilePolicyRegistry;
import com.jabiz.runtime.file.FileUploadService;
import com.jabiz.runtime.file.LocalFileStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The files of external jobs (decision D50 items 2–3), for the executor. Business handlers stay synchronous and never
 * see the platform's reactive file storage: this class works with it and hands them plain files.
 * <ul>
 *   <li><b>Input</b>: before an attempt, the files recorded with the job when it started, and only those, are copied
 *       from the storage into the upload area; the handler reads the copies ({@link ExternalJobFile}, plain blocking
 *       I/O on its virtual thread), and once the attempt is over they are deleted and refuse to be read. A file gone
 *       since the start fails the job for good ({@value #INPUT_FILE_MISSING}).</li>
 *   <li><b>Output</b>: after a successful attempt, each file the handler returned is stored exactly as an upload of
 *       the job's requester under the type's output policy ({@link FileUploadService#storeGenerated}: content type
 *       recognised and checked, images re-encoded without metadata, {@code FILE_REGISTER}). A file the policy refuses
 *       fails the job for good ({@value #OUTPUT_FILE_REJECTED}); the files stored before it, like those of an attempt
 *       whose result is dropped, are referred to by nothing and go with the sweep. A failure of the storage is tried
 *       again a few times with the same bytes ({@code store-retries}); only then does the attempt fail, and the next
 *       attempt runs the handler, and so calls the provider, again.</li>
 * </ul>
 * Copying and storing are each bounded by a time that grows with what the type declares ({@code work-minimum} and
 * {@code work-per-mib}), added to the attempt's lease. Each attempt of a type with output files reserves the type's
 * {@code maxTotalBytes} of the instance's {@code output-budget} while it runs; the claim takes no attempt that does
 * not fit, so the output files held at once stay within the budget whatever {@code max-in-flight} is.
 */
@Component
class ExternalJobFiles {

    static final String INPUT_FILE_MISSING = "INPUT_FILE_MISSING";
    static final String INPUT_FILE_CHANGED = "INPUT_FILE_CHANGED";
    static final String INPUT_FILE_UNREADABLE = "INPUT_FILE_UNREADABLE";
    static final String INPUT_FILE_NO_SPACE = "INPUT_FILE_NO_SPACE";
    static final String OUTPUT_FILE_REJECTED = "OUTPUT_FILE_REJECTED";
    static final String OUTPUT_FILE_FAILED = "OUTPUT_FILE_FAILED";

    /** What the output policy's refusals of an upload are; anything else is a failure of the storage. */
    static final Set<String> REFUSALS = Set.of(PlatformErrorCodes.FILE_TYPE_NOT_ALLOWED, PlatformErrorCodes.FILE_INVALID,
        PlatformErrorCodes.FILE_TOO_LARGE);

    /**
     * Settings of the jobs' files ({@code jabiz.external-jobs.files.*}).
     *
     * @param workMinimum    the least time copying a job's files, or storing them, may take (at least 1 s): beyond the
     *                       handler's timeout, and added to the lease
     * @param workPerMib     more time per MiB the type may copy or store, so that large declarations get longer
     * @param outputBudget   bytes of output files the attempts of one instance may hold at once: each attempt of a
     *                       type with output files reserves its {@code maxTotalBytes} when it is claimed
     * @param minFreeDisk    bytes the upload area must keep free after an input file is copied into it
     * @param storeRetries   further tries to store one output file after a failure of the storage, before the attempt
     *                       fails (and a new attempt calls the provider again)
     */
    record Settings(Duration workMinimum, Duration workPerMib, long outputBudget, long minFreeDisk, int storeRetries) {

        static final Settings DEFAULTS = new Settings(Duration.ofMinutes(1), Duration.ofMillis(500),
            256L * 1024 * 1024, 256L * 1024 * 1024, 2);

        Settings {
            if (workMinimum == null || workMinimum.compareTo(Duration.ofSeconds(1)) < 0 || workPerMib == null
                || workPerMib.isNegative() || outputBudget < ExternalJobType.MAX_OUTPUT_FILE_BYTES || minFreeDisk < 0
                || storeRetries < 0 || storeRetries > 10) {
                throw new IllegalArgumentException("jabiz.external-jobs.files: work-minimum at least 1s, work-per-mib "
                    + "not negative, output-budget at least " + ExternalJobType.MAX_OUTPUT_FILE_BYTES
                    + " bytes, min-free-disk not negative, store-retries 0 to 10");
            }
        }

        /** How long copying or storing {@code bytes} may take. */
        Duration work(long bytes) {
            long mib = Math.max(0, bytes) / (1024 * 1024) + 1;
            return workMinimum.plus(workPerMib.multipliedBy(mib));
        }
    }

    /**
     * A file an attempt produced, stored and registered.
     *
     * @param privateMetadata whether its policy keeps its metadata private (decision D48): its record then shows
     *                        neither when nor in what order it was made
     */
    record Output(int position, String key, ExternalJobRecorder.StoredFile file, boolean privateMetadata) {

        Output(int position, String key, ExternalJobRecorder.StoredFile file) {
            this(position, key, file, false);
        }
    }

    /**
     * What storing an attempt's files came to: every file stored, or a failure with the files stored before it.
     *
     * @param failure null when every file was stored
     */
    record Outputs(List<Output> stored, ExternalJobException failure) {}

    private final ExternalJobRecorder recorder;
    private final LocalFileStore store;
    private final FileUploadService uploads;
    private final FilePolicyRegistry policies;
    private final Settings settings;
    /** Bytes of the output budget the running attempts of this instance reserved. */
    private final AtomicLong reserved = new AtomicLong();

    @Autowired
    ExternalJobFiles(ExternalJobRecorder recorder, LocalFileStore store, FileUploadService uploads,
        FilePolicyRegistry policies,
        @Value("${jabiz.external-jobs.files.work-minimum:1m}") Duration workMinimum,
        @Value("${jabiz.external-jobs.files.work-per-mib:500ms}") Duration workPerMib,
        @Value("${jabiz.external-jobs.files.output-budget:256MB}") DataSize outputBudget,
        @Value("${jabiz.external-jobs.files.min-free-disk:256MB}") DataSize minFreeDisk,
        @Value("${jabiz.external-jobs.files.store-retries:2}") int storeRetries) {
        this(recorder, store, uploads, policies, new Settings(workMinimum, workPerMib, outputBudget.toBytes(),
            minFreeDisk.toBytes(), storeRetries));
    }

    ExternalJobFiles(ExternalJobRecorder recorder, LocalFileStore store, FileUploadService uploads,
        FilePolicyRegistry policies, Settings settings) {
        this.recorder = recorder;
        this.store = store;
        this.uploads = uploads;
        this.policies = policies;
        this.settings = settings;
    }

    Settings settings() {
        return settings;
    }

    /** How long copying the type's input files may take: its declared maximum, at the settings' pace. */
    Duration inputWork(ExternalJobType<?, ?> type) {
        return type.inputFiles() == null ? Duration.ZERO
            : settings.work(type.inputFiles().maxFiles() * type.inputFiles().maxBytes());
    }

    /** How long storing the type's output files may take, retries of the storage included. */
    Duration outputWork(ExternalJobType<?, ?> type) {
        return type.outputFiles() == null ? Duration.ZERO
            : settings.work(type.outputFiles().maxTotalBytes()).multipliedBy(1L + settings.storeRetries());
    }

    // ------------------------------------------------------------------ output budget

    /**
     * How many more attempts of the type this instance may run within its output budget: unlimited for a type that
     * produces no files. The claim takes no more, so that what the handlers hold at the end of their attempts stays
     * within {@code output-budget} (decision D50; the handler's own memory before it returns is its own).
     */
    int roomFor(ExternalJobType<?, ?> type) {
        if (type.outputFiles() == null) {
            return Integer.MAX_VALUE;
        }
        long free = settings.outputBudget() - reserved.get();
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, free / type.outputFiles().maxTotalBytes()));
    }

    /** Reserves the type's output for one claimed attempt; returns what to release when it is over. */
    Runnable reserve(ExternalJobType<?, ?> type) {
        if (type.outputFiles() == null) {
            return () -> { };
        }
        long bytes = type.outputFiles().maxTotalBytes();
        reserved.addAndGet(bytes);
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                reserved.addAndGet(-bytes);
            }
        };
    }

    long reserved() {
        return reserved.get();
    }

    // ------------------------------------------------------------------ input

    /** The copies of one attempt's input files; {@link #close()} deletes them and makes them unreadable. */
    static final class Inputs {

        static final Inputs NONE = new Inputs(List.of());

        private final List<Copy> copies;

        Inputs(List<Copy> copies) {
            this.copies = List.copyOf(copies);
        }

        List<ExternalJobFile> files() {
            return Collections.unmodifiableList(new ArrayList<>(copies));
        }

        Mono<Void> close(LocalFileStore store) {
            return Flux.fromIterable(copies).concatMap(copy -> copy.close(store)).then();
        }
    }

    /** A copy of an input file in the upload area, readable while the attempt runs. */
    static final class Copy implements ExternalJobFile {

        private final UUID fileId;
        private final String contentType;
        private final long sizeBytes;
        private final Path path;
        private final AtomicBoolean closed = new AtomicBoolean();

        Copy(UUID fileId, String contentType, long sizeBytes, Path path) {
            this.fileId = fileId;
            this.contentType = contentType;
            this.sizeBytes = sizeBytes;
            this.path = path;
        }

        @Override
        public UUID fileId() {
            return fileId;
        }

        @Override
        public String contentType() {
            return contentType;
        }

        @Override
        public long sizeBytes() {
            return sizeBytes;
        }

        @Override
        public InputStream open() {
            if (closed.get()) {
                throw new IllegalStateException("File " + fileId + " was readable during its job's attempt only");
            }
            try {
                return Files.newInputStream(path, StandardOpenOption.READ);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        Mono<Void> close(LocalFileStore store) {
            closed.set(true);
            return store.discard(path);
        }

        Path path() {
            return path;
        }

        @Override
        public String toString() {
            return "ExternalJobFile[fileId=" + fileId + ", contentType=" + contentType + ", sizeBytes=" + sizeBytes
                + "]";
        }
    }

    /**
     * Copies the files the job reads for one attempt; none for a type that reads none. Fails with a permanent
     * {@value #INPUT_FILE_MISSING} for a file deleted since the job started, with a retryable
     * {@value #INPUT_FILE_UNREADABLE} when the storage fails; what was copied is deleted then.
     */
    Mono<Inputs> open(ExternalJobType<?, ?> type, String jobId) {
        if (type.inputFiles() == null) {
            return Mono.just(Inputs.NONE);
        }
        return Mono.defer(() -> {
            // Every copy is listed as soon as its file exists, before anything is written into it: whatever ends the
            // copying - a failure, the timeout, a cancellation - deletes what is there.
            List<Copy> copies = Collections.synchronizedList(new ArrayList<>());
            Runnable discard = () -> new Inputs(copies).close(store).onErrorResume(cleanup -> Mono.empty())
                .subscribe();
            return recorder.inputFiles(jobId)
                .concatMap(file -> copy(file, copies))
                .then(Mono.fromSupplier(() -> new Inputs(copies)))
                .timeout(inputWork(type))
                .doOnCancel(discard)
                .onErrorResume(error -> new Inputs(copies).close(store)
                    .onErrorResume(cleanup -> Mono.empty())
                    .then(Mono.error(inputFailure(error))));
        });
    }

    private Mono<Copy> copy(ExternalJobRecorder.InputFile file, List<Copy> copies) {
        ExternalJobRecorder.StoredFile current = file.current();
        if (current == null || current.sizeBytes() != file.sizeBytes() || !current.sha256().equals(file.sha256())) {
            // Deleted since the start (by its owner, or as an orphan): the job cannot do what it was asked.
            return Mono.error(ExternalJobException.permanent(INPUT_FILE_MISSING, "file " + file.fileId()
                + " given to the job no longer exists"));
        }
        return store.newUploadFile()
            .map(path -> {
                Copy copy = new Copy(file.fileId(), current.contentType(), file.sizeBytes(), path);
                copies.add(copy);
                return copy;
            })
            .flatMap(copy -> requireSpace(copy.path(), file.sizeBytes())
                .then(DataBufferUtils.write(store.read(FileKeys.key(file.fileId(), FileKeys.ORIGINAL), 0,
                    file.sizeBytes() + 1), copy.path(), StandardOpenOption.WRITE).then())
                .then(Mono.fromCallable(() -> verify(copy.path(), file)).subscribeOn(Schedulers.boundedElastic()))
                .thenReturn(copy));
    }

    /**
     * Refuses a copy that would leave the upload area with less than {@code min-free-disk}: a retryable
     * {@value #INPUT_FILE_NO_SPACE}, so that a full disk delays the job rather than failing writes elsewhere.
     */
    private Mono<Void> requireSpace(Path path, long bytes) {
        return Mono.fromCallable(() -> Files.getFileStore(path).getUsableSpace())
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(usable -> enoughSpace(usable, bytes, settings.minFreeDisk()) ? Mono.empty()
                : Mono.error(ExternalJobException.retryable(INPUT_FILE_NO_SPACE, "not enough free disk to copy the "
                    + "job's files (" + bytes + " bytes, keeping " + settings.minFreeDisk() + " free)")));
    }

    static boolean enoughSpace(long usable, long bytes, long keepFree) {
        return usable - bytes >= keepFree;
    }

    /**
     * The copy is the file recorded when the job started: its size and its SHA-256, read back from the copy. The
     * stored content is never replaced (its key is the file's id), so a difference means the storage was changed
     * behind the platform's back: a permanent {@value #INPUT_FILE_CHANGED}.
     */
    private static Void verify(Path copy, ExternalJobRecorder.InputFile file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        long size = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(copy)) {
            for (int n; (n = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, n);
                size += n;
            }
        }
        if (size != file.sizeBytes() || !HexFormat.of().formatHex(digest.digest()).equals(file.sha256())) {
            throw ExternalJobException.permanent(INPUT_FILE_CHANGED, "the stored content of file " + file.fileId()
                + " is not what was recorded");
        }
        return null;
    }

    private static Throwable inputFailure(Throwable error) {
        if (error instanceof ExternalJobException job) {
            return job;
        }
        // The class only: messages of the storage may name paths.
        return ExternalJobException.retryable(INPUT_FILE_UNREADABLE, "the files of the job could not be read ("
            + error.getClass().getSimpleName() + ")");
    }

    // ------------------------------------------------------------------ output

    /**
     * Stores the files an attempt produced, one after another, as uploads of {@code requester} under the type's
     * output policy. A file the policy refuses (its size, its type, an image that does not decode) is a permanent
     * {@value #OUTPUT_FILE_REJECTED}; anything else a retryable {@value #OUTPUT_FILE_FAILED}. Never fails: a failure
     * comes back with the files stored before it, which are recorded with the attempt.
     */
    Mono<Outputs> store(ExternalJobType<?, ?> type, String jobId, String requester, List<GeneratedFile> files) {
        String policy = type.outputFiles().policy();
        boolean hidden = policies.find(policy).map(FilePolicy::privateMetadata).orElse(false);
        List<Output> stored = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<String> current = new AtomicReference<>();
        RequestContext as = new RequestContext(requester, null, Locale.ENGLISH, "job-" + jobId, Set.of(), Set.of());
        return Flux.range(0, files.size())
            .concatMap(position -> {
                GeneratedFile file = files.get(position);
                current.set(file.key());
                // A failure of the storage is tried again here, with the bytes the handler gave: a new attempt
                // would call the provider again, and pay for it (decision D50 item 3).
                return Mono.defer(() -> uploads.storeGenerated(policy, file.name(), file.size(), file::open, jobId))
                    .retryWhen(Retry.backoff(settings.storeRetries(), Duration.ofMillis(200))
                        .maxBackoff(Duration.ofSeconds(2)).filter(error -> refusal(error) == null)
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                    .map(result -> new Output(position, file.key(), new ExternalJobRecorder.StoredFile(
                        result.info().fileId(), result.info().policy(), result.info().contentType(),
                        result.info().sizeBytes(), result.sha256()), hidden))
                    .doOnNext(stored::add);
            })
            .then(Mono.fromSupplier(() -> new Outputs(List.copyOf(stored), null)))
            .timeout(outputWork(type))
            .contextWrite(view -> RequestContexts.put(view, as))
            .onErrorResume(error -> Mono.just(new Outputs(List.copyOf(stored), outputFailure(current.get(),
                error))));
    }

    /** The rule code of a refusal by the output policy, or null when the error is something else. */
    static String refusal(Throwable error) {
        List<Violation> violations = error instanceof ValidationException invalid ? invalid.violations()
            : error instanceof PayloadTooLargeException large ? large.violations() : List.of();
        return violations.stream().map(Violation::ruleCode).filter(REFUSALS::contains).findFirst().orElse(null);
    }

    static ExternalJobException outputFailure(String key, Throwable error) {
        String refused = refusal(error);
        if (refused != null) {
            return ExternalJobException.permanent(OUTPUT_FILE_REJECTED, "file " + key + " was refused by the "
                + "output policy (" + refused + ")");
        }
        return ExternalJobException.retryable(OUTPUT_FILE_FAILED, "file " + key + " could not be stored ("
            + error.getClass().getSimpleName() + "); a new attempt calls the service again");
    }

    LocalFileStore storage() {
        return store;
    }
}
