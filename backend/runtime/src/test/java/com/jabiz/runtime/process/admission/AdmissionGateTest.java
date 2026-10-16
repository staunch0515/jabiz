package com.jabiz.runtime.process.admission;

import com.jabiz.runtime.ProcessBusyException;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.scheduler.Scheduler;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gates of the admission queues (decision D45), in virtual time: first come first served, at most
 * {@code concurrency} per key and {@code maxActive} over all, a full queue refused at once, a wait cut short at its
 * time, and nothing lost or kept when waiters leave or holders end.
 */
class AdmissionGateTest {

    private final VirtualTimeScheduler time = VirtualTimeScheduler.create();

    private AdmissionGate gate(int concurrency, int maxQueue, int maxActive) {
        return gate(concurrency, maxQueue, maxActive, 100, 100, Duration.ofSeconds(1));
    }

    private AdmissionGate gate(int concurrency, int maxQueue, int maxActive, int maxKeys, int maxWaiting,
        Duration retryAfter) {
        return new AdmissionGate(maxActive, maxKeys, maxWaiting,
            family -> new AdmissionGate.Limits(concurrency, maxQueue, retryAfter),
            () -> time.now(TimeUnit.NANOSECONDS), () -> time);
    }

    /** One run's request for a permit, as a subscriber sees it. */
    static final class Run {
        final String name;
        final AtomicReference<AdmissionGate.Permit> permit = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        Disposable subscription;

        Run(String name) {
            this.name = name;
        }

        boolean admitted() {
            return permit.get() != null;
        }

        ProcessBusyException busy() {
            return (ProcessBusyException) error.get();
        }

        void release() {
            permit.get().release();
        }
    }

    private final List<String> order = new ArrayList<>();

    private Run arrive(AdmissionGate gate, String key, String name, Duration maxWait) {
        Run run = new Run(name);
        run.subscription = gate.acquire("test.hot", key, maxWait).subscribe(permit -> {
            order.add(name);
            run.permit.set(permit);
        }, run.error::set);
        time.advanceTime();
        return run;
    }

    private Run arrive(AdmissionGate gate, String key, String name) {
        return arrive(gate, key, name, Duration.ofSeconds(10));
    }

    @Test
    void runsOfOneKeyAreAdmittedInOrderOfArrivalConcurrencyAtATime() {
        AdmissionGate gate = gate(2, 10, 10);
        Run a = arrive(gate, "k", "a");
        Run b = arrive(gate, "k", "b");
        Run c = arrive(gate, "k", "c");
        Run d = arrive(gate, "k", "d");
        assertThat(order).containsExactly("a", "b");
        assertThat(gate.active()).isEqualTo(2);
        assertThat(gate.waiting()).isEqualTo(2);
        assertThat(gate.waiting("test.hot")).isEqualTo(2);
        assertThat(gate.active("test.hot")).isEqualTo(2);

        // A newcomer does not overtake the waiters when a permit is freed.
        b.release();
        Run e = arrive(gate, "k", "e");
        assertThat(order).containsExactly("a", "b", "c");
        a.release();
        time.advanceTime();
        assertThat(order).containsExactly("a", "b", "c", "d");
        c.release();
        d.release();
        time.advanceTime();
        assertThat(order).containsExactly("a", "b", "c", "d", "e");
        e.release();
        assertThat(gate.active()).isZero();
        assertThat(gate.keys()).as("an idle key is forgotten").isZero();
    }

    @Test
    void otherKeysAreNotKeptWaiting() {
        AdmissionGate gate = gate(1, 10, 10);
        arrive(gate, "k", "a");
        arrive(gate, "k", "b");
        Run other = arrive(gate, "other", "c");
        assertThat(other.admitted()).isTrue();
        assertThat(gate.keys()).isEqualTo(2);
    }

