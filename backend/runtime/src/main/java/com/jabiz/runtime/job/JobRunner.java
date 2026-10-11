package com.jabiz.runtime.job;

import com.jabiz.context.RequestContext;
import com.jabiz.job.JobDefinition;
import com.jabiz.job.JobItems;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.custom.ProjectedField;
import com.jabiz.query.custom.SemanticRow;
import com.jabiz.runtime.ConcurrentUpdateException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.process.ExecutionOptions;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.ProcessResult;
import com.jabiz.runtime.process.entity.EntityIdGenerator;
import com.jabiz.runtime.query.AdvancedQueryExecutor;
import com.jabiz.runtime.query.SqlTemplateRegistry;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import io.micrometer.common.KeyValues;
import net.javacrumbs.shedlock.core.ClockProvider;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs one scheduled time of a job (docs/design/11-ledger-events-jobs.md section 4; decisions D14 and D42) under the
 * job's cluster lock (ShedLock), as the system actor, and records the run in {@code sys_job_run}. The lock keeps other
 * instances from running the job at the same time; idempotency keys keep a scheduled time from running twice even when
 * a lock expired or clocks differ.
 * <ul>
 *   <li>A single-process job executes its process with the key {@code job:<name>:<scheduled time>}; a second run of
 *       the time replays the first result.</li>
 *   <li>A per-item job selects its items with its SQL template in a short read-only transaction, leaves out the items
 *       waiting for their backoff, and runs the item process for each of at most {@code maxItems} as a root process
 *       with a transaction of its own, {@code parallelism} at a time, with the key
 *       {@code job:<name>:<scheduled time in ms>:<item key>} and the item's {@code HoldLock} waits bounded by
 *       {@code itemLockWait}. An item that commits {@code SUCCEEDED}, one that ran before at this time
 *       {@code REPLAYED}, one that met a held lock, a deadlock or a stale version is {@code BUSY} (left to the next
 *       run, not a failure), one that fails otherwise {@code FAILED} (rolled back, recorded in
 *       {@code sys_job_item_failure} and backed off); an item not started is {@code DEFERRED}. No item's result
 *       affects another's. After {@code maxRunTime}, or once the application stops, no new item starts.</li>
 * </ul>
 *
 * <p><strong>Blocks</strong> until the run is over, as ShedLock's R2DBC provider does: call it from a scheduler or
 * test thread, never from a non-blocking one.
 */
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    static final String RUN_TABLE = "sys_job_run";
    private static final int MAX_ERROR_LENGTH = 2000;
    /** From this many failures in a row on, a failing item is logged as an error, not a warning. */
    static final int ERROR_FROM_FAILURES = 5;
    static final String KEY_INVALID = "JOB_ITEM_KEY_INVALID";
    /** From this many BUSY outcomes in a row on, a busy item is logged as a warning. */
    static final int WARN_AFTER_BUSY = 5;
    private static final int MAX_BUSY_STREAKS = 10_000;

    /** Result of asking for a run. */
    public enum Outcome {
        /** The process ran and committed; for a per-item job: no item failed. */
        SUCCEEDED,
        /** The scheduled time had run already (the idempotency key replayed its result); single-process jobs only. */
        REPLAYED,
        /** The process failed and nothing it did was committed; for a per-item job: the selection failed. */
        FAILED,
        /** A per-item job's run in which some items failed; the others committed. */
        PARTIAL,
        /** Another instance held the lock; this one did nothing and recorded nothing. */
        LOCKED
    }

    /** What became of one item of a per-item job's run. */
    public enum ItemOutcome { SUCCEEDED, REPLAYED, BUSY, FAILED, DEFERRED }

    /**
     * How many items of a run ended how.
     *
     * @param selected all items of the run: the sum of the others
     * @param more     whether the selection had more items than {@code maxItems}
     */
    public record ItemCounts(int selected, int succeeded, int replayed, int busy, int failed, int deferred,
        boolean more) {

        static final ItemCounts NONE = new ItemCounts(0, 0, 0, 0, 0, 0, false);

        static ItemCounts of(Map<ItemOutcome, Integer> counts, boolean more) {
            int total = counts.values().stream().mapToInt(Integer::intValue).sum();
            return new ItemCounts(total, counts.getOrDefault(ItemOutcome.SUCCEEDED, 0),
                counts.getOrDefault(ItemOutcome.REPLAYED, 0), counts.getOrDefault(ItemOutcome.BUSY, 0),
                counts.getOrDefault(ItemOutcome.FAILED, 0), counts.getOrDefault(ItemOutcome.DEFERRED, 0), more);
        }
    }

    /** @param items the counts of a per-item job's run; null for a single-process job or when locked */
    public record JobRun(Outcome outcome, ItemCounts items) {}

    private final LockingTaskExecutor locks;
    private final ProcessExecutor executor;
    private final StorageAdapterRegistry storages;
    private final EntityIdGenerator ids;
    private final Clock clock;
    private final String poolRef;
    private final String instanceId;
    private final Duration lockAtLeastFor;
    private final PlatformObservations observations;
    private final SqlTemplateRegistry templates;
    private final AdvancedQueryExecutor queries;
    private final int maxInBackoff;
    private volatile boolean stopping;
    /** Consecutive BUSY outcomes per job and item, on this instance, to warn about items that are always busy. */
    private final Map<String, Integer> busyStreaks = new ConcurrentHashMap<>();

    /** A runner of single-process jobs only (per-item jobs need the templates). */
    public JobRunner(LockProvider lockProvider, ProcessExecutor executor, StorageAdapterRegistry storages,
        EntityIdGenerator ids, Clock clock, String poolRef, String instanceId, Duration lockAtLeastFor,
        PlatformObservations observations) {
        this(lockProvider, executor, storages, ids, clock, poolRef, instanceId, lockAtLeastFor, observations, null,
            null, JobItemFailures.MAX_IN_BACKOFF);
    }

    /**
     * @param maxInBackoff how many items waiting for their backoff widen a selection at most
     *                     ({@code jabiz.jobs.max-in-backoff}, 1000)
     */
    public JobRunner(LockProvider lockProvider, ProcessExecutor executor, StorageAdapterRegistry storages,
        EntityIdGenerator ids, Clock clock, String poolRef, String instanceId, Duration lockAtLeastFor,
        PlatformObservations observations, SqlTemplateRegistry templates, AdvancedQueryExecutor queries,
        int maxInBackoff) {
        if (maxInBackoff < 0) {
            throw new IllegalArgumentException("maxInBackoff must not be negative");
        }
        this.maxInBackoff = maxInBackoff;
        this.observations = Objects.requireNonNull(observations, "observations must not be null");
        this.locks = new DefaultLockingTaskExecutor(Objects.requireNonNull(lockProvider, "lockProvider"));
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.storages = Objects.requireNonNull(storages, "storages must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.poolRef = Objects.requireNonNull(poolRef, "poolRef must not be null");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
        this.lockAtLeastFor = Objects.requireNonNull(lockAtLeastFor, "lockAtLeastFor must not be null");
        this.templates = templates;
        this.queries = queries;
    }

    /** The idempotency key of one scheduled time of a single-process job. */
    public static String idempotencyKey(String jobName, Instant scheduledTime) {
        return "job:" + jobName + ":" + scheduledTime;
    }

    /** Runs the job for its scheduled time if no other instance holds its lock; blocks until done. */
    public <I> Outcome run(JobDefinition<I> job, Instant scheduledTime) {
        return execute(job, scheduledTime).outcome();
    }

    /** As {@link #run}, with the counts of a per-item job's items. */
    public <I> JobRun execute(JobDefinition<I> job, Instant scheduledTime) {
        return observations.blocking(PlatformObservations.JOB, "job " + job.name(),
            KeyValues.of("job", job.name()), () -> runOnce(job, scheduledTime), run -> run.outcome().name());
    }

    /**
     * Stops starting items (the application is stopping): items running end or roll back as they would, those not
     * started yet count as deferred. Single-process runs are not affected.
     */
    @jakarta.annotation.PreDestroy
    public void stop() {
        stopping = true;
    }

    private <I> JobRun runOnce(JobDefinition<I> job, Instant scheduledTime) {
        // Lock times are infrastructure time: ShedLock compares them with the real clock of every instance.
        LockConfiguration lock = new LockConfiguration(ClockProvider.now(), job.name(), job.lockAtMostFor(),
            lockAtLeastFor.compareTo(job.lockAtMostFor()) < 0 ? lockAtLeastFor : job.lockAtMostFor());
        try {
            LockingTaskExecutor.TaskResult<JobRun> result = locks.executeWithLock(
                () -> job.kind() == JobDefinition.Kind.PER_ITEM ? runItems(job, scheduledTime)
                    : runSingle(job, scheduledTime), lock);
            if (!result.wasExecuted()) {
                log.debug("Job {} at {}: another instance holds the lock", job.name(), scheduledTime);
                return new JobRun(Outcome.LOCKED, null);
            }
            return result.getResult();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("Job " + job.name() + " could not run", e);
        }
    }

    private <I> JobRun runSingle(JobDefinition<I> job, Instant scheduledTime) {
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
        record(newId(), job, scheduledTime, outcome, processSeqId, error, started, null);
        return new JobRun(outcome, null);
    }

    // ================= Per-item jobs =================

    /**
     * One selected item.
     *
     * @param key     its key; null when its key column was empty or invalid
     * @param invalid why its key is not usable, or null
     * @param failing its failures in a row, or null when it is not failing
     */
    private record Item(String key, Map<String, Object> row, String invalid, JobItemFailures.Failing failing) {}

    /** The items of a run: those to execute in order, those in backoff, and whether more were left out. */
    private record Selection(List<Item> work, int inBackoff, boolean more) {}

    private <I> JobRun runItems(JobDefinition<I> job, Instant scheduledTime) {
        Instant started = now();
        // How long the run may start items is infrastructure time, like the lock it must stay within.
        long deadline = System.nanoTime() + job.maxRunTime().toNanos();
        UUID runId = newId();
        Outcome outcome;
        ItemCounts counts;
        String error = null;
        try {
            Selection selection = select(job, scheduledTime);
            Map<ItemOutcome, Integer> tally = new EnumMap<>(ItemOutcome.class);
            if (selection.inBackoff() > 0) {
                tally.put(ItemOutcome.DEFERRED, selection.inBackoff());
            }
            List<ItemOutcome> outcomes = Flux.fromIterable(selection.work())
                .flatMap(item -> runItem(job, scheduledTime, runId, item, deadline), job.parallelism())
                .collectList()
                .block();
            Objects.requireNonNull(outcomes, "no item outcomes").forEach(item -> tally.merge(item, 1, Integer::sum));
            counts = ItemCounts.of(tally, selection.more());
            outcome = counts.failed() > 0 ? Outcome.PARTIAL : Outcome.SUCCEEDED;
        } catch (RuntimeException e) {
            log.error("Job {} at {}: selecting its items failed", job.name(), scheduledTime, e);
            outcome = Outcome.FAILED;
            counts = ItemCounts.NONE;
            error = describe(e);
        }
        record(runId, job, scheduledTime, outcome, null, error, started, counts);
        return new JobRun(outcome, counts);
    }

    /**
     * Runs the selection template as the system in a read-only transaction, in its default order, asking for the
     * items in backoff besides {@code maxItems} (so that failing items cannot starve the others) and one more (to know
     * whether there are more).
     */
    private Selection select(JobDefinition<?> job, Instant scheduledTime) {
        if (templates == null || queries == null) {
            throw new IllegalStateException("This runner cannot select the items of job " + job.name());
        }
        JobItems items = job.items();
        AdvancedQueryDefinition template = templates.find(items.templateId()).orElseThrow(() ->
            new IllegalStateException("Job " + job.name() + ": unknown SQL template " + items.templateId()));
        String keyColumn = template.result(items.keyColumn()).map(ProjectedField::name).orElseThrow(() ->
            new IllegalStateException("Job " + job.name() + ": template " + items.templateId() + " has no column "
                + items.keyColumn()));
        StorageEngine engine = engine();
        Instant now = clock.instant();
        Map<String, JobItemFailures.Failing> backoff = Objects.requireNonNull(
            JobItemFailures.inBackoff(engine, job, now, maxInBackoff).block());
        Map<String, Object> params = items.parameters().apply(scheduledTime);
        int limit = job.maxItems() + backoff.size() + 1;
        RequestContext system = RequestContext.system(Locale.ENGLISH,
            "job-" + job.name() + "-" + scheduledTime.toEpochMilli());
        // Masked: whatever the system may read, the selection reveals nothing and so records nothing (read-only).
        List<SemanticRow> rows = Objects.requireNonNull(engine.inTransaction(
                engine.select("SET TRANSACTION READ ONLY", Map.of()).then()
                    .then(queries.all(template, params == null ? Map.of() : params, AdvancedQueryExecutor.At.NOW,
                        null, List.of(), limit, true)))
            .map(AdvancedQueryExecutor.Page::items)
            .contextWrite(view -> RequestContexts.put(view, system))
            .block(), "no rows");
        List<Item> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SemanticRow row : rows) {
            Map<String, Object> values = new LinkedHashMap<>();
            row.getAllColumns().forEach((name, value) -> values.put(name, value.value()));
            Object raw = values.get(keyColumn);
            String key = raw == null ? null : String.valueOf(raw);
            if (key == null || !JobItems.ITEM_KEY.matcher(key).matches()) {
                candidates.add(new Item(null, values, key == null ? "is empty" : "is not 1 to 36 of [A-Za-z0-9._:-]",
                    null));
            } else if (!seen.add(key)) {
                candidates.add(new Item(null, values, key + " was selected twice", null));
            } else {
                candidates.add(new Item(key, values, null, null));
            }
        }
        // Every selected key's own backoff decides, not only the capped list that widened the selection.
        Map<String, JobItemFailures.Failing> failing = Objects.requireNonNull(JobItemFailures.failing(engine, job,
            candidates.stream().map(Item::key).filter(Objects::nonNull).toList()).block());
        List<Item> work = new ArrayList<>();
        int inBackoff = 0;
        int others = 0;
        for (Item candidate : candidates) {
            JobItemFailures.Failing failures = candidate.key() == null ? null : failing.get(candidate.key());
            if (failures != null && failures.retryAt(job.itemRetry()).isAfter(now)) {
                inBackoff++;
                continue;
            }
            if (++others <= job.maxItems()) {
                work.add(new Item(candidate.key(), candidate.row(), candidate.invalid(), failures));
            }
        }
        // A full selection may have left items out, whatever became of the rows it brought.
        return new Selection(List.copyOf(work), inBackoff, others > job.maxItems() || rows.size() >= limit);
    }

    private <I> Mono<ItemOutcome> runItem(JobDefinition<I> job, Instant scheduledTime, UUID runId, Item item,
        long deadline) {
        return Mono.defer(() -> {
            if (stopping || System.nanoTime() - deadline > 0) {
                return Mono.just(ItemOutcome.DEFERRED);
            }
            return observations.mono(PlatformObservations.JOB_ITEM, "job item " + job.name(),
                KeyValues.of("job", job.name()), executeItem(job, scheduledTime, runId, item)
                    .flatMap(outcome -> PlatformObservations.tag(PlatformObservations.RESULT, outcome.name())
                        .thenReturn(outcome)));
        });
    }

    private <I> Mono<ItemOutcome> executeItem(JobDefinition<I> job, Instant scheduledTime, UUID runId, Item item) {
        JobItemFailures.Failing failing = item.failing();
        if (item.invalid() != null) {
            log.error("Job {} at {}: an item's key {} ({}); the item is skipped", job.name(), scheduledTime,
                item.invalid(), KEY_INVALID);
            return Mono.just(ItemOutcome.FAILED);
        }
        String key = item.key();
        int failures = failing == null ? 0 : failing.failures();
        I input;
        try {
            input = job.itemInput().apply(scheduledTime, Collections.unmodifiableMap(item.row()));
        } catch (RuntimeException e) {
            return failed(job, scheduledTime, runId, key, failures, e);
        }
        RequestContext system = RequestContext.system(Locale.ENGLISH,
            "job-" + job.name() + "-" + scheduledTime.toEpochMilli() + "-" + key);
        ExecutionOptions options = ExecutionOptions.idempotent(
            JobDefinition.itemIdempotencyKey(job.name(), scheduledTime, key)).withLockWait(job.itemLockWait());
        return Mono.defer(() -> executor.run(job.process(), input, options))
            .contextWrite(view -> RequestContexts.put(view, system))
            .flatMap(result -> {
                busyStreaks.remove(streakKey(job, key));
                if (result.replayed()) {
                    return Mono.just(ItemOutcome.REPLAYED);
                }
                if (failures == 0) {
                    return Mono.just(ItemOutcome.SUCCEEDED);
                }
                log.info("Job {} at {}: item {} succeeded after {} failure(s)", job.name(), scheduledTime, key,
                    failures);
                return recordQuietly(JobItemFailures.record(engine(), newId(), job, key, JobItemFailures.RECOVERED,
                    runId, scheduledTime, 0, null, result.processSeqId(), now()), job, key)
                    .thenReturn(ItemOutcome.SUCCEEDED);
            })
            .onErrorResume(error -> {
                // A held lock, a deadlock or a stale version: busy, not failed; the next run selects it again.
                // Other conflicts (an entity that already exists...) will not go away by waiting: failed.
                if (error instanceof ConcurrentUpdateException conflict && conflict.busy()) {
                    busy(job, scheduledTime, key, error);
                    return Mono.just(ItemOutcome.BUSY);
                }
                busyStreaks.remove(streakKey(job, key));
                if (stopping) {
                    // Stopping: the item was cut short with the application, not by its own fault.
                    log.info("Job {} at {}: item {} ended as the application stops ({})", job.name(), scheduledTime,
                        key, error.toString());
                    return Mono.just(ItemOutcome.DEFERRED);
                }
                return failed(job, scheduledTime, runId, key, failures, error);
            });
    }

    private static String streakKey(JobDefinition<?> job, String key) {
        return job.name() + ' ' + key;
    }

    /** Logs a busy item: at info, and as a warning once it has been busy {@value #WARN_AFTER_BUSY} runs in a row. */
    private void busy(JobDefinition<?> job, Instant scheduledTime, String key, Throwable error) {
        if (busyStreaks.size() > MAX_BUSY_STREAKS) {
            // Items that left the selection keep no entry for ever; losing the counts only delays a warning.
            busyStreaks.clear();
        }
        int streak = busyStreaks.merge(streakKey(job, key), 1, Integer::sum);
        if (streak >= WARN_AFTER_BUSY) {
            log.warn("Job {} at {}: item {} has been busy {} runs in a row ({})", job.name(), scheduledTime, key,
                streak, error.getMessage());
        } else {
            log.info("Job {} at {}: item {} is busy, left to the next run ({})", job.name(), scheduledTime, key,
                error.getMessage());
        }
    }

    private Mono<ItemOutcome> failed(JobDefinition<?> job, Instant scheduledTime, UUID runId, String key,
        int failuresBefore, Throwable error) {
        int attempt = failuresBefore + 1;
        if (attempt >= ERROR_FROM_FAILURES) {
            log.error("Job {} at {}: item {} failed ({} times in a row)", job.name(), scheduledTime, key, attempt,
                error);
        } else {
            log.warn("Job {} at {}: item {} failed (attempt {}): {}", job.name(), scheduledTime, key, attempt,
                error.toString());
        }
        return recordQuietly(JobItemFailures.record(engine(), newId(), job, key, JobItemFailures.FAILED, runId,
            scheduledTime, attempt, describe(error), null, now()), job, key)
            .thenReturn(ItemOutcome.FAILED);
    }

    /** The item's own transaction has ended already; a lost failure record must not change its result. */
    private static Mono<Void> recordQuietly(Mono<Void> record, JobDefinition<?> job, String key) {
        return record.onErrorResume(error -> {
            log.error("Could not record the failure or recovery of item {} of job {}", key, job.name(), error);
            return Mono.empty();
        });
    }

    // ================= Records =================

    private void record(UUID runId, JobDefinition<?> job, Instant scheduledTime, Outcome outcome, Long processSeqId,
        String error, Instant started, ItemCounts items) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("run_id", runId);
        row.put("job_name", job.name());
        row.put("scheduled_time", scheduledTime);
        row.put("instance_id", instanceId);
        row.put("outcome", outcome.name());
        row.put("process_seq_id", processSeqId);
        row.put("error", error);
        row.put("started_time", started);
        row.put("finished_time", now());
        if (items != null) {
            row.put("items_selected", items.selected());
            row.put("items_succeeded", items.succeeded());
            row.put("items_replayed", items.replayed());
            row.put("items_busy", items.busy());
            row.put("items_failed", items.failed());
            row.put("items_deferred", items.deferred());
            row.put("items_more", items.more());
        }
        StorageEngine engine = engine();
        try {
            engine.inTransaction(engine.insert(RUN_TABLE, row)).block();
        } catch (RuntimeException e) {
            // The run itself is committed (or rolled back) already; a lost record must not hide that.
            log.error("Could not record run of job {} at {} ({})", job.name(), scheduledTime, outcome, e);
        }
    }

    private StorageEngine engine() {
        return storages.getEngine(poolRef);
    }

    private UUID newId() {
        return UUID.fromString(String.valueOf(ids.next(null)));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static String describe(Throwable error) {
        String text = error.getClass().getName() + (error.getMessage() == null ? "" : ": " + error.getMessage());
        return text.length() > MAX_ERROR_LENGTH ? text.substring(0, MAX_ERROR_LENGTH) : text;
    }
}
