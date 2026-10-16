package com.jabiz.runtime.process.admission;

import com.jabiz.runtime.ProcessBusyException;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.scheduler.Scheduler;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Fair counting gates by admission key, under one limit over all keys (decision D45). A run takes a permit of its
 * key - at most {@code concurrency} per key - and then one of the gate as a whole - at most {@code maxActive} - in
 * order of arrival at each: a permit set free goes to the first waiter, never to a newcomer. Only waiters wait, holders
 * never wait for another permit, so the two kinds of permit form no cycle.
 *
 * <p>A full queue refuses at once ({@code QUEUE_FULL}); a waiter leaves the queue after its {@code maxWait}
 * ({@code WAIT_TIMEOUT}) or when its subscriber cancels; either way it holds nothing afterwards. A key with neither
 * holders nor waiters is forgotten, and keys and waiters are bounded in number, so memory stays bounded.
 *
 * <p>Non-blocking: the state changes in short {@code synchronized} sections, and permits are handed to waiters on the
 * scheduler, so that one release does not run the next waiter's work on its own stack. Times come from the monotonic
 * clock: this is throttling, not business time.
 */
final class AdmissionGate {

    /** The limits of a family's keys (resolved from {@link AdmissionSettings.Family}). */
    record Limits(int concurrency, int maxQueue, Duration retryAfter) {}

    /** The longest {@code Retry-After} of a refusal. */
    static final long MAX_RETRY_AFTER_SECONDS = 30;
    /** How many of a family's latest holding times its estimate of the wait averages. */
    static final int HOLD_SAMPLES = 50;

    private final int maxActive;
    private final int maxKeys;
    private final int maxWaiting;
    private final Function<String, Limits> limits;
    private final LongSupplier nanoTime;
    private final Supplier<Scheduler> scheduler;

    private final Map<String, Key> keys = new HashMap<>();
    private final Map<String, Family> families = new HashMap<>();
    /** Waiters holding a permit of their key, waiting for one of the gate; never empty while one is free. */
    private final ArrayDeque<Waiter> global = new ArrayDeque<>();
    private int active;
    private int waiting;