    @Test
    void aFullQueueIsRefusedAtOnce() {
        AdmissionGate gate = gate(1, 2, 10, 100, 100, Duration.ofSeconds(3));
        arrive(gate, "k", "a");
        arrive(gate, "k", "b");
        arrive(gate, "k", "c");
        Run late = arrive(gate, "k", "d");
        assertThat(late.admitted()).isFalse();
        assertThat(late.busy().reason()).isEqualTo(ProcessBusyException.Reason.QUEUE_FULL);
        assertThat(late.busy().family()).isEqualTo("test.hot");
        assertThat(late.busy().retryAfterSeconds()).as("the floor, without holding times yet").isEqualTo(3);
        assertThat(late.busy().getMessage()).doesNotContain(":k").contains("test.hot");
        assertThat(gate.waiting()).isEqualTo(2);

        // max-queue 0: only who finds a permit free gets in.
        AdmissionGate none = gate(1, 0, 10);
        assertThat(arrive(none, "k", "x").admitted()).isTrue();
        assertThat(arrive(none, "k", "y").busy().reason()).isEqualTo(ProcessBusyException.Reason.QUEUE_FULL);
    }

    @Test
    void aWaitPastItsTimeLeavesTheQueueHoldingNothing() {
        AdmissionGate gate = gate(1, 10, 10);
        Run holder = arrive(gate, "k", "a");
        Run waiter = arrive(gate, "k", "b", Duration.ofMillis(500));
        Run patient = arrive(gate, "k", "c", Duration.ofSeconds(10));
        time.advanceTimeBy(Duration.ofMillis(499));
        assertThat(waiter.error.get()).isNull();
        time.advanceTimeBy(Duration.ofMillis(1));
        assertThat(waiter.busy().reason()).isEqualTo(ProcessBusyException.Reason.WAIT_TIMEOUT);
        assertThat(gate.waiting()).isEqualTo(1);

        holder.release();
        time.advanceTime();
        assertThat(order).containsExactly("a", "c");
        patient.release();
        assertThat(gate.active()).isZero();
        assertThat(gate.waiting()).isZero();
        assertThat(gate.keys()).isZero();
    }

    @Test
    void aWaiterThatGoesAwayLeavesTheQueue() {
        AdmissionGate gate = gate(1, 10, 10);
        Run holder = arrive(gate, "k", "a");
        Run gone = arrive(gate, "k", "b");
        Run next = arrive(gate, "k", "c");
        gone.subscription.dispose();
        assertThat(gate.waiting()).isEqualTo(1);
        holder.release();
        time.advanceTime();
        assertThat(order).containsExactly("a", "c");
        assertThat(gone.admitted()).isFalse();
        next.release();
        assertThat(gate.keys()).isZero();
    }

    /** Runs hand-overs only when told to; timers in virtual time. */
    private final List<Runnable> handOvers = new ArrayList<>();

    private final Scheduler held = new Scheduler() {
        @Override
        public Disposable schedule(Runnable task) {
            handOvers.add(task);
            return Disposables.single();
        }

        @Override
        public Disposable schedule(Runnable task, long delay, TimeUnit unit) {
            return time.schedule(task, delay, unit);
        }

        @Override
        public Worker createWorker() {
            return time.createWorker();
        }
    };

    private void runHandOvers() {
        List<Runnable> due = List.copyOf(handOvers);
        handOvers.clear();
        due.forEach(Runnable::run);
    }

    @Test
    void aPermitHandedOverAsItsWaiterGoesAwayIsNotLost() {
        AdmissionGate gate = new AdmissionGate(10, 100, 100,
            family -> new AdmissionGate.Limits(1, 10, Duration.ofSeconds(1)), () -> time.now(TimeUnit.NANOSECONDS),
            () -> held);
        Run holder = arrive(gate, "k", "a");
        Run gone = arrive(gate, "k", "b");
        Run next = arrive(gate, "k", "c");
        // The holder ends: b's permit is on its way (handed over on the scheduler) when b goes away.
        holder.release();
        gone.subscription.dispose();
        runHandOvers();
        assertThat(gone.admitted()).isFalse();
        runHandOvers();
        assertThat(gone.admitted()).isFalse();
        assertThat(order).containsExactly("a", "c");
        assertThat(gate.active()).isEqualTo(1);
        next.release();
        assertThat(gate.active()).isZero();
        assertThat(gate.keys()).isZero();
    }

