package com.jabiz.runtime.external;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.external.ExternalJobException;
import com.jabiz.external.ExternalJobFile;
import com.jabiz.external.ExternalJobType;
import com.jabiz.external.GeneratedFile;
import com.jabiz.runtime.PayloadTooLargeException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.file.FileKeys;
import com.jabiz.runtime.file.FileUploadService;
import com.jabiz.runtime.file.LocalFileStore;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
 *       whose result is dropped, are referred to by nothing and go with the sweep.</li>
 * </ul>
 */
@Component
class ExternalJobFiles {

    static final String INPUT_FILE_MISSING = "INPUT_FILE_MISSING";
    static final String INPUT_FILE_UNREADABLE = "INPUT_FILE_UNREADABLE";
    static final String OUTPUT_FILE_REJECTED = "OUTPUT_FILE_REJECTED";
    static final String OUTPUT_FILE_FAILED = "OUTPUT_FILE_FAILED";

    /** How long copying a job's files, or storing them, may take; it is beyond the handler's timeout. */
    static final Duration FILE_WORK_TIMEOUT = Duration.ofMinutes(1);

    /** A file an attempt produced, stored and registered. */
    record Output(int position, String key, ExternalJobRecorder.StoredFile file) {}

    /**
     * What storing an attempt's files came to: every file stored, or a failure with the files stored before it.
     *
     * @param failure null when every file was stored
     */
    record Outputs(List<Output> stored, ExternalJobException failure) {}

    private final ExternalJobRecorder recorder;
    private final LocalFileStore store;
    private final FileUploadService uploads;

    ExternalJobFiles(ExternalJobRecorder recorder, LocalFileStore store, FileUploadService uploads) {
        this.recorder = recorder;
        this.store = store;
        this.uploads = uploads;
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
            List<Copy> copies = Collections.synchronizedList(new ArrayList<>());
            return recorder.inputFiles(jobId)
                .concatMap(file -> copy(jobId, file).doOnNext(copies::add))
                .then(Mono.fromSupplier(() -> new Inputs(copies)))
                .timeout(FILE_WORK_TIMEOUT)
                .onErrorResume(error -> new Inputs(copies).close(store)
                    .onErrorResume(cleanup -> Mono.empty())
                    .then(Mono.error(inputFailure(error))));
        });
    }

    private Mono<Copy> copy(String jobId, ExternalJobRecorder.InputFile file) {
        ExternalJobRecorder.StoredFile current = file.current();
        if (current == null || current.sizeBytes() != file.sizeBytes() || !current.sha256().equals(file.sha256())) {
            // Deleted since the start (by its owner, or as an orphan): the job cannot do what it was asked.
            return Mono.error(ExternalJobException.permanent(INPUT_FILE_MISSING, "file " + file.fileId()
                + " given to the job no longer exists"));
        }
        return store.newUploadFile().flatMap(path -> DataBufferUtils.write(
                    store.read(FileKeys.key(file.fileId(), FileKeys.ORIGINAL), 0, file.sizeBytes() + 1), path,
                    StandardOpenOption.WRITE)
                .then(Mono.fromCallable(() -> Files.size(path)).subscribeOn(Schedulers.boundedElastic()))
                .flatMap(size -> size == file.sizeBytes()
                    ? Mono.just(new Copy(file.fileId(), current.contentType(), size, path))
                    : Mono.error(new IOException("stored content of " + file.fileId() + " has " + size + " bytes")))
                .onErrorResume(error -> store.discard(path).onErrorResume(cleanup -> Mono.empty())
                    .then(Mono.error(error))));
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
        List<Output> stored = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<String> current = new AtomicReference<>();
        RequestContext as = new RequestContext(requester, null, Locale.ENGLISH, "job-" + jobId, Set.of(), Set.of());
        return Flux.range(0, files.size())
            .concatMap(position -> {
                GeneratedFile file = files.get(position);
                current.set(file.key());
                return uploads.storeGenerated(policy, file.name(), file.content(), jobId)
                    .map(result -> new Output(position, file.key(), new ExternalJobRecorder.StoredFile(
                        result.info().fileId(), result.info().policy(), result.info().contentType(),
                        result.info().sizeBytes(), result.sha256())))
                    .doOnNext(stored::add);
            })
            .then(Mono.fromSupplier(() -> new Outputs(List.copyOf(stored), null)))
            .timeout(FILE_WORK_TIMEOUT)
            .contextWrite(view -> RequestContexts.put(view, as))
            .onErrorResume(error -> Mono.just(new Outputs(List.copyOf(stored), outputFailure(current.get(),
                error))));
    }

    private static ExternalJobException outputFailure(String key, Throwable error) {
        List<Violation> refusals = error instanceof ValidationException invalid ? invalid.violations()
            : error instanceof PayloadTooLargeException large ? large.violations() : null;
        if (refusals != null && !refusals.isEmpty()) {
            return ExternalJobException.permanent(OUTPUT_FILE_REJECTED, "file " + key + " was refused by the "
                + "output policy (" + refusals.getFirst().ruleCode() + ")");
        }
        return ExternalJobException.retryable(OUTPUT_FILE_FAILED, "file " + key + " could not be stored ("
            + error.getClass().getSimpleName() + ")");
    }

    LocalFileStore storage() {
        return store;
    }
}
