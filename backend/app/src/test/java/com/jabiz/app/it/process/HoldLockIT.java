package com.jabiz.app.it.process;

import com.jabiz.app.it.fixture.ItLockFixtures;
import com.jabiz.app.it.fixture.ItLockFixtures.LockInput;
import com.jabiz.app.it.fixture.ItLockFixtures.LockOutput;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.ConcurrentUpdateException;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

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
    private static void awaitWaiting(int count) throws InterruptedException {
        for (int i = 0; i < 200 && waiting() < count; i++) {
            Thread.sleep(25);
        }
        assertThat(waiting()).as("sessions waiting for a lock").isEqualTo(count);
    }

    private static int waiting() {
        return ((Number) query("SELECT count(*) AS n FROM pg_locks WHERE locktype = 'advisory' AND NOT granted")
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
        awaitWaiting(1);
        String other = unique("other");
        CompletableFuture<LockOutput> another = start(name, false, other, false);
        awaitWaiting(2);
        assertThat(ItLockFixtures.arrived(posting) + ItLockFixtures.arrived(other)).isZero();

        // The exclusive holder commits: the shared one goes in and keeps the exclusive one waiting in its turn.
        release.countDown();
        assertThat(close.get(10, TimeUnit.SECONDS).gate()).isEqualTo(closing);
        awaitArrived(posting, 1);
        awaitWaiting(1);
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
        awaitWaiting(1);
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
        awaitWaiting(1);

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
        awaitWaiting(1);

        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        assertThat(after.get(10, TimeUnit.SECONDS)).isNotNull();
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
