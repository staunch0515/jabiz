package com.jabiz.app.it.process;

import com.jabiz.app.it.fixture.ItAdmissionFixtures;
import com.jabiz.app.it.fixture.ItAdmissionFixtures.HotInput;
import com.jabiz.app.it.fixture.ItAdmissionFixtures.HotOutput;
import com.jabiz.app.it.fixture.ItAdmissionFixtures.Mode;
import com.jabiz.app.it.fixture.ItLockFixtures;
import com.jabiz.app.it.fixture.ItLockFixtures.LockInput;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.job.JobDefinition;
import com.jabiz.job.JobItems;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.ConcurrentUpdateException;
import com.jabiz.runtime.ProcessBusyException;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.event.OutboxDeliverer;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.job.JobRunner.ItemCounts;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.operation.OperationRecorder;
import com.jabiz.runtime.process.ChangeSetCommitter;
import com.jabiz.runtime.process.ExecutionOptions;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.ProcessRegistry;
import com.jabiz.runtime.process.ProcessResult;
import com.jabiz.runtime.process.ProcessSequence;
import com.jabiz.runtime.process.admission.AdmissionSettings;
import com.jabiz.runtime.process.admission.ProcessAdmission;
import com.jabiz.runtime.process.admission.ProcessAdmissionChecks;
import com.jabiz.runtime.security.SensitiveDataMasker;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.web.ProcessRateLimitProperties;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Wrapped;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The queue before a hot named lock (decision D45) on a real database with a small pool (6 connections, the
 * dictionary listener off so that the test counts them all): runs of one key wait in the application, not on
 * connections, so the rest of the application keeps its connections; a full queue or a long wait ends in 503
 * {@code PROCESS_BUSY} at once or soon after; runs go in order of arrival; two instances queue apart and the lock alone
 * keeps the result right; every outcome gives the permit back; replays, jobs and events behave as before; the process
 * API's rate limits come before any read. Waiting for locks is seen in {@code pg_locks}, connections in the pool's
 * metrics, not guessed from times. {@link ProcessAdmissionControlIT} is the same load without the queue.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "spring.r2dbc.pool.max-size=6", "spring.r2dbc.pool.initial-size=1", "jabiz.dictionary.listen.enabled=false",
    "jabiz.jobs.lock-at-least-for=0s", "jabiz.events.delivery.initial-backoff=5s",
    "jabiz.events.delivery.max-attempts=2",
    "jabiz.process.admission.families[test.hot].max-queue=100",
    "jabiz.process.admission.families[test.hot].max-wait=20s",
    "jabiz.process.admission.families[test.small].concurrency=1",
    "jabiz.process.admission.families[test.small].max-queue=5",
    "jabiz.process.admission.families[test.small].max-wait=500ms",
    "jabiz.process.admission.families[test.small].retry-after=2s",
    "jabiz.process.admission.families[test.one].concurrency=1",
    "jabiz.process.admission.families[test.one].max-wait=20s",
    "jabiz.process.rate-limits.[IT_PLAIN].per-user=3",
    "jabiz.process.rate-limits.[IT_PLAIN].per-address=5",
    "jabiz.process.rate-limits.[IT_HOT_PUBLISH].per-instance=2"})
@ActiveProfiles("dev")
class ProcessAdmissionIT extends PostgresIntegrationTest {

    static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    /** The max-wait of {@value ItAdmissionFixtures#FAMILY} here: what a run that queued for it could wait. */
    static final Duration HOT_MAX_WAIT = Duration.ofSeconds(20);

    @Autowired
    ProcessExecutor executor;

    @Autowired
    ProcessAdmission admission;

    @Autowired
    ApplicationContext context;

    @Autowired
    ConnectionFactory connections;

    @Autowired
    JobRunner runner;

    @Autowired
    OutboxDeliverer deliverer;

    @Autowired
    ProcessRegistry registry;

    @Autowired
    AdmissionSettings settings;

    WebTestClient client;

