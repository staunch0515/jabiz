package com.jabiz.runtime.external;

import com.jabiz.query.BoundValue;
import com.jabiz.runtime.event.Outbox;
import com.jabiz.runtime.process.entity.EntityIdGenerator;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The rows of external jobs (decision D38 items 3–5), for the start step, the executor, the cancellation and the
 * status: every statement the platform runs on {@code sys_external_job*} is here. The tables are append-only; what
 * two instances may race for is inserted with {@code ON CONFLICT DO NOTHING} on its primary key, and the caller learns
 * whether its row went in: an attempt or an outcome that did not was decided by someone else, and the caller drops
 * its own.
 *
 * <p>Lock order: the executor's claim takes the type's advisory lock and then job rows ({@code SKIP LOCKED}, or for
 * an expired lease {@code FOR UPDATE}); recording an attempt and cancelling take the job's row only. Nobody waits for
 * the advisory lock while holding a row, so the two never deadlock.
 */
@Component
public class ExternalJobRecorder {

    /** The event that leads to a job's completion or failure process. */
    public static final String FINISHED_EVENT = "jabiz.external-job.finished";

    static final int MAX_DETAIL = 2000;

    /** A job's status as {@code GET /api/external-jobs/{id}} reports it. */
    public enum Status { QUEUED, RUNNING, WAITING_RETRY, SUCCEEDED, FAILED, CANCELLED }

    /** How an attempt ended. */
    enum AttemptOutcome { SUCCEEDED, FAILED, TIMED_OUT, LEASE_EXPIRED }

    /** A job's own row. */
    record Job(String jobId, String jobType, String input, String reference, String requestedBy, Instant createdTime) {}

    /** A job due for an attempt: its row and the number of attempts so far. */
    record Due(String jobId, String input, int attempts) {}

    /** A claim whose lease ran out without an attempt recorded. */
    record Expired(String jobId, int attemptNo) {}

    /** A job's final outcome. */
    record Outcome(String status, String result, String errorCode, String errorDetail, int attempts,
        Instant finishedTime) {}

    private final StorageAdapterRegistry storages;
    private final String poolRef;
    private final EntityIdGenerator ids;
    private final Outbox outbox;
    private final JsonMapper json;

    public ExternalJobRecorder(StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef, EntityIdGenerator ids, Outbox outbox,
        JsonMapper json) {
        this.storages = storages;
        this.poolRef = Objects.requireNonNull(poolRef, "poolRef must not be null");
        this.ids = ids;
        this.outbox = outbox;
        this.json = json;
    }

    StorageEngine engine() {
        return storages.getEngine(poolRef);
    }

    static Instant micros(Instant time) {
        return time.truncatedTo(ChronoUnit.MICROS);
    }

    // ------------------------------------------------------------------ start

    /** Inserts a job, in the transaction of the process that starts it ({@code StartExternalJob}); returns its id. */
    public Mono<String> insertJob(String jobType, String input, String reference, String requestedBy, long processSeqId,
        Instant createdTime) {
        String id = String.valueOf(ids.next(ExternalJobEntities.JOB_ENTITY));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("job_id", id);
        row.put("job_type", jobType);
        row.put("input", input);
        row.put("reference", reference);
        row.put("requested_by", requestedBy);
        row.put("process_seq_id", processSeqId);
        row.put("created_time", micros(createdTime));
        return engine().insert(ExternalJobEntities.JOB_TABLE, row).thenReturn(id);
    }

    // ------------------------------------------------------------------ claim

    /** The type's advisory lock, held to the end of the transaction: claims of one type are made one at a time. */
    Mono<Void> lockType(String jobType) {
        return engine().select("SELECT 1 AS locked FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) l",
            Map.of("key", BoundValue.of("jabiz.external-job:" + jobType))).then();
    }

