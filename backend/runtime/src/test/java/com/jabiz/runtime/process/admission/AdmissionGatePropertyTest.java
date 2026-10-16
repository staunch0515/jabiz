package com.jabiz.runtime.process.admission;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import reactor.core.Disposable;
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
 * than {@code concurrency} per key nor {@code maxActive} in all, none is lost or handed out twice, runs of one key are
 * admitted in order of arrival, and once everyone has left nothing is held or remembered.
 */
class AdmissionGatePropertyTest {

    /** One step: who arrives, who leaves, how much time passes. */
    record Op(int kind, int key, int pick, int millis) {}

    private static final String[] KEYS = {"a", "b", "c"};

    @Provide
    Arbitrary<List<Op>> operations() {
        Arbitrary<Op> op = Combinators.combine(Arbitraries.integers().between(0, 3), Arbitraries.integers()
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
        AdmissionGate gate = new AdmissionGate(maxActive, 100, 1000,
            family -> new AdmissionGate.Limits(concurrency, maxQueue, Duration.ofSeconds(1)),
            () -> time.now(TimeUnit.NANOSECONDS), () -> time);
        List<Run> runs = new ArrayList<>();
        Map<String, List<Integer>> admittedOrder = new HashMap<>();
        for (Op op : ops) {
            switch (op.kind()) {
                case 0 -> {
                    Run run = new Run(runs.size(), KEYS[op.key()]);
                    runs.add(run);
                    run.subscription = gate.acquire("test.hot", run.key, Duration.ofMillis(op.millis()))
                        .subscribe(permit -> {
                            run.signals.incrementAndGet();
                            run.permit = permit;
                            admittedOrder.computeIfAbsent(run.key, k -> new ArrayList<>()).add(run.arrival);
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
                default -> time.advanceTimeBy(Duration.ofMillis(op.millis()));
            }
            time.advanceTime();
            check(gate, runs, concurrency, maxActive);
        }
        for (List<Integer> order : admittedOrder.values()) {
            assertThat(order).as("runs of a key in order of arrival").isSorted();
        }

        // Everyone leaves: nothing is held or remembered afterwards.
        for (int round = 0; round < runs.size() + 1; round++) {
            runs.stream().filter(r -> r.signals.get() == 0).forEach(r -> r.subscription.dispose());
            runs.stream().filter(r -> r.permit != null && !r.released).forEach(r -> {
                r.released = true;
                r.permit.release();
            });
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
        // Permits handed over but not yet received do not happen here: the virtual scheduler runs them at once.
        assertThat(gate.active()).as("permits counted as held").isEqualTo(holding);
    }
}
