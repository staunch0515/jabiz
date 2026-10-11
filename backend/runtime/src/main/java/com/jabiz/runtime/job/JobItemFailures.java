package com.jabiz.runtime.job;

import com.jabiz.job.JobDefinition;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageEngine;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The failures of per-item jobs' items and their recovery (docs/design/11-ledger-events-jobs.md section 4.1; decision
 * D42), in the append-only {@code sys_job_item_failure}: one {@code FAILED} row per failure, carrying how many failures
 * in a row it is, and one {@code RECOVERED} row when a failing item succeeds again. An item is failing while its
 * latest row is {@code FAILED}; it then waits for its backoff ({@link JobDefinition.ItemRetry}). Rows are written
 * outside the item's transaction (which rolled back), as the outbox records failed deliveries.
 */
final class JobItemFailures {

    static final String TABLE = "sys_job_item_failure";
    static final String FAILED = "FAILED";
    static final String RECOVERED = "RECOVERED";

    /** Items in backoff that widen a selection at most (decision D42, rule 4). */
    static final int MAX_IN_BACKOFF = 1000;

    /**
     * An item whose latest row is a failure.
     *
     * @param failures    its consecutive failures
     * @param lastFailure when the latest was recorded
     * @param error       the latest error
     */
    record Failing(String itemKey, int failures, Instant lastFailure, String error) {

        /** When a later run may retry it. */
        Instant retryAt(JobDefinition.ItemRetry retry) {
            return lastFailure.plus(retry.delayAfter(failures));
        }
    }

    private static final String LATEST = """
        SELECT item_key, attempt, recorded_time, error FROM (
            SELECT DISTINCT ON (item_key) item_key, kind, attempt, recorded_time, error
            FROM sys_job_item_failure
            WHERE job_name = :job %s
            ORDER BY item_key, entry_no DESC
        ) l
        WHERE kind = 'FAILED' %s""";

    /** The delay of {@link JobDefinition.ItemRetry#delayAfter}, in seconds, in SQL. */
    private static final String DELAY = "CASE WHEN attempt <= 1 THEN 0"
        + " ELSE LEAST(:cap, :first * power(2, LEAST(attempt - 2, 62))) END";

    private JobItemFailures() {}

    /**
     * Failing items still waiting for their backoff at {@code now}, at most {@code limit}. No backoff is longer than
     * the job's longest delay, so only the rows of that last stretch are read (index on job and time).
     */
    static Mono<Map<String, Failing>> inBackoff(StorageEngine engine, JobDefinition<?> job, Instant now, int limit) {
        if (limit == 0) {
            return Mono.just(Map.of());
        }
        Map<String, BoundValue> params = new LinkedHashMap<>();
        params.put("job", BoundValue.of(job.name()));
        params.put("now", BoundValue.of(now));
        params.put("since", BoundValue.of(now.minus(job.itemRetry().max())));
        params.put("first", BoundValue.of(seconds(job.itemRetry().first())));
        params.put("cap", BoundValue.of(seconds(job.itemRetry().max())));
        params.put("limit", BoundValue.of((long) limit));
        // The latest row of an item within the stretch is its latest row at all: later rows are later still.
        return collect(engine.select(LATEST.formatted("AND recorded_time > :since",
            "AND recorded_time + make_interval(secs => " + DELAY + ") > :now ORDER BY recorded_time LIMIT :limit"),
            params));
    }

    /** Which of {@code keys} are failing, whatever their backoff. */
    static Mono<Map<String, Failing>> failing(StorageEngine engine, JobDefinition<?> job, List<String> keys) {
        if (keys.isEmpty()) {
            return Mono.just(Map.of());
        }
        Map<String, BoundValue> params = new LinkedHashMap<>();
        params.put("job", BoundValue.of(job.name()));
        params.put("keys", BoundValue.of(keys.toArray(String[]::new)));
        return collect(engine.select(LATEST.formatted("AND item_key = ANY(:keys)", ""), params));
    }

    /**
     * The failing items of the job that failed after {@code since} (older ones have left the selection, most
     * likely), those failing most often first, at most {@code limit}.
     */
    static Mono<List<Failing>> failing(StorageEngine engine, JobDefinition<?> job, int limit, Instant since) {
        Map<String, BoundValue> params = new LinkedHashMap<>();
        params.put("job", BoundValue.of(job.name()));
        params.put("since", BoundValue.of(since));
        params.put("limit", BoundValue.of((long) limit));
        return collect(engine.select(LATEST.formatted("AND recorded_time > :since",
            "ORDER BY attempt DESC, recorded_time DESC, item_key LIMIT :limit"), params))
            .map(found -> List.copyOf(found.values()));
    }

    private static Mono<Map<String, Failing>> collect(reactor.core.publisher.Flux<Map<String, Object>> rows) {
        return rows.map(row -> new Failing(Rows.string(row.get("item_key")), Rows.intValue(row.get("attempt")),
                Rows.instant(row.get("recorded_time")), Rows.string(row.get("error"))))
            .collectList()
            .map(list -> {
                Map<String, Failing> byKey = new LinkedHashMap<>();
                list.forEach(failing -> byKey.put(failing.itemKey(), failing));
                return byKey;
            });
    }

    /** Appends a failure or a recovery in a transaction of its own. */
    static Mono<Void> record(StorageEngine engine, UUID failureId, JobDefinition<?> job, String itemKey, String kind,
        UUID runId, Instant scheduledTime, int attempt, String error, Long processSeqId, Instant recordedTime) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("failure_id", failureId);
        row.put("job_name", job.name());
        row.put("item_key", itemKey);
        row.put("kind", kind);
        row.put("run_id", runId);
        row.put("scheduled_time", scheduledTime);
        row.put("attempt", attempt);
        row.put("error", error);
        row.put("process_seq_id", processSeqId);
        row.put("recorded_time", recordedTime);
        return engine.inTransaction(engine.insert(TABLE, row));
    }

    private static double seconds(Duration duration) {
        return duration.toMillis() / 1000.0;
    }
}