    AdmissionGate(int maxActive, int maxKeys, int maxWaiting, Function<String, Limits> limits, LongSupplier nanoTime,
        Supplier<Scheduler> scheduler) {
        if (maxActive < 1 || maxKeys < 1 || maxWaiting < 0) {
            throw new IllegalArgumentException("maxActive and maxKeys must be positive, maxWaiting not negative");
        }
        this.maxActive = maxActive;
        this.maxKeys = maxKeys;
        this.maxWaiting = maxWaiting;
        this.limits = Objects.requireNonNull(limits, "limits");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /** A permit held: released once, by whoever ends the run (later releases do nothing). */
    final class Permit {
        private final Key key;
        private final long admitted;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(Key key, long admitted) {
            this.key = key;
            this.admitted = admitted;
        }

        void release() {
            if (released.compareAndSet(false, true)) {
                released(this);
            }
        }

        Mono<Void> releaseLater() {
            return Mono.fromRunnable(this::release);
        }

        String family() {
            return key.family;
        }
    }

    private enum Stage { AT_KEY, AT_GATE, ADMITTED, GONE }

    private static final class Key {
        final String name;
        final String family;
        final Limits limits;
        final ArrayDeque<Waiter> queue = new ArrayDeque<>();
        /** Permits of this key: holders, and waiters at the gate. */
        int slots;
        /** Waiters of this key, at the key or at the gate. */
        int waiting;

        Key(String name, String family, Limits limits) {
            this.name = name;
            this.family = family;
            this.limits = limits;
        }
    }

    private static final class Family {
        final long[] holds = new long[HOLD_SAMPLES];
        int samples;
        int next;
        int active;
        int waiting;

        void sample(long nanos) {
            holds[next] = nanos;
            next = (next + 1) % HOLD_SAMPLES;
            samples = Math.min(HOLD_SAMPLES, samples + 1);
        }

        long averageHold() {
            if (samples == 0) {
                return 0;
            }
            long sum = 0;
            for (int i = 0; i < samples; i++) {
                sum += holds[i];
            }
            return sum / samples;
        }
    }

    private final class Waiter {
        final Key key;
        final MonoSink<Permit> sink;
        final AtomicBoolean handedOver = new AtomicBoolean();
        Stage stage;
        Permit permit;
        volatile Disposable timer;

        Waiter(Key key, MonoSink<Permit> sink, Stage stage) {
            this.key = key;
            this.sink = sink;
            this.stage = stage;
        }
    }

    /**
     * A permit of {@code key} (of {@code family}) once this run's turn has come, or {@link ProcessBusyException} when
     * the queue is full or the wait longer than {@code maxWait}. Cancelling leaves the queue; a permit received must be
     * released when the run ends.
     */
    Mono<Permit> acquire(String family, String key, Duration maxWait) {
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(maxWait, "maxWait");
        return Mono.<Permit>create(sink -> {
            Permit now = null;
            Waiter waiter = null;
            ProcessBusyException refusal = null;
            synchronized (this) {
                Key state = keys.get(key);
                if (state == null && keys.size() >= maxKeys) {
                    refusal = busy(family, null, ProcessBusyException.Reason.QUEUE_FULL);
                } else {
                    if (state == null) {
                        state = new Key(key, family, limits.apply(family));
                        keys.put(key, state);
                    }
                    boolean keyFree = state.slots < state.limits.concurrency() && state.queue.isEmpty();
                    if (keyFree && active < maxActive && global.isEmpty()) {
                        state.slots++;
                        now = admit(state);
                    } else if (state.waiting >= state.limits.maxQueue() || waiting >= maxWaiting) {
                        refusal = busy(family, state, ProcessBusyException.Reason.QUEUE_FULL);
                        forgetIfIdle(state);
                    } else if (keyFree) {
                        state.slots++;
                        waiter = enqueue(state, sink, Stage.AT_GATE);
                        global.add(waiter);
                    } else {
                        waiter = enqueue(state, sink, Stage.AT_KEY);
                        state.queue.add(waiter);
                    }
                }
            }
            if (refusal != null) {
                sink.error(refusal);
            } else if (now != null) {
                sink.success(now);
            } else {
                Waiter queued = waiter;
                sink.onCancel(() -> cancelled(queued));
                queued.timer = scheduler.get().schedule(() -> timedOut(queued), Math.max(1, maxWait.toNanos()),
                    TimeUnit.NANOSECONDS);
            }
        })
            // A permit handed over just as its subscriber went away is not lost.
            .doOnDiscard(Permit.class, Permit::release);
    }

    /** Waiters, holders and keys now: for gauges and tests. */
    synchronized int waiting() {
        return waiting;
    }

    synchronized int active() {
        return active;
    }

    synchronized int keys() {
        return keys.size();
    }

    synchronized int waiting(String family) {
        Family state = families.get(family);
        return state == null ? 0 : state.waiting;
    }

    synchronized int active(String family) {
        Family state = families.get(family);
        return state == null ? 0 : state.active;
    }

    // ================= under the lock =================

    private Family family(String name) {
        return families.computeIfAbsent(name, n -> new Family());
    }

    private Waiter enqueue(Key key, MonoSink<Permit> sink, Stage stage) {
        key.waiting++;
        waiting++;
        family(key.family).waiting++;
        return new Waiter(key, sink, stage);
    }

    private void dequeued(Waiter waiter) {
        waiter.key.waiting--;
        waiting--;
        family(waiter.key.family).waiting--;
    }

    private Permit admit(Key key) {
        active++;
        family(key.family).active++;
        return new Permit(key, nanoTime.getAsLong());
    }

    private void forgetIfIdle(Key key) {
        if (key.slots == 0 && key.waiting == 0) {
            keys.remove(key.name, key);
        }
    }

    /** Moves waiters on as far as permits are free: the key's to the gate, the gate's into their run. */
    private void advance(Key key, List<Waiter> handOver) {
        if (key != null) {
            while (key.slots < key.limits.concurrency() && !key.queue.isEmpty()) {
                Waiter next = key.queue.poll();
                key.slots++;
                next.stage = Stage.AT_GATE;
                global.add(next);
            }
        }
        while (active < maxActive && !global.isEmpty()) {
            Waiter next = global.poll();
            dequeued(next);
            next.stage = Stage.ADMITTED;
            next.permit = admit(next.key);
            handOver.add(next);
        }
    }

    /** Takes a waiter out of its queue; false when it was no longer waiting. */
    private boolean leave(Waiter waiter, List<Waiter> handOver) {
        switch (waiter.stage) {
            case AT_KEY -> {
                waiter.key.queue.remove(waiter);
                dequeued(waiter);
                waiter.stage = Stage.GONE;
                forgetIfIdle(waiter.key);
                return true;
            }
            case AT_GATE -> {
                global.remove(waiter);
                dequeued(waiter);
                waiter.stage = Stage.GONE;
                waiter.key.slots--;
                advance(waiter.key, handOver);
                forgetIfIdle(waiter.key);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** The refusal of a run of {@code key}, with a {@code Retry-After} estimated from its queue. */
    private ProcessBusyException busy(String familyName, Key key, ProcessBusyException.Reason reason) {
        Limits own = key != null ? key.limits : limits.apply(familyName);
        long floor = Math.max(1, ceilSeconds(own.retryAfter().toNanos()));
        long ahead = key == null ? 0 : key.waiting + 1L;
        long estimate = ceilSeconds(ahead * family(familyName).averageHold() / Math.max(1, own.concurrency()));
        return new ProcessBusyException(familyName, reason,
            Math.min(MAX_RETRY_AFTER_SECONDS, Math.max(floor, estimate)));
    }

    private static long ceilSeconds(long nanos) {
        return (nanos + 999_999_999L) / 1_000_000_000L;
    }

    // ================= events =================

    private void released(Permit permit) {
        List<Waiter> handOver = new ArrayList<>();
        synchronized (this) {
            Key key = permit.key;
            key.slots--;
            active--;
            Family family = family(key.family);
            family.active--;
            family.sample(Math.max(0, nanoTime.getAsLong() - permit.admitted));
            advance(key, handOver);
            forgetIfIdle(key);
        }
        handOver(handOver);
    }

    private void cancelled(Waiter waiter) {
        List<Waiter> handOver = new ArrayList<>();
        boolean admitted;
        synchronized (this) {
            admitted = !leave(waiter, handOver) && waiter.stage == Stage.ADMITTED;
        }
        dispose(waiter);
        // Admitted but not yet handed over: the permit is nobody's, so it goes back.
        if (admitted && waiter.handedOver.compareAndSet(false, true)) {
            waiter.permit.release();
        }
        handOver(handOver);
    }

    private void timedOut(Waiter waiter) {
        List<Waiter> handOver = new ArrayList<>();
        ProcessBusyException refusal = null;
        synchronized (this) {
            if (leave(waiter, handOver)) {
                refusal = busy(waiter.key.family, waiter.key, ProcessBusyException.Reason.WAIT_TIMEOUT);
            }
        }
        if (refusal != null) {
            waiter.sink.error(refusal);
        }
        handOver(handOver);
    }

    private void handOver(List<Waiter> waiters) {
        for (Waiter waiter : waiters) {
            dispose(waiter);
            scheduler.get().schedule(() -> {
                if (waiter.handedOver.compareAndSet(false, true)) {
                    // Discarded, and so released, if the subscriber has just gone away.
                    waiter.sink.success(waiter.permit);
                }
            });
        }
    }

    private static void dispose(Waiter waiter) {
        Disposable timer = waiter.timer;
        if (timer != null) {
            timer.dispose();
        }
    }
}
