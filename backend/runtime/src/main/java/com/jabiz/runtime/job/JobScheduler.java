package com.jabiz.runtime.job;

import com.jabiz.job.JobDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;

/**
 * Fires the declared jobs at the times of their cron expressions (docs/design/11-ledger-events-jobs.md section 4;
 * decision D14). Spring's task scheduler does the timing, on the injected clock, so the scheduled time handed to a
 * run is the time the cron expression names; {@link JobRunner} makes one instance run it. A time missed while no
 * instance was up is not caught up.
 *
 * <p>Starts when the application is ready ({@code jabiz.jobs.scheduler.enabled}, default true); the platform check and
 * tests that trigger runs themselves leave it off.
 */
@Component
public class JobScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobScheduler.class);

    private final JobRegistry jobs;
    private final JobRunner runner;
    private final Clock clock;
    private final boolean enabled;
    private final int poolSize;
    private volatile ThreadPoolTaskScheduler scheduler;

    public JobScheduler(JobRegistry jobs, JobRunner runner, Clock clock,
        @Value("${jabiz.jobs.scheduler.enabled:true}") boolean enabled,
        @Value("${jabiz.jobs.scheduler.pool-size:2}") int poolSize) {
        this.jobs = jobs;
        this.runner = runner;
        this.clock = clock;
        this.enabled = enabled;
        this.poolSize = poolSize;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (!enabled || jobs.all().isEmpty() || scheduler != null) {
            return;
        }
        ThreadPoolTaskScheduler tasks = new ThreadPoolTaskScheduler();
        tasks.setPoolSize(poolSize);
        tasks.setThreadNamePrefix("jabiz-job-");
        tasks.setClock(clock);
        tasks.setWaitForTasksToCompleteOnShutdown(true);
        tasks.initialize();
        scheduler = tasks;
        jobs.all().forEach(job -> scheduleNext(job, clock.instant()));
        log.info("Scheduled {} job(s)", jobs.all().size());
    }

    @jakarta.annotation.PreDestroy
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }
    }

    /** The first time of the job's cron expression after {@code after}; null if it never fires again. */
    static Instant nextTime(JobDefinition<?> job, Instant after) {
        ZonedDateTime next = CronExpression.parse(job.cron()).next(after.atZone(job.zone()));
        return next == null ? null : next.toInstant();
    }

    private void scheduleNext(JobDefinition<?> job, Instant after) {
        ThreadPoolTaskScheduler tasks = scheduler;
        Instant next = nextTime(job, after);
        if (tasks == null || next == null) {
            return;
        }
        tasks.schedule(() -> {
            try {
                JobRunner.Outcome outcome = runner.run(job, next);
                log.info("Job {} at {}: {}", job.name(), next, outcome);
            } catch (RuntimeException e) {
                log.error("Job {} at {} could not run", job.name(), next, e);
            } finally {
                // From now, not from the time just run: times missed by a long run or a pause are skipped.
                Instant now = clock.instant();
                scheduleNext(job, now.isAfter(next) ? now : next);
            }
        }, next);
    }
}
