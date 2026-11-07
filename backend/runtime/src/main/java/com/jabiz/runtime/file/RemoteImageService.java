package com.jabiz.runtime.file;

import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.file.FilePolicy;
import com.jabiz.file.MediaTypeDetector;
import com.jabiz.file.MediaTypes;
import com.jabiz.file.RemoteImageLink;
import com.jabiz.file.RemoteImages;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.resource.ResourceNotFoundException;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.ProcessBusyException;
import com.jabiz.runtime.RateLimitedException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.security.Permissions;
import com.jabiz.runtime.web.ProcessRateLimits;
import io.micrometer.common.KeyValues;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Images downloaded from links (docs/design/14-files.md section 12, decision D75), the two ways in:
 * <ul>
 *   <li>{@link #fetch} for {@code POST /api/files/fetch}: the caller's upload permission of the policy (403), a policy
 *       accepting an image type, the link checked, the per-user, per-host and per-instance rate (429), a free slot of
 *       {@code jabiz.files.fetch.max-concurrent} (503 {@code PROCESS_BUSY}), then the download outside any
 *       transaction ({@link RemoteImageFetcher}), the rest of the upload pipeline and {@code FILE_REGISTER}, the
 *       upload's own short transaction, as the caller;</li>
 *   <li>{@link #forJob} for external jobs' handlers ({@code ExternalJobContext.remoteImages()}): the same download,
 *       blocking on the handler's virtual thread, the job's requester's rate; a handler waits for a free slot until
 *       its deadline. The bytes go back through the job's output files (decision D50).</li>
 * </ul>
 * No database connection is held while downloading. The link is never stored or logged.
 */
@Component
public class RemoteImageService {

    /** The name downloads of a fetched file get: neutral, since a link's last segment may be a token. */
    static final String NAME = "image";

    static final String FETCHED = "fetched";

    private final FilePolicyRegistry policies;
    private final LocalFileStore store;
    private final FileUploadService uploads;
    private final RemoteImageFetcher fetcher;
    private final RemoteFetchProperties properties;
    private final FileProperties files;
    private final ProcessRateLimits rateLimits;
    private final PlatformObservations observations;
    private final Clock clock;
    private final Semaphore slots;
    /** Downloads in flight per actor, at most {@code max-concurrent-per-user} each. */
    private final Map<String, Integer> perUser = new ConcurrentHashMap<>();

    public RemoteImageService(FilePolicyRegistry policies, LocalFileStore store, FileUploadService uploads,
        RemoteImageFetcher fetcher, RemoteFetchProperties properties, FileProperties files,
        ProcessRateLimits rateLimits, PlatformObservations observations, Clock clock) {
        this.policies = policies;
        this.store = store;
        this.uploads = uploads;
        this.fetcher = fetcher;
        this.properties = properties;
        this.files = files;
        this.rateLimits = rateLimits;
        this.observations = observations;
        this.clock = clock;
        // An out-of-range value is the startup check's to report.
        this.slots = new Semaphore(Math.max(1, properties.maxConcurrent()));
    }

    /**
     * Downloads the image {@code url} gives and stores it as the caller's upload under {@code policyName}; refusals
     * are 422 {@code FILE_FETCH_*} (or the upload's 400 {@code FILE_INVALID}), 403, 404, 429 and 503.
     */
    public Mono<FileProcesses.FileInfo> fetch(String policyName, String url) {
        return RequestContexts.current().flatMap(request -> {
            FilePolicy policy = policyName == null ? null : policies.find(policyName).orElse(null);
            if (policy == null) {
                return Mono.error(new ResourceNotFoundException("No file policy " + policyName));
            }
            Permissions.require(request, policy.uploadPermission(), "Uploading under " + policy.name());
            if (policy.allowed().stream().noneMatch(MediaTypes::isImage)) {
                return Mono.error(refused(PlatformErrorCodes.FILE_FETCH_NOT_IMAGE, "Policy " + policy.name()
                    + " accepts no image", Map.of("allowed", "")));
            }
            Mono<FileProcesses.FileInfo> stored = Mono.defer(() -> {
                RemoteImageLink link = RemoteImageLink.parse(url, properties.ports());
                String actor = request.actorId();
                rateLimits.checkFetch(request, link.host());
                if (!takeUserSlot(actor)) {
                    return Mono.error(userBusy());
                }
                if (!slots.tryAcquire()) {
                    releaseUserSlot(actor);
                    return Mono.error(new ProcessBusyException(ProcessRateLimits.FILE_FETCH,
                        ProcessBusyException.Reason.QUEUE_FULL, 1));
                }
                return Mono.usingWhen(store.newUploadFile(),
                        download -> fetcher.fetch(link, policy.maxBytes(), download, rateLimits::checkFetchHost)
                            // Re-encoding and FILE_REGISTER (its own short transaction) as for an upload.
                            .flatMap(size -> uploads.storeFetched(policy, download, size, NAME,
                                uploads::registerProcess))
                            .map(FileUploadService.Stored::info),
                        download -> store.discard(download)
                            .then(store.discard(FileUploadService.workDirectory(download))))
                    .doFinally(signal -> {
                        slots.release();
                        releaseUserSlot(actor);
                    });
            });
            return observations.monoOutcome(PlatformObservations.FILE_FETCH, "fetch image", KeyValues.empty(),
                stored, info -> FETCHED, RemoteImageService::outcome);
        }).onErrorMap(RemoteImageLink.Refused.class, e -> refused(e.code(), e.getMessage(), Map.of()))
            .onErrorMap(RemoteImageFetcher.Refusal.class, e -> refused(e.code(), e.getMessage(), e.params()));
    }

    /** The downloads of one attempt of an external job requested by {@code requester}, waiting up to {@code deadline}. */
    public RemoteImages forJob(String requester, java.util.function.Supplier<Instant> deadline) {
        Objects.requireNonNull(deadline, "deadline");
        return (url, maxBytes) -> fetchBlocking(requester, deadline.get(), url, maxBytes);
    }

    private RemoteImages.Image fetchBlocking(String requester, Instant deadline, String url, long maxBytes) {
        if (Schedulers.isInNonBlockingThread()) {
            throw new IllegalStateException("Images are downloaded on a handler's own thread, never on an event loop");
        }
        long limit = Math.min(maxBytes, files.maxRequestBytes().toBytes());
        if (limit <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        RemoteImageLink link;
        try {
            link = RemoteImageLink.parse(url, properties.ports());
            rateLimits.checkFetch(requester, link.host());
        } catch (RemoteImageLink.Refused e) {
            throw new RemoteImages.Refused(e.code(), e.getMessage(), Map.of());
        } catch (RateLimitedException e) {
            throw new RemoteImages.Refused(PlatformErrorCodes.RATE_LIMITED, e.getMessage(),
                Map.of("retryAfter", e.retryAfterSeconds()));
        }
        if (!takeUserSlot(requester)) {
            throw new RemoteImages.Refused(PlatformErrorCodes.RATE_LIMITED, userBusy().getMessage(),
                Map.of("retryAfter", 1));
        }
        long wait = Math.max(0, Duration.between(clock.instant(), deadline).toMillis());
        try {
            if (!slots.tryAcquire(wait, TimeUnit.MILLISECONDS)) {
                releaseUserSlot(requester);
                throw new RemoteImages.Refused(PlatformErrorCodes.PROCESS_BUSY, "No download slot became free in time",
                    Map.of());
            }
        } catch (InterruptedException e) {
            releaseUserSlot(requester);
            Thread.currentThread().interrupt();
            throw new RemoteImages.Refused(PlatformErrorCodes.FILE_FETCH_TIMEOUT, "Interrupted while waiting", Map.of());
        }
        try {
            Mono<RemoteImages.Image> image = Mono.usingWhen(store.newUploadFile(),
                download -> fetcher.fetch(link, limit, download, rateLimits::checkFetchHost).flatMap(size -> Mono.fromCallable(() -> {
                    byte[] content = Files.readAllBytes(download);
                    MediaTypes type = MediaTypeDetector.detect(content).filter(MediaTypes::isImage).orElse(null);
                    if (type == null) {
                        throw new RemoteImages.Refused(PlatformErrorCodes.FILE_FETCH_NOT_IMAGE,
                            "What the link gives is not an image", Map.of("allowed", "JPEG, PNG"));
                    }
                    return new RemoteImages.Image(content, type);
                }).subscribeOn(Schedulers.boundedElastic())),
                download -> store.discard(download));
            return observations.monoOutcome(PlatformObservations.FILE_FETCH, "fetch image", KeyValues.empty(), image,
                    result -> FETCHED, RemoteImageService::outcome)
                .onErrorMap(RemoteImageFetcher.Refusal.class,
                    e -> new RemoteImages.Refused(e.code(), e.getMessage(), e.params()))
                .onErrorMap(RemoteImageLink.Refused.class,
                    e -> new RemoteImages.Refused(e.code(), e.getMessage(), Map.of()))
                .onErrorMap(RateLimitedException.class, e -> new RemoteImages.Refused(PlatformErrorCodes.RATE_LIMITED,
                    e.getMessage(), Map.of("retryAfter", e.retryAfterSeconds())))
                .block();
        } catch (UncheckedIOException e) {
            throw new RemoteImages.Refused(PlatformErrorCodes.FILE_FETCH_FAILED, "The download could not be kept",
                Map.of("reason", "UNREACHABLE"));
        } finally {
            slots.release();
            releaseUserSlot(requester);
        }
    }

    private boolean takeUserSlot(String actor) {
        String key = Objects.requireNonNullElse(actor, "anonymous");
        int max = Math.max(1, properties.maxConcurrentPerUser());
        boolean[] taken = {false};
        perUser.compute(key, (k, n) -> {
            int now = n == null ? 0 : n;
            if (now >= max) {
                return n;
            }
            taken[0] = true;
            return now + 1;
        });
        return taken[0];
    }

    private void releaseUserSlot(String actor) {
        perUser.computeIfPresent(Objects.requireNonNullElse(actor, "anonymous"), (k, n) -> n <= 1 ? null : n - 1);
    }

    private RateLimitedException userBusy() {
        return new RateLimitedException("At most " + properties.maxConcurrentPerUser()
            + " downloads from links at once per person; try again in 1 second", 1);
    }

    /** Free download slots now (tests). */
    int freeSlots() {
        return slots.availablePermits();
    }

    /** The observation's outcome: the error code of a refusal, otherwise the kind of failure, never its message. */
    static String outcome(Throwable error) {
        if (error instanceof RemoteImageFetcher.Refusal refusal) {
            return refusal.code();
        }
        if (error instanceof RemoteImageLink.Refused refused) {
            return refused.code();
        }
        if (error instanceof RemoteImages.Refused refused) {
            return refused.code();
        }
        if (error instanceof BusinessRuleViolationException rule) {
            return rule.violations().getFirst().ruleCode();
        }
        if (error instanceof ValidationException invalid && !invalid.violations().isEmpty()) {
            return invalid.violations().getFirst().ruleCode();
        }
        if (error instanceof RateLimitedException) {
            return PlatformErrorCodes.RATE_LIMITED;
        }
        if (error instanceof ProcessBusyException) {
            return PlatformErrorCodes.PROCESS_BUSY;
        }
        return "error";
    }

    private static BusinessRuleViolationException refused(String code, String message, Map<String, Object> params) {
        return new BusinessRuleViolationException(new Violation("url", code, message, params));
    }
}
