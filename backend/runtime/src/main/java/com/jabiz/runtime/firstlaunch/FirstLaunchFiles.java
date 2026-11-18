package com.jabiz.runtime.firstlaunch;

import com.jabiz.context.RequestContext;
import com.jabiz.firstlaunch.FirstLaunchConfig;
import com.jabiz.firstlaunch.FirstLaunchHandler;
import com.jabiz.firstlaunch.FirstLaunchStep;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.file.FileUploadService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The file steps of the first launch (decision D82, docs/design/23-first-launch.md section 6): the bytes come from the
 * configuration's directory only ({@link FirstLaunchConfig.Files}: normalised paths that stay inside it, no symbolic
 * links, the size limit), go through the platform's whole upload pipeline ({@link FileUploadService#storeGenerated}:
 * the type recognised from the content, images re-encoded without their metadata, the policy's size and types) and
 * are registered as the upload of the step's account, so that content processes, which accept only their actor's own
 * uploads, take them. Nothing is loosened for the first launch but the upload permission, which an account the run
 * created has not yet: in its place stand the handler's declared policies and the conditions of acting (a user the run
 * created with no credential of their own, while the run's content phase goes on). Only the runner uses this class (an
 * architecture test).
 */
@Component
public class FirstLaunchFiles {

    private final FileUploadService uploads;
    private final FirstLaunchRuns runs;
    private final FirstLaunchPowers powers;
    private final ObjectProvider<FirstLaunchHandler> handlers;

    public FirstLaunchFiles(FileUploadService uploads, FirstLaunchRuns runs, FirstLaunchPowers powers,
        ObjectProvider<FirstLaunchHandler> handlers) {
        this.uploads = uploads;
        this.runs = runs;
        this.powers = powers;
        this.handlers = handlers;
    }

    /** The policies the application's handler names for file steps. */
    public Set<String> policies() {
        Set<String> policies = new LinkedHashSet<>();
        handlers.orderedStream().forEach(handler -> policies.addAll(handler.filePolicies()));
        return Set.copyOf(policies);
    }

    /**
     * Stores the file of {@code step} as {@code actor} (a user id, or {@code null} for the system) in the content
     * phase of {@code runId}; gives the stored file's id.
     */
    public Mono<UUID> store(UUID runId, FirstLaunchConfig config, FirstLaunchStep step, String actor, RequestContext request) {
        FirstLaunchStep.FileSource source = step.file();
        if (source == null) {
            return Mono.error(new IllegalStateException("Step " + step.key() + " is not a file step"));
        }
        if (!policies().contains(source.policy())) {
            return Mono.error(new PermissionDeniedException(FirstLaunchProcesses.PERMISSION, "First launch: file"
                + " step " + step.key() + " uses policy " + source.policy() + ", which the application's handler"
                + " does not name"));
        }
        Mono<Void> allowed = runs.current().flatMap(run -> {
            if (run.isEmpty() || !run.get().runId().equals(runId)
                || run.get().state() != FirstLaunchRun.State.RUNNING) {
                return Mono.error(new PermissionDeniedException(FirstLaunchProcesses.PERMISSION,
                    "First launch: the first launch's content phase is over"));
            }
            return actor == null ? Mono.<Void>empty() : powers.requireActable(run.get(), actor);
        });
        return allowed.then(Mono.fromCallable(() -> source.extract().apply(config.files().read(source.path()))))
            .flatMap(bytes -> uploads.storeGenerated(source.policy(), source.name(), bytes.length,
                () -> new ByteArrayInputStream(bytes), null))
            .map(stored -> stored.info().fileId())
            .contextWrite(view -> RequestContexts.put(view, request));
    }
}
