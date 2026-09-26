package com.jabiz.app.it.job;

import com.jabiz.app.it.fixture.ItEventFixtures;
import com.jabiz.job.JobDefinition;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.entity.EntityIdGenerator;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.test.TestTokens;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactoryOptions;
import net.javacrumbs.shedlock.provider.r2dbc.R2dbcLockProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ROADMAP phase 9 acceptance (docs/design/11-ledger-events-jobs.md section 4): when two instances fire the same job
 * at the same time, it runs once. Each "instance" has its own connection pool, lock provider and instance id, as two
 * application processes would; they share the database. Every test uses its own job name, since the cluster lock is
 * kept for {@code lockAtLeastFor} after a run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class JobIT extends PostgresIntegrationTest {

    @Autowired
    ProcessExecutor executor;

    @Autowired
    StorageAdapterRegistry storages;

    @Autowired
    EntityIdGenerator ids;

    @Autowired
    ApplicationContext context;

    @Autowired
    JwtService tokens;

    private final List<ConnectionPool> pools = new ArrayList<>();

    @AfterEach
    void closePools() {
        pools.forEach(ConnectionPool::dispose);
        pools.clear();
    }

    @Test
    void twoInstancesFiringTogetherRunTheJobOnce() throws Exception {
        for (int round = 0; round < 3; round++) {
            JobDefinition<ItEventFixtures.TickInput> job = job(false);
            JobRunner instanceA = instance("instance-a", Duration.ofSeconds(30));
            JobRunner instanceB = instance("instance-b", Duration.ofSeconds(30));
            Instant scheduled = START.plus(Duration.ofHours(round + 1));
            int runsBefore = ItEventFixtures.TICK_RUNS.get();

            List<JobRunner.Outcome> outcomes = together(() -> instanceA.run(job, scheduled),
                () -> instanceB.run(job, scheduled));

            assertThat(outcomes).containsExactlyInAnyOrder(JobRunner.Outcome.SUCCEEDED, JobRunner.Outcome.LOCKED);
            assertThat(ItEventFixtures.TICK_RUNS.get() - runsBefore).isEqualTo(1);
            List<Map<String, Object>> runs = runs(job.name());
            assertThat(runs).hasSize(1);
            assertThat(runs.getFirst()).containsEntry("outcome", "SUCCEEDED");
            assertThat(runs.getFirst().get("instance_id")).isIn("instance-a", "instance-b");
            assertThat(operations(job.name(), scheduled)).hasSize(1);
            assertThat(query("SELECT * FROM it_event_log WHERE f_id = ?", "tick:" + scheduled)).hasSize(1);
            // Right after the run the lock is still held (lockAtLeastFor): a late instance does nothing at all.
            assertThat(instanceB.run(job, scheduled)).isEqualTo(JobRunner.Outcome.LOCKED);
            assertThat(runs(job.name())).hasSize(1);
        }
    }

    @Test
    void aScheduledTimeRunsOnceEvenWhenTheLockHasExpired() {
        JobDefinition<ItEventFixtures.TickInput> job = job(false);
        // No lockAtLeastFor: as if the second instance's clock were behind, or the lock had expired.
        JobRunner instanceA = instance("instance-a", Duration.ZERO);
        JobRunner instanceB = instance("instance-b", Duration.ZERO);
        Instant scheduled = START.plus(Duration.ofDays(1));
        int runsBefore = ItEventFixtures.TICK_RUNS.get();

        assertThat(instanceA.run(job, scheduled)).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(instanceB.run(job, scheduled)).isEqualTo(JobRunner.Outcome.REPLAYED);

        assertThat(ItEventFixtures.TICK_RUNS.get() - runsBefore).isEqualTo(1);
        assertThat(operations(job.name(), scheduled)).hasSize(1);
        assertThat(runs(job.name())).extracting(row -> row.get("outcome"))
            .containsExactlyInAnyOrder("SUCCEEDED", "REPLAYED");
        // The next scheduled time is a run of its own.
        assertThat(instanceB.run(job, scheduled.plus(Duration.ofHours(1)))).isEqualTo(JobRunner.Outcome.SUCCEEDED);
    }

    @Test
    void failedRunsAreRecordedAndLeaveNoOperation() {
        JobDefinition<ItEventFixtures.TickInput> job = job(true);
        JobRunner runner = instance("instance-a", Duration.ZERO);
        Instant scheduled = START.plus(Duration.ofDays(2));

        assertThat(runner.run(job, scheduled)).isEqualTo(JobRunner.Outcome.FAILED);

        Map<String, Object> run = runs(job.name()).getFirst();
        assertThat(run).containsEntry("outcome", "FAILED").containsEntry("process_seq_id", null);
        assertThat(String.valueOf(run.get("error"))).contains("BusinessRuleViolationException");
        assertThat(operations(job.name(), scheduled)).isEmpty();
        // Nothing was committed, so the same scheduled time may run again.
        assertThat(runner.run(job, scheduled)).isEqualTo(JobRunner.Outcome.FAILED);
    }

    @Test
    void theDeclaredJobsAreListedWithTheirRuns() {
        JobRunner declared = context.getBean(JobRunner.class);
        JobDefinition<?> tick = context.getBeansOfType(JobDefinition.class).values().stream()
            .filter(job -> job.name().equals(ItEventFixtures.TICK_JOB)).findFirst().orElseThrow();
        Instant scheduled = START.minus(Duration.ofHours(1));
        assertThat(declared.run(tick, scheduled)).isIn(JobRunner.Outcome.SUCCEEDED, JobRunner.Outcome.LOCKED);

        WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
        client.get().uri("/api/jobs").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/jobs").header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(tokens, "it-reader",
            "audit.read")).exchange().expectStatus().isForbidden();
        client.get().uri("/api/jobs").header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(tokens, "it-admin",
                "job.read")).exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$[?(@.name == 'it.tick')].cron").isEqualTo("0 0 * * * *")
            .jsonPath("$[?(@.name == 'it.tick')].processName").isEqualTo("IT_TICK")
            .jsonPath("$[?(@.name == 'it.tick')].nextTime").isEqualTo("2026-01-31T10:00:00Z")
            .jsonPath("$[?(@.name == 'it.tick')].recentRuns[0].scheduledTime").isEqualTo("2026-01-31T08:00:00Z");
    }

    @Test
    void runRecordsAreAppendOnly() {
        JobDefinition<ItEventFixtures.TickInput> job = job(false);
        instance("instance-a", Duration.ZERO).run(job, START.plus(Duration.ofDays(3)));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                execute("UPDATE sys_job_run SET outcome = 'FAILED' WHERE job_name = ?", job.name()))
            .hasMessageContaining("append-only table");
    }

    // ================= helpers =================

    private static JobDefinition<ItEventFixtures.TickInput> job(boolean fail) {
        return JobDefinition.cron("it.job-" + UUID.randomUUID().toString().substring(0, 8), "0 0 * * * *",
            ZoneOffset.UTC, ItEventFixtures.TICK, at -> new ItEventFixtures.TickInput(at, fail));
    }

    /** One application instance's runner: its own pool and lock provider over the shared schema. */
    private JobRunner instance(String instanceId, Duration lockAtLeastFor) {
        ConnectionFactoryOptions options = ConnectionFactoryOptions.parse(DB.r2dbcUrl() + "?schema=" + schema())
            .mutate()
            .option(ConnectionFactoryOptions.USER, DB.username())
            .option(ConnectionFactoryOptions.PASSWORD, DB.password())
            .build();
        ConnectionPool pool = new ConnectionPool(ConnectionPoolConfiguration.builder(ConnectionFactories.get(options))
            .maxSize(4).build());
        pools.add(pool);
        return new JobRunner(new R2dbcLockProvider(pool), executor, storages, ids, clock, "default", instanceId,
            lockAtLeastFor);
    }

    @SafeVarargs
    private static <T> List<T> together(java.util.concurrent.Callable<T>... tasks) throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(tasks.length);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<CompletableFuture<T>> futures = new ArrayList<>();
            for (java.util.concurrent.Callable<T> task : tasks) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                        return task.call();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }, threads));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (CompletableFuture<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            threads.shutdownNow();
        }
    }

    private static List<Map<String, Object>> runs(String jobName) {
        return query("SELECT * FROM sys_job_run WHERE job_name = ? ORDER BY finished_time", jobName);
    }

    private static List<Map<String, Object>> operations(String jobName, Instant scheduled) {
        return query("SELECT * FROM op_process WHERE idempotency_key = ?", JobRunner.idempotencyKey(jobName, scheduled));
    }
}
