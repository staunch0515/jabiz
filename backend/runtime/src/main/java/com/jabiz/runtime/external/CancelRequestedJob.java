package com.jabiz.runtime.external;

import com.jabiz.context.RequestContext;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Objects;

/**
 * The step of {@code EXTERNAL_JOB_CANCEL} (decision D38 item 5), behind {@code POST /api/external-jobs/{id}/cancel}.
 * Only the requester, or a holder of {@code external-job.cancel}, may cancel; anyone else is told the job does not
 * exist (404), as for a job that does not. The cancellation itself is {@link ExternalJobRecorder#cancel}, shared with
 * the platform step {@code CancelExternalJob} that business processes use (decision D52 item 2): a finished job is
 * 422 {@code EXTERNAL_JOB_FINISHED}; the type's {@code onCancelled} runs once the job has ended.
 */
@Component
class CancelRequestedJob implements StepHandler<CancelRequestedJob.Metadata, ProcessContext> {

    record Metadata(String inputKey, String outputKey) {
        Metadata {
            Objects.requireNonNull(inputKey, "inputKey must not be null");
            Objects.requireNonNull(outputKey, "outputKey must not be null");
        }
    }

    static StepSpec<Metadata, ProcessContext> of(String inputKey, String outputKey) {
        return StepSpec.of(CancelRequestedJob.class, new Metadata(inputKey, outputKey));
    }

    private final ExternalJobRecorder recorder;

    CancelRequestedJob(ExternalJobRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public Mono<Void> execute(Metadata metadata, ProcessContext ctx) {
        return Mono.defer(() -> {
            String jobId = ctx.get(metadata.inputKey(), ExternalJobProcesses.CancelInput.class).jobId();
            RequestContext request = ctx.request();
            return recorder.cancel(jobId, job -> job.requestedBy().equals(request.actorId())
                        || request.hasPermission(ExternalJobPermissions.CANCEL), false, request.actorId(),
                    ctx.opTime(), ctx.processSeqId())
                .doOnNext(cancelled -> ctx.put(metadata.outputKey(),
                    new ExternalJobProcesses.CancelOutput(jobId, "CANCELLED")))
                .then();
        });
    }
}