    /** Attempts of the type running now: claimed, not recorded, lease not over. */
    Mono<Integer> activeClaims(String jobType, Instant now) {
        return engine().select("""
                SELECT count(*) AS n FROM sys_external_job_claim c
                JOIN sys_external_job j ON j.job_id = c.job_id
                WHERE j.job_type = :type AND c.lease_until > :now
                  AND NOT EXISTS (SELECT 1 FROM sys_external_job_attempt a
                                   WHERE a.job_id = c.job_id AND a.attempt_no = c.attempt_no)""",
                Map.of("type", BoundValue.of(jobType), "now", BoundValue.of(now)))
            .next().map(row -> Math.toIntExact(Rows.longValue(row.get("n"))));
    }

    /** Claims of the type whose lease is over without an attempt recorded, of jobs not finished. */
    Flux<Expired> expiredClaims(String jobType, Instant now) {
        return engine().select("""
                SELECT c.job_id, c.attempt_no FROM sys_external_job_claim c
                JOIN sys_external_job j ON j.job_id = c.job_id
                WHERE j.job_type = :type AND c.lease_until <= :now
                  AND NOT EXISTS (SELECT 1 FROM sys_external_job_attempt a
                                   WHERE a.job_id = c.job_id AND a.attempt_no = c.attempt_no)
                  AND NOT EXISTS (SELECT 1 FROM sys_external_job_outcome o WHERE o.job_id = c.job_id)
                ORDER BY c.job_id, c.attempt_no""",
                Map.of("type", BoundValue.of(jobType), "now", BoundValue.of(now)))
            .map(row -> new Expired(Rows.string(row.get("job_id")), Rows.intValue(row.get("attempt_no"))));
    }

    /**
     * Jobs of the type due for an attempt, oldest first, locked ({@code SKIP LOCKED}: a job a cancellation holds is
     * left for later): not finished, not cancelled, nothing running, attempts left, and a first attempt or the backoff
     * since the last one over.
     */
    Flux<Due> dueJobs(String jobType, int maxAttempts, Duration initialBackoff, Duration maxBackoff, Instant now,
        int limit) {
        Map<String, BoundValue> params = new LinkedHashMap<>();
        params.put("type", BoundValue.of(jobType));
        params.put("max", BoundValue.of((long) maxAttempts));
        params.put("initial", BoundValue.of(seconds(initialBackoff)));
        params.put("cap", BoundValue.of(seconds(maxBackoff)));
        params.put("now", BoundValue.of(now));
        params.put("limit", BoundValue.of((long) limit));
        return engine().select("""
                SELECT j.job_id, j.input,
                       (SELECT count(*) FROM sys_external_job_attempt a WHERE a.job_id = j.job_id) AS attempts
                FROM sys_external_job j
                WHERE j.job_type = :type
                  AND NOT EXISTS (SELECT 1 FROM sys_external_job_outcome o WHERE o.job_id = j.job_id)
                  AND NOT EXISTS (SELECT 1 FROM sys_external_job_cancel x WHERE x.job_id = j.job_id)
                  AND NOT EXISTS (SELECT 1 FROM sys_external_job_claim c
                                   WHERE c.job_id = j.job_id
                                     AND NOT EXISTS (SELECT 1 FROM sys_external_job_attempt a
                                                      WHERE a.job_id = c.job_id AND a.attempt_no = c.attempt_no))
                  AND (SELECT count(*) FROM sys_external_job_attempt a WHERE a.job_id = j.job_id) < :max
                  AND COALESCE((SELECT max(a.finished_time)
                                       + make_interval(secs => LEAST(:cap, :initial * power(2, count(*) - 1)))
                                FROM sys_external_job_attempt a WHERE a.job_id = j.job_id),
                               '-infinity'::timestamptz) <= :now
                ORDER BY j.job_seq
                LIMIT :limit
                FOR UPDATE OF j SKIP LOCKED""", params)
            .map(row -> new Due(Rows.string(row.get("job_id")), Rows.string(row.get("input")),
                Math.toIntExact(Rows.longValue(row.get("attempts")))));
    }

