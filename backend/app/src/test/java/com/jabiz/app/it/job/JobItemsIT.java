package com.jabiz.app.it.job;

import com.jabiz.app.it.fixture.ItJobItemFixtures;
import com.jabiz.app.it.fixture.ItJobItemFixtures.BatchInput;
import com.jabiz.app.it.fixture.ItJobItemFixtures.ItemInput;
import com.jabiz.app.it.fixture.ItJobItemFixtures.OnlineInput;
import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.context.RequestContext;
import com.jabiz.job.JobDefinition;
import com.jabiz.runtime.LockNotAvailableException;
import com.jabiz.runtime.integrity.IntegrityProcesses;
import com.jabiz.runtime.job.JobConfig;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.job.JobRunner.ItemCounts;
import com.jabiz.runtime.job.JobRunner.JobRun;
import com.jabiz.runtime.job.JobRunner.Outcome;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.entity.EntityIdGenerator;
import com.jabiz.runtime.query.AdvancedQueryExecutor;
import com.jabiz.runtime.query.SqlTemplateRegistry;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactoryOptions;
import net.javacrumbs.shedlock.provider.r2dbc.R2dbcLockProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Per-item jobs (ROADMAP phase 16k; decision D42; docs/design/11-ledger-events-jobs.md section 4.1): every item runs
 * as the system in a transaction of its own; one failing item affects no other; a held lock makes an item busy, not
 * failed, and an item holds only its own locks, so a job cannot deadlock with requests the way a batch in one
 * transaction does; reruns of a scheduled time replay; failing items back off without starving the others; two
 * instances run a scheduled time once; {@code maxRunTime} stops starting items.
 */
@SpringBootTest(properties = {"it.sql-log.enabled=true", "jabiz.process.lock-timeout=10s",
    "jabiz.jobs.lock-at-least-for=0s"})
class JobItemsIT extends PostgresIntegrationTest {

    @Autowired
    JobRunner runner;

    @Autowired
    ProcessExecutor executor;

    @Autowired
    StorageAdapterRegistry storages;

    @Autowired
    EntityIdGenerator ids;

    @Autowired
    SqlTemplateRegistry templates;

    @Autowired
    AdvancedQueryExecutor queries;

