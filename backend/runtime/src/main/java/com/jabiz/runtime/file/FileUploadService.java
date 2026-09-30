package com.jabiz.runtime.file;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.file.FileNames;
import com.jabiz.file.FilePolicy;
import com.jabiz.file.MediaTypeDetector;
import com.jabiz.file.MediaTypes;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.resource.ResourceNotFoundException;
import com.jabiz.runtime.PayloadTooLargeException;
import com.jabiz.runtime.RateLimitedException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.process.ExecutionOptions;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.ProcessResult;
import com.jabiz.runtime.process.entity.EntityIdGenerator;
import com.jabiz.runtime.security.Permissions;
import com.jabiz.runtime.web.TokenBucketLimiter;
import io.micrometer.common.KeyValues;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.codec.multipart.FilePartEvent;
import org.springframework.http.codec.multipart.PartEvent;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Receives an upload (docs/design/14-files.md section 1): checks the policy's upload permission and the caller's
 * rate, streams the single part {@code file} into the upload area while counting (the request is abandoned as soon
 * as the policy's limit is passed), recognises the type by content, re-encodes images, stores the objects and records
 * the file with {@code FILE_REGISTER}. If recording fails, the stored objects are deleted; the upload area is always
 * cleaned up.
 */
@Component
public class FileUploadService {

    /** Name of the one multipart part an upload carries. */
    public static final String PART = "file";

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int RATE_LIMITED_ACTORS = 10_000;

    /** A received upload in the upload area. */
    private record Received(Path path, long size, String originalName) {}

    /** What goes into storage: the (processed) original and its variants. */
    private record Prepared(MediaTypes type, Path original, Map<String, Path> variants, Integer width,
        Integer height) {}

    private final FilePolicyRegistry policies;
    private final LocalFileStore store;
    private final ImageProcessor images;
    private final ProcessExecutor executor;
    private final EntityIdGenerator ids;
    private final FileProperties properties;
    private final PlatformObservations observations;
    private final TokenBucketLimiter limiter;

    public FileUploadService(FilePolicyRegistry policies, LocalFileStore store, ImageProcessor images,
        ProcessExecutor executor, EntityIdGenerator ids, FileProperties properties,
        PlatformObservations observations) {
        this.policies = policies;
        this.store = store;
        this.images = images;
        this.executor = executor;
        this.ids = ids;
        this.properties = properties;
        this.observations = observations;
        this.limiter = new TokenBucketLimiter(Math.max(1, properties.uploadRatePerMinute()), Duration.ofMinutes(1),
            RATE_LIMITED_ACTORS);
    }

    /**
     * Stores one upload.
     *
     * @param declaredLength the request's {@code Content-Length}, or -1 when it has none
     */
    public Mono<FileProcesses.FileInfo> upload(String policyName, Flux<PartEvent> parts, long declaredLength) {
        return RequestContexts.current().flatMap(request -> {
            FilePolicy policy = policies.find(policyName)
                .orElseThrow(() -> new ResourceNotFoundException("No file policy " + policyName));
            Permissions.require(request, policy.uploadPermission(), "Uploading under " + policy.name());
            throttle(request);
            long maxRequest = properties.maxRequestBytes().toBytes();
            if (declaredLength > maxRequest) {
                throw tooLarge(maxRequest);
            }
            return observations.mono(PlatformObservations.FILE_UPLOAD, "upload " + policy.name(),
                KeyValues.of("policy", policy.name()),
                Mono.usingWhen(store.newUploadFile(),
                    upload -> receive(policy, parts, upload).flatMap(received -> storeFile(policy, received)),
                    upload -> store.discard(upload).then(store.discard(workDirectory(upload)))));
        });
    }

    private void throttle(RequestContext request) {
        TokenBucketLimiter.Decision decision = limiter.tryAcquire(request.actorId());
        if (!decision.allowed()) {
            throw new RateLimitedException("Too many uploads; try again in " + decision.retryAfterSeconds()
                + " s", decision.retryAfterSeconds());
        }
    }

    /** Streams the part named {@value #PART} into {@code target}; any other part is refused. */
    private Mono<Received> receive(FilePolicy policy, Flux<PartEvent> parts, Path target) {
        AtomicLong size = new AtomicLong();
        AtomicBoolean seen = new AtomicBoolean();
        AtomicReference<String> name = new AtomicReference<>();
        return parts.windowUntil(PartEvent::isLast)
            .concatMap(part -> part.switchOnFirst((first, events) -> {
                if (!first.hasValue()) {
                    return events.then();
                }
                PartEvent event = first.get();
                if (!(event instanceof FilePartEvent filePart) || !PART.equals(event.name()) || seen.getAndSet(true)) {
                    DataBufferUtils.release(event.content());
                    return Mono.error(invalid("An upload is exactly one file part named '" + PART + "'"));
                }
                name.set(filePart.filename());
                Flux<DataBuffer> content = events.map(PartEvent::content).doOnNext(buffer -> {
                    if (size.addAndGet(buffer.readableByteCount()) > policy.maxBytes()) {
                        DataBufferUtils.release(buffer);
                        throw tooLarge(policy.maxBytes());
                    }
                });
                return DataBufferUtils.write(content, target, StandardOpenOption.WRITE).then();
            }))
            .then(Mono.fromCallable(() -> {
                if (!seen.get()) {
                    throw invalid("An upload is exactly one file part named '" + PART + "'");
                }
                if (size.get() == 0) {
                    throw invalid("The file is empty");
                }
                return new Received(target, size.get(), FileNames.sanitize(name.get()));
            }));
    }

    private Mono<FileProcesses.FileInfo> storeFile(FilePolicy policy, Received received) {
        return detect(received.path()).flatMap(detected -> {
            if (detected.isEmpty() || !policy.allows(detected.get())) {
                return Mono.error(notAllowed(policy));
            }
            MediaTypes type = detected.get();
            UUID fileId = UUID.fromString(String.valueOf(ids.next(FileEntities.SYS_FILE)));
            return prepare(policy, type, received.path())
                .flatMap(prepared -> digestAndSize(prepared.original()).flatMap(digest ->
                    write(fileId, prepared)
                        .then(register(policy, fileId, prepared, digest, received.originalName()))
                        .onErrorResume(error -> store.deleteAll(FileKeys.prefix(fileId))
                            .onErrorResume(cleanup -> Mono.empty())
                            .then(Mono.error(error)))));
        });
    }

    /** The type recognised from the content; empty when it is none the platform knows. */
    private Mono<Optional<MediaTypes>> detect(Path path) {
        return Mono.fromCallable(() -> MediaTypeDetector.detect(path)).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Prepared> prepare(FilePolicy policy, MediaTypes type, Path upload) {
        if (!type.isImage()) {
            return Mono.just(new Prepared(type, upload, Map.of(), null, null));
        }
        return images.process(upload, type, policy.image(), workDirectory(upload))
            .map(image -> new Prepared(type, image.original(), image.variants(), image.width(), image.height()));
    }

    private Mono<Void> write(UUID fileId, Prepared prepared) {
        Map<String, Path> objects = new LinkedHashMap<>();
        objects.put(FileKeys.ORIGINAL, prepared.original());
        objects.putAll(prepared.variants());
        return Flux.fromIterable(objects.entrySet())
            .concatMap(entry -> store.write(FileKeys.key(fileId, entry.getKey()),
                DataBufferUtils.read(entry.getValue(), DefaultDataBufferFactory.sharedInstance, BUFFER_SIZE)))
            .then();
    }

    private Mono<FileProcesses.FileInfo> register(FilePolicy policy, UUID fileId, Prepared prepared,
        Digest digest, String originalName) {
        FileProcesses.RegisterInput input = new FileProcesses.RegisterInput(fileId, policy.name(),
            prepared.type().contentType(), digest.size(), digest.sha256(), prepared.width(), prepared.height(),
            new ArrayList<>(prepared.variants().keySet()), originalName);
        return executor.run(FileProcesses.REGISTER_PROCESS, input, ExecutionOptions.NONE)
            .map(ProcessResult::output);
    }

    private record Digest(long size, String sha256) {}

    private static Mono<Digest> digestAndSize(Path path) {
        return Mono.fromCallable(() -> {
            MessageDigest digest = sha256();
            long size = 0;
            byte[] buffer = new byte[BUFFER_SIZE];
            try (InputStream in = Files.newInputStream(path)) {
                for (int n; (n = in.read(buffer)) > 0; ) {
                    digest.update(buffer, 0, n);
                    size += n;
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return new Digest(size, HexFormat.of().formatHex(digest.digest()));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static Path workDirectory(Path upload) {
        return upload.resolveSibling(upload.getFileName() + ".work");
    }

    private static ValidationException notAllowed(FilePolicy policy) {
        String allowed = policy.allowed().stream().map(Enum::name).collect(Collectors.joining(", "));
        return new ValidationException(List.of(new Violation(PART, PlatformErrorCodes.FILE_TYPE_NOT_ALLOWED,
            "Policy " + policy.name() + " accepts " + allowed, Map.of("allowed", allowed))));
    }

    static ValidationException invalid(String message) {
        return new ValidationException(List.of(new Violation(PART, PlatformErrorCodes.FILE_INVALID, message)));
    }

    static PayloadTooLargeException tooLarge(long maxBytes) {
        return new PayloadTooLargeException(new Violation(PART, PlatformErrorCodes.FILE_TOO_LARGE,
            "The upload exceeds " + maxBytes + " bytes", Map.of("max", humanSize(maxBytes))));
    }

    /** A size for people: bytes, KB or MB (binary units), as the limit was most likely declared. */
    static String humanSize(long bytes) {
        if (bytes >= FilePolicy.MB && bytes % FilePolicy.MB == 0) {
            return bytes / FilePolicy.MB + " MB";
        }
        if (bytes >= FilePolicy.KB && bytes % FilePolicy.KB == 0) {
            return bytes / FilePolicy.KB + " KB";
        }
        return bytes + " B";
    }
}