    /**
     * The oldest job of the type (with that reference, if given) not finished, not cancelled and with nothing
     * claimed but not recorded, whether due or not, locked: for results written without running the handler
     * (scenario replay).
     */
    Mono<Due> pendingJob(String jobType, String reference) {
        Map<String, BoundValue> params = new LinkedHashMap<>();
        params.put("type", BoundValue.of(jobType));
        params.put("reference", reference == null ? BoundValue.nullOf(String.class) : BoundValue.of(reference));
        return engine().select("""
                SELECT j.job_id, j.input,
                       (SELECT count(*) FROM sys_external_job_attempt a WHERE a.job_id = j.job_id) AS attempts
                FROM sys_external_job j
                WHERE j.job_type = :type
                  AND (CAST(:reference AS varchar) IS NULL OR j.reference = :reference)
                  AND NOT EXISTS (SELECT 1 FROM sys_external_job_outcome o WHERE o.job_id = j.job_id)
                  AND NOT EXISTS (SELECT 1 FROM sys_external_job_cancel x WHERE x.job_id = j.job_id)
                  AND NOT EXISTS (SELECT 1 FROM sys_external_job_claim c
                                   WHERE c.job_id = j.job_id
                                     AND NOT EXISTS (SELECT 1 FROM sys_external_job_attempt a
                                                      WHERE a.job_id = c.job_id AND a.attempt_no = c.attempt_no))
                ORDER BY j.job_seq
                LIMIT 1
                FOR UPDATE OF j""", params)
            .next().map(row -> new Due(Rows.string(row.get("job_id")), Rows.string(row.get("input")),
                Math.toIntExact(Rows.longValue(row.get("attempts")))));
    }

    private static double seconds(Duration duration) {
        return duration.toMillis() / 1000.0;
    }

    /**
     * Claims one attempt; false when another instance has it already, or the job was cancelled meanwhile (each
     * statement sees what committed before it).
     */
    Mono<Boolean> insertClaim(String jobId, int attemptNo, String instanceId, Instant now, Instant leaseUntil) {
        Map<String, BoundValue> params = new LinkedHashMap<>();
        params.put("job", BoundValue.of(jobId));
        params.put("attempt", BoundValue.of(attemptNo));
        params.put("instance", BoundValue.of(instanceId));
        params.put("now", BoundValue.of(micros(now)));
        params.put("lease", BoundValue.of(micros(leaseUntil)));
        return engine().select("""
                INSERT INTO sys_external_job_claim (job_id, attempt_no, instance_id, claimed_time, lease_until)
                SELECT :job, :attempt, :instance, :now, :lease
                WHERE NOT EXISTS (SELECT 1 FROM sys_external_job_cancel x WHERE x.job_id = :job)
                ON CONFLICT DO NOTHING
                RETURNING job_id""", params)
            .hasElements();
    }

    // ------------------------------------------------------------------ record

    /** The job's row, locked until the transaction ends: recording and cancelling one job happen one at a time. */
    Mono<Job> lockJob(String jobId) {
        return engine().select("SELECT job_id, job_type, input, reference, requested_by, created_time"
                + " FROM sys_external_job WHERE job_id = :id FOR UPDATE", Map.of("id", BoundValue.of(jobId)))
            .next().map(ExternalJobRecorder::job);
    }

    /** The job's row, unlocked. */
    Mono<Job> findJob(String jobId) {
        return engine().select("SELECT job_id, job_type, input, reference, requested_by, created_time"
                + " FROM sys_external_job WHERE job_id = :id", Map.of("id", BoundValue.of(jobId)))
            .next().map(ExternalJobRecorder::job);
    }

    private static Job job(Map<String, Object> row) {
        return new Job(Rows.string(row.get("job_id")), Rows.string(row.get("job_type")), Rows.string(row.get("input")),
            Rows.string(row.get("reference")), Rows.string(row.get("requested_by")),
            Rows.instant(row.get("created_time")));
    }

