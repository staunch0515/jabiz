package com.jabiz.runtime.job;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jabiz.job.JobDefinition;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import net.javacrumbs.shedlock.core.ClockProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code GET /api/jobs}: the declared jobs with their kind, next scheduled time and latest runs (permission
 * {@value JobPermissions#READ}); a per-item job's runs carry their item counts, and the job its failing items (decision
 * D42). {@code POST /api/jobs/{job}/runs}: runs a past scheduled time again by hand (permission
 * {@value JobPermissions#RUN}, decision D77), answered 202 at once; the run shows among the latest runs once it is
 * over, with the operation of the request.
 */
@RestController
@RequestMapping("/api/jobs")
class JobController {

    static final int RECENT_RUNS = 10;
    static final int FAILING_ITEMS = 50;
    /** Items whose last failure is older are left out: they no longer fail (or are no longer selected). */
    static final Duration FAILING_SINCE = Duration.ofDays(7);
    static final int MAX_SHOWN_ERROR = 200;
    private static final String RUN_COLUMNS = """
        SELECT scheduled_time, instance_id, outcome, process_seq_id, error, started_time, finished_time,
               items_selected, items_succeeded, items_replayed, items_busy, items_failed, items_deferred,
               items_more, requested_by, request_seq_id""";

    /**
     * A declared job.
     *
     * @param nextTime     its next scheduled time; null if it never fires again
     * @param running      whether a run holds its cluster lock (one is under way, or ended within
     *                     {@code jabiz.jobs.lock-at-least-for}): a run by hand would be refused now
     * @param rerunTimes   the scheduled times that may be run again by hand, newest first: its latest 20 and those of
     *                     its latest runs, no older than {@code jabiz.jobs.manual-run.max-age}
     * @param recentRuns   its latest runs, newest scheduled time first
     * @param failingItems a per-item job's items failing in a row, the most often first; absent for a single-process
     *                     job
     */
    record JobView(String name, String kind, String cron, String zone, String processName, int processVersion,
        Instant nextTime, boolean running, List<Instant> rerunTimes, List<JobRunView> recentRuns,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<FailingItemView> failingItems) {}

    /**
     * One run.
     *
     * @param outcome      {@code SUCCEEDED}, {@code REPLAYED}, {@code FAILED} or {@code PARTIAL}
     * @param items        a per-item job's counts; absent for a single-process job
     * @param requestedBy  the user who asked for the run by hand; null for a scheduled run
     * @param requestSeqId the operation of that request ({@code JOB_RUN_REQUEST}, with its reason); null for a
     *                     scheduled run
     */
    record JobRunView(Instant scheduledTime, String instanceId, String outcome, Long processSeqId, String error,
        Instant startedTime, Instant finishedTime,
        @JsonInclude(JsonInclude.Include.NON_NULL) ItemCountsView items, String requestedBy, Long requestSeqId) {}

    record ItemCountsView(Integer selected, Integer succeeded, Integer replayed, Integer busy, Integer failed,
        Integer deferred, Boolean more) {}

    record FailingItemView(String itemKey, int failures, String lastError, Instant lastFailureTime, Instant retryAt) {}

    /**
     * @param scheduledTime the past scheduled time to run again
     * @param reason        why; kept with the operation of the request
     */
    record RunRequest(Instant scheduledTime, String reason) {}

    private final JobRegistry jobs;
    private final JobRunRequests requests;
    private final StorageAdapterRegistry storages;
    private final Clock clock;
    private final String poolRef;
    private final Duration maxAge;

    JobController(JobRegistry jobs, JobRunRequests requests, StorageAdapterRegistry storages, Clock clock,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
        @Value("${jabiz.jobs.manual-run.max-age:P90D}") Duration maxAge) {
        this.jobs = jobs;
        this.requests = requests;
        this.storages = storages;
        this.clock = clock;
        this.poolRef = poolRef;
        this.maxAge = maxAge;
    }

    @GetMapping
    Mono<List<JobView>> list() {
        return RequestContexts.current().flatMap(request -> {
            if (!request.hasPermission(JobPermissions.READ)) {
                return Mono.error(new PermissionDeniedException(JobPermissions.READ,
                    "Reading jobs needs permission " + JobPermissions.READ));
            }
            return lockedJobs().flatMap(locked -> Flux.fromIterable(jobs.all())
                .concatMap(job -> describe(job, locked.contains(job.name()))).collectList());
        });
    }

    /** Asks for a past scheduled time of the job to run again (decision D77); see {@link JobRunRequests#request}. */
    @PostMapping("/{job}/runs")
    Mono<ResponseEntity<JobRunRequests.JobRunAccepted>> requestJobRun(@PathVariable String job,
        @RequestBody RunRequest body,
        @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return requests.request(job, body.scheduledTime(), body.reason(), idempotencyKey)
            .map(accepted -> ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                .body(accepted));
    }

    /**
     * The run a request by hand started ({@code JOB_RUN_REQUEST}'s operation), once it is over; empty while it runs
     * (or if it never started). Found whatever its scheduled time, unlike the latest runs of the list.
     */
    @GetMapping("/{job}/runs")
    Mono<List<JobRunView>> requestedJobRuns(@PathVariable String job, @RequestParam long requestSeqId) {
        return RequestContexts.current().flatMap(request -> {
            if (!request.hasPermission(JobPermissions.READ)) {
                return Mono.error(new PermissionDeniedException(JobPermissions.READ,
                    "Reading jobs needs permission " + JobPermissions.READ));
            }
            JobDefinition<?> definition = jobs.find(job).orElse(null);
            if (definition == null) {
                return Mono.error(new EntityNotFoundException("Job " + job + " not found"));
            }
            return runs(definition, "job_name = :name AND request_seq_id = :request",
                Map.of("name", BoundValue.of(job), "request", BoundValue.of(requestSeqId),
                    "limit", BoundValue.of(1L))).collectList();
        });
    }

    /** The names of the jobs whose cluster lock is held now (by infrastructure time, as ShedLock compares it). */
    private Mono<Set<String>> lockedJobs() {
        return storages.getEngine(poolRef).select(
                "SELECT name FROM " + JobConfig.LOCK_TABLE + " WHERE lock_until > :now",
                Map.of("now", BoundValue.of(ClockProvider.now())))
            .map(row -> Rows.string(row.get("name")))
            .collect(Collectors.toSet());
    }

    /** Runs of the job matching {@code where} (a fixed condition with bound values), newest scheduled time first. */
    private Flux<JobRunView> runs(JobDefinition<?> job, String where, Map<String, BoundValue> params) {
        boolean perItem = job.kind() == JobDefinition.Kind.PER_ITEM;
        return storages.getEngine(poolRef).select(RUN_COLUMNS + " FROM sys_job_run WHERE " + where
                + " ORDER BY scheduled_time DESC, finished_time DESC LIMIT :limit", params)
            .map(row -> new JobRunView(Rows.instant(row.get("scheduled_time")), Rows.string(row.get("instance_id")),
                Rows.string(row.get("outcome")), Rows.longValue(row.get("process_seq_id")),
                Rows.string(row.get("error")), Rows.instant(row.get("started_time")),
                Rows.instant(row.get("finished_time")),
                perItem ? new ItemCountsView(Rows.intValue(row.get("items_selected")),
                    Rows.intValue(row.get("items_succeeded")), Rows.intValue(row.get("items_replayed")),
                    Rows.intValue(row.get("items_busy")), Rows.intValue(row.get("items_failed")),
                    Rows.intValue(row.get("items_deferred")), (Boolean) row.get("items_more")) : null,
                Rows.string(row.get("requested_by")), Rows.longValue(row.get("request_seq_id"))));
    }

    private Mono<JobView> describe(JobDefinition<?> job, boolean running) {
        boolean perItem = job.kind() == JobDefinition.Kind.PER_ITEM;
        return runs(job, "job_name = :name",
                Map.of("name", BoundValue.of(job.name()), "limit", BoundValue.of((long) RECENT_RUNS)))
            .collectList()
            .zipWith(failingItems(job))
            .map(found -> {
                List<JobRunView> runs = found.getT1();
                Instant now = clock.instant();
                return new JobView(job.name(), job.kind().name(), job.cron(), job.zone().getId(),
                    job.process().name(), job.process().version(), JobScheduler.nextTime(job, now), running,
                    JobRunProcesses.offeredTimes(job, now, maxAge, runs.stream().map(JobRunView::scheduledTime)
                        .toList()), runs, perItem ? found.getT2() : null);
            });
    }

    /** The items failing in a row, the most often first; their keys are the selection's, never more of the row. */
    private Mono<List<FailingItemView>> failingItems(JobDefinition<?> job) {
        if (job.kind() != JobDefinition.Kind.PER_ITEM) {
            return Mono.just(List.of());
        }
        return JobItemFailures.failing(storages.getEngine(poolRef), job, FAILING_ITEMS,
                clock.instant().minus(FAILING_SINCE))
            .map(failing -> failing.stream().map(item -> {
                String error = item.error();
                return new FailingItemView(item.itemKey(), item.failures(),
                    error == null || error.length() <= MAX_SHOWN_ERROR ? error : error.substring(0, MAX_SHOWN_ERROR),
                    item.lastFailure(), item.retryAt(job.itemRetry()));
            }).toList());
    }
}
