package com.jabiz.runtime.process.admission;

import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admission gates on real threads (decision D45): many runs from several threads, with waits of 0 to a few
 * milliseconds, callers that go away at random, permits handed over on {@code Schedulers.parallel()} and held around
 * the work as {@link ProcessAdmission} does (detached from the caller, released when the work ends). Never more runs
 * in their work than {@code concurrency} per key and {@code maxActive} in all, and afterwards nothing held, waiting or
 * remembered.
 */
class AdmissionGateStressTest {

    private static final int CONCURRENCY = 2;
    private static final int MAX_ACTIVE = 3;
    private static final String[] KEYS = {"a", "b", "c", "d", "e"};

    private final Map<String, AtomicInteger> inWork = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();
    private final AtomicInteger mostPerKey = new AtomicInteger();
    private final AtomicInteger mostInAll = new AtomicInteger();
    private final AtomicInteger worked = new AtomicInteger();

    /** The run's work: counts itself in, pauses a little, counts itself out. */
    private Mono<String> work(String key) {
        return Mono.defer(() -> {
            mostPerKey.accumulateAndGet(inWork.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet(),
                Math::max);
            mostInAll.accumulateAndGet(total.incrementAndGet(), Math::max);
            worked.incrementAndGet();
            return Mono.delay(Duration.ofNanos(ThreadLocalRandom.current().nextLong(0, 500_000)))
                .thenReturn(key)
                // Before the end reaches the release (doFinally would run after it).
                .doOnTerminate(() -> {
                    inWork.get(key).decrementAndGet();
                    total.decrementAndGet();
                });
        });
    }

    /** As {@link ProcessAdmission#admit}: the permit is held until the work has ended, whatever the caller does. */
    private Mono<String> admitted(AdmissionGate gate, String key, Duration maxWait) {
        return gate.acquire("test.hot", key, maxWait).flatMap(held -> Mono.fromFuture(work(key)
            .doFinally(signal -> held.release()).toFuture(), true));
    }

    @Test
    void underRaceTheLimitsHoldAndNothingIsLeftBehind() throws Exception {
        AdmissionGate gate = new AdmissionGate(MAX_ACTIVE, 100, 1000,
            family -> new AdmissionGate.Limits(CONCURRENCY, 20, Duration.ofSeconds(1)), System::nanoTime,
            Schedulers::parallel);
        int threads = 8;
        int perThread = 1500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger settled = new AtomicInteger();
        List<CompletableFuture<?>> submitted = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                submitted.add(CompletableFuture.runAsync(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    ThreadLocalRandom random = ThreadLocalRandom.current();
                    for (int i = 0; i < perThread; i++) {
                        String key = KEYS[random.nextInt(KEYS.length)];
                        Duration maxWait = Duration.ofNanos(random.nextLong(0, 3_000_000));
                        Disposable caller = admitted(gate, key, maxWait)
                            .doFinally(signal -> settled.incrementAndGet())
                            .subscribe(value -> { }, error -> { });
                        if (random.nextInt(3) == 0) {
                            if (random.nextBoolean()) {
                                Thread.onSpinWait();
                            }
                            caller.dispose();
                        }
                    }
                }, pool));
            }
            start.countDown();
            CompletableFuture.allOf(submitted.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);
            for (int i = 0; i < 400 && !(gate.active() == 0 && gate.waiting() == 0 && gate.keys() == 0
                && total.get() == 0); i++) {
                Thread.sleep(25);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(settled.get()).as("every caller ended, one way or another").isEqualTo(threads * perThread);
        assertThat(worked.get()).as("some runs got through").isPositive();
        assertThat(mostPerKey.get()).as("runs of one key at once").isLessThanOrEqualTo(CONCURRENCY);
        assertThat(mostInAll.get()).as("runs at once").isLessThanOrEqualTo(MAX_ACTIVE);
        assertThat(gate.active()).isZero();
        assertThat(gate.waiting()).isZero();
        assertThat(gate.keys()).isZero();
        assertThat(total.get()).isZero();
    }
}
