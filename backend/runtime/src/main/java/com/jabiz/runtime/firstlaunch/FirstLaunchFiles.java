package com.jabiz.runtime.firstlaunch;

import com.jabiz.context.RequestContext;
import com.jabiz.firstlaunch.FirstLaunchConfig;
import com.jabiz.firstlaunch.FirstLaunchHandler;
import com.jabiz.firstlaunch.FirstLaunchStep;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.file.FileProcesses;
import com.jabiz.runtime.file.FileUploadService;
import com.jabiz.runtime.process.ProcessExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The file steps of the first launch (decision D82, docs/design/23-first-launch.md section 6): the bytes come from the
 * configuration's directory only ({@link FirstLaunchConfig.Files}: normalised paths that stay inside it, no symbolic
 * links, each file as its SHA-256 was when the configuration was read, the size limit), go through the platform's
 * whole upload pipeline ({@link FileUploadService#storeGenerated}: the type recognised from the content, images
 * re-encoded without their metadata, the policy's size and types), and are then registered by the sub-process
 * {@code FIRST_LAUNCH_FILE_REGISTER} under the run's step process {@code FIRST_LAUNCH_STEP}, as the upload of the
 * step's account, so that content processes, which accept only their actor's own uploads, take them; a refused
 * registration deletes the stored object. The registration holds the run's row lock and checks, by the process's
 * lineage, the content phase and the conditions of acting (a user the run created with no credential of their own);
 * in place of the upload permission, which such an account has not yet, stand the handler's declared policies. The
 * bytes in memory are bounded twice: the source file is read only up to {@code jabiz.first-launch.max-file-bytes},
 * and what the handler picks out of it is held to the policy's size before anything else is done with it (413). Only
 * the first launch's package uses this class (an architecture test).
 */
@Component
public class FirstLaunchFiles {

    private final FileUploadService uploads;
    private final ProcessExecutor processes;
    private final ObjectProvider<FirstLaunchHandler> handlers;

    public FirstLaunchFiles(FileUploadService uploads, ProcessExecutor processes,
        ObjectProvider<FirstLaunchHandler> handlers) {
        this.uploads = uploads;
        this.processes = processes;
        this.handlers = handlers;
    }

    /** The policies the application's handler names for file steps. */
    public Set<String> policies() {
        Set<String> policies = new LinkedHashSet<>();
        handlers.orderedStream().forEach(handler -> policies.addAll(handler.filePolicies()));
        return Set.copyOf(policies);
    }

    /**
     * Stores the file of {@code step} as the account {@code actor} in the content phase of {@code runId}; gives the
     * stored file's id. The bytes are read from the configuration's directory as it was when the run began (each read
     * checked against its hash) and stored through the whole upload pipeline; the row is then registered by
     * {@link FirstLaunchFileRegister} under the run's {@code FIRST_LAUNCH_STEP}, with the powers' checks in that
     * transaction; if they refuse, the stored objects are deleted.
     */
    public Mono<UUID> store(UUID runId, FirstLaunchConfig config, FirstLaunchStep step, String actor,
        RequestContext request) {
        FirstLaunchStep.FileSource source = step.file();
        if (source == null) {
            return Mono.error(new IllegalStateException("Step " + step.key() + " is not a file step"));
        }
        if (actor == null || RequestContext.SYSTEM_ACTOR.equals(actor)) {
            return Mono.error(new PermissionDeniedException(FirstLaunchProcesses.PERMISSION, "First launch: file step "
                + step.key() + " stores the file as an account (FirstLaunchStep.asAccount)"));
        }
        if (!policies().contains(source.policy())) {
            return Mono.error(new PermissionDeniedException(FirstLaunchProcesses.PERMISSION, "First launch: file"
                + " step " + step.key() + " uses policy " + source.policy() + ", which the application's handler"
                + " does not name"));
        }
        return Mono.fromCallable(() -> {
                byte[] bytes = source.extract().apply(config.files().read(source.path()));
                if (bytes == null) {
                    throw new IllegalStateException("File step " + step.key() + " picked nothing out of "
                        + source.path());
                }
                return bytes;
            })
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(bytes -> uploads.storeGenerated(source.policy(), source.name(), bytes.length,
                () -> new ByteArrayInputStream(bytes), input -> processes.executeAtEntry(
                        FirstLaunchProcesses.STEP_PROCESS, new FirstLaunchProcesses.StepInput(runId.toString(),
                            step.key(), FirstLaunchFileRegister.NAME, input))
                    .map(output -> (FileProcesses.FileInfo) output.output())))
            .map(stored -> stored.info().fileId())
            .contextWrite(view -> RequestContexts.put(view, request));
    }
}
