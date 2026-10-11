package com.jabiz.app.it.process;

import com.jabiz.app.it.fixture.ItLockFixtures;
import com.jabiz.app.it.fixture.ItLockFixtures.LockInput;
import com.jabiz.app.it.fixture.ItLockFixtures.LockOutput;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.ConcurrentUpdateException;
import com.jabiz.runtime.process.ExecutionOptions;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.ProcessResult;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Named locks held until the transaction ends (docs/design/06-process.md section 4.1): an exclusive holder keeps
 * everyone else of the name out, shared holders run side by side, other names are not affected, the lock is let go
 * when the process commits or rolls back, a child's lock is the root transaction's, and a wait longer than
 * {@code jabiz.process.lock-timeout} is a 409. Waiting is seen in {@code pg_locks}, not guessed from the time.
 */
@SpringBootTest(properties = "jabiz.process.lock-timeout=4s")
class HoldLockIT extends PostgresIntegrationTest {

    @Autowired
    ProcessExecutor executor;

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private CompletableFuture<LockOutput> start(ProcessDefinition<LockInput, LockOutput, ProcessContext> process,
        String name, boolean shared, String gate, boolean fail) {
        return asTestRequest(executor.execute(process, new LockInput(name, shared, gate, fail))).toFuture();
    }

    private CompletableFuture<LockOutput> start(String name, boolean shared, String gate, boolean fail) {
        return start(ItLockFixtures.LOCKED, name, shared, gate, fail);
    }

    /** Closes a gate: processes reaching it wait until it is opened. */
    private static CountDownLatch close(String gate) {
        CountDownLatch latch = new CountDownLatch(1);
        ItLockFixtures.GATES.put(gate, latch);
        return latch;
    }

    private static void awaitArrived(String gate, int count) throws InterruptedException {
        for (int i = 0; i < 200 && ItLockFixtures.arrived(gate) < count; i++) {
            Thread.sleep(25);
        }
        assertThat(ItLockFixtures.arrived(gate)).as("processes at " + gate).isEqualTo(count);
    }

    /** Waits until {@code count} sessions wait for an advisory lock (the tests of a class run one at a time). */
    private static void awaitWaiting(String name, int count) throws InterruptedException {
        for (int i = 0; i < 200 && waiting(name) < count; i++) {
            Thread.sleep(25);
        }
        assertThat(waiting(name)).as("sessions waiting for a lock").isEqualTo(count);
    }

    private static int waiting(String name) {
        return ((Number) query("SELECT count(*) AS n FROM pg_locks WHERE locktype = 'advisory' AND NOT granted"
            // Only this lock: test classes in other forks share the database server and its pg_locks.
            + " AND ((classid::bigint << 32) | objid::bigint) = hashtextextended(?, 0)", "jabiz.app-lock:" + name)
            .getFirst().get("n")).intValue();
    }

    @Test
    void anExclusiveHolderKeepsTheOthersOutUntilItCommits() throws Exception {
        String name = unique("period");
        String closing = unique("closing");
        CountDownLatch release = close(closing);
        CompletableFuture<LockOutput> close = start(name, false, closing, false);
        awaitArrived(closing, 1);

        String posting = unique("posting");
        CountDownLatch postings = close(posting);
        CompletableFuture<LockOutput> post = start(name, true, posting, false);
        awaitWaiting(name, 1);
        String other = unique("other");
        CompletableFuture<LockOutput> another = start(name, false, other, false);
        awaitWaiting(name, 2);
        assertThat(ItLockFixtures.arrived(posting) + ItLockFixtures.arrived(other)).isZero();

        // The exclusive holder commits: the shared one goes in and keeps the exclusive one waiting in its turn.
        release.countDown();
        assertThat(close.get(10, TimeUnit.SECONDS).gate()).isEqualTo(closing);
        awaitArrived(posting, 1);
        awaitWaiting(name, 1);
        assertThat(another).isNotDone();
        postings.countDown();
        assertThat(post.get(10, TimeUnit.SECONDS).gate()).isEqualTo(posting);
        assertThat(another.get(10, TimeUnit.SECONDS).gate()).isEqualTo(other);
    }

