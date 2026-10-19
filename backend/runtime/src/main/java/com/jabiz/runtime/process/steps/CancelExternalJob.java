package com.jabiz.runtime.process.steps;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSource;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.external.ExternalJobRecorder;
import com.jabiz.runtime.external.ExternalJobRegistry;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Cancels an asynchronous external job from a business process (decision D52 item 2): {@code .step("Stop the
 * generation", CancelExternalJob.of("qb.ai.questions", ctx -> ctx.get("jobId", String.class)).ifOpen())}.
 *
 * <p>Who may run it is the process's own permission, declared as every process's is; which jobs it may cancel is
 * narrowed by the step, deny by default: jobs of the type it declares, <b>started by the process's actor</b>. A step
 * may declare several types ({@link #of(List, Function)}: an application whose one "stop" action ends jobs of a few
 * types), each of which must be declared; a job of any other type is still refused. A job of
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
     * @param jobType      one of {@code jobTypes} (the first, from {@code of(List, …)}), for logs and the callers of
     *                     a single type
     * @param jobId        reads the id of the job to cancel from the context
     * @param ifOpen       whether a finished job is no error (the step then does nothing)
     * @param outputKey    context key receiving whether the job was cancelled now ({@code Boolean}); null to discard it
     * @param requestedBy  reads whose job may be cancelled; null for the process's actor (the default)
     * @param anyRequester whether a job of the type may be cancelled whoever started it
     * @param jobTypes     the declared job types (one of them {@code jobType}), in the order given; only their jobs are
     *                     cancelled
     */
    public record Metadata<C>(String jobType, Function<C, String> jobId, boolean ifOpen, String outputKey,
        Function<C, String> requestedBy, boolean anyRequester, Set<String> jobTypes) {
        public Metadata {
            Objects.requireNonNull(jobType, "jobType must not be null");
            Objects.requireNonNull(jobId, "jobId must not be null");
            Objects.requireNonNull(jobTypes, "jobTypes must not be null");
            for (String type : jobTypes) {
                Objects.requireNonNull(type, "a job type must not be null");
            }
            if (!jobTypes.contains(jobType)) {
                throw new IllegalArgumentException("jobType must be one of jobTypes");
            }
            // In the order given (of(List) passes them in the list's order).
            jobTypes = Collections.unmodifiableSet(new LinkedHashSet<>(jobTypes));
            if (anyRequester && requestedBy != null) {
                throw new IllegalArgumentException("A cancel step names an owner or allows any requester, not both");
            }
        }

        /** A step of one type, as decision D52 item 2 had it. */
        public Metadata(String jobType, Function<C, String> jobId, boolean ifOpen, String outputKey,
            Function<C, String> requestedBy, boolean anyRequester) {
            this(jobType, jobId, ifOpen, outputKey, requestedBy, anyRequester,
                Set.of(Objects.requireNonNull(jobType, "jobType must not be null")));
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
                m.anyRequester(), m.jobTypes()));
        }

        /** Where whether the job was cancelled now ({@code Boolean}) goes in the context. */
        public Spec<C> outputKey(String key) {
            Metadata<C> m = metadata;
            return new Spec<>(new Metadata<>(m.jobType(), m.jobId(), m.ifOpen(), key, m.requestedBy(),
                m.anyRequester(), m.jobTypes()));
        }

        /**
         * Cancels only jobs started by the user {@code owner} reads from the context (a process acting for a known
         * owner, say the system's process for a ban), instead of the process's actor; anyone else's is 404.
         */
        public Spec<C> requestedBy(Function<C, String> owner) {
            Metadata<C> m = metadata;
            return new Spec<>(new Metadata<>(m.jobType(), m.jobId(), m.ifOpen(), m.outputKey(),
                Objects.requireNonNull(owner, "owner must not be null"), false, m.jobTypes()));
        }

        /**
         * Cancels a job of the type whoever started it: for an operator's or the system's process, whose permission
         * is meant for every requester's jobs. Never the default.
         */
        public Spec<C> anyRequester() {
            Metadata<C> m = metadata;
            return new Spec<>(new Metadata<>(m.jobType(), m.jobId(), m.ifOpen(), m.outputKey(), null, true,
                m.jobTypes()));
        }
    }

    /** Cancels a job of the type {@code jobType}. */
    public static <C extends ProcessContext> Spec<C> of(String jobType, Function<C, String> jobId) {
        return new Spec<>(new Metadata<>(jobType, jobId, false, null, null, false));
    }

    /**
     * Cancels a job of any of the types {@code jobTypes} (decision D52 item 2): one step for an action that stops
     * whichever of a few kinds of job is running. Every type must be declared; a job of any other type is the same 404
     * as a missing one.
     *
     * @throws IllegalArgumentException when the list is empty or names a type twice
     */
    public static <C extends ProcessContext> Spec<C> of(List<String> jobTypes, Function<C, String> jobId) {
        Objects.requireNonNull(jobTypes, "jobTypes must not be null");
        if (jobTypes.isEmpty()) {
            throw new IllegalArgumentException("A cancel step names at least one job type");
        }
        Set<String> types = new LinkedHashSet<>();
        for (String type : jobTypes) {
            if (!types.add(Objects.requireNonNull(type, "a job type must not be null"))) {
                throw new IllegalArgumentException("A cancel step names the job type " + type + " twice");
            }
        }
        return new Spec<>(new Metadata<>(jobTypes.getFirst(), jobId, false, null, null, false, types));
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
            List<String> undeclared = undeclared(metadata);
            if (!undeclared.isEmpty()) {
                return Mono.error(new IllegalStateException("No external job type " + String.join(", ", undeclared)
                    + " is declared"));
            }
            String jobId = metadata.jobId().apply(ctx);
            // Deny by default: the actor's own jobs, unless the step names the owner or allows any requester.
            String owner = metadata.anyRequester() ? null
                : metadata.requestedBy() == null ? ctx.request().actorId() : metadata.requestedBy().apply(ctx);
            // An unknown owner matches no job: 404, as for anyone else's.
            return recorder.cancel(jobId, job -> metadata.jobTypes().contains(job.jobType())
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

    /** Every undeclared type, at once. */
    @Override
    public List<String> problems(Metadata<C> metadata) {
        return undeclared(metadata).stream().map(type -> "cancels undeclared external job type " + type).toList();
    }

    private List<String> undeclared(Metadata<C> metadata) {
        return metadata.jobTypes().stream().filter(type -> jobs.find(type).isEmpty()).toList();
    }
}
