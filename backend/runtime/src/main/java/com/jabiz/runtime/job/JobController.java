package com.jabiz.runtime.job;

import com.jabiz.job.JobDefinition;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/jobs}: the declared jobs with their next scheduled time and latest runs (permission
 * {@value #READ}).
 */
@RestController
@RequestMapping("/api/jobs")
class JobController {

    static final String READ = "job.read";
    static final int RECENT_RUNS = 10;

    private final JobRegistry jobs;
    private final StorageAdapterRegistry storages;
    private final Clock clock;
    private final String poolRef;

    JobController(JobRegistry jobs, StorageAdapterRegistry storages, Clock clock,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.jobs = jobs;
        this.storages = storages;
        this.clock = clock;
        this.poolRef = poolRef;
    }

    @GetMapping
    Mono<List<Map<String, Object>>> list() {
        return RequestContexts.current().flatMap(request -> {
            if (!request.hasPermission(READ)) {
                return Mono.error(new PermissionDeniedException(READ, "Reading jobs needs permission " + READ));
            }
            return Flux.fromIterable(jobs.all()).concatMap(this::describe).collectList();
        });
    }

    private Mono<Map<String, Object>> describe(JobDefinition<?> job) {
        return storages.getEngine(poolRef).select("""
                SELECT scheduled_time, instance_id, outcome, process_seq_id, error, started_time, finished_time
                FROM sys_job_run WHERE job_name = :name
                ORDER BY scheduled_time DESC, finished_time DESC LIMIT :limit""",
                Map.of("name", BoundValue.of(job.name()), "limit", BoundValue.of((long) RECENT_RUNS)))
            .map(row -> {
                Map<String, Object> run = new LinkedHashMap<>();
                run.put("scheduledTime", Rows.instant(row.get("scheduled_time")));
                run.put("instanceId", Rows.string(row.get("instance_id")));
                run.put("outcome", Rows.string(row.get("outcome")));
                run.put("processSeqId", Rows.longValue(row.get("process_seq_id")));
                run.put("error", Rows.string(row.get("error")));
                run.put("startedTime", Rows.instant(row.get("started_time")));
                run.put("finishedTime", Rows.instant(row.get("finished_time")));
                return run;
            })
            .collectList()
            .map(runs -> {
                Map<String, Object> json = new LinkedHashMap<>();
                json.put("name", job.name());
                json.put("cron", job.cron());
                json.put("zone", job.zone().getId());
                json.put("processName", job.process().name());
                json.put("processVersion", job.process().version());
                json.put("nextTime", JobScheduler.nextTime(job, clock.instant()));
                json.put("recentRuns", runs);
                return json;
            });
    }
}