    @Test
    void sharedHoldersRunSideBySideAndKeepAnExclusiveOneWaiting() throws Exception {
        String name = unique("period");
        String posting = unique("posting");
        CountDownLatch release = close(posting);
        CompletableFuture<LockOutput> first = start(name, true, posting, false);
        CompletableFuture<LockOutput> second = start(name, true, posting, false);
        awaitArrived(posting, 2);

        String closing = unique("closing");
        CompletableFuture<LockOutput> close = start(name, false, closing, false);
        awaitWaiting(name, 1);
        assertThat(ItLockFixtures.arrived(closing)).isZero();

        release.countDown();
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);
        assertThat(close.get(10, TimeUnit.SECONDS).gate()).isEqualTo(closing);
    }

    @Test
    void otherNamesAndNoNameAreNotKeptWaiting() throws Exception {
        String held = unique("held");
        CountDownLatch release = close(held);
        CompletableFuture<LockOutput> holder = start(unique("period"), false, held, false);
        awaitArrived(held, 1);

        assertThat(start(unique("period"), false, unique("other"), false).get(10, TimeUnit.SECONDS)).isNotNull();
        assertThat(start(null, false, unique("none"), false).get(10, TimeUnit.SECONDS)).isNotNull();
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
    }

    @Test
    void aRolledBackHolderLetsItGo() throws Exception {
        String name = unique("period");
        String failing = unique("failing");
        CountDownLatch release = close(failing);
        CompletableFuture<LockOutput> failed = start(name, false, failing, true);
        awaitArrived(failing, 1);
        String next = unique("next");
        CompletableFuture<LockOutput> after = start(name, false, next, false);
        awaitWaiting(name, 1);

        release.countDown();
        ExecutionException error = catchThrowableOfType(ExecutionException.class,
            () -> failed.get(10, TimeUnit.SECONDS));
        assertThat(error).hasCauseInstanceOf(BusinessRuleViolationException.class);
        assertThat(after.get(10, TimeUnit.SECONDS).gate()).isEqualTo(next);
    }

    @Test
    void aChildsLockIsHeldUntilTheRootCommits() throws Exception {
        String name = unique("period");
        String parent = unique("parent");
        CountDownLatch release = close(parent);
        CompletableFuture<LockOutput> holder = start(ItLockFixtures.PARENT, name, false, parent, false);
        awaitArrived(parent, 1);
        // The child has ended; its lock has not.
        CompletableFuture<LockOutput> after = start(name, true, unique("after"), false);
        awaitWaiting(name, 1);

        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        assertThat(after.get(10, TimeUnit.SECONDS)).isNotNull();
    }

    /** Waits for a held lock until it gives up; returns how long that took. */
    private Duration timeToGiveUp(java.util.function.Supplier<CompletableFuture<LockOutput>> late) {
        long started = System.nanoTime();
        ExecutionException error = catchThrowableOfType(ExecutionException.class, () -> late.get().get(15,
            TimeUnit.SECONDS));
        assertThat(error).hasCauseInstanceOf(ConcurrentUpdateException.class);
        return Duration.ofNanos(System.nanoTime() - started);
    }

    @Test
    void aStepsOwnWaitAndTheExecutionsWaitAreShorterThanTheTimeout() throws Exception {
        String name = unique("period");
        String held = unique("held");
        CountDownLatch release = close(held);
        CompletableFuture<LockOutput> holder = start(name, false, held, false);
        awaitArrived(held, 1);

        // The step's own wait (300 ms), well below the global 4 s.
        assertThat(timeToGiveUp(() -> start(ItLockFixtures.LOCKED_BRIEFLY, name, false, unique("brief"), false)))
            .isLessThan(Duration.ofSeconds(3));
        // The execution's wait (a job's item), for a step that declares none.
        assertThat(timeToGiveUp(() -> asTestRequest(executor.run(ItLockFixtures.LOCKED,
            new LockInput(name, false, unique("item"), false), ExecutionOptions.NONE.withLockWait(
                Duration.ofMillis(300)))).map(ProcessResult::output).toFuture())).isLessThan(Duration.ofSeconds(3));
        // Both: the shorter one (the step's 300 ms rather than the execution's 3.5 s).
        assertThat(timeToGiveUp(() -> asTestRequest(executor.run(ItLockFixtures.LOCKED_BRIEFLY,
            new LockInput(name, false, unique("both"), false), ExecutionOptions.NONE.withLockWait(
                Duration.ofMillis(3500)))).map(ProcessResult::output).toFuture())).isLessThan(Duration.ofSeconds(3));

        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        // Free again: the brief wait takes it at once.
        assertThat(start(ItLockFixtures.LOCKED_BRIEFLY, name, false, unique("free"), false).get(10, TimeUnit.SECONDS))
            .isNotNull();
    }

    @Test
    void everyTransactionBoundsItsLockWaitsAndAStepsWaitIsPutBack() {
        // The global timeout (4 s here) for the whole transaction; the step's 300 ms only for its own statement.
        assertThat(asTestRequest(executor.execute(ItLockFixtures.LOCK_TIMEOUT_PROBE,
            new LockInput(unique("free"), false, null, false))).block()).isEqualTo(new LockOutput("4s"));
        // A job item's shorter wait for the whole transaction, row locks and the platform's own locks included.
        assertThat(asTestRequest(executor.run(ItLockFixtures.LOCK_TIMEOUT_PROBE,
                new LockInput(unique("free"), false, null, false),
                ExecutionOptions.NONE.withLockWait(Duration.ofSeconds(2)))).block().output())
            .isEqualTo(new LockOutput("2s"));
    }

    @Test
    void waitingLongerThanTheTimeoutIsAConflict() throws Exception {
        String name = unique("period");
        String held = unique("held");
        CountDownLatch release = close(held);
        CompletableFuture<LockOutput> holder = start(name, false, held, false);
        awaitArrived(held, 1);

        ExecutionException error = catchThrowableOfType(ExecutionException.class,
            () -> start(name, true, unique("late"), false).get(15, TimeUnit.SECONDS));
        assertThat(error).hasCauseInstanceOf(ConcurrentUpdateException.class);
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
    }
}