    @Test
    void releasingTwiceFreesOnePermit() {
        AdmissionGate gate = gate(1, 10, 10);
        Run holder = arrive(gate, "k", "a");
        arrive(gate, "k", "b");
        arrive(gate, "k", "c");
        holder.release();
        holder.release();
        time.advanceTime();
        assertThat(order).containsExactly("a", "b");
        assertThat(gate.active()).isEqualTo(1);
    }

    @Test
    void allKeysTogetherHoldAtMostMaxActive() {
        AdmissionGate gate = gate(2, 10, 2);
        Run a = arrive(gate, "k1", "a");
        Run b = arrive(gate, "k2", "b");
        Run c = arrive(gate, "k3", "c");
        Run d = arrive(gate, "k1", "d");
        assertThat(order).containsExactly("a", "b");
        assertThat(gate.waiting()).isEqualTo(2);

        // The first to wait for the gate goes first, whatever its key.
        b.release();
        time.advanceTime();
        assertThat(order).containsExactly("a", "b", "c");
        a.release();
        time.advanceTime();
        assertThat(order).containsExactly("a", "b", "c", "d");

        // A waiter at the gate that times out gives its key's permit back.
        Run e = arrive(gate, "k4", "e", Duration.ofMillis(100));
        Run f = arrive(gate, "k4", "f");
        time.advanceTimeBy(Duration.ofMillis(100));
        assertThat(e.busy().reason()).isEqualTo(ProcessBusyException.Reason.WAIT_TIMEOUT);
        c.release();
        time.advanceTime();
        assertThat(f.admitted()).isTrue();
        d.release();
        f.release();
        assertThat(gate.active()).isZero();
        assertThat(gate.keys()).isZero();
    }

    @Test
    void waitersAndKeysAreBounded() {
        AdmissionGate gate = gate(1, 10, 10, 2, 1, Duration.ofSeconds(1));
        arrive(gate, "k1", "a");
        arrive(gate, "k1", "b");
        assertThat(arrive(gate, "k1", "c").busy().reason()).as("max-waiting")
            .isEqualTo(ProcessBusyException.Reason.QUEUE_FULL);
        arrive(gate, "k2", "d");
        assertThat(arrive(gate, "k3", "e").busy().reason()).as("max-keys")
            .isEqualTo(ProcessBusyException.Reason.QUEUE_FULL);
        assertThat(gate.keys()).isEqualTo(2);
    }

    @Test
    void retryAfterIsEstimatedFromTheQueueAndTheHoldingTimes() {
        AdmissionGate gate = gate(2, 4, 10, 100, 100, Duration.ofSeconds(1));
        // Permits held 3 s on average.
        for (int i = 0; i < 4; i++) {
            Run run = arrive(gate, "k", "warm" + i);
            time.advanceTimeBy(Duration.ofSeconds(3));
            run.release();
        }
        arrive(gate, "k", "a");
        arrive(gate, "k", "b");
        for (int i = 0; i < 4; i++) {
            arrive(gate, "k", "w" + i);
        }
        // Four waiting and this one: 5 x 3 s / 2 = 7.5 s, up to 8.
        assertThat(arrive(gate, "k", "late").busy().retryAfterSeconds()).isEqualTo(8);

        AdmissionGate slow = gate(1, 1, 10, 100, 100, Duration.ofSeconds(1));
        Run run = arrive(slow, "k", "warm");
        time.advanceTimeBy(Duration.ofMinutes(5));
        run.release();
        arrive(slow, "k", "a");
        arrive(slow, "k", "b");
        assertThat(arrive(slow, "k", "late").busy().retryAfterSeconds()).as("at most 30 s").isEqualTo(30);
    }

    @Test
    void theLimitsAreChecked() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> gate(1, 1, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
