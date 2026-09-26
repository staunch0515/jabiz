package com.jabiz.runtime.job;

import com.jabiz.context.RequestContext;
import com.jabiz.job.JobDefinition;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.process.ExecutionOptions;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.ProcessResult;
import com.jabiz.runtime.process.entity.EntityIdGenerator;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import net.javacrumbs.shedlock.core.ClockProvider;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Runs one scheduled time of a job (docs/design/11-ledger-events-jobs.md section 4; decision D14): under the job's
 * cluster lock (ShedLock) one instance executes the job's process as the system actor, with the idempotency key
 * {@code job:<name>:<scheduled time>}, and records the run in {@code sys_job_run}. The lock keeps other instances from
 * running at the same time; the key keeps a scheduled time from running twice even when a lock expired or clocks
 * differ (the second run replays the first result).
 *
 * <p><strong>Blocks</strong> until the run is over, as ShedLock's R2DBC provider does: call it from a scheduler or
 * test thread, never from a non-blocking one.
 */
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    static final String RUN_TABLE = "sys_job_run";
    private static final int MAX_ERROR_LENGTH = 2000;

    /** Result of asking for a run. */
    public enum Outcome {
        /** The process ran and committed. */
        SUCCEEDED,
        /** The scheduled time had run already (the idempotency key replayed its result). */
        REPLAYED,
        /** The process failed; nothing it did was committed. */
        FAILED,
        /** Another instance held the lock; this one did nothing and recorded nothing. */
        LOCKED
    }

    private final LockingTaskExecutor locks;
    private final ProcessExecutor executor;
    private final StorageAdapterRegistry storages;
    private final EntityIdGenerator ids;
    private final Clock clock;
    private final String poolRef;
    private final String instanceId;
    private final Duration lockAtLeastFor;

    public JobRunner(LockProvider lockProvider, ProcessExecutor executor, StorageAdapterRegistry storages,
        EntityIdGenerator ids, Clock clock, String poolRef, String instanceId, Duration lockAtLeastFor) {
        this.locks = new DefaultLockingTaskExecutor(Objects.requireNonNull(lockProvider, "lockProvider"));
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.storages = Objects.requireNonNull(storages, "storages must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.poolRef = Objects.requireNonNull(poolRef, "poolRef must not be null");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
        this.lockAtLeastFor = Objects.requireNonNull(lockAtLeastFor, "lockAtLeastFor must not be null");
    }

    /** The idempotency key of one scheduled time of a job. */
    public static String idempotencyKey(String jobName, Instant scheduledTime) {
        return "job:" + jobName + ":" + scheduledTime;
    }

    /** Runs the job for its scheduled time if no other instance holds its lock; blocks until done. */
    public <I> Outcome run(JobDefinition<I> job, Instant scheduledTime) {
        // Lock times are infrastructure time: ShedLock compares them with the real clock of every instance.
        LockConfiguration lock = new LockConfiguration(ClockProvider.now(), job.name(), job.lockAtMostFor(),
            lockAtLeastFor.compareTo(job.lockAtMostFor()) < 0 ? lockAtLeastFor : job.lockAtMostFor());
        try {
            LockingTaskExecutor.TaskResult<Outcome> result =
                locks.executeWithLock(() -> runLocked(job, scheduledTime), lock);
            if (!result.wasExecuted()) {
                log.debug("Job {} at {}: another instance holds the lock", job.name(), scheduledTime);
                return Outcome.LOCKED;
            }
            return result.getResult();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("Job " + job.name() + " could not run", e);
        }
    }

    private <I> Outcome runLocked(JobDefinition<I> job, Instant scheduledTime) {
        Instant started = now();
        RequestContext system = RequestContext.system(Locale.ENGLISH,
            "job-" + job.name() + "-" + scheduledTime.toEpochMilli());
        Outcome outcome;
        Long processSeqId = null;
        String error = null;
        try {
            I input = job.input().apply(scheduledTime);
            ProcessResult<?> result = executor.run(job.process(), input,
                    ExecutionOptions.idempotent(idempotencyKey(job.name(), scheduledTime)))
                .contextWrite(view -> RequestContexts.put(view, system))
                .block();
            Objects.requireNonNull(result, "the process returned no result");
            processSeqId = result.processSeqId();
            outcome = result.replayed() ? Outcome.REPLAYED : Outcome.SUCCEEDED;
        } catch (RuntimeException e) {
            log.error("Job {} at {} failed", job.name(), scheduledTime, e);
            outcome = Outcome.FAILED;
            error = describe(e);
        }
        record(job, scheduledTime, outcome, processSeqId, error, started);
        return outcome;
    }

    private void record(JobDefinition<?> job, Instant scheduledTime, Outcome outcome, Long processSeqId,
        String error, Instant started) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("run_id", UUID.fromString(String.valueOf(ids.next(null))));
        row.put("job_name", job.name());
        row.put("scheduled_time", scheduledTime);
        row.put("instance_id", instanceId);
        row.put("outcome", outcome.name());
        row.put("process_seq_id", processSeqId);
        row.put("error", error);
        row.put("started_time", started);
        row.put("finished_time", now());
        StorageEngine engine = storages.getEngine(poolRef);
        try {
            engine.inTransaction(engine.insert(RUN_TABLE, row)).block();
        } catch (RuntimeException e) {
            // The run itself is committed (or rolled back) already; a lost record must not hide that.
            log.error("Could not record run of job {} at {} ({})", job.name(), scheduledTime, outcome, e);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static String describe(Throwable error) {
        String text = error.getClass().getName() + (error.getMessage() == null ? "" : ": " + error.getMessage());
        return text.length() > MAX_ERROR_LENGTH ? text.substring(0, MAX_ERROR_LENGTH) : text;
    }
}
