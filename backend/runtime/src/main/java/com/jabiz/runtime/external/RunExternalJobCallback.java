package com.jabiz.runtime.external;

import com.jabiz.context.RequestContext;
import com.jabiz.external.ExternalJobCompletion;
import com.jabiz.external.ExternalJobFailure;
import com.jabiz.external.ExternalJobType;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.ProcessRegistry;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The step of {@code EXTERNAL_JOB_COMPLETE} (decision D38 item 3): reads the finished job, its outcome and its usage,
 * and calls the job type's completion process with an {@link ExternalJobCompletion} (the result read back as the
 * type's result record, with the ids of the files the successful attempt produced, decision D50) or its failure process with an {@link ExternalJobFailure}, as a sub-process in the same
 * transaction, which also records that the event was consumed: called once. A cancelled job calls nothing (Q13).
 *
 * <p>Only the platform's event delivery runs it, as the system: anyone else, an administrator holding every
 * permission included, is refused, so that no caller can hand a made-up result to a completion process. (Until
 * decision D41 settles what {@code internal()} means, the completion and failure processes guard themselves the same
 * way.)
 */
@Component
class RunExternalJobCallback implements StepHandler<RunExternalJobCallback.Metadata, ProcessContext> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RunExternalJobCallback.class);

    record Metadata(String inputKey, String outputKey) {
        Metadata {
            Objects.requireNonNull(inputKey, "inputKey must not be null");
            Objects.requireNonNull(outputKey, "outputKey must not be null");
        }
    }

    static StepSpec<Metadata, ProcessContext> of(String inputKey, String outputKey) {
        return StepSpec.of(RunExternalJobCallback.class, new Metadata(inputKey, outputKey));
    }

    private final ExternalJobRecorder recorder;
    private final ExternalJobRegistry jobs;
    private final ProcessRegistry processes;
    private final ProcessExecutor executor;
    private final JsonMapper json;

    RunExternalJobCallback(ExternalJobRecorder recorder, ExternalJobRegistry jobs, ProcessRegistry processes,
        ProcessExecutor executor, JsonMapper json) {
        this.recorder = recorder;
        this.jobs = jobs;
        this.processes = processes;
        this.executor = executor;
        this.json = json;
    }

    @Override
    public Mono<Void> execute(Metadata metadata, ProcessContext ctx) {
        return Mono.defer(() -> {
            if (!RequestContext.SYSTEM_ACTOR.equals(ctx.request().actorId())) {
                return Mono.error(new PermissionDeniedException(ExternalJobPermissions.COMPLETE,
                    "External jobs are completed by the platform only"));
            }
            String jobId = ctx.get(metadata.inputKey(), ExternalJobProcesses.CompleteInput.class).jobId();
            return recorder.findJob(jobId)
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("No external job " + jobId)))
                .flatMap(job -> recorder.outcome(jobId)
                    .switchIfEmpty(Mono.error(() -> new IllegalStateException("External job " + jobId
                        + " has not finished")))
                    .flatMap(outcome -> Mono.zip(recorder.usage(jobId), producesFiles(job.jobType())
                        ? recorder.outputFiles(jobId) : Mono.just(Map.<String, UUID>of())).flatMap(found -> {
                        Map<String, Long> usage = found.getT1();
                        Map<String, UUID> files = found.getT2();
                        if ("CANCELLED".equals(outcome.status())) {
                            ctx.put(metadata.outputKey(), new ExternalJobProcesses.CompleteOutput(jobId,
                                outcome.status(), null));
                            return Mono.empty();
                        }
                        ExternalJobType<?, ?> type = jobs.find(job.jobType()).orElse(null);
                        if (type == null) {
                            // A type removed since (its jobs fail with TYPE_REMOVED): nobody to call, and retrying the
                            // event would not change that.
                            log.warn("External job {} is of type {}, which is no longer declared; no process is called",
                                jobId, job.jobType());
                            ctx.put(metadata.outputKey(), new ExternalJobProcesses.CompleteOutput(jobId,
                                outcome.status(), null));
                            return Mono.empty();
                        }
                        Object result = null;
                        String errorCode = outcome.errorCode();
                        String errorMessage = outcome.errorDetail();
                        boolean succeeded = "SUCCEEDED".equals(outcome.status());
                        if (succeeded) {
                            try {
                                result = json.readValue(outcome.result(), type.resultType());
                            } catch (RuntimeException e) {
                                // Stored by an earlier version of the result type, say: the job is reported failed
                                // rather than the event retried for nothing.
                                succeeded = false;
                                errorCode = "RESULT_UNREADABLE";
                                errorMessage = "the stored result could not be read as "
                                    + type.resultType().getSimpleName() + " (" + e.getClass().getSimpleName() + ")";
                            }
                        }
                        String target = succeeded ? type.onCompleted() : type.onFailed();
                        Object input = succeeded
                            ? new ExternalJobCompletion(jobId, job.jobType(), job.requestedBy(), job.reference(),
                                result, usage, outcome.attempts(), outcome.finishedTime(), files)
                            : new ExternalJobFailure(jobId, job.jobType(), job.requestedBy(), job.reference(), usage,
                                outcome.attempts(), outcome.finishedTime(), errorCode, errorMessage);
                        ProcessDefinition<?, ?, ?> process = processes.findLatest(target).orElseThrow(() ->
                            new IllegalStateException("Unknown process " + target));
                        ctx.put(metadata.outputKey(), new ExternalJobProcesses.CompleteOutput(jobId,
                            outcome.status(), process.name()));
                        return call(process, input);
                    })));
        });
    }

    private boolean producesFiles(String jobType) {
        return jobs.find(jobType).map(type -> type.outputFiles() != null).orElse(false);
    }

    private <I, O, C extends ProcessContext> Mono<Void> call(ProcessDefinition<I, O, C> process, Object input) {
        if (!process.inputType().isInstance(input)) {
            return Mono.error(new IllegalStateException("Process " + process.name() + " takes "
                + process.inputType().getName() + ", not " + input.getClass().getName()));
        }
        return executor.executeChild(process, process.inputType().cast(input)).then();
    }
}