    private final List<ConnectionPool> pools = new ArrayList<>();
    private final List<ExecutorService> threads = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        threads.forEach(ExecutorService::shutdownNow);
        threads.clear();
        pools.forEach(ConnectionPool::dispose);
        pools.clear();
    }

    // ================= 1. one failing item =================

    @Test
    void oneFailingItemAffectsNoOther() {
        String batch = batch();
        List<String> keys = items(batch, 5);
        execute("UPDATE it_job_item SET f_fail = true WHERE f_id = ?", keys.get(2));
        JobDefinition<ItemInput> job = ItJobItemFixtures.job(name(), batch);
        Instant at = at(1);

        JobRun run = runner.execute(job, at);

        assertThat(run.outcome()).isEqualTo(Outcome.PARTIAL);
        assertThat(run.items()).isEqualTo(new ItemCounts(5, 4, 0, 0, 1, 0, false));
        assertThat(runs(keys)).containsExactly(1, 1, 0, 1, 1);
        // The failed item left nothing: no operation, no write; its failure is recorded once.
        assertThat(query("SELECT * FROM op_process WHERE idempotency_key = ?",
            JobDefinition.itemIdempotencyKey(job.name(), at, keys.get(2)))).isEmpty();
        assertThat(query("SELECT * FROM op_process WHERE idempotency_key LIKE ?", "job:" + job.name() + ":%"))
            .hasSize(4).allSatisfy(op -> assertThat(op).containsEntry("actor_id", RequestContext.SYSTEM_ACTOR)
                .containsEntry("parent_seq_id", null));
        assertThat(failures(job)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("item_key", keys.get(2)).containsEntry("kind", "FAILED")
                .containsEntry("attempt", 1).containsEntry("process_seq_id", null);
            assertThat((String) row.get("error")).contains("BusinessRuleViolationException");
        });
        assertThat(jobRuns(job)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("outcome", "PARTIAL").containsEntry("items_selected", 5)
            .containsEntry("items_succeeded", 4).containsEntry("items_failed", 1)
            .containsEntry("items_busy", 0).containsEntry("items_replayed", 0).containsEntry("items_deferred", 0)
            .containsEntry("items_more", false).containsEntry("process_seq_id", null));
    }

    // ================= 2. reruns =================

    @Test
    void rerunsOfAScheduledTimeReplayAndFailingItemsRecover() {
        String batch = batch();
        List<String> keys = items(batch, 3);
        execute("UPDATE it_job_item SET f_stay_due = true WHERE f_batch = ?", batch);
        execute("UPDATE it_job_item SET f_fail = true WHERE f_id = ?", keys.get(1));
        JobDefinition<ItemInput> job = ItJobItemFixtures.job(name(), batch);
        Instant at = at(2);

        assertThat(runner.execute(job, at).items()).isEqualTo(new ItemCounts(3, 2, 0, 0, 1, 0, false));
        // The same scheduled time again: done items replay, the failing one may be retried at once (first failure).
        assertThat(runner.execute(job, at).items()).isEqualTo(new ItemCounts(3, 0, 2, 0, 1, 0, false));
        assertThat(runs(keys)).containsExactly(1, 0, 1);
        // Twice in a row now: it waits a minute.
        assertThat(runner.execute(job, at).items()).isEqualTo(new ItemCounts(3, 0, 2, 0, 0, 1, false));

        execute("UPDATE it_job_item SET f_fail = false WHERE f_id = ?", keys.get(1));
        clock.advance(Duration.ofMinutes(1));
        JobRun recovered = runner.execute(job, at);
        assertThat(recovered.outcome()).isEqualTo(Outcome.SUCCEEDED);
        assertThat(recovered.items()).isEqualTo(new ItemCounts(3, 1, 2, 0, 0, 0, false));
        assertThat(runs(keys)).containsExactly(1, 1, 1);
        List<Map<String, Object>> rows = failures(job);
        assertThat(rows).extracting(row -> row.get("kind"), row -> row.get("attempt"))
            .containsExactly(org.assertj.core.groups.Tuple.tuple("FAILED", 1),
                org.assertj.core.groups.Tuple.tuple("FAILED", 2),
                org.assertj.core.groups.Tuple.tuple("RECOVERED", 0));
        assertThat(rows.get(2).get("process_seq_id")).isNotNull();

        // A failure after the recovery counts from one again: retried by the very next run.
        execute("UPDATE it_job_item SET f_fail = true WHERE f_id = ?", keys.get(1));
        Instant next = at.plus(Duration.ofMinutes(1));
        assertThat(runner.execute(job, next).items().failed()).isEqualTo(1);
        assertThat(failures(job).getLast()).containsEntry("attempt", 1);
        assertThat(runner.execute(job, next.plus(Duration.ofMinutes(1))).items().failed()).isEqualTo(1);
        assertThat(failures(job).getLast()).containsEntry("attempt", 2);
    }

    // ================= 3. backoff without starvation =================

    @Test
    void aFailingItemBacksOffWithoutStarvingTheOthers() {
        String batch = batch();
        List<String> keys = items(batch, 5);
        String poison = keys.getFirst();
        execute("UPDATE it_job_item SET f_fail = true WHERE f_id = ?", poison);
        JobDefinition<ItemInput> job = ItJobItemFixtures.job(name(), batch).maxItems(2);
        Instant base = at(3);
        clock.set(base);

        // Poison first in the order, maxItems 2: it fails, the next item goes through.
        assertThat(runner.execute(job, base).items()).isEqualTo(new ItemCounts(2, 1, 0, 0, 1, 0, true));
        assertThat(runner.execute(job, base.plusSeconds(60)).items()).isEqualTo(new ItemCounts(2, 1, 0, 0, 1, 0, true));
        // In backoff now: left out, and the selection widened past it, so two others run.
        assertThat(runner.execute(job, base.plusSeconds(120)).items())
            .isEqualTo(new ItemCounts(3, 2, 0, 0, 0, 1, false));
        assertThat(runs(keys)).containsExactly(0, 1, 1, 1, 1);

        // 1 minute after the second failure, then 2, then 4: each retry waits twice as long.
        Duration[] waits = {Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(4)};
        Instant time = base.plusSeconds(180);
        for (Duration wait : waits) {
            clock.advance(wait.minusSeconds(1));
            time = time.plusSeconds(60);
            assertThat(runner.execute(job, time).items().deferred()).as("still waiting " + wait).isEqualTo(1);
            clock.advance(Duration.ofSeconds(1));
            time = time.plusSeconds(60);
            assertThat(runner.execute(job, time).items().failed()).as("retried after " + wait).isEqualTo(1);
        }
        assertThat(failures(job)).extracting(row -> row.get("attempt")).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void retriesAreCappedAndNeverGivenUp() {
        String batch = batch();
        String key = items(batch, 1).getFirst();
        execute("UPDATE it_job_item SET f_fail = true WHERE f_id = ?", key);
        JobDefinition<ItemInput> job = ItJobItemFixtures.job(name(), batch)
            .itemRetry(Duration.ofSeconds(10), Duration.ofSeconds(30));
        Instant time = at(4);
        clock.set(time);
        for (int failure = 1; failure <= 6; failure++) {
            assertThat(runner.execute(job, time).items().failed()).as("failure " + failure).isEqualTo(1);
            time = time.plusSeconds(60);
            clock.advance(Duration.ofSeconds(30));
        }
        assertThat(failures(job)).extracting(row -> row.get("attempt")).containsExactly(1, 2, 3, 4, 5, 6);
    }

    // ================= 4. busy =================

    @Test
    void anItemWhoseLockIsHeldIsBusyAndRunsNextTime() throws Exception {
        String batch = batch();
        List<String> keys = items(batch, 3);
        String lock = "it-busy-" + UUID.randomUUID();
        execute("UPDATE it_job_item SET f_lock1 = ? WHERE f_id = ?", lock, keys.get(1));
        String held = gate();
        CompletableFuture<OnlineInput> holder = online(lock, null, held);
        awaitArrived(held);
        JobDefinition<ItemInput> job = ItJobItemFixtures.job(name(), batch).itemLockWait(Duration.ofMillis(300));

        long started = System.nanoTime();
        JobRun run = runner.execute(job, at(5));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).as("well within the global 10 s")
            .isLessThan(Duration.ofSeconds(5));
        assertThat(run.outcome()).isEqualTo(Outcome.SUCCEEDED);
        assertThat(run.items()).isEqualTo(new ItemCounts(3, 2, 0, 1, 0, 0, false));
        assertThat(failures(job)).isEmpty();
        assertThat(runs(keys)).containsExactly(1, 0, 1);

        open(held);
        holder.get(10, TimeUnit.SECONDS);
        assertThat(runner.execute(job, at(5).plusSeconds(60)).items())
            .isEqualTo(new ItemCounts(1, 1, 0, 0, 0, 0, false));
        assertThat(runs(keys)).containsExactly(1, 1, 1);
    }

    // ================= 5. no deadlock with requests =================

    /**
     * QuizBuks Q5 D-Q5-9: items take "publication then wallet"; a request holds publication B and wants wallet S.
     * Item 1 holds A and S, item 2 wants B. Each item in a transaction of its own: item 1 commits and lets S go, the
     * request goes on at once, then item 2 gets B.
     */
    @Test
    void itemsDoNotDeadlockWithRequests() throws Exception {
        String batch = batch();
        List<String> keys = items(batch, 2);
        String suffix = UUID.randomUUID().toString();
        String pubA = "pub-a-" + suffix;
        String pubB = "pub-b-" + suffix;
        String wallet = "wallet-" + suffix;
        String first = gate();
        String second = gate();
        execute("UPDATE it_job_item SET f_lock1 = ?, f_lock2 = ?, f_gate = ? WHERE f_id = ?", pubA, wallet, first,
            keys.get(0));
        execute("UPDATE it_job_item SET f_lock1 = ?, f_lock2 = ?, f_gate = ? WHERE f_id = ?", pubB, wallet, second,
            keys.get(1));
        JobDefinition<ItemInput> job = ItJobItemFixtures.job(name(), batch);

        CompletableFuture<JobRun> run = background(() -> runner.execute(job, at(6)));
        awaitArrived(first);
        CompletableFuture<OnlineInput> request = online(pubB, wallet, null);
        awaitWaiting(wallet, 1);

        open(first);
        // The request gets the wallet as soon as item 1 commits, while the job is still running.
        assertThat(request.get(10, TimeUnit.SECONDS)).isNotNull();
        awaitArrived(second);
        assertThat(run).isNotDone();
        open(second);
        JobRun done = run.get(20, TimeUnit.SECONDS);
        assertThat(done.items()).isEqualTo(new ItemCounts(2, 2, 0, 0, 0, 0, false));
        assertThat(runs(keys)).containsExactly(1, 1);
    }

    /** The same shape with the old batch (CallProcess.forEach, one transaction): the database finds a deadlock. */
    @Test
    void theOldBatchInOneTransactionDeadlocksWithRequests() throws Exception {
        String batch = batch();
        List<String> keys = items(batch, 2);
        String suffix = UUID.randomUUID().toString();
        String pubA = "pub-a-" + suffix;
        String pubB = "pub-b-" + suffix;
        String wallet = "wallet-" + suffix;
        String first = gate();
        execute("UPDATE it_job_item SET f_lock1 = ?, f_lock2 = ?, f_gate = ? WHERE f_id = ?", pubA, wallet, first,
            keys.get(0));
        execute("UPDATE it_job_item SET f_lock1 = ?, f_lock2 = ? WHERE f_id = ?", pubB, wallet, keys.get(1));

        CompletableFuture<BatchInput> whole = asRequest(RequestContext.system(Locale.ENGLISH, "it-batch"),
            executor.execute(ItJobItemFixtures.BATCH, new BatchInput(keys))).toFuture();
        awaitArrived(first);
        CompletableFuture<OnlineInput> request = online(pubB, wallet, null);
        awaitWaiting(wallet, 1);
        open(first);

        // Item 1's locks stay with the batch until it commits: it wants B, the request wants S. One of them dies.
        List<Throwable> errors = new ArrayList<>();
        for (CompletableFuture<?> future : List.of(whole, request)) {
            try {
                future.get(20, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                errors.add(e.getCause());
            }
        }
        assertThat(errors).singleElement().isInstanceOfSatisfying(LockNotAvailableException.class,
            error -> assertThat(error.deadlock()).isTrue());
        if (whole.isCompletedExceptionally()) {
            // The whole batch rolled back, item 1 included.
            assertThat(runs(keys)).containsExactly(0, 0);
        }
    }

    // ================= 6. parallelism and two instances =================

    @Test
    void itemsRunInParallelOnceEachAndTwoInstancesRunATimeOnce() throws Exception {
        String batch = batch();
        List<String> keys = items(batch, 200);
        JobDefinition<ItemInput> job = ItJobItemFixtures.job(name(), batch).parallelism(4);
        JobRunner instanceA = instance("instance-a", Duration.ofSeconds(30));
        JobRunner instanceB = instance("instance-b", Duration.ofSeconds(30));
        Instant at = at(7);

        List<JobRun> runs = together(() -> instanceA.execute(job, at), () -> instanceB.execute(job, at));

        assertThat(runs).extracting(JobRun::outcome).containsExactlyInAnyOrder(Outcome.SUCCEEDED, Outcome.LOCKED);
        assertThat(runs).filteredOn(run -> run.outcome() == Outcome.SUCCEEDED).singleElement()
            .satisfies(run -> assertThat(run.items()).isEqualTo(new ItemCounts(200, 200, 0, 0, 0, 0, false)));
        assertThat(runs(keys)).containsOnly(1);
        assertThat(query("SELECT count(*) AS n FROM op_process WHERE idempotency_key LIKE ?",
            "job:" + job.name() + ":%").getFirst().get("n")).isEqualTo(200L);

        // The lock gone (released by hand, as if expired), another instance runs the same time: all replay.
        expireLock(job);
        execute("UPDATE it_job_item SET f_due = true WHERE f_batch = ?", batch);
        JobRun again = instance("instance-c", Duration.ZERO).execute(job, at);
        assertThat(again.items()).isEqualTo(new ItemCounts(200, 0, 200, 0, 0, 0, false));
        assertThat(runs(keys)).containsOnly(1);
        assertThat(jobRuns(job)).hasSize(2);
    }

    /**
     * A lock that expires while its run goes on: a second instance runs the same time beside it. The first instance's
     * item 3 is held at a gate until the second waits for its idempotency key, so the runs really overlap.
     */
    @Test
    void concurrentInstancesAfterAnExpiredLockRunEachItemOnce() throws Exception {
        String batch = batch();
        List<String> keys = items(batch, 30);
        String held = gate();
        execute("UPDATE it_job_item SET f_stay_due = true WHERE f_batch = ?", batch);
        execute("UPDATE it_job_item SET f_gate = ? WHERE f_id = ?", held, keys.get(3));
        JobDefinition<ItemInput> job = ItJobItemFixtures.job(name(), batch).parallelism(2)
            .itemLockWait(Duration.ofSeconds(8));
        Instant at = at(8);

        CompletableFuture<JobRun> first = background(() -> instance("instance-a", Duration.ZERO).execute(job, at));
        awaitArrived(held);
        expireLock(job);
        CompletableFuture<JobRun> second = background(() -> instance("instance-b", Duration.ZERO).execute(job, at));
        // The second instance reaches item 3 and waits for the first one's uncommitted operation of it.
        for (int i = 0; i < 400 && waitingForTransactions() < 1; i++) {
            Thread.sleep(25);
        }
        assertThat(waitingForTransactions()).as("a session waiting for the other instance's item").isPositive();
        open(held);
        JobRun firstRun = first.get(60, TimeUnit.SECONDS);
        JobRun secondRun = second.get(60, TimeUnit.SECONDS);

        assertThat(List.of(firstRun.outcome(), secondRun.outcome())).containsOnly(Outcome.SUCCEEDED);
        assertThat(runs(keys)).containsOnly(1);
        assertThat(firstRun.items().succeeded() + secondRun.items().succeeded()).isEqualTo(30);
        assertThat(secondRun.items().replayed()).isPositive();
        assertThat(query("SELECT count(*) AS n FROM op_process WHERE idempotency_key LIKE ?",
            "job:" + job.name() + ":%").getFirst().get("n")).isEqualTo(30L);
    }

    // ================= 7. maxRunTime =================

    /** The first item is held past maxRunTime: once it ends, no other item starts. */
    @Test
    void afterMaxRunTimeNoItemStarts() throws Exception {
        String batch = batch();
        List<String> keys = items(batch, 7);
        String held = gate();
        execute("UPDATE it_job_item SET f_gate = ? WHERE f_id = ?", held, keys.getFirst());
        JobDefinition<ItemInput> job = ItJobItemFixtures.job(name(), batch).maxItems(6)
            .maxRunTime(Duration.ofSeconds(2));

        CompletableFuture<JobRun> running = background(() -> runner.execute(job, at(9)));
        awaitArrived(held);
        Thread.sleep(Duration.ofSeconds(2).toMillis());
        open(held);
        JobRun run = running.get(30, TimeUnit.SECONDS);

        assertThat(run.items()).isEqualTo(new ItemCounts(6, 1, 0, 0, 0, 5, true));
        assertThat(run.outcome()).isEqualTo(Outcome.SUCCEEDED);
        assertThat(runs(keys)).containsExactly(1, 0, 0, 0, 0, 0, 0);
    }

    // ================= review: busy only for lock failures and stale versions =================

    /**
     * 40P01 for one item: the item holds A and pauses, a request takes B and waits for A, then the item wants B. The
     * request waited first, past the deadlock check (1 s), so the database finds the cycle on the item's wait and
     * chooses it: the item is busy, not failed, and runs next time; the request goes through.
     */
    @Test
    void anItemChosenAsADeadlockVictimIsBusy() throws Exception {
        String batch = batch();
        List<String> keys = items(batch, 1);
        String suffix = UUID.randomUUID().toString();
        String a = "dl-a-" + suffix;
        String b = "dl-b-" + suffix;
        String pause = gate();
        execute("UPDATE it_job_item SET f_lock1 = ?, f_pause = ?, f_lock2 = ? WHERE f_id = ?", a, pause, b,
            keys.getFirst());
        JobDefinition<ItemInput> job = ItJobItemFixtures.job(name(), batch).itemLockWait(Duration.ofSeconds(8));

        CompletableFuture<JobRun> run = background(() -> runner.execute(job, at(12)));
        awaitArrived(pause);
        CompletableFuture<OnlineInput> request = online(b, a, null);
        awaitWaiting(a, 1);
        Thread.sleep(1500);
        open(pause);

        JobRun done = run.get(30, TimeUnit.SECONDS);
        assertThat(done.outcome()).isEqualTo(Outcome.SUCCEEDED);
        assertThat(done.items()).isEqualTo(new ItemCounts(1, 0, 0, 1, 0, 0, false));
        assertThat(request.get(10, TimeUnit.SECONDS)).isNotNull();
        assertThat(failures(job)).isEmpty();
        assertThat(runs(keys)).containsExactly(0);
        execute("UPDATE it_job_item SET f_pause = NULL WHERE f_id = ?", keys.getFirst());
        assertThat(runner.execute(job, at(12).plusSeconds(60)).items().succeeded()).isEqualTo(1);
    }

    /** Someone changed the item between its reading and its writing: a stale version is busy. */
    @Test
    void aStaleVersionIsBusy() throws Exception {
        String batch = batch();
        List<String> keys = items(batch, 1);
        String held = gate();
        execute("UPDATE it_job_item SET f_gate = ? WHERE f_id = ?", held, keys.getFirst());
        JobDefinition<ItemInput> job = ItJobItemFixtures.job(name(), batch);

        CompletableFuture<JobRun> run = background(() -> runner.execute(job, at(13)));
        awaitArrived(held);
        execute("UPDATE it_job_item SET f_version = f_version + 1, f_gate = NULL WHERE f_id = ?", keys.getFirst());
        open(held);

        assertThat(run.get(30, TimeUnit.SECONDS).items()).isEqualTo(new ItemCounts(1, 0, 0, 1, 0, 0, false));
        assertThat(failures(job)).isEmpty();
        assertThat(runner.execute(job, at(13).plusSeconds(60)).items().succeeded()).isEqualTo(1);
    }

    /** A conflict that waiting does not resolve (the row exists already) is a failure, not busy. */
    @Test
    void aRowThatExistsAlreadyIsAFailure() {
        String batch = batch();
        List<String> keys = items(batch, 2);
        String existing = "it-exists-" + UUID.randomUUID();
        execute("INSERT INTO it_event_log (f_id, f_source, f_consumer) VALUES (?, 'test', 'test')", existing);
        execute("UPDATE it_job_item SET f_insert = ? WHERE f_id = ?", existing, keys.getFirst());
        JobDefinition<ItemInput> job = ItJobItemFixtures.job(name(), batch);

        JobRun run = runner.execute(job, at(14));

        assertThat(run.outcome()).isEqualTo(Outcome.PARTIAL);
        assertThat(run.items()).isEqualTo(new ItemCounts(2, 1, 0, 0, 1, 0, false));
        assertThat(failures(job)).singleElement().satisfies(row ->
            assertThat((String) row.get("error")).contains("ConcurrentUpdateException", "conflicts with existing"));
    }

    // ================= review: keys, backoff cap, stopping =================

    /** No selected item has a usable key: each is a failed item, the run is partial (no failure rows to key). */
    @Test
    void itemsWithoutUsableKeysFail() {
        String batch = batch();
        execute("INSERT INTO it_job_item (f_id, f_batch, f_seq) VALUES (?, ?, 0)", "bad key " + batch.substring(0, 8),
            batch);
        execute("INSERT INTO it_job_item (f_id, f_batch, f_seq) VALUES (?, ?, 1)",
            "also bad/" + batch.substring(0, 8), batch);
        JobDefinition<ItemInput> job = ItJobItemFixtures.job(name(), batch);

        JobRun run = runner.execute(job, at(15));

        assertThat(run.outcome()).isEqualTo(Outcome.PARTIAL);
        assertThat(run.items()).isEqualTo(new ItemCounts(2, 0, 0, 0, 2, 0, false));
        assertThat(failures(job)).isEmpty();
    }

    /**
     * More items in backoff than widen the selection (the cap lowered to 3 here; 1000 by default): each selected
     * item's own backoff still holds, none of them is retried early.
     */
    @Test
    void itemsInBackoffBeyondTheCapAreNotRetriedEarly() {
        String batch = batch();
        List<String> keys = items(batch, 8);
        for (String key : keys.subList(0, 6)) {
            execute("UPDATE it_job_item SET f_fail = true WHERE f_id = ?", key);
        }
        JobDefinition<ItemInput> job = ItJobItemFixtures.job(name(), batch).maxItems(20);
        Instant base = at(16);
        clock.set(base);
        JobRunner capped = new JobRunner(new R2dbcLockProvider(pool(), JobConfig.LOCK_TABLE), executor, storages, ids,
            clock, "default", "instance-capped", Duration.ZERO, PlatformObservations.NOOP, templates, queries, 3);
        // Twice in a row: all six wait a minute now.
        assertThat(capped.execute(job, base).items()).isEqualTo(new ItemCounts(8, 2, 0, 0, 6, 0, false));
        assertThat(capped.execute(job, base.plusSeconds(60)).items())
            .isEqualTo(new ItemCounts(6, 0, 0, 0, 6, 0, false));

        // Six in backoff, three of them known to widen the selection of two: all six are left out all the same.
        JobRun third = capped.execute(job.maxItems(2), base.plusSeconds(120));
        assertThat(third.items().deferred()).isEqualTo(6);
        assertThat(third.items().failed()).isZero();
        assertThat(failures(job)).hasSize(12);
    }

    /** Once the application stops, an item cut short is deferred, not failed, and no new item starts. */
    @Test
    void stoppingDefersWhatIsLeft() throws Exception {
        String batch = batch();
        List<String> keys = items(batch, 3);
        String held = gate();
        execute("UPDATE it_job_item SET f_gate = ?, f_fail = true WHERE f_id = ?", held, keys.getFirst());
        JobDefinition<ItemInput> job = ItJobItemFixtures.job(name(), batch);
        JobRunner stopping = instance("instance-stopping", Duration.ZERO);

        CompletableFuture<JobRun> run = background(() -> stopping.execute(job, at(17)));
        awaitArrived(held);
        stopping.stop();
        open(held);

        assertThat(run.get(30, TimeUnit.SECONDS).items()).isEqualTo(new ItemCounts(3, 0, 0, 0, 0, 3, false));
        assertThat(failures(job)).isEmpty();
        assertThat(runs(keys)).containsExactly(0, 0, 0);
    }

    // ================= 8, 9: system actor, invalid selections, append-only =================

    @Test
    void aFailingSelectionFailsTheRunAndProcessesNothing() {
        JobDefinition<ItemInput> job = JobDefinition.forEach(name(), "0 * * * * *", java.time.ZoneOffset.UTC,
            com.jabiz.job.JobItems.template(ItJobItemFixtures.TEMPLATE, "itemId"), ItJobItemFixtures.ITEM_RUN,
            (at, row) -> new ItemInput((String) row.get("itemId")));

        JobRun run = runner.execute(job, at(10));

        assertThat(run.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(run.items()).isEqualTo(new ItemCounts(0, 0, 0, 0, 0, 0, false));
        assertThat(jobRuns(job)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("outcome", "FAILED").containsEntry("items_selected", 0);
            assertThat((String) row.get("error")).isNotBlank();
        });
    }

    @Test
    void itemRecordsAreAppendOnly() {
        SqlStatementLog.STATEMENTS.clear();
        String batch = batch();
        List<String> keys = items(batch, 2);
        execute("UPDATE it_job_item SET f_fail = true WHERE f_id = ?", keys.get(0));
        JobDefinition<ItemInput> job = ItJobItemFixtures.job(name(), batch);
        clock.set(at(11));
        runner.execute(job, at(11));
        execute("UPDATE it_job_item SET f_fail = false WHERE f_id = ?", keys.get(0));
        runner.execute(job, at(11).plusSeconds(60));
        assertThat(failures(job)).extracting(row -> row.get("kind")).containsExactly("FAILED", "RECOVERED");

        assertThat(SqlStatementLog.STATEMENTS).noneMatch(sql -> sql.matches(
            "(?is).*(UPDATE|DELETE FROM)\\s+(sys_job_run|sys_job_item_failure)\\b.*"));
        assertThatThrownBy(() -> execute("UPDATE sys_job_item_failure SET attempt = 0 WHERE job_name = ?",
            job.name())).hasMessageContaining("append-only");
        assertThatThrownBy(() -> execute("DELETE FROM sys_job_item_failure WHERE job_name = ?", job.name()))
            .hasMessageContaining("append-only");
        assertThatThrownBy(() -> execute("UPDATE sys_job_run SET items_failed = 0 WHERE job_name = ?", job.name()))
            .hasMessageContaining("append-only");

        // The integrity seals (21 section 2) take the new table and the runs with their added columns, and verify.
        RequestContext system = RequestContext.system(Locale.ENGLISH, "it-seal");
        IntegrityProcesses.SealOutput sealed = asRequest(system, executor.execute(IntegrityProcesses.SEAL_PROCESS,
            new IntegrityProcesses.SealInput(null))).block();
        assertThat(sealed).isNotNull();
        assertThat(query("SELECT count(*) AS n FROM sys_integrity_item WHERE table_name IN ('sys_job_run',"
            + " 'sys_job_item_failure') AND seal_no = ?", sealed.sealNo()).getFirst().get("n")).isNotEqualTo(0L);
        IntegrityProcesses.VerifyOutput verified = asRequest(system, executor.execute(
            IntegrityProcesses.VERIFY_PROCESS, new IntegrityProcesses.VerifyInput(sealed.sealNo(), null))).block();
        assertThat(verified).isNotNull();
        assertThat(verified.intact()).isTrue();
    }

    // ================= helpers =================

    /** As if the run's cluster lock had expired. */
    private static void expireLock(JobDefinition<?> job) {
        execute("UPDATE " + JobConfig.LOCK_TABLE + " SET lock_until = now() - interval '1 hour' WHERE name = ?",
            job.name());
    }

    private static String batch() {
        return UUID.randomUUID().toString();
    }

    private static String name() {
        return "it.items-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String gate() {
        String gate = "gate-" + UUID.randomUUID();
        ItJobItemFixtures.GATES.put(gate, new CountDownLatch(1));
        return gate;
    }

    private static void open(String gate) {
        ItJobItemFixtures.GATES.get(gate).countDown();
    }

    private static Instant at(int hour) {
        return START.plus(Duration.ofDays(30)).plus(Duration.ofHours(hour));
    }

    /** Inserts {@code count} due items of the batch, in order; returns their keys. */
    private static List<String> items(String batch, int count) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String key = UUID.randomUUID().toString();
            execute("INSERT INTO it_job_item (f_id, f_batch, f_seq) VALUES (?, ?, ?)", key, batch, i);
            keys.add(key);
        }
        return keys;
    }

    /** The committed runs of each item, in the order of the keys. */
    private static List<Integer> runs(List<String> keys) {
        List<Integer> runs = new ArrayList<>();
        for (String key : keys) {
            runs.add(((Number) query("SELECT f_runs FROM it_job_item WHERE f_id = ?", key).getFirst().get("f_runs"))
                .intValue());
        }
        return runs;
    }

    private static List<Map<String, Object>> failures(JobDefinition<?> job) {
        return query("SELECT * FROM sys_job_item_failure WHERE job_name = ? ORDER BY entry_no", job.name());
    }

    private static List<Map<String, Object>> jobRuns(JobDefinition<?> job) {
        return query("SELECT * FROM sys_job_run WHERE job_name = ? ORDER BY finished_time", job.name());
    }

    private CompletableFuture<OnlineInput> online(String lock1, String lock2, String gate) {
        return asTestRequest(executor.execute(ItJobItemFixtures.ONLINE, new OnlineInput(lock1, lock2, gate)))
            .toFuture();
    }

    private <T> CompletableFuture<T> background(Callable<T> work) {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        threads.add(pool);
        return CompletableFuture.supplyAsync(() -> {
            try {
                return work.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, pool);
    }

    private static void awaitArrived(String gate) throws InterruptedException {
        for (int i = 0; i < 400 && ItJobItemFixtures.arrived(gate) < 1; i++) {
            Thread.sleep(25);
        }
        assertThat(ItJobItemFixtures.arrived(gate)).as("arrived at " + gate).isEqualTo(1);
    }

    /** Waits until {@code count} sessions wait for an advisory lock. */
    private static void awaitWaiting(String name, int count) throws InterruptedException {
        for (int i = 0; i < 400 && waiting(name) < count; i++) {
            Thread.sleep(25);
        }
        assertThat(waiting(name)).as("sessions waiting for a lock").isEqualTo(count);
    }

    /** Sessions waiting for another transaction to end (an idempotency key taken but not committed yet). */
    private static int waitingForTransactions() {
        return ((Number) query("SELECT count(*) AS n FROM pg_locks WHERE locktype = 'transactionid' AND NOT granted")
            .getFirst().get("n")).intValue();
    }

    private static int waiting(String name) {
        return ((Number) query("SELECT count(*) AS n FROM pg_locks WHERE locktype = 'advisory' AND NOT granted"
            // Only this lock: test classes in other forks share the database server and its pg_locks.
            + " AND ((classid::bigint << 32) | objid::bigint) = hashtextextended(?, 0)", "jabiz.app-lock:" + name)
            .getFirst().get("n")).intValue();
    }

    /** One application instance's runner: its own pool and lock provider over the shared schema. */
    private JobRunner instance(String instanceId, Duration lockAtLeastFor) {
        return new JobRunner(new R2dbcLockProvider(pool(), JobConfig.LOCK_TABLE), executor, storages, ids, clock,
            "default", instanceId, lockAtLeastFor, PlatformObservations.NOOP, templates, queries, 1000);
    }

    /** A connection pool of its own over the shared schema, for an instance's cluster locks. */
    private ConnectionPool pool() {
        ConnectionFactoryOptions options = ConnectionFactoryOptions.parse(DB.r2dbcUrl() + "?schema=" + schema())
            .mutate()
            .option(ConnectionFactoryOptions.USER, DB.username())
            .option(ConnectionFactoryOptions.PASSWORD, DB.password())
            .build();
        ConnectionPool pool = new ConnectionPool(ConnectionPoolConfiguration.builder(ConnectionFactories.get(options))
            .maxSize(4).build());
        pools.add(pool);
        return pool;
    }

    @SafeVarargs
    private <T> List<T> together(Callable<T>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        threads.add(pool);
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                    return task.call();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }, pool));
        }
        start.countDown();
        List<T> results = new ArrayList<>();
        for (CompletableFuture<T> future : futures) {
            results.add(future.get(60, TimeUnit.SECONDS));
        }
        return results;
    }
}
