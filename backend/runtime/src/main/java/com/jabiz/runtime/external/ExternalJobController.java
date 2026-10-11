package com.jabiz.runtime.external;

import com.jabiz.context.RequestContext;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.process.ExecutionOptions;
import com.jabiz.runtime.process.ProcessExecutor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * An external job's status and its cancellation (decision D38 item 5; docs/design/11-ledger-events-jobs.md section
 * 4a), for its requester, or for holders of {@code external-job.read} (status) and {@code external-job.cancel}
 * (cancellation). Anyone else is answered 404, as for a job that does not exist. Neither the input nor the result is
 * shown (Q14): the result reaches the application through its completion process.
 */
@RestController
@RequestMapping("/api/external-jobs")
class ExternalJobController {

    /**
     * One job as its requester follows it.
     *
     * @param status       {@code QUEUED}, {@code RUNNING}, {@code WAITING_RETRY}, {@code SUCCEEDED}, {@code FAILED} or
     *                     {@code CANCELLED} (also while a cancelled attempt is still running: its result is dropped)
     * @param attempts     attempts made so far
     * @param startedTime  when the first attempt was taken; null before
     * @param finishedTime when the job got its outcome; null before
     * @param errorCode    the last failed attempt's code, or the failed job's; null otherwise
     * @param usage        what all attempts consumed, by metric
     */
    record ExternalJobStatus(String jobId, String jobType, String reference, String requestedBy, String status,
        int attempts, Instant createdTime, Instant startedTime, Instant finishedTime, String errorCode,
        Map<String, Long> usage) {}

    private final ExternalJobRecorder recorder;
    private final ProcessExecutor executor;
    private final Clock clock;

    ExternalJobController(ExternalJobRecorder recorder, ProcessExecutor executor, Clock clock) {
        this.recorder = recorder;
        this.executor = executor;
        this.clock = clock;
    }

    @GetMapping("/{jobId}")
    Mono<ResponseEntity<ExternalJobStatus>> externalJobStatus(@PathVariable String jobId) {
        return RequestContexts.current().flatMap(request -> status(jobId, request, ExternalJobPermissions.READ))
            .map(status -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(status));
    }

    /**
     * Runs {@code EXTERNAL_JOB_CANCEL}, which checks who may cancel (its own permission is for the process API), and
     * answers the job's status after it.
     */
    @PostMapping("/{jobId}/cancel")
    Mono<ResponseEntity<ExternalJobStatus>> cancelExternalJob(@PathVariable String jobId,
        @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return RequestContexts.current().flatMap(request -> executor.run(ExternalJobProcesses.CANCEL_PROCESS,
                new ExternalJobProcesses.CancelInput(jobId), new ExecutionOptions(idempotencyKey))
            .then(status(jobId, request, ExternalJobPermissions.CANCEL)))
            .map(status -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(status));
    }

    private Mono<ExternalJobStatus> status(String jobId, RequestContext request, String permission) {
        return recorder.findJob(jobId)
            .filter(job -> job.requestedBy().equals(request.actorId()) || request.hasPermission(permission))
            .switchIfEmpty(Mono.error(() -> new EntityNotFoundException("External job not found")))
            .flatMap(job -> recorder.progress(jobId, clock.instant())
                .flatMap(progress -> recorder.outcome(jobId)
                    .map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty())
                    .flatMap(outcome -> recorder.usage(jobId).map(usage -> {
                        String status;
                        if (outcome.isPresent()) {
                            status = outcome.get().status();
                        } else if (progress.cancelled()) {
                            status = ExternalJobRecorder.Status.CANCELLED.name();
                        } else if (progress.running()) {
                            status = ExternalJobRecorder.Status.RUNNING.name();
                        } else if (progress.firstClaimed() == null) {
                            // Never taken; a claim whose lease ran out waits to be taken again.
                            status = ExternalJobRecorder.Status.QUEUED.name();
                        } else {
                            status = ExternalJobRecorder.Status.WAITING_RETRY.name();
                        }
                        String errorCode = outcome.map(ExternalJobRecorder.Outcome::errorCode)
                            .orElse(progress.lastErrorCode());
                        return new ExternalJobStatus(job.jobId(), job.jobType(), job.reference(), job.requestedBy(),
                            status, progress.attempts(), job.createdTime(), progress.firstClaimed(),
                            outcome.map(ExternalJobRecorder.Outcome::finishedTime).orElse(null),
                            "SUCCEEDED".equals(status) ? null : errorCode, usage);
                    }))));
    }
}