    private final List<ExecutorService> threads = new ArrayList<>();
    private final List<Connection> held = new ArrayList<>();

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).configureClient()
            .responseTimeout(Duration.ofSeconds(60)).build();
    }

    @AfterEach
    void cleanUp() {
        releaseConnections();
        threads.forEach(ExecutorService::shutdownNow);
        threads.clear();
        ItAdmissionFixtures.GATES.values().forEach(CountDownLatch::countDown);
        ItAdmissionFixtures.reset();
    }

    // ================= 1. the pool is not exhausted =================

    @Test
    void runsWaitingForAHotKeyHoldNoConnectionsAndOthersAreServedMeanwhile() throws Exception {
        String owner = owner();
        // Warm: the first decoding of a request body builds Jackson's deserializer under a lock.
        post("/api/queries/it.slow_titles", Map.of("params", Map.of("owner", owner, "pause", 0)))
            .expectStatus().isOk();
        String key = unique();
        String gate = close();
        CompletableFuture<HotOutput> holder = start(ItAdmissionFixtures.HOT, key, gate, "holder");
        awaitArrived(gate, 1);
        List<CompletableFuture<HotOutput>> crowd = IntStream.range(0, 60)
            .mapToObj(i -> start(ItAdmissionFixtures.HOT, key, null, "r" + i)).toList();
        // concurrency 2: the holder and one more in their transaction; the latter waits for the lock, the rest here.
        awaitWaitingFor(ItAdmissionFixtures.FAMILY, key, 1);
        await(() -> admission.waiting() == 59, "59 runs queued in the application");
        Thread.sleep(200);
        assertThat(waitingFor(ItAdmissionFixtures.FAMILY, key)).as("sessions waiting for the lock").isEqualTo(1);
        assertThat(pool().getMetrics().orElseThrow().acquiredSize()).as("connections borrowed").isLessThanOrEqualTo(2);

        // Meanwhile the rest of the application has its connections.
        List<CompletableFuture<Long>> reads = IntStream.range(0, 20).mapToObj(i -> background(() -> {
            long started = System.nanoTime();
            post("/api/queries/it.slow_titles", Map.of("params", Map.of("owner", owner, "pause", 0)))
                .expectStatus().isOk();
            return System.nanoTime() - started;
        })).toList();
        long started = System.nanoTime();
        assertThat(asTestRequest(executor.execute(ItAdmissionFixtures.PLAIN, new HotInput(null, null, Mode.OK,
            "other"))).block(Duration.ofSeconds(5))).isEqualTo(new HotOutput("other"));
        // Not behind the hot key: well within its max-wait (and an exhausted pool would have answered 503).
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(HOT_MAX_WAIT.dividedBy(4));
        for (CompletableFuture<Long> read : reads) {
            assertThat(Duration.ofNanos(read.get(10, TimeUnit.SECONDS))).as("a template read")
                .isLessThan(HOT_MAX_WAIT.dividedBy(4));
        }

        open(gate);
        assertThat(holder.get(30, TimeUnit.SECONDS).tag()).isEqualTo("holder");
        for (CompletableFuture<HotOutput> run : crowd) {
            assertThat(run.get(30, TimeUnit.SECONDS)).isNotNull();
        }
        awaitIdle();
    }

    // ================= 2. failing fast =================

    @Test
    void aFullQueueIsRefusedAtOnceAndALongWaitSoonAfter() throws Exception {
        String key = unique();
        String gate = close();
        CompletableFuture<HotOutput> holder = start(ItAdmissionFixtures.SMALL, key, gate, "holder");
        awaitArrived(gate, 1);
        // concurrency 1, max-queue 5, max-wait 500 ms.
        List<CompletableFuture<EntityExchangeResult<Map<String, Object>>>> queued = new ArrayList<>();
        List<Long> starts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            starts.add(System.nanoTime());
            queued.add(background(() -> runOverHttp(ItAdmissionFixtures.SMALL_PROCESS, key)));
        }
        await(() -> admission.waiting() == 5, "5 runs queued");

        long started = System.nanoTime();
        EntityExchangeResult<Map<String, Object>> full = runOverHttp(ItAdmissionFixtures.SMALL_PROCESS, key);
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        assertThat(full.getStatus().value()).isEqualTo(503);
        assertThat(took).as("refused at once").isLessThan(Duration.ofSeconds(1));
        assertBusy(full, "QUEUE_FULL");
        // No holding time yet: the floor of retry-after.
        assertThat(full.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");

        for (int i = 0; i < queued.size(); i++) {
            EntityExchangeResult<Map<String, Object>> waited = queued.get(i).get(10, TimeUnit.SECONDS);
            Duration waitedFor = Duration.ofNanos(System.nanoTime() - starts.get(i));
            assertThat(waited.getStatus().value()).isEqualTo(503);
            assertBusy(waited, "WAIT_TIMEOUT");
            assertThat(waitedFor).isBetween(Duration.ofMillis(450), Duration.ofMillis(3000));
        }
        open(gate);
        holder.get(10, TimeUnit.SECONDS);
        awaitIdle();
    }

    private static void assertBusy(EntityExchangeResult<Map<String, Object>> response, String reason) {
        Map<String, Object> body = response.getResponseBody();
        assertThat(body).containsEntry("reason", reason).containsKey("retryAfter");
        assertThat(body.toString()).contains(PlatformErrorCodes.PROCESS_BUSY).doesNotContain("QUERY_TIMEOUT")
            .contains("Too many people are doing this right now");
        assertThat(response.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
    }

    // ================= 3. first come, first served =================

    @Test
    void runsOfAKeyArePermittedInOrderOfArrival() throws Exception {
        // One at a time per key: the order of the permits is the order of the lock.
        String key = unique();
        String gate = close();
        CompletableFuture<HotOutput> holder = start(ItAdmissionFixtures.ONE, key, gate, "holder");
        awaitArrived(gate, 1);
        List<CompletableFuture<HotOutput>> runs = new ArrayList<>();
        List<String> expected = new ArrayList<>(List.of("holder"));
        for (int i = 0; i < 8; i++) {
            runs.add(start(ItAdmissionFixtures.ONE, key, null, "r" + i));
            expected.add("r" + i);
            int queued = i + 1;
            await(() -> admission.waiting() == queued, queued + " queued");
        }
        assertThat(waitingFor(ItAdmissionFixtures.ONE_FAMILY, key)).as("nobody waits for the lock").isZero();
        open(gate);
        holder.get(10, TimeUnit.SECONDS);
        for (CompletableFuture<HotOutput> run : runs) {
            run.get(10, TimeUnit.SECONDS);
        }
        assertThat(ItAdmissionFixtures.order()).containsExactlyElementsOf(expected);
    }

    // ================= 4. two instances =================

    @Autowired
    ProcessSequence sequence;
    @Autowired
    OperationRecorder operations;
    @Autowired
    ChangeSetCommitter committer;
    @Autowired
    StorageAdapterRegistry storages;
    @Autowired
    JsonMapper json;
    @Autowired
    SensitiveDataMasker masker;
    @Autowired
    ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meters;

    /** Another instance's executor: its own queue over the same database. */
    private ProcessExecutor otherInstance() {
        ProcessAdmission own = new ProcessAdmission(settings, 6, PlatformObservations.NOOP, meters);
        return new ProcessExecutor(context, sequence, operations, committer, storages, json, masker, "default",
            PlatformObservations.NOOP, Duration.ofSeconds(30), own);
    }

    @Test
    void twoInstancesQueueApartAndTheLockAloneKeepsTheCountRight() throws Exception {
        ProcessExecutor other = otherInstance();
        String key = unique();
        AtomicInteger mostWaiting = new AtomicInteger();
        AtomicBoolean done = new AtomicBoolean();
        CompletableFuture<Void> watcher = background(() -> {
            while (!done.get()) {
                mostWaiting.accumulateAndGet(waitingFor(ItAdmissionFixtures.FAMILY, key), Math::max);
                Thread.sleep(5);
            }
            return null;
        });
        List<CompletableFuture<HotOutput>> runs = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            runs.add(count(executor, ItAdmissionFixtures.HOT, key));
            runs.add(count(other, ItAdmissionFixtures.HOT, key));
        }
        for (CompletableFuture<HotOutput> run : runs) {
            run.get(60, TimeUnit.SECONDS);
        }
        done.set(true);
        watcher.get(10, TimeUnit.SECONDS);
        assertThat(counter(key)).isEqualTo(200);
        assertThat(mostWaiting.get()).as("sessions waiting for the lock: 2 x concurrency - 1 at most")
            .isLessThanOrEqualTo(3);

        // The other instance computes no key (a wrong one does no better): its runs do not queue, and still the lock
        // keeps every update.
        String second = unique();
        ProcessDefinition<HotInput, HotOutput, ProcessContext> unqueued =
            ItAdmissionFixtures.HOT.withAdmission(ItAdmissionFixtures.FAMILY, input -> null);
        List<CompletableFuture<HotOutput>> mixed = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            mixed.add(count(executor, ItAdmissionFixtures.HOT, second));
            mixed.add(count(other, unqueued, second));
        }
        for (CompletableFuture<HotOutput> run : mixed) {
            run.get(60, TimeUnit.SECONDS);
        }
        assertThat(counter(second)).isEqualTo(200);
        awaitIdle();
    }

    private CompletableFuture<HotOutput> count(ProcessExecutor on,
        ProcessDefinition<HotInput, HotOutput, ProcessContext> process, String key) {
        return asTestRequest(on.execute(process, new HotInput(key, null, Mode.COUNT, "c"))).toFuture();
    }

    private static long counter(String key) {
        return ((Number) query("SELECT n FROM it_admission_counter WHERE k = ?", key).getFirst().get("n"))
            .longValue();
    }

    // ================= 5. the permit goes back whatever happens =================

    @Test
    void thePermitGoesBackAfterARejectionAFailureAConflictACancellationAndADryRun() throws Exception {
        for (Mode mode : List.of(Mode.REJECT, Mode.FAIL)) {
            String key = unique();
            String gate = close();
            CompletableFuture<HotOutput> failing = start(ItAdmissionFixtures.SMALL, key, gate, mode, "failing");
            awaitArrived(gate, 1);
            CompletableFuture<HotOutput> next = start(ItAdmissionFixtures.HOT, key, null, "next");
            open(gate);
            ExecutionException error = catchThrowableOfType(ExecutionException.class,
                () -> failing.get(10, TimeUnit.SECONDS));
            assertThat(error.getCause()).isInstanceOf(mode == Mode.REJECT ? BusinessRuleViolationException.class
                : IllegalStateException.class);
            assertThat(next.get(10, TimeUnit.SECONDS).tag()).isEqualTo("next");
            awaitIdle();
        }

        // 409: the lock is held by a run that does not queue, and the wait is short.
        String key = unique();
        CountDownLatch lockHeld = new CountDownLatch(1);
        String lockGate = "lock-" + UUID.randomUUID();
        ItLockFixtures.GATES.put(lockGate, lockHeld);
        CompletableFuture<?> stand = asTestRequest(executor.execute(ItLockFixtures.LOCKED, new LockInput(
            ItAdmissionFixtures.lockName(ItAdmissionFixtures.FAMILY, key), false, lockGate, false))).toFuture();
        await(() -> ItLockFixtures.arrived(lockGate) == 1, "the stand-in holds the lock");
        ExecutionException conflict = catchThrowableOfType(ExecutionException.class, () -> asTestRequest(
            executor.run(ItAdmissionFixtures.HOT, new HotInput(key, null, Mode.OK, "late"),
                ExecutionOptions.NONE.withLockWait(Duration.ofMillis(300)))).toFuture().get(10, TimeUnit.SECONDS));
        assertThat(conflict.getCause()).isInstanceOf(ConcurrentUpdateException.class);
        awaitIdle();
        lockHeld.countDown();
        stand.get(10, TimeUnit.SECONDS);

        // Cancelled while queued, and while holding the permit.
        String cancelled = unique();
        String gate = close();
        Disposable holding = asTestRequest(executor.execute(ItAdmissionFixtures.SMALL,
            new HotInput(cancelled, gate, Mode.OK, "holding"))).subscribe(out -> { }, error -> { });
        awaitArrived(gate, 1);
        Disposable queued = asTestRequest(executor.execute(ItAdmissionFixtures.SMALL,
            new HotInput(cancelled, null, Mode.OK, "queued"))).subscribe(out -> { }, error -> { });
        await(() -> admission.waiting() == 1, "one queued");
        queued.dispose();
        await(() -> admission.waiting() == 0, "the queued run left");
        // The holder's caller goes away: the run goes on (its transaction holds a connection until it ends), and so does
        // its permit, until it has ended.
        holding.dispose();
        Thread.sleep(200);
        assertThat(admission.active()).as("the permit of a run still in its transaction").isEqualTo(1);
        open(gate);
        awaitIdle();

        // A dry run rolls back and gives it back too.
        String dry = unique();
        assertThat(asTestRequest(executor.run(ItAdmissionFixtures.HOT, new HotInput(dry, null, Mode.COUNT, "dry"),
            ExecutionOptions.DRY_RUN)).block(Duration.ofSeconds(10)).output().tag()).isEqualTo("dry");
        assertThat(query("SELECT * FROM it_admission_counter WHERE k = ?", dry)).isEmpty();
        awaitIdle();
    }

    // ================= 5b. callers that go away do not take connections =================

    @Test
    void callersGoingAwayLeaveTheConnectionsBoundedByThePermits() throws Exception {
        int maxActive = 3;
        // One key: a holder parked on its gate holds the lock; each round, three runs come and their callers give up
        // while one waits for the lock and the others queue.
        String key = unique();
        String gate = close();
        CompletableFuture<HotOutput> holder = start(ItAdmissionFixtures.HOT, key, gate, "holder");
        awaitArrived(gate, 1);
        AtomicInteger mostConnections = new AtomicInteger();
        AtomicInteger mostWaiting = new AtomicInteger();
        AtomicBoolean done = new AtomicBoolean();
        CompletableFuture<Void> watcher = background(() -> {
            while (!done.get()) {
                mostConnections.accumulateAndGet(pool().getMetrics().orElseThrow().acquiredSize(), Math::max);
                mostWaiting.accumulateAndGet(waitingFor(ItAdmissionFixtures.FAMILY, key), Math::max);
                Thread.sleep(5);
            }
            return null;
        });
        for (int round = 0; round < 5; round++) {
            List<Disposable> callers = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                callers.add(asTestRequest(executor.execute(ItAdmissionFixtures.HOT,
                    new HotInput(key, null, Mode.OK, "gone" + round + i))).subscribe(out -> { }, error -> { }));
            }
            Thread.sleep(100);
            callers.forEach(Disposable::dispose);
        }
        Thread.sleep(300);
        assertThat(waitingFor(ItAdmissionFixtures.FAMILY, key)).as("sessions waiting for the lock")
            .isLessThanOrEqualTo(1);
        // Many keys: runs parked on their gates fill max-active; their callers go away, more come and go.
        List<String> gates = new ArrayList<>();
        for (int round = 0; round < 3; round++) {
            List<Disposable> callers = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                String parked = close();
                gates.add(parked);
                callers.add(asTestRequest(executor.execute(ItAdmissionFixtures.HOT,
                    new HotInput(unique(), parked, Mode.OK, "parked" + round + i))).subscribe(out -> { }, e -> { }));
            }
            Thread.sleep(100);
            callers.forEach(Disposable::dispose);
        }
        Thread.sleep(300);
        assertThat(admission.active()).as("permits held").isEqualTo(maxActive);
        // A read meanwhile still has a connection.
        post("/api/queries/it.slow_titles", Map.of("params", Map.of("owner", owner(), "pause", 0)))
            .expectStatus().isOk();
        done.set(true);
        watcher.get(10, TimeUnit.SECONDS);
        assertThat(mostConnections.get()).as("connections: the permits, and the read")
            .isLessThanOrEqualTo(maxActive + 1);
        assertThat(mostWaiting.get()).as("sessions waiting for the lock, ever").isLessThanOrEqualTo(1);

        gates.forEach(ProcessAdmissionIT::open);
        open(gate);
        holder.get(30, TimeUnit.SECONDS);
        awaitIdle();
        assertThat(pool().getMetrics().orElseThrow().acquiredSize()).isZero();
    }

    // ================= 7. replays do not queue =================

    @Test
    void aReplayIsAnsweredEvenWhenTheQueueIsFull() throws Exception {
        String key = unique();
        String idempotencyKey = "admission-" + UUID.randomUUID();
        ProcessResult<HotOutput> first = asTestRequest(executor.run(ItAdmissionFixtures.HOT,
            new HotInput(key, null, Mode.COUNT, "first"), ExecutionOptions.idempotent(idempotencyKey)))
            .block(Duration.ofSeconds(10));

        String gate = close();
        CompletableFuture<HotOutput> holder = start(ItAdmissionFixtures.HOT, key, gate, "holder");
        awaitArrived(gate, 1);
        List<CompletableFuture<HotOutput>> crowd = IntStream.range(0, 101)
            .mapToObj(i -> start(ItAdmissionFixtures.HOT, key, null, "q" + i)).toList();
        await(() -> admission.waiting() == 100, "the queue is full");
        ExecutionException full = catchThrowableOfType(ExecutionException.class, () -> start(ItAdmissionFixtures.HOT,
            key, null, "late").get(5, TimeUnit.SECONDS));
        assertThat(full.getCause()).isInstanceOfSatisfying(ProcessBusyException.class,
            busy -> assertThat(busy.reason()).isEqualTo(ProcessBusyException.Reason.QUEUE_FULL));

        long started = System.nanoTime();
        ProcessResult<HotOutput> replay = asTestRequest(executor.run(ItAdmissionFixtures.HOT,
            new HotInput(key, null, Mode.COUNT, "first"), ExecutionOptions.idempotent(idempotencyKey)))
            .block(Duration.ofSeconds(5));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(HOT_MAX_WAIT.dividedBy(4));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.processSeqId()).isEqualTo(first.processSeqId());

        open(gate);
        holder.get(30, TimeUnit.SECONDS);
        for (CompletableFuture<HotOutput> run : crowd) {
            run.get(30, TimeUnit.SECONDS);
        }
        assertThat(counter(key)).as("the first run, and none of the replay").isEqualTo(1);
        awaitIdle();
    }

    // ================= 8. jobs and events =================

    @Test
    void aJobsItemsNotAdmittedAreBusyAndAnEventIsRetriedAfterItsBackoff() throws Exception {
        String key = unique();
        String gate = close();
        CompletableFuture<HotOutput> holder = start(ItAdmissionFixtures.SMALL, key, gate, "holder");
        awaitArrived(gate, 1);

        String batch = UUID.randomUUID().toString();
        for (int i = 0; i < 2; i++) {
            execute("INSERT INTO it_job_item (f_id, f_batch, f_seq) VALUES (?, ?, ?)", UUID.randomUUID().toString(),
                batch, i);
        }
        JobDefinition<HotInput> job = JobDefinition.forEach("it.hot-items-" + key.substring(0, 8), "0 * * * * *",
            java.time.ZoneOffset.UTC, JobItems.template("it.job_items", at -> Map.of("batch", batch), "itemId"),
            ItAdmissionFixtures.ITEM, (at, row) -> new HotInput(key, null, Mode.OK, (String) row.get("itemId")));
        Instant at = START.plus(Duration.ofMinutes(1));
        // Each item waits its max-wait (500 ms) in the queue, then is busy: no failure, no backoff.
        assertThat(runner.execute(job, at).items()).isEqualTo(new ItemCounts(2, 0, 0, 2, 0, 0, false));
        assertThat(query("SELECT * FROM sys_job_item_failure WHERE job_name = ?", job.name())).isEmpty();

        // An event: the consumer's process is not admitted, a failed delivery is recorded and retried later.
        asTestRequest(executor.execute(ItAdmissionFixtures.PUBLISH, new HotInput(key, null, Mode.OK, "event")))
            .block(Duration.ofSeconds(10));
        UUID eventId = (UUID) query("SELECT event_id FROM sys_outbox_event WHERE event_type = ? AND payload ->> 'key'"
            + " = ?", ItAdmissionFixtures.EVENT, key).getFirst().get("event_id");
        deliverer.deliverPending().block(Duration.ofSeconds(30));
        List<Map<String, Object>> attempts = query("SELECT * FROM sys_outbox_attempt WHERE consumer = ? AND"
            + " event_id = ?", ItAdmissionFixtures.CONSUMER, eventId);
        assertThat(attempts).singleElement().satisfies(attempt -> assertThat((String) attempt.get("error"))
            .contains(ProcessBusyException.class.getSimpleName()));
        deliverer.deliverPending().block(Duration.ofSeconds(30));
        assertThat(query("SELECT * FROM sys_outbox_attempt WHERE consumer = ? AND event_id = ?",
            ItAdmissionFixtures.CONSUMER, eventId)).as("backing off").hasSize(1);
        // Busy attempts do not use up max-attempts (2 here): the event is still retried after three of them.
        for (int round = 0; round < 2; round++) {
            clock.advance(Duration.ofMinutes(1));
            deliverer.deliverPending().block(Duration.ofSeconds(30));
        }
        assertThat(query("SELECT * FROM sys_outbox_attempt WHERE consumer = ? AND event_id = ?",
            ItAdmissionFixtures.CONSUMER, eventId)).hasSize(3);

        open(gate);
        holder.get(10, TimeUnit.SECONDS);
        assertThat(runner.execute(job, at.plus(Duration.ofMinutes(1))).items())
            .isEqualTo(new ItemCounts(2, 2, 0, 0, 0, 0, false));
        clock.advance(Duration.ofMinutes(1));
        deliverer.deliverPending().block(Duration.ofSeconds(30));
        assertThat(query("SELECT * FROM sys_event_consumption WHERE consumer = ? AND event_id = ?",
            ItAdmissionFixtures.CONSUMER, eventId)).hasSize(1);
        awaitIdle();
    }

    // ================= 9. rate limits of the process API =================

    @Test
    void theProcessApiLimitsPerUserPerAddressAndPerInstanceBeforeAnyRead() throws Exception {
        Map<String, Object> body = Map.of("tag", "limited");
        for (int i = 0; i < 3; i++) {
            run("/api/processes/IT_PLAIN/latest", "limit-a", body, null).expectStatus().isOk();
        }
        // The fourth of one user: 429, saying when to try again.
        EntityExchangeResult<Map<String, Object>> refused = run("/api/processes/IT_PLAIN/latest", "limit-a", body,
            null).expectStatus().isEqualTo(429).expectBody(MAP).returnResult();
        assertThat(refused.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
        assertThat(refused.getResponseBody().toString()).contains(PlatformErrorCodes.RATE_LIMITED);
        // Another user from the same client (no trusted proxy: a forged forwarding header changes nothing): two
        // more, then the address's limit of 5.
        run("/api/processes/IT_PLAIN/latest", "limit-b", body, "198.51.100.1").expectStatus().isOk();
        run("/api/processes/IT_PLAIN/latest", "limit-b", body, "198.51.100.2").expectStatus().isOk();
        run("/api/processes/IT_PLAIN/latest", "limit-c", body, "198.51.100.3").expectStatus().isEqualTo(429);
        // Everyone together: two per instance.
        Map<String, Object> event = Map.of("key", unique(), "tag", "publish");
        run("/api/processes/IT_HOT_PUBLISH/latest", "limit-d", event, null).expectStatus().isOk();
        run("/api/processes/IT_HOT_PUBLISH/latest", "limit-e", event, null).expectStatus().isOk();
        run("/api/processes/IT_HOT_PUBLISH/latest", "limit-f", event, null).expectStatus().isEqualTo(429);

        // With every connection taken, a request beyond its limit is still answered at once: nothing was read.
        holdAllConnections();
        long started = System.nanoTime();
        run("/api/processes/IT_PLAIN/latest", "limit-a", body, null).expectStatus().isEqualTo(429);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        releaseConnections();

        // A limit of a process that is not run through the API, or of none at all, is a startup error.
        List<CheckProblem> problems = new ProcessAdmissionChecks(registry, settings, new ProcessRateLimitProperties(
            Map.of("NO_SUCH_PROCESS", new ProcessRateLimitProperties.Limit(1, null, null, null),
                ItAdmissionFixtures.ITEM_PROCESS, new ProcessRateLimitProperties.Limit(1, null, null, null))), 6,
            Duration.ofSeconds(30)).check();
        assertThat(problems).extracting(CheckProblem::location).contains("jabiz.process.rate-limits.NO_SUCH_PROCESS",
            "jabiz.process.rate-limits." + ItAdmissionFixtures.ITEM_PROCESS);
        assertThat(problems).allMatch(problem -> problem.severity() == CheckProblem.Severity.ERROR);
        // The configured ones bound with their names' case.
        assertThat(new ProcessAdmissionChecks(registry, settings, context.getBean(ProcessRateLimitProperties.class),
            6, Duration.ofSeconds(30)).check()).isEmpty();
    }

    // ================= helpers =================

    private WebTestClient.ResponseSpec run(String path, String actor, Map<String, Object> body, String forwardedFor) {
        WebTestClient.RequestBodySpec request = client.post().uri(path).header("X-Jabiz-Actor", actor)
            .header("X-Jabiz-Permissions", ItAdmissionFixtures.PERMISSION).header("Accept-Language", "en")
            .contentType(MediaType.APPLICATION_JSON);
        if (forwardedFor != null) {
            request = request.header("X-Forwarded-For", forwardedFor);
        }
        return request.bodyValue(body).exchange();
    }

    private WebTestClient.ResponseSpec post(String path, Map<String, Object> body) {
        return client.post().uri(path).header("X-Jabiz-Actor", "reader").header("X-Jabiz-Permissions", "*")
            .header("Accept-Language", "en").contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private EntityExchangeResult<Map<String, Object>> runOverHttp(String process, String key) {
        return post("/api/processes/" + process + "/latest", Map.of("key", key, "mode", "OK", "tag", "http"))
            .expectBody(MAP).returnResult();
    }

    private CompletableFuture<HotOutput> start(ProcessDefinition<HotInput, HotOutput, ProcessContext> process,
        String key, String gate, String tag) {
        return start(process, key, gate, Mode.OK, tag);
    }

    private CompletableFuture<HotOutput> start(ProcessDefinition<HotInput, HotOutput, ProcessContext> process,
        String key, String gate, Mode mode, String tag) {
        return asTestRequest(executor.execute(process, new HotInput(key, gate, mode, tag))).toFuture();
    }

    private static String unique() {
        return UUID.randomUUID().toString();
    }

    private static String close() {
        String gate = "gate-" + UUID.randomUUID();
        ItAdmissionFixtures.GATES.put(gate, new CountDownLatch(1));
        return gate;
    }

    private static void open(String gate) {
        ItAdmissionFixtures.GATES.get(gate).countDown();
    }

    private static void awaitArrived(String gate, int count) throws InterruptedException {
        await(() -> ItAdmissionFixtures.arrived(gate) >= count, count + " at " + gate);
        assertThat(ItAdmissionFixtures.arrived(gate)).isEqualTo(count);
    }

    static void await(BooleanSupplier condition, String what) throws InterruptedException {
        for (int i = 0; i < 400 && !condition.getAsBoolean(); i++) {
            Thread.sleep(25);
        }
        assertThat(condition.getAsBoolean()).as(what).isTrue();
    }

    private void awaitIdle() throws InterruptedException {
        await(() -> admission.active() == 0 && admission.waiting() == 0 && admission.keys() == 0,
            "no permit held, nobody waiting, no key kept");
    }

    private static void awaitWaitingFor(String family, String key, int count) throws InterruptedException {
        await(() -> waitingFor(family, key) >= count, count + " sessions waiting for the lock");
        assertThat(waitingFor(family, key)).isEqualTo(count);
    }

    static int waitingFor(String family, String key) {
        return ((Number) query("SELECT count(*) AS n FROM pg_locks WHERE locktype = 'advisory' AND NOT granted"
            // Only this lock: test classes in other forks share the database server and its pg_locks.
            + " AND ((classid::bigint << 32) | objid::bigint) = hashtextextended(?, 0)", "jabiz.app-lock:"
            + ItAdmissionFixtures.lockName(family, key)).getFirst().get("n")).intValue();
    }

    private static String owner() {
        String owner = "o-" + UUID.randomUUID().toString().substring(0, 8);
        execute("INSERT INTO it_ticket (f_id, f_title, f_amount, f_status, f_owner, f_created_at) VALUES"
            + " (?, 't', 1, 'OPEN', ?, now())", UUID.randomUUID().toString(), owner);
        return owner;
    }

    private ConnectionPool pool() {
        Object current = connections;
        while (!(current instanceof ConnectionPool) && current instanceof Wrapped<?> wrapped) {
            current = wrapped.unwrap();
        }
        return (ConnectionPool) current;
    }

    private void holdAllConnections() {
        for (int i = 0; i < 6; i++) {
            held.add(Mono.from(connections.create()).block(Duration.ofSeconds(10)));
        }
    }

    private void releaseConnections() {
        held.forEach(connection -> Mono.from(connection.close()).block(Duration.ofSeconds(10)));
        held.clear();
    }

    private <T> CompletableFuture<T> background(java.util.concurrent.Callable<T> work) {
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
}
