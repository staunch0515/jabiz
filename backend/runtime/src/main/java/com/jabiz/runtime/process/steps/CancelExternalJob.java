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
 * <p>Who may run it is the process's own permission, declared as every process's is; which jobs it may cancel is
 * narrowed by the step, deny by default: jobs of the type it declares, <b>started by the process's actor</b>. A job of
 * another type, a job someone else started and a job that does not exist are the same 404, so that a process cannot
 * be used to probe or cancel other people's jobs. A process that acts for a known owner names them
 * ({@link Spec#requestedBy}); an operator's or the system's process that may cancel anyone's job of the type says so
 * explicitly ({@link Spec#anyRequester()}).
 *
 * <p>A job that has finished (or was cancelled before) is 422 {@code EXTERNAL_JOB_FINISHED}, or nothing with
 * {@link Spec#ifOpen()}. The cancellation is recorded with the process's actor as the canceller, in the process's
 * transaction: a rollback leaves the job open, and the job's row stays locked until the process's transaction ends.
 * Once the job has ended (at once when no attempt is running, otherwise after the running attempt, whose result is
 * dropped), the type's {@code onCancelled} process runs, as for every cancellation.
 */
@Component
public class CancelExternalJob<C extends ProcessContext> implements StepHandler<CancelExternalJob.Metadata<C>, C>,
    CheckedStep<CancelExternalJob.Metadata<C>> {

    /**
     * @param jobType      the declared job type; only its jobs are cancelled
     * @param jobId        reads the id of the job to cancel from the context
     * @param ifOpen       whether a finished job is no error (the step then does nothing)
     * @param outputKey    context key receiving whether the job was cancelled now ({@code Boolean}); null to discard it
     * @param requestedBy  reads whose job may be cancelled; null for the process's actor (the default)
     * @param anyRequester whether a job of the type may be cancelled whoever started it
     */
    public record Metadata<C>(String jobType, Function<C, String> jobId, boolean ifOpen, String outputKey,
        Function<C, String> requestedBy, boolean anyRequester) {
        public Metadata {
            Objects.requireNonNull(jobType, "jobType must not be null");
            Objects.requireNonNull(jobId, "jobId must not be null");
            if (anyRequester && requestedBy != null) {
                throw new IllegalArgumentException("A cancel step names an owner or allows any requester, not both");
            }
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
            Metadata<C> m = metadata;
            return new Spec<>(new Metadata<>(m.jobType(), m.jobId(), true, m.outputKey(), m.requestedBy(),
                m.anyRequester()));
        }

        /** Where whether the job was cancelled now ({@code Boolean}) goes in the context. */
        public Spec<C> outputKey(String key) {
            Metadata<C> m = metadata;
            return new Spec<>(new Metadata<>(m.jobType(), m.jobId(), m.ifOpen(), key, m.requestedBy(),
                m.anyRequester()));
        }

        /**
         * Cancels only jobs started by the user {@code owner} reads from the context (a process acting for a known
         * owner, say the system's process for a ban), instead of the process's actor; anyone else's is 404.
         */
        public Spec<C> requestedBy(Function<C, String> owner) {
            Metadata<C> m = metadata;
            return new Spec<>(new Metadata<>(m.jobType(), m.jobId(), m.ifOpen(), m.outputKey(),
                Objects.requireNonNull(owner, "owner must not be null"), false));
        }

        /**
         * Cancels a job of the type whoever started it: for an operator's or the system's process, whose permission
         * is meant for every requester's jobs. Never the default.
         */
        public Spec<C> anyRequester() {
            Metadata<C> m = metadata;
            return new Spec<>(new Metadata<>(m.jobType(), m.jobId(), m.ifOpen(), m.outputKey(), null, true));
        }
    }

    public static <C extends ProcessContext> Spec<C> of(String jobType, Function<C, String> jobId) {
        return new Spec<>(new Metadata<>(jobType, jobId, false, null, null, false));
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
            // Deny by default: the actor's own jobs, unless the step names the owner or allows any requester.
            String owner = metadata.anyRequester() ? null
                : metadata.requestedBy() == null ? ctx.request().actorId() : metadata.requestedBy().apply(ctx);
            // An unknown owner matches no job: 404, as for anyone else's.
            return recorder.cancel(jobId, job -> metadata.jobType().equals(job.jobType())
                        && (metadata.anyRequester() || owner != null && owner.equals(job.requestedBy())),
                    metadata.ifOpen(),
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
