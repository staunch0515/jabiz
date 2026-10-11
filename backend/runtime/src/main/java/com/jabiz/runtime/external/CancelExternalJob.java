package com.jabiz.runtime.external;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Objects;

/**
 * The step of {@code EXTERNAL_JOB_CANCEL} (decision D38 item 5). The job's row is locked first, as the executor locks
 * it to record an attempt, so the two happen one after the other. Only the requester, or a holder of
 * {@code external-job.cancel}, may cancel; anyone else is told the job does not exist (404), as for a job that does
 * not. A finished job, or one cancelled already, is 422 {@code EXTERNAL_JOB_FINISHED}. Otherwise the cancellation is
 * recorded; a job with no attempt running gets its outcome {@code CANCELLED} at once (with the event, which calls no
 * process), a running one when its attempt ends, its result dropped.
 */
@Component
class CancelExternalJob implements StepHandler<CancelExternalJob.Metadata, ProcessContext> {

    record Metadata(String inputKey, String outputKey) {
        Metadata {
            Objects.requireNonNull(inputKey, "inputKey must not be null");
            Objects.requireNonNull(outputKey, "outputKey must not be null");
        }
    }

    static StepSpec<Metadata, ProcessContext> of(String inputKey, String outputKey) {
        return StepSpec.of(CancelExternalJob.class, new Metadata(inputKey, outputKey));
    }

    private final ExternalJobRecorder recorder;

    CancelExternalJob(ExternalJobRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public Mono<Void> execute(Metadata metadata, ProcessContext ctx) {
        return Mono.defer(() -> {
            String jobId = ctx.get(metadata.inputKey(), ExternalJobProcesses.CancelInput.class).jobId();
            RequestContext request = ctx.request();
            return recorder.lockJob(jobId)
                .filter(job -> job.requestedBy().equals(request.actorId())
                    || request.hasPermission(ExternalJobPermissions.CANCEL))
                .switchIfEmpty(Mono.error(() -> new EntityNotFoundException("External job not found")))
                // One statement after the other: they share the transaction's connection.
                .flatMap(job -> recorder.outcome(jobId).hasElement()
                    .flatMap(finished -> finished ? Mono.just(true) : recorder.isCancelled(jobId))
                    .flatMap(over -> {
                        if (over) {
                            return Mono.error(new BusinessRuleViolationException(new Violation("jobId",
                                PlatformErrorCodes.EXTERNAL_JOB_FINISHED, "The external job has finished already",
                                Map.of())));
                        }
                        return recorder.insertCancel(jobId, request.actorId(), ctx.opTime(), ctx.processSeqId())
                            .then(recorder.isRunning(jobId, ctx.opTime()))
                            .flatMap(running -> running ? Mono.just(false)
                                : recorder.finish(job, "CANCELLED", null, null, null, ctx.opTime()));
                    }))
                .doOnNext(finished -> ctx.put(metadata.outputKey(),
                    new ExternalJobProcesses.CancelOutput(jobId, "CANCELLED")))
                .then();
        });
    }
}
