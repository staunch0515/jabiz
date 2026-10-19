package com.jabiz.runtime.process.steps;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSource;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.external.ExternalJobRecorder;
import com.jabiz.runtime.external.ExternalJobRegistry;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Cancels an asynchronous external job from a business process (decision D52 item 2): {@code .step("Stop the
 * generation", CancelExternalJob.of("qb.ai.questions", ctx -> ctx.get("jobId", String.class)).ifOpen())}.
 *
 * <p>Who may cancel is the process's own permission, declared as every process's is: the step checks no permission
 * itself, and is therefore limited to jobs of the type it declares. A job of another type and a job that does not
 * exist are the same 404, so that a process cannot be used to probe or cancel other jobs. A job that has finished (or
 * was cancelled before) is 422 {@code EXTERNAL_JOB_FINISHED}, or nothing with {@link Spec#ifOpen()}. The cancellation
 * is recorded with the process's actor as the canceller, in the process's transaction: a rollback leaves the job open.
 * Once the job has ended (at once when no attempt is running, otherwise after the running attempt, whose result is
 * dropped), the type's {@code onCancelled} process runs, as for every cancellation. Business code checks the owner of
 * the job itself when it should (the process knows which of its records holds the job's id).
 */
@Component
public class CancelExternalJob<C extends ProcessContext> implements StepHandler<CancelExternalJob.Metadata<C>, C>,
    CheckedStep<CancelExternalJob.Metadata<C>> {

    /**
     * @param jobType   the declared job type; only its jobs are cancelled
     * @param jobId     reads the id of the job to cancel from the context
     * @param ifOpen    whether a finished job is no error (the step then does nothing)
     * @param outputKey context key receiving whether the job was cancelled now ({@code Boolean}); null to discard it
     */
    public record Metadata<C>(String jobType, Function<C, String> jobId, boolean ifOpen, String outputKey) {
        public Metadata {
            Objects.requireNonNull(jobType, "jobType must not be null");
            Objects.requireNonNull(jobId, "jobId must not be null");
        }
    }

    /** A use of the step under construction; each method returns a new one. */
    public static final class Spec<C extends ProcessContext> implements StepSource<Metadata<C>, C> {

        private final Metadata<C> metadata;

        private Spec(Metadata<C> metadata) {
            this.metadata = metadata;
        }

        @Override
        public StepSpec<Metadata<C>, C> toStepSpec() {
            return StepSpec.of(CancelExternalJob.class, metadata);
        }

        Metadata<C> metadata() {
            return metadata;
        }

        /** A job that has finished, or was cancelled before, is no error: the step does nothing. */
        public Spec<C> ifOpen() {
            return new Spec<>(new Metadata<>(metadata.jobType(), metadata.jobId(), true, metadata.outputKey()));
        }

        /** Where whether the job was cancelled now ({@code Boolean}) goes in the context. */
        public Spec<C> outputKey(String key) {
            return new Spec<>(new Metadata<>(metadata.jobType(), metadata.jobId(), metadata.ifOpen(), key));
        }
    }

    public static <C extends ProcessContext> Spec<C> of(String jobType, Function<C, String> jobId) {
        return new Spec<>(new Metadata<>(jobType, jobId, false, null));
    }

    private final ExternalJobRegistry jobs;
    private final ExternalJobRecorder recorder;

    public CancelExternalJob(ExternalJobRegistry jobs, ExternalJobRecorder recorder) {
        this.jobs = jobs;
        this.recorder = recorder;
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        return Mono.defer(() -> {
            if (jobs.find(metadata.jobType()).isEmpty()) {
                return Mono.error(new IllegalStateException("No external job type " + metadata.jobType()
                    + " is declared"));
            }
            String jobId = metadata.jobId().apply(ctx);
            return recorder.cancel(jobId, job -> metadata.jobType().equals(job.jobType()), metadata.ifOpen(),
                    ctx.request().actorId(), ctx.opTime(), ctx.processSeqId())
                .doOnNext(cancelled -> {
                    if (metadata.outputKey() != null) {
                        ctx.put(metadata.outputKey(), cancelled);
                    }
                })
                .then();
        });
    }

    @Override
    public List<String> problems(Metadata<C> metadata) {
        return jobs.find(metadata.jobType()).isPresent() ? List.of()
            : List.of("cancels undeclared external job type " + metadata.jobType());
    }
}
