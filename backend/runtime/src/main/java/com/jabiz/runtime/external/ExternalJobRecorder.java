package com.jabiz.runtime.external;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.event.Outbox;
import com.jabiz.runtime.process.entity.EntityIdGenerator;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;

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
    record Due(String jobId, String input, int attempts, String requestedBy) {}

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

    // ------------------------------------------------------------------ files (decision D50)

    /** A stored file as a job reads or produced it. */
    public record StoredFile(UUID fileId, String policy, String contentType, long sizeBytes, String sha256) {}

    /**
     * The stored file {@code fileId}, its row locked {@code FOR KEY SHARE} until the transaction ends (as a field's
     * write check reads it), so that it is not deleted while the job that reads it is being started; empty when
     * there is none.
     */
    public Mono<StoredFile> lockStoredFile(UUID fileId) {
        return engine().select("SELECT file_id, policy, content_type, size_bytes, sha256 FROM sys_file"
                + " WHERE file_id = :id FOR KEY SHARE", Map.of("id", BoundValue.of(fileId)))
            .next().map(row -> new StoredFile(Rows.uuid(row.get("file_id")), Rows.string(row.get("policy")),
                Rows.string(row.get("content_type")), Rows.longValue(row.get("size_bytes")),
                Rows.string(row.get("sha256"))));
    }

    /**
     * Records a file the job reads, in the transaction that starts it.
     *
     * @param privateMetadata whether the file's policy keeps its metadata private ({@link #fileRow})
     */
    public Mono<Void> insertInputFile(String jobId, int position, StoredFile file, Instant time,
        boolean privateMetadata) {
        return rowId(privateMetadata).flatMap(id -> engine().insert(ExternalJobEntities.FILE_TABLE, fileRow(id, jobId,
            "INPUT", position, null, null, file, time, privateMetadata)));
    }

    /** Records a file an attempt produced, in the transaction that records the attempt. */
    Mono<Void> insertOutputFile(String jobId, int attemptNo, int position, String key, StoredFile file, Instant time,
        boolean privateMetadata) {
        return rowId(privateMetadata).flatMap(id -> engine().insert(ExternalJobEntities.FILE_TABLE, fileRow(id, jobId,
            "OUTPUT", position, attemptNo, key, file, time, privateMetadata)));
    }

    /**
     * The id of a job's file record: a UUIDv7 as usual, or for a file with private metadata a random UUIDv4, drawn
     * on {@code boundedElastic} since {@code SecureRandom} may read the system's entropy.
     */
    private Mono<String> rowId(boolean privateMetadata) {
        return privateMetadata
            ? Mono.fromCallable(() -> UUID.randomUUID().toString()).subscribeOn(Schedulers.boundedElastic())
            : Mono.fromSupplier(() -> String.valueOf(ids.next(ExternalJobEntities.FILE_ENTITY)));
    }

    /**
     * The row of a job's file. For a file whose policy keeps its metadata private (decisions D47, D48) the row tells
     * no more than {@code /api/files} does: a random id instead of a UUIDv7 (which carries the moment), and the day
     * only of when it was recorded.
     */
    private static Map<String, Object> fileRow(String id, String jobId, String direction, int position,
        Integer attemptNo, String key, StoredFile file, Instant time, boolean privateMetadata) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("job_file_id", id);
        if (privateMetadata) {
            time = time.truncatedTo(ChronoUnit.DAYS);
        }
        row.put("job_id", jobId);
        row.put("direction", direction);
        row.put("position", position);
        row.put("attempt_no", attemptNo);
        row.put("output_key", key);
        row.put("file_id", file.fileId().toString());
        row.put("policy", file.policy());
        row.put("size_bytes", file.sizeBytes());
        row.put("sha256", file.sha256());
        row.put("recorded_time", micros(time));
        return row;
    }

    /**
     * A file the job reads, as recorded when it started, with what {@code sys_file} has of it now: {@code current}
     * is null once the file is gone.
     */
    record InputFile(UUID fileId, long sizeBytes, String sha256, StoredFile current) {}

    /** The files the job reads, in their order. */
    Flux<InputFile> inputFiles(String jobId) {
        return engine().select("""
                SELECT jf.file_id, jf.size_bytes, jf.sha256, f.file_id AS current_id, f.policy AS current_policy,
                       f.content_type AS current_type, f.size_bytes AS current_size, f.sha256 AS current_sha256
                FROM sys_external_job_file jf
                LEFT JOIN sys_file f ON f.file_id = CAST(jf.file_id AS uuid)
                WHERE jf.job_id = :id AND jf.direction = 'INPUT'
                ORDER BY jf.position""", Map.of("id", BoundValue.of(jobId)))
            .map(row -> new InputFile(UUID.fromString(Rows.string(row.get("file_id"))),
                Rows.longValue(row.get("size_bytes")), Rows.string(row.get("sha256")),
                row.get("current_id") == null ? null : new StoredFile(Rows.uuid(row.get("current_id")),
                    Rows.string(row.get("current_policy")), Rows.string(row.get("current_type")),
                    Rows.longValue(row.get("current_size")), Rows.string(row.get("current_sha256")))));
    }

    /**
     * The files the job's successful attempt produced, by their key, in their order; empty when none did. A job has
     * at most one successful attempt: once it is recorded the job has its outcome.
     */
    Mono<Map<String, UUID>> outputFiles(String jobId) {
        return engine().select("""
                SELECT jf.output_key, jf.file_id FROM sys_external_job_file jf
                JOIN sys_external_job_attempt a ON a.job_id = jf.job_id AND a.attempt_no = jf.attempt_no
                WHERE jf.job_id = :id AND jf.direction = 'OUTPUT' AND a.outcome = 'SUCCEEDED'
                ORDER BY jf.position""", Map.of("id", BoundValue.of(jobId)))
            .collect(LinkedHashMap<String, UUID>::new, (map, row) -> map.put(Rows.string(row.get("output_key")),
                UUID.fromString(Rows.string(row.get("file_id")))))
            .map(map -> (Map<String, UUID>) map);
    }

    // ------------------------------------------------------------------ start limits

    /**
     * A transaction-scoped advisory lock on starting jobs of one type for one requester or one reference: starts that
     * count the same open jobs run one after another, so two cannot both pass a limit.
     */
    public Mono<Void> lockStarts(String jobType, String scope, String value) {
        return engine().select("SELECT 1 AS locked FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) l",
            Map.of("key", BoundValue.of("jabiz.external-job-start:" + jobType + ":" + scope + ":" + value))).then();
    }

    /** Open jobs (neither finished nor cancelled) of the type started by {@code requester}. */
    public Mono<Integer> openJobsOfRequester(String jobType, String requester) {
        return countOpen("j.requested_by = :value", jobType, requester);
    }

    /** Open jobs (neither finished nor cancelled) of the type about {@code reference}. */
    public Mono<Integer> openJobsOfReference(String jobType, String reference) {
        return countOpen("j.reference = :value", jobType, reference);
    }

    private Mono<Integer> countOpen(String condition, String jobType, String value) {
        return engine().select("SELECT count(*) AS n FROM sys_external_job j WHERE j.job_type = :type AND " + condition
                + " AND NOT EXISTS (SELECT 1 FROM sys_external_job_outcome o WHERE o.job_id = j.job_id)"
                + " AND NOT EXISTS (SELECT 1 FROM sys_external_job_cancel x WHERE x.job_id = j.job_id)",
                Map.of("type", BoundValue.of(jobType), "value", BoundValue.of(value)))
            .next().map(row -> Math.toIntExact(Rows.longValue(row.get("n"))));
    }

    // ------------------------------------------------------------------ claim

    /** The type's advisory lock, held to the end of the transaction: claims of one type are made one at a time. */
    Mono<Void> lockType(String jobType) {
        return engine().select("SELECT 1 AS locked FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) l",
            Map.of("key", BoundValue.of("jabiz.external-job:" + jobType))).then();
    }

    /**
     * The type's watermark: the lowest {@code job_seq} of a job without an outcome, or one past the highest of any job
     * of the type. Jobs below it all had their outcome when it was taken, which they keep; the queries of a claim look
     * at jobs from it on, and a sweep (every {@code watermark-refresh}) at all of them, for a job of a transaction
     * that committed late with a lower number.
     */
    Mono<Long> watermark(String jobType) {
        return engine().select("""
                SELECT COALESCE(
                    (SELECT min(j.job_seq) FROM sys_external_job j WHERE j.job_type = :type
                      AND NOT EXISTS (SELECT 1 FROM sys_external_job_outcome o WHERE o.job_id = j.job_id)),
                    (SELECT max(j.job_seq) + 1 FROM sys_external_job j WHERE j.job_type = :type),
                    0) AS mark""", Map.of("type", BoundValue.of(jobType)))
            .next().map(row -> Rows.longValue(row.get("mark")));
    }

    /** Attempts of the type running now: claimed, not recorded, lease not over. */
    Mono<Integer> activeClaims(String jobType, Instant now, long mark) {
        return engine().select("""
                SELECT count(*) AS n FROM sys_external_job_claim c
                JOIN sys_external_job j ON j.job_id = c.job_id
                WHERE j.job_type = :type AND j.job_seq >= :mark AND c.lease_until > :now
                  AND NOT EXISTS (SELECT 1 FROM sys_external_job_attempt a
                                   WHERE a.job_id = c.job_id AND a.attempt_no = c.attempt_no)""",
                Map.of("type", BoundValue.of(jobType), "now", BoundValue.of(now), "mark", BoundValue.of(mark)))
            .next().map(row -> Math.toIntExact(Rows.longValue(row.get("n"))));
    }

    /** Claims of the type whose lease is over without an attempt recorded, of jobs not finished. */
    Flux<Expired> expiredClaims(String jobType, Instant now, long mark) {
        return engine().select("""
                SELECT c.job_id, c.attempt_no FROM sys_external_job_claim c
                JOIN sys_external_job j ON j.job_id = c.job_id
                WHERE j.job_type = :type AND j.job_seq >= :mark AND c.lease_until <= :now
                  AND NOT EXISTS (SELECT 1 FROM sys_external_job_attempt a
                                   WHERE a.job_id = c.job_id AND a.attempt_no = c.attempt_no)
                  AND NOT EXISTS (SELECT 1 FROM sys_external_job_outcome o WHERE o.job_id = c.job_id)
                ORDER BY c.job_id, c.attempt_no""",
                Map.of("type", BoundValue.of(jobType), "now", BoundValue.of(now), "mark", BoundValue.of(mark)))
            .map(row -> new Expired(Rows.string(row.get("job_id")), Rows.intValue(row.get("attempt_no"))));
    }

    /**
     * Jobs of the type without an outcome and nothing claimed but unrecorded that can have no further attempt: their
     * attempts are used up (the type may allow fewer than it did), or they were cancelled. Locked, {@code SKIP LOCKED}.
     */
    Flux<Settle> unsettledJobs(String jobType, int maxAttempts, long mark) {
        return engine().select("""
                SELECT j.job_id, j.job_type, j.input, j.reference, j.requested_by, j.created_time,
                       EXISTS (SELECT 1 FROM sys_external_job_cancel x WHERE x.job_id = j.job_id) AS cancelled
                FROM sys_external_job j
                WHERE j.job_type = :type AND j.job_seq >= :mark
                  AND NOT EXISTS (SELECT 1 FROM sys_external_job_outcome o WHERE o.job_id = j.job_id)
                  AND NOT EXISTS (SELECT 1 FROM sys_external_job_claim c
                                   WHERE c.job_id = j.job_id
                                     AND NOT EXISTS (SELECT 1 FROM sys_external_job_attempt a
                                                      WHERE a.job_id = c.job_id AND a.attempt_no = c.attempt_no))
                  AND (EXISTS (SELECT 1 FROM sys_external_job_cancel x WHERE x.job_id = j.job_id)
                       OR (SELECT count(*) FROM sys_external_job_attempt a WHERE a.job_id = j.job_id) >= :max)
                ORDER BY j.job_seq
                FOR UPDATE OF j SKIP LOCKED""",
                Map.of("type", BoundValue.of(jobType), "max", BoundValue.of((long) maxAttempts),
                    "mark", BoundValue.of(mark)))
            .map(row -> new Settle(job(row), Boolean.TRUE.equals(row.get("cancelled"))));
    }

    /** A job that can have no further attempt, and whether because it was cancelled. */
    record Settle(Job job, boolean cancelled) {}

    /** Open jobs of types no longer declared, locked ({@code SKIP LOCKED}). */
    Flux<Job> jobsOfOtherTypes(List<String> declared) {
        return engine().select("""
                SELECT j.job_id, j.job_type, j.input, j.reference, j.requested_by, j.created_time
                FROM sys_external_job j
                WHERE NOT (j.job_type = ANY(:types))
                  AND NOT EXISTS (SELECT 1 FROM sys_external_job_outcome o WHERE o.job_id = j.job_id)
                ORDER BY j.job_seq
                LIMIT 1000
                FOR UPDATE OF j SKIP LOCKED""",
                Map.of("types", BoundValue.of(declared.toArray(String[]::new))))
            .map(ExternalJobRecorder::job);
    }

    /**
     * Jobs of the type due for an attempt, oldest first, locked ({@code SKIP LOCKED}: a job a cancellation holds is
     * left for later): not finished, not cancelled, nothing running, attempts left, and a first attempt or the wait
     * after the last one over. {@code waits[n - 1]} is the wait after {@code n} failed attempts, in milliseconds,
     * computed by {@link com.jabiz.external.ExternalJobType#backoffAfter}.
     */
    Flux<Due> dueJobs(String jobType, int maxAttempts, Long[] waits, Instant now, int limit, long mark) {
        Map<String, BoundValue> params = new LinkedHashMap<>();
        params.put("type", BoundValue.of(jobType));
        params.put("max", BoundValue.of((long) maxAttempts));
        params.put("waits", BoundValue.of(waits));
        params.put("now", BoundValue.of(now));
        params.put("limit", BoundValue.of((long) limit));
        params.put("mark", BoundValue.of(mark));
        return engine().select("""
                SELECT j.job_id, j.input, j.requested_by, s.attempts
                FROM sys_external_job j
                CROSS JOIN LATERAL (SELECT count(*) AS attempts, max(a.finished_time) AS last_finished
                                    FROM sys_external_job_attempt a WHERE a.job_id = j.job_id) s
                WHERE j.job_type = :type AND j.job_seq >= :mark
                  AND NOT EXISTS (SELECT 1 FROM sys_external_job_outcome o WHERE o.job_id = j.job_id)
                  AND NOT EXISTS (SELECT 1 FROM sys_external_job_cancel x WHERE x.job_id = j.job_id)
                  AND NOT EXISTS (SELECT 1 FROM sys_external_job_claim c
                                   WHERE c.job_id = j.job_id
                                     AND NOT EXISTS (SELECT 1 FROM sys_external_job_attempt a
                                                      WHERE a.job_id = c.job_id AND a.attempt_no = c.attempt_no))
                  AND s.attempts < :max
                  AND (s.attempts = 0 OR s.last_finished
                       + make_interval(secs => (CAST(:waits AS bigint[]))[s.attempts::int] / 1000.0) <= :now)
                ORDER BY j.job_seq
                LIMIT :limit
                FOR UPDATE OF j SKIP LOCKED""", params)
            .map(ExternalJobRecorder::due);
    }

    private static Due due(Map<String, Object> row) {
        return new Due(Rows.string(row.get("job_id")), Rows.string(row.get("input")),
            Math.toIntExact(Rows.longValue(row.get("attempts"))), Rows.string(row.get("requested_by")));
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
                SELECT j.job_id, j.input, j.requested_by,
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
            .next().map(ExternalJobRecorder::due);
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

    /**
     * The job's row, locked until the transaction ends, or empty when another transaction holds it ({@code SKIP
     * LOCKED}): for the claim's transaction, which holds the type's lock and must not wait for a business process
     * that cancels inside a long transaction. What it skips is taken at the next poll.
     */
    Mono<Job> lockJobIfFree(String jobId) {
        return engine().select("SELECT job_id, job_type, input, reference, requested_by, created_time"
                + " FROM sys_external_job WHERE job_id = :id FOR UPDATE SKIP LOCKED",
                Map.of("id", BoundValue.of(jobId)))
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

    /** How an attempt of the job is recorded; empty when it is not. */
    Mono<AttemptOutcome> recordedOutcome(String jobId, int attemptNo) {
        return engine().select("SELECT outcome FROM sys_external_job_attempt"
                + " WHERE job_id = :job AND attempt_no = :attempt",
                Map.of("job", BoundValue.of(jobId), "attempt", BoundValue.of(attemptNo)))
            .next().map(row -> AttemptOutcome.valueOf(Rows.string(row.get("outcome"))));
    }

    /**
     * Records what a late attempt consumed (decision D52 item 5): an attempt that ended after its lease had expired,
     * whose number is recorded already as {@code LEASE_EXPIRED}. Its result is dropped, but the provider bills what it
     * consumed, so its usage is recorded against that attempt, once: only when the recorded attempt is
     * {@code LEASE_EXPIRED} and has no usage rows yet, each row {@code ON CONFLICT DO NOTHING}. The job's row must be
     * locked (as the executor locks it to record an attempt), so that nothing else records this attempt's usage
     * meanwhile. An attempt recorded otherwise (this instance's own record, committed before a retry) is left as it is.
     *
     * @return what was recorded now, by metric; empty when nothing was
     */
    Mono<Map<String, Long>> insertLateUsage(String jobId, int attemptNo, Map<String, Long> usage) {
        if (usage.isEmpty()) {
            return Mono.just(Map.of());
        }
        return engine().select("""
                SELECT a.outcome,
                       EXISTS (SELECT 1 FROM sys_external_job_usage u
                                WHERE u.job_id = a.job_id AND u.attempt_no = a.attempt_no) AS has_usage
                FROM sys_external_job_attempt a
                WHERE a.job_id = :job AND a.attempt_no = :attempt""",
                Map.of("job", BoundValue.of(jobId), "attempt", BoundValue.of(attemptNo)))
            .next()
            .filter(row -> AttemptOutcome.LEASE_EXPIRED.name().equals(Rows.string(row.get("outcome")))
                && !Boolean.TRUE.equals(row.get("has_usage")))
            .flatMap(late -> Flux.fromIterable(usage.entrySet())
                .concatMap(entry -> {
                    Map<String, BoundValue> params = new LinkedHashMap<>();
                    params.put("job", BoundValue.of(jobId));
                    params.put("attempt", BoundValue.of(attemptNo));
                    params.put("metric", BoundValue.of(entry.getKey()));
                    params.put("id", BoundValue.of(String.valueOf(ids.next(ExternalJobEntities.USAGE_ENTITY))));
                    params.put("quantity", BoundValue.of(entry.getValue()));
                    return engine().select("""
                            INSERT INTO sys_external_job_usage (job_id, attempt_no, metric, usage_id, quantity)
                            VALUES (:job, :attempt, :metric, :id, :quantity)
                            ON CONFLICT (job_id, attempt_no, metric) DO NOTHING
                            RETURNING metric""", params)
                        .hasElements()
                        .filter(Boolean::booleanValue)
                        .map(inserted -> entry);
                })
                .collect(LinkedHashMap<String, Long>::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()))
                .map(Map::copyOf))
            .defaultIfEmpty(Map.of());
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

    /** Of a job, what decides who may cancel it: its type and its requester. */
    public record JobOwner(String jobId, String jobType, String requestedBy) {}

    /**
     * Cancels a job in the caller's transaction (decision D38 item 5, decision D52 item 2): the endpoint's
     * {@code EXTERNAL_JOB_CANCEL} and the platform step {@code CancelExternalJob} both come here.
     *
     * <p>Whether the caller may cancel is decided on the unlocked row first, so that someone who may not never takes
     * (or waits for) its lock; a job that does not exist and one {@code mayCancel} refuses are the same 404. Then the
     * row is locked, as the executor locks it to record an attempt, so the two happen one after the other. A job that
     * has its outcome, or was cancelled before, is 422 {@code EXTERNAL_JOB_FINISHED}, or nothing when
     * {@code ifOpen}. Otherwise the cancellation is recorded; a job with no attempt running gets its outcome
     * {@code CANCELLED} at once (with the event that leads to the type's {@code onCancelled}), a running one when its
     * attempt ends, its result dropped. A rollback of the transaction leaves the job open.
     *
     * @return true when the job was cancelled now; false when {@code ifOpen} and it was over already
     */
    public Mono<Boolean> cancel(String jobId, Predicate<JobOwner> mayCancel, boolean ifOpen, String cancelledBy,
        Instant time, long processSeqId) {
        return Mono.defer(() -> {
            if (jobId == null || jobId.isBlank()) {
                return Mono.error(new EntityNotFoundException("External job not found"));
            }
            return findJob(jobId)
                .filter(job -> mayCancel.test(new JobOwner(job.jobId(), job.jobType(), job.requestedBy())))
                .switchIfEmpty(Mono.error(() -> new EntityNotFoundException("External job not found")))
                .flatMap(found -> lockJob(jobId))
                // One statement after the other: they share the transaction's connection.
                .flatMap(job -> outcome(jobId).hasElement()
                    .flatMap(finished -> finished ? Mono.just(true) : isCancelled(jobId))
                    .flatMap(over -> {
                        if (over) {
                            return ifOpen ? Mono.just(false) : Mono.error(new BusinessRuleViolationException(
                                new Violation("jobId", PlatformErrorCodes.EXTERNAL_JOB_FINISHED,
                                    "The external job has finished already", Map.of())));
                        }
                        return recordExpiredClaims(jobId, time)
                            .then(insertCancel(jobId, cancelledBy, time, processSeqId))
                            .then(isRunning(jobId, time))
                            .flatMap(running -> running ? Mono.just(false)
                                : finish(job, "CANCELLED", null, null, null, time))
                            .thenReturn(true);
                    }));
        });
    }

    /**
     * Records the job's claims whose lease ran out without an attempt as {@code LEASE_EXPIRED}, as the executor does
     * at its next poll; the job's row must be locked. A cancellation does it first, so that a job it ends at once has
     * every attempt counted in its outcome and its cancellation process.
     */
    private Mono<Void> recordExpiredClaims(String jobId, Instant now) {
        return engine().select("""
                SELECT c.attempt_no FROM sys_external_job_claim c
                WHERE c.job_id = :id AND c.lease_until <= :now
                  AND NOT EXISTS (SELECT 1 FROM sys_external_job_attempt a
                                   WHERE a.job_id = c.job_id AND a.attempt_no = c.attempt_no)
                ORDER BY c.attempt_no""", Map.of("id", BoundValue.of(jobId), "now", BoundValue.of(now)))
            .map(row -> Rows.intValue(row.get("attempt_no")))
            .collectList()
            .flatMapMany(Flux::fromIterable)
            .concatMap(attemptNo -> insertAttempt(jobId, attemptNo, AttemptOutcome.LEASE_EXPIRED, true,
                "LEASE_EXPIRED", "no result before the lease expired", null, now))
            .then();
    }

    /** Who cancelled a job, and when. */
    record Cancel(String cancelledBy, Instant cancelledTime) {}

    /** The job's cancellation, if it was cancelled. */
    Mono<Cancel> cancellation(String jobId) {
        return engine().select("SELECT cancelled_by, cancelled_time FROM sys_external_job_cancel WHERE job_id = :id",
                Map.of("id", BoundValue.of(jobId)))
            .next().map(row -> new Cancel(Rows.string(row.get("cancelled_by")),
                Rows.instant(row.get("cancelled_time"))));
    }

    /** Whether any attempt of the job was ever claimed (started), whatever became of it. */
    Mono<Boolean> anyClaim(String jobId) {
        return engine().select("SELECT 1 AS found FROM sys_external_job_claim WHERE job_id = :id LIMIT 1",
            Map.of("id", BoundValue.of(jobId))).hasElements();
    }

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

    /**
     * The usage of all the job's attempts, by metric, and how many attempts ended without recording any (decision D52
     * item 5).
     *
     * @param unrecordedAttempts the attempts whose outcome is {@code LEASE_EXPIRED} or {@code TIMED_OUT} and that have
     *                           no usage rows; a {@code FAILED} attempt without usage is not counted: it is the
     *                           handler's own outcome, and the handler had the chance to record usage first (an
     *                           unanswered call: {@code recordUsage(estimate)}, then rethrow)
     */
    record AttemptUsage(Map<String, Long> usage, int unrecordedAttempts) {}

    /**
     * {@link AttemptUsage} of the job, read in one statement (its attempts, joined to their usage rows): one snapshot,
     * so that the late usage of an attempt whose lease had expired ({@link #insertLateUsage}) is in both the totals
     * and the count, or in neither.
     */
    Mono<AttemptUsage> attemptUsage(String jobId) {
        return engine().select("""
                SELECT a.attempt_no, a.outcome, u.metric, u.quantity
                FROM sys_external_job_attempt a
                LEFT JOIN sys_external_job_usage u ON u.job_id = a.job_id AND u.attempt_no = a.attempt_no
                WHERE a.job_id = :id
                ORDER BY a.attempt_no, u.metric""", Map.of("id", BoundValue.of(jobId)))
            .collectList()
            .map(ExternalJobRecorder::attemptUsage);
    }

    static AttemptUsage attemptUsage(List<Map<String, Object>> rows) {
        Map<String, Long> totals = new TreeMap<>();
        int unrecorded = 0;
        for (Map<String, Object> row : rows) {
            String metric = Rows.string(row.get("metric"));
            if (metric != null) {
                totals.merge(metric, Rows.longValue(row.get("quantity")), ExternalJobRecorder::saturatedSum);
                continue;
            }
            // The join's one row for an attempt without usage rows.
            AttemptOutcome outcome = AttemptOutcome.valueOf(Rows.string(row.get("outcome")));
            if (outcome == AttemptOutcome.LEASE_EXPIRED || outcome == AttemptOutcome.TIMED_OUT) {
                unrecorded++;
            }
        }
        return new AttemptUsage(Map.copyOf(totals), unrecorded);
    }

    /**
     * A total that stops at {@link Long#MAX_VALUE} rather than failing: an absurd quantity reported by a handler must
     * not keep the job's completion from ever being delivered. It is logged, since the total is then a floor.
     */
    static long saturatedSum(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            LOG.warn("An external job's usage total exceeds {}; it is reported as that", Long.MAX_VALUE);
            return Long.MAX_VALUE;
        }
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ExternalJobRecorder.class);

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
