package com.jabiz.runtime.process.admission;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.scheduler.Scheduler;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admission gates under random arrivals, releases, cancellations and waits (decision D45): permits are never more
 * than {@code concurrency} per key nor {@code maxActive} in all, none is lost or handed out twice, the permits of one
 * key are given out in order of arrival, and once everyone has left nothing is held or remembered.
 *
 * <p>First come first served is an order of giving out ({@link AdmissionGate.Permit#sequence()}, 06 §4.2), not of
 * receipt. A waiter receives its permit on the scheduler; meanwhile a permit set free with nobody waiting (a run
 * cancelled while its own permit was on its way) goes to a newcomer at once, who then receives it first. That takes a
 * second permit of the key, so the order of receipt is asserted only when {@code concurrency} is 1. In production the
 * hand-overs run on several workers of {@code Schedulers.parallel()}, so even two of them may arrive in either order.
 */
class AdmissionGatePropertyTest {

    /** One step: who arrives, who leaves, how much time passes. */
    record Op(int kind, int key, int pick, int millis) {}

    private static final String[] KEYS = {"a", "b", "c"};

    @Provide
    Arbitrary<List<Op>> operations() {
        Arbitrary<Op> op = Combinators.combine(Arbitraries.integers().between(0, 5), Arbitraries.integers()
                .between(0, KEYS.length - 1), Arbitraries.integers().between(0, 50), Arbitraries.integers()
                .between(1, 400))
            .as(Op::new);
        return op.list().ofMinSize(1).ofMaxSize(120);
    }

    static final class Run {
        final int arrival;
        final String key;
        final AtomicInteger signals = new AtomicInteger();
        volatile AdmissionGate.Permit permit;
        volatile boolean failed;
        boolean released;
        Disposable subscription;

        Run(int arrival, String key) {
            this.arrival = arrival;
            this.key = key;
        }
    }

    @Property(tries = 300)
    void permitsStayWithinTheLimitsAndGoInOrder(@ForAll("operations") List<Op> ops,
        @ForAll @IntRange(min = 1, max = 3) int concurrency, @ForAll @IntRange(min = 1, max = 4) int maxActive,
        @ForAll @IntRange(min = 0, max = 4) int maxQueue) {
        VirtualTimeScheduler time = VirtualTimeScheduler.create();
        // Hand-overs wait until an operation runs them, so that cancellations and releases land between a permit
        // being handed over and its subscriber receiving it; timers run in virtual time.
        List<Runnable> handOvers = new ArrayList<>();
        Scheduler scheduler = new Scheduler() {
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
        Runnable drain = () -> {
            while (!handOvers.isEmpty()) {
                List<Runnable> due = List.copyOf(handOvers);
                handOvers.clear();
                due.forEach(Runnable::run);
            }
        };
        AdmissionGate gate = new AdmissionGate(maxActive, 100, 1000,
            family -> new AdmissionGate.Limits(concurrency, maxQueue, Duration.ofSeconds(1)),
            () -> time.now(TimeUnit.NANOSECONDS), () -> scheduler);
        List<Run> runs = new ArrayList<>();
        Map<String, List<Integer>> receivedOrder = new HashMap<>();
        for (Op op : ops) {
            switch (op.kind()) {
                case 0 -> {
                    Run run = new Run(runs.size(), KEYS[op.key()]);
                    runs.add(run);
                    run.subscription = gate.acquire("test.hot", run.key, Duration.ofMillis(op.millis()))
                        .subscribe(permit -> {
                            run.signals.incrementAndGet();
                            run.permit = permit;
                            receivedOrder.computeIfAbsent(run.key, k -> new ArrayList<>()).add(run.arrival);
                        }, error -> {
                            run.signals.incrementAndGet();
                            run.failed = true;
                        });
                }
                case 1 -> {
                    List<Run> holding = runs.stream().filter(r -> r.permit != null && !r.released).toList();
                    if (!holding.isEmpty()) {
                        Run run = holding.get(op.pick() % holding.size());
                        run.released = true;
                        run.permit.release();
                    }
                }
                case 2 -> {
                    List<Run> waiting = runs.stream().filter(r -> r.signals.get() == 0).toList();
                    if (!waiting.isEmpty()) {
                        waiting.get(op.pick() % waiting.size()).subscription.dispose();
                    }
                }
                case 3 -> time.advanceTimeBy(Duration.ofMillis(op.millis()));
                case 4 -> drain.run();
                default -> {
                    // A holder ends and, before its permit reaches the next waiter, a waiter goes away.
                    List<Run> holding = runs.stream().filter(r -> r.permit != null && !r.released).toList();
                    if (!holding.isEmpty()) {
                        Run run = holding.get(op.pick() % holding.size());
                        run.released = true;
                        run.permit.release();
                    }
                    List<Run> waiting = runs.stream().filter(r -> r.signals.get() == 0).toList();
                    if (!waiting.isEmpty()) {
                        waiting.get((op.pick() + op.millis()) % waiting.size()).subscription.dispose();
                    }
                }
            }
            check(gate, runs, concurrency, maxActive);
        }
        for (List<Integer> received : receivedOrder.values()) {
            List<Long> givenOut = received.stream().sorted().map(arrival -> runs.get(arrival).permit.sequence())
                .toList();
            assertThat(givenOut).as("permits of a key given out in order of arrival").isSorted();
            if (concurrency == 1) {
                assertThat(received).as("with one permit per key, also received in order of arrival").isSorted();
            }
        }

        // Everyone leaves: nothing is held or remembered afterwards.
        for (int round = 0; round < runs.size() + 1; round++) {
            runs.stream().filter(r -> r.signals.get() == 0).forEach(r -> r.subscription.dispose());
            runs.stream().filter(r -> r.permit != null && !r.released).forEach(r -> {
                r.released = true;
                r.permit.release();
            });
            drain.run();
            time.advanceTime();
        }
        assertThat(gate.active()).isZero();
        assertThat(gate.waiting()).isZero();
        assertThat(gate.keys()).isZero();
    }

    private static void check(AdmissionGate gate, List<Run> runs, int concurrency, int maxActive) {
        Map<String, Integer> perKey = new HashMap<>();
        int holding = 0;
        for (Run run : runs) {
            assertThat(run.signals.get()).as("a permit or a refusal, once").isLessThanOrEqualTo(1);
            if (run.permit != null && !run.released) {
                holding++;
                perKey.merge(run.key, 1, Integer::sum);
            }
        }
        assertThat(holding).isLessThanOrEqualTo(maxActive);
        perKey.values().forEach(count -> assertThat(count).isLessThanOrEqualTo(concurrency));
        // Permits on their way to a waiter are counted as held too.
        assertThat(gate.active()).as("permits counted as held").isBetween(holding, maxActive);
    }
}
