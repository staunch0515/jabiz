package com.jabiz.runtime.job;

import com.jabiz.job.JobDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.process.ExecutionOptions;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.ProcessInputs;
import com.jabiz.runtime.process.ProcessResult;
import com.jabiz.runtime.security.MfaPolicy;
import com.jabiz.runtime.sod.SodGuard;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs by hand of a past scheduled time (decision D77; docs/design/11-ledger-events-jobs.md section 4.2). The job's
 * cluster lock is taken first, as a scheduled run takes it (held: 409 {@code JOB_BUSY}); then {@code JOB_RUN_REQUEST}
 * checks the time and records the request with its reason; once it has committed, the run starts on a thread of its
 * own under that lock ({@link JobRunner#executeRequested}) and the request is answered at once. Any failure before
 * the run starts releases the lock. A request replayed by its idempotency key starts nothing again.
 */
@Component
public class JobRunRequests {

    private static final Logger log = LoggerFactory.getLogger(JobRunRequests.class);

    /**
     * @param requestSeqId  the operation of the request; the run's record carries it once the run is over
     * @param replayed      whether the request was made before with the same idempotency key (nothing new started)
     */
    public record JobRunAccepted(long requestSeqId, String job, Instant scheduledTime, boolean replayed) {}

    private final JobRegistry jobs;
    private final JobRunner runner;
    private final ProcessExecutor executor;
    private final ProcessInputs inputs;
    private final MfaPolicy mfa;
    private final SodGuard sod;
    private final ProcessDefinition<JobRunProcesses.RunRequestInput, JobRunProcesses.RunRequestOutput,
        ProcessContext> process;
    private final Duration shutdownGrace;
    private final ExecutorService runs = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("jabiz-job-manual-", 0).factory());

    public JobRunRequests(JobRegistry jobs, JobRunner runner, ProcessExecutor executor, ProcessInputs inputs,
        MfaPolicy mfa, SodGuard sod,
        ProcessDefinition<JobRunProcesses.RunRequestInput, JobRunProcesses.RunRequestOutput, ProcessContext> process,
        @Value("${jabiz.jobs.scheduler.shutdown-grace:30s}") Duration shutdownGrace) {
        this.jobs = jobs;
        this.runner = runner;
        this.executor = executor;
        this.inputs = inputs;
        this.mfa = mfa;
        this.sod = sod;
        this.process = process;
        this.shutdownGrace = shutdownGrace;
    }

    /**
     * Asks for {@code scheduledTime} of {@code jobName} to run again. Errors: 403 without {@value JobPermissions#RUN}
     * (or {@code SOD_CONFLICT}), 400 without a reason or time, 403 {@code MFA_REQUIRED} without a recent second
     * factor, 404 for an unknown job, 409 {@code JOB_BUSY}, 422 for a time that may not run again.
     */
    public Mono<JobRunAccepted> request(String jobName, Instant scheduledTime, String reason, String idempotencyKey) {
        return RequestContexts.current().flatMap(request -> {
            // What the process API checks at its entry, before the lock is taken: nobody else can keep a job from
            // running, and a step-up leaves the lock alone.
            if (!request.hasPermission(JobPermissions.RUN)) {
                return Mono.error(new PermissionDeniedException(JobPermissions.RUN,
                    "Running a job by hand needs permission " + JobPermissions.RUN));
            }
            JobRunProcesses.RunRequestInput input = new JobRunProcesses.RunRequestInput(jobName, scheduledTime, reason);
            // 400 before anything else: the process API checks a body so, which this entry stands in for.
            inputs.validate(input);
            mfa.require(request, process.mfa(), "Running a job by hand");
            JobDefinition<?> job = jobs.find(jobName).orElse(null);
            if (job == null) {
                return Mono.error(new EntityNotFoundException("Job " + jobName + " not found"));
            }
            Mono<SimpleLock> locked = Mono.fromCallable(() -> runner.tryLock(job)
                    .orElseThrow(() -> new JobBusyException(job.name())))
                .subscribeOn(Schedulers.boundedElastic());
            return sod.check(request, process.permissions()).then(locked)
                .flatMap(lock -> {
                    // Released here unless the run took it over (or a request replayed): on an error, and when the
                    // caller went away before the run started.
                    AtomicBoolean handedOver = new AtomicBoolean();
                    return executor.run(process, input,
                            new ExecutionOptions(idempotencyKey).atEntryOf(process))
                        .flatMap(result -> {
                            handedOver.set(true);
                            return start(job, scheduledTime, request.actorId(), result, lock);
                        })
                        .onErrorResume(error -> handedOver.get() ? Mono.error(error)
                            : release(lock).then(Mono.error(error)))
                        .doOnCancel(() -> {
                            if (handedOver.compareAndSet(false, true)) {
                                release(lock).subscribe();
                            }
                        });
                });
        });
    }

    private Mono<JobRunAccepted> start(JobDefinition<?> job, Instant scheduledTime, String actorId,
        ProcessResult<?> result, SimpleLock lock) {
        JobRunAccepted accepted = new JobRunAccepted(result.processSeqId(), job.name(), scheduledTime,
            result.replayed());
        if (result.replayed()) {
            return release(lock).thenReturn(accepted);
        }
        JobRunner.Request request = new JobRunner.Request(actorId, result.processSeqId());
        try {
            runs.execute(() -> {
                try {
                    JobRunner.JobRun run = runner.executeRequested(job, scheduledTime, request, lock);
                    log.info("Job {} at {} run by hand (operation {}): {}", job.name(), scheduledTime,
                        request.requestSeqId(), run.outcome());
                } catch (RuntimeException e) {
                    log.error("Job {} at {} could not run by hand (operation {})", job.name(), scheduledTime,
                        request.requestSeqId(), e);
                }
            });
        } catch (RejectedExecutionException e) {
            // Stopping: the request is recorded, the run did not start.
            log.warn("Job {} at {} was not run by hand (operation {}): the application is stopping", job.name(),
                scheduledTime, request.requestSeqId());
            return release(lock).then(Mono.error(e));
        }
        return Mono.just(accepted);
    }

    private static Mono<Void> release(SimpleLock lock) {
        return Mono.<Void>fromRunnable(lock::unlock).subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(error -> {
                log.error("Could not release a job's lock", error);
                return Mono.empty();
            });
    }

    /** Stops taking runs and waits for those under way up to {@code jabiz.jobs.scheduler.shutdown-grace}. */
    @jakarta.annotation.PreDestroy
    public void stop() throws InterruptedException {
        runner.stop();
        runs.shutdown();
        if (!runs.awaitTermination(shutdownGrace.toMillis(), TimeUnit.MILLISECONDS)) {
            runs.shutdownNow();
        }
    }
}
