package com.jabiz.runtime.file;

import com.jabiz.entity.Violation;
import com.jabiz.file.FilePolicy;
import com.jabiz.file.MediaTypes;
import com.jabiz.file.RemoteImageLink;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.resource.ResourceNotFoundException;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.security.Permissions;
import com.jabiz.runtime.web.ProcessRateLimits;
import io.micrometer.common.KeyValues;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * The step of {@code FILE_FETCH} (decision D75): the policy's upload permission (403, as for an upload), a policy
 * accepting an image type, the link checked, the per-actor, per-host and per-instance rate (429), then the download
 * ({@link RemoteImageFetcher}) and the rest of the upload pipeline ({@link FileUploadService#storeFetched}); the file's
 * row is inserted in this transaction, uploaded by the actor. The objects stored before a later failure rolls the
 * transaction back are left to the sweep, as objects without a row (14 section 6).
 */
@Component
class FetchImage implements StepHandler<NoMetadata, ProcessContext> {

    /** The name downloads of the file get: neutral, since a link's last segment may be a token. */
    static final String NAME = "image";

    static final String FETCHED = "fetched";

    private final FilePolicyRegistry policies;
    private final LocalFileStore store;
    private final FileUploadService uploads;
    private final RemoteImageFetcher fetcher;
    private final RemoteFetchProperties properties;
    private final ProcessRateLimits rateLimits;
    private final PlatformObservations observations;

    FetchImage(FilePolicyRegistry policies, LocalFileStore store, FileUploadService uploads,
        RemoteImageFetcher fetcher, RemoteFetchProperties properties, ProcessRateLimits rateLimits,
        PlatformObservations observations) {
        this.policies = policies;
        this.store = store;
        this.uploads = uploads;
        this.fetcher = fetcher;
        this.properties = properties;
        this.rateLimits = rateLimits;
        this.observations = observations;
    }

    @Override
    public Mono<Void> execute(NoMetadata metadata, ProcessContext ctx) {
        return Mono.defer(() -> {
            FetchProcesses.FetchInput input = ctx.get(FetchProcesses.INPUT, FetchProcesses.FetchInput.class);
            // A sub-process's input is not bean-validated (CallProcess), so the step checks everything itself.
            FilePolicy policy = input.policy() == null ? null : policies.find(input.policy()).orElse(null);
            if (policy == null) {
                return Mono.error(new ResourceNotFoundException("No file policy " + input.policy()));
            }
            Permissions.require(ctx.request(), policy.uploadPermission(), "Uploading under " + policy.name());
            if (policy.allowed().stream().noneMatch(MediaTypes::isImage)) {
                return Mono.error(refused(PlatformErrorCodes.FILE_FETCH_NOT_IMAGE, "Policy " + policy.name()
                    + " accepts no image", Map.of("allowed", "")));
            }
            Mono<FileProcesses.FileInfo> stored = Mono.defer(() -> {
                RemoteImageLink link = RemoteImageLink.parse(input.imageLink(), properties.ports());
                rateLimits.checkFetch(ctx.request(), link.host());
                return Mono.usingWhen(store.newUploadFile(),
                    download -> fetcher.fetch(link, policy.maxBytes(), download)
                        .flatMap(size -> uploads.storeFetched(policy, download, size, NAME,
                            register -> Mono.fromCallable(() -> FileProcesses.record(ctx, register))))
                        .map(FileUploadService.Stored::info),
                    download -> store.discard(download)
                        .then(store.discard(FileUploadService.workDirectory(download))));
            });
            return observations.monoOutcome(PlatformObservations.FILE_FETCH, "fetch image", KeyValues.empty(),
                    stored, info -> FETCHED, FetchImage::outcome)
                .doOnNext(info -> ctx.put(FetchProcesses.OUTPUT, info))
                .then();
        }).onErrorMap(RemoteImageLink.Refused.class, e -> refused(e.code(), e.getMessage(), Map.of()))
            .onErrorMap(RemoteImageFetcher.Refusal.class, e -> refused(e.code(), e.getMessage(), e.params()));
    }

    /** The observation's outcome: the error code of a refusal, otherwise the kind of failure, never its message. */
    static String outcome(Throwable error) {
        if (error instanceof RemoteImageFetcher.Refusal refusal) {
            return refusal.code();
        }
        if (error instanceof RemoteImageLink.Refused refused) {
            return refused.code();
        }
        if (error instanceof BusinessRuleViolationException rule) {
            return rule.violations().getFirst().ruleCode();
        }
        if (error instanceof com.jabiz.entity.ValidationException invalid && !invalid.violations().isEmpty()) {
            return invalid.violations().getFirst().ruleCode();
        }
        if (error instanceof com.jabiz.runtime.RateLimitedException) {
            return PlatformErrorCodes.RATE_LIMITED;
        }
        return "error";
    }

    private static BusinessRuleViolationException refused(String code, String message, Map<String, Object> params) {
        return new BusinessRuleViolationException(new Violation(FileUploadService.PART, code, message, params));
    }
}