    /** Records how an attempt ended; false when it was recorded already (its lease expired, and another took over). */
    Mono<Boolean> insertAttempt(String jobId, int attemptNo, AttemptOutcome outcome, boolean retryable,
        String errorCode, String errorDetail, Long durationMs, Instant finishedTime) {
        Map<String, BoundValue> params = new LinkedHashMap<>();
        params.put("job", BoundValue.of(jobId));
        params.put("attempt", BoundValue.of(attemptNo));
        params.put("id", BoundValue.of(String.valueOf(ids.next(ExternalJobEntities.ATTEMPT_ENTITY))));
        params.put("outcome", BoundValue.of(outcome.name()));
        params.put("retryable", BoundValue.of(retryable));
        params.put("code", errorCode == null ? BoundValue.nullOf(String.class) : BoundValue.of(errorCode));
        params.put("detail", errorDetail == null ? BoundValue.nullOf(String.class)
            : BoundValue.of(cut(errorDetail, MAX_DETAIL)));
        params.put("duration", durationMs == null ? BoundValue.nullOf(Long.class) : BoundValue.of(durationMs));
        params.put("finished", BoundValue.of(micros(finishedTime)));
        return engine().select("""
                INSERT INTO sys_external_job_attempt (job_id, attempt_no, attempt_id, outcome, retryable, error_code,
                    error_detail, duration_ms, finished_time)
                VALUES (:job, :attempt, :id, :outcome, :retryable, :code, :detail, :duration, :finished)
                ON CONFLICT (job_id, attempt_no) DO NOTHING
                RETURNING job_id""", params)
            .hasElements();
    }

