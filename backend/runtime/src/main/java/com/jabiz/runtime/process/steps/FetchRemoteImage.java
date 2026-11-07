package com.jabiz.runtime.process.steps;

import com.jabiz.file.FilePolicy;
import com.jabiz.file.MediaTypes;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.file.FetchProcesses;
import com.jabiz.runtime.file.FilePolicyRegistry;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Downloads the image a link gives and stores it as a file of {@code policy} uploaded by the process's actor (decision
 * D75): {@code .step("Fetch the photo", FetchRemoteImage.of("commerce.image", ctx -> ctx.get("link", String.class))
 * .into("photo"))}, then {@code ctx.get("photo", FileProcesses.FileInfo.class).fileId()} in a compute step.
 *
 * <p>The step runs the sub-process {@code FILE_FETCH}: the actor must hold the policy's upload permission (403), the
 * link must be {@code https} on an allowed port, every host on the way must resolve to public addresses only, at most
 * {@code jabiz.files.fetch.max-redirects} redirects, at most the policy's size, and what arrives must be an image the
 * policy accepts; it is re-encoded without its metadata like an upload. Each failure fails the process with 422 and
 * one code ({@code FILE_FETCH_NOT_HTTPS}, {@code FILE_FETCH_LINK_INVALID}, {@code FILE_FETCH_ADDRESS_NOT_PUBLIC},
 * {@code FILE_FETCH_TOO_MANY_REDIRECTS}, {@code FILE_FETCH_TOO_LARGE}, {@code FILE_FETCH_NOT_IMAGE},
 * {@code FILE_FETCH_TIMEOUT}, {@code FILE_FETCH_FAILED}), or 400 {@code FILE_INVALID} for an image that cannot be
 * decoded or has too many pixels, 429 beyond {@code jabiz.process.rate-limits.FILE_FETCH}. A page that confirms
 * several links runs a process per link to list each reason.
 *
 * <p>The download happens inside the process's transaction (up to {@code jabiz.files.fetch.deadline}): put the step
 * before any lock. The policy must exist and accept an image type (startup check).
 */
@Component
public class FetchRemoteImage<C extends ProcessContext> implements StepHandler<FetchRemoteImage.Metadata<C>, C>,
    CheckedStep<FetchRemoteImage.Metadata<C>> {

    /**
     * @param policy    the file policy the image is stored under
     * @param link      gives the link from the context
     * @param targetKey where the stored file ({@code FileProcesses.FileInfo}) is put
     */
    public record Metadata<C>(String policy, Function<C, String> link, String targetKey) {
        public Metadata {
            Objects.requireNonNull(policy, "policy must not be null");
            Objects.requireNonNull(link, "link must not be null");
            Objects.requireNonNull(targetKey, "targetKey must not be null");
        }
    }

    /** The first half of the declaration: the policy and the link. */
    public record Builder<C extends ProcessContext>(String policy, Function<C, String> link) {

        /** Puts the stored file ({@code FileProcesses.FileInfo}) under {@code targetKey}. */
        public StepSpec<Metadata<C>, C> into(String targetKey) {
            return StepSpec.of(FetchRemoteImage.class, new Metadata<>(policy, link, targetKey));
        }
    }

    /** Downloads the image {@code link} gives into a file of {@code policy}. */
    public static <C extends ProcessContext> Builder<C> of(String policy, Function<C, String> link) {
        return new Builder<>(Objects.requireNonNull(policy, "policy must not be null"),
            Objects.requireNonNull(link, "link must not be null"));
    }

    private final ProcessExecutor executor;
    private final FilePolicyRegistry policies;

    public FetchRemoteImage(ProcessExecutor executor, FilePolicyRegistry policies) {
        this.executor = executor;
        this.policies = policies;
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        return Mono.defer(() -> executor.executeChild(FetchProcesses.FETCH_PROCESS,
                new FetchProcesses.FetchInput(metadata.policy(), metadata.link().apply(ctx))))
            .doOnNext(file -> ctx.put(metadata.targetKey(), file))
            .then();
    }

    @Override
    public List<String> problems(Metadata<C> metadata) {
        FilePolicy policy = policies.find(metadata.policy()).orElse(null);
        if (policy == null) {
            return List.of("fetches an image into unknown file policy " + metadata.policy());
        }
        if (policy.allowed().stream().noneMatch(MediaTypes::isImage)) {
            return List.of("fetches an image into file policy " + metadata.policy() + ", which accepts no image");
        }
        return List.of();
    }
}