    /** Records what an attempt consumed, in the transaction that recorded the attempt. */
    Mono<Void> insertUsage(String jobId, int attemptNo, Map<String, Long> usage) {
        return Flux.fromIterable(usage.entrySet())
            .concatMap(entry -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("job_id", jobId);
                row.put("attempt_no", attemptNo);
                row.put("metric", entry.getKey());
                row.put("usage_id", String.valueOf(ids.next(ExternalJobEntities.USAGE_ENTITY)));
                row.put("quantity", entry.getValue());
                return engine().insert(ExternalJobEntities.USAGE_TABLE, row);
            })
            .then();
    }

    /** Whether the job was cancelled. */
    Mono<Boolean> isCancelled(String jobId) {
        return engine().select("SELECT 1 AS found FROM sys_external_job_cancel WHERE job_id = :id",
            Map.of("id", BoundValue.of(jobId))).hasElements();
    }

    /** How many attempts the job has recorded. */
    Mono<Integer> attempts(String jobId) {
        return engine().select("SELECT count(*) AS n FROM sys_external_job_attempt WHERE job_id = :id",
                Map.of("id", BoundValue.of(jobId)))
            .next().map(row -> Math.toIntExact(Rows.longValue(row.get("n"))));
    }

    /** Whether an attempt of the job is running now: claimed, not recorded, lease not over. */
    Mono<Boolean> isRunning(String jobId, Instant now) {
        return engine().select("""
                SELECT 1 AS found FROM sys_external_job_claim c
                WHERE c.job_id = :id AND c.lease_until > :now
                  AND NOT EXISTS (SELECT 1 FROM sys_external_job_attempt a
                                   WHERE a.job_id = c.job_id AND a.attempt_no = c.attempt_no)""",
            Map.of("id", BoundValue.of(jobId), "now", BoundValue.of(now))).hasElements();
    }

    /**
     * Writes the job's one final outcome and, with it, the event that leads to its completion or failure process;
     * false (and no event) when it had one already.
     */
    Mono<Boolean> finish(Job job, String status, String result, String errorCode, String errorDetail,
        Instant finishedTime) {
        return attempts(job.jobId()).flatMap(attempts -> {
            Map<String, BoundValue> params = new LinkedHashMap<>();
            params.put("job", BoundValue.of(job.jobId()));
            params.put("status", BoundValue.of(status));
            params.put("result", result == null ? BoundValue.nullOf(String.class) : BoundValue.of(result));
            params.put("code", errorCode == null ? BoundValue.nullOf(String.class) : BoundValue.of(errorCode));
            params.put("detail", errorDetail == null ? BoundValue.nullOf(String.class)
                : BoundValue.of(cut(errorDetail, MAX_DETAIL)));
            params.put("attempts", BoundValue.of(attempts));
            params.put("finished", BoundValue.of(micros(finishedTime)));
            return engine().select("""
                    INSERT INTO sys_external_job_outcome (job_id, status, result, error_code, error_detail, attempts,
                        finished_time)
                    VALUES (:job, :status, :result, :code, :detail, :attempts, :finished)
                    ON CONFLICT (job_id) DO NOTHING
                    RETURNING job_id""", params)
                .hasElements()
                .flatMap(inserted -> {
                    if (!inserted) {
                        return Mono.just(false);
                    }
                    // The status only: the result stays in the table, read by the completion process.
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("jobId", job.jobId());
                    payload.put("jobType", job.jobType());
                    payload.put("status", status);
                    return outbox.append(engine(), FINISHED_EVENT, ExternalJobEntities.JOB, job.jobId(),
                        json.writeValueAsString(payload)).thenReturn(true);
                });
        });
    }

    // ------------------------------------------------------------------ cancel

    /** Records a cancellation; the job's row must be locked. */
    Mono<Void> insertCancel(String jobId, String cancelledBy, Instant time, long processSeqId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("job_id", jobId);
        row.put("cancelled_by", cancelledBy);
        row.put("cancelled_time", micros(time));
        row.put("process_seq_id", processSeqId);
        return engine().insert(ExternalJobEntities.CANCEL_TABLE, row);
    }

    // ------------------------------------------------------------------ read

    /** The job's final outcome, if it has one. */
    Mono<Outcome> outcome(String jobId) {
        return engine().select("SELECT status, result, error_code, error_detail, attempts, finished_time"
                + " FROM sys_external_job_outcome WHERE job_id = :id", Map.of("id", BoundValue.of(jobId)))
            .next().map(row -> new Outcome(Rows.string(row.get("status")), Rows.string(row.get("result")),
                Rows.string(row.get("error_code")), Rows.string(row.get("error_detail")),
                Rows.intValue(row.get("attempts")), Rows.instant(row.get("finished_time"))));
    }

    /** The usage of all the job's attempts, by metric. */
    Mono<Map<String, Long>> usage(String jobId) {
        return engine().select("SELECT metric, sum(quantity) AS total FROM sys_external_job_usage"
                + " WHERE job_id = :id GROUP BY metric ORDER BY metric", Map.of("id", BoundValue.of(jobId)))
            .collect(LinkedHashMap<String, Long>::new,
                (map, row) -> map.put(Rows.string(row.get("metric")), Rows.longValue(row.get("total"))))
            .map(Map::copyOf);
    }

    /** What the status endpoint shows of a job, besides the job's own row. */
    record Progress(int attempts, boolean running, boolean cancelled, Instant firstClaimed, Instant lastFinished,
        String lastErrorCode) {}

    Mono<Progress> progress(String jobId, Instant now) {
        return engine().select("""
                SELECT (SELECT count(*) FROM sys_external_job_attempt a WHERE a.job_id = :id) AS attempts,
                       EXISTS (SELECT 1 FROM sys_external_job_claim c
                                WHERE c.job_id = :id AND c.lease_until > :now
                                  AND NOT EXISTS (SELECT 1 FROM sys_external_job_attempt a
                                                   WHERE a.job_id = c.job_id AND a.attempt_no = c.attempt_no))
                           AS running,
                       EXISTS (SELECT 1 FROM sys_external_job_cancel x WHERE x.job_id = :id) AS cancelled,
                       (SELECT min(c.claimed_time) FROM sys_external_job_claim c WHERE c.job_id = :id) AS first_claimed,
                       (SELECT max(a.finished_time) FROM sys_external_job_attempt a WHERE a.job_id = :id)
                           AS last_finished,
                       (SELECT a.error_code FROM sys_external_job_attempt a WHERE a.job_id = :id
                         ORDER BY a.attempt_no DESC LIMIT 1) AS last_error""",
                Map.of("id", BoundValue.of(jobId), "now", BoundValue.of(now)))
            .next().map(row -> new Progress(Math.toIntExact(Rows.longValue(row.get("attempts"))),
                Boolean.TRUE.equals(row.get("running")), Boolean.TRUE.equals(row.get("cancelled")),
                Rows.instant(row.get("first_claimed")), Rows.instant(row.get("last_finished")),
                Rows.string(row.get("last_error"))));
    }

    static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
