package com.jabiz.app.it.fixture;

import com.jabiz.entity.Violation;
import com.jabiz.event.EventSubscription;
import com.jabiz.process.BlockingStep;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The processes of the admission tests (decision D45). {@value #HOT_PROCESS} queues under the family
 * {@value #FAMILY} with the key of its input, takes the named lock of the same name, counts itself in at its gate and
 * waits there until the test opens it, holding lock, connection and permit all the while; then, as its mode says,
 * adds one to a counter by reading and writing it back (protected by the lock alone), fails with 422 or 500, or does
 * nothing. {@value #SMALL_PROCESS} does the same in the family {@value #SMALL_FAMILY} (configured with a short queue
 * by the tests), {@value #ONE_PROCESS} in {@value #ONE_FAMILY} (one at a time), {@value #ITEM_PROCESS} and {@value #EVENT_PROCESS} as the system's (a job's items, an event
 * consumer's). {@value #PLAIN_PROCESS} queues for nothing.
 */
public final class ItAdmissionFixtures {

    public static final String PERMISSION = "it.hot";
    public static final String FAMILY = "test.hot";
    public static final String SMALL_FAMILY = "test.small";
    public static final String ONE_FAMILY = "test.one";
    public static final String HOT_PROCESS = "IT_HOT";
    public static final String SMALL_PROCESS = "IT_HOT_SMALL";
    public static final String ONE_PROCESS = "IT_HOT_ONE";
    public static final String ITEM_PROCESS = "IT_HOT_ITEM";
    public static final String EVENT_PROCESS = "IT_HOT_EVENT";
    public static final String PLAIN_PROCESS = "IT_PLAIN";
    public static final String PUBLISH_PROCESS = "IT_HOT_PUBLISH";
    public static final String EVENT = "it.hot-event";
    public static final String CONSUMER = "it.hot-consumer";

    /** What the process does once it holds the lock. */
    public enum Mode { OK, COUNT, REJECT, FAIL }

    /**
     * @param key  the admission key, and the lock's name after the family; null queues for nothing and takes no lock
     * @param gate where to wait holding everything; one the test has not closed is open
     * @param tag  the run's name in {@link #ORDER}
     */
    public record HotInput(String key, String gate, Mode mode, String tag) {}

    public record HotOutput(String tag) {}

    /** Gates the test closes before a process reaches them and opens to let it end. */
    public static final Map<String, CountDownLatch> GATES = new ConcurrentHashMap<>();
    /** How many processes reached each gate, that is, hold the lock. */
    public static final Map<String, AtomicInteger> ARRIVED = new ConcurrentHashMap<>();
    /** The tags of the runs in the order they took the lock. */
    public static final List<String> ORDER = new CopyOnWriteArrayList<>();

    public static int arrived(String gate) {
        AtomicInteger count = ARRIVED.get(gate);
        return count == null ? 0 : count.get();
    }

    public static String lockName(String family, String key) {
        return key == null ? null : family + ":" + key;
    }

    private static HotInput input(ProcessContext ctx) {
        return ctx.get("input", HotInput.class);
    }

    static ProcessDefinition<HotInput, HotOutput, ProcessContext> hot(String name, String family,
        java.util.function.Consumer<ProcessDefinitionBuilder<HotInput, HotOutput, ProcessContext>> access) {
        return ProcessDefinition.define(name, 1, HotInput.class, HotOutput.class, ProcessContext.class, pb -> {
            pb.permissions(PERMISSION)
                .admission(family, HotInput::key)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("input", input);
                    return ctx;
                })
                .outputMapper(ctx -> new HotOutput(input(ctx).tag()))
                .step("Take the lock", HoldLock.<ProcessContext>exclusive(ctx -> lockName(family, input(ctx).key())))
                .step("Wait at the gate", Gate.class, NoMetadata.INSTANCE)
                .step("Count", Count.class, NoMetadata.INSTANCE)
                .compute("Fail when asked", (metadata, ctx) -> {
                    if (input(ctx).mode() == Mode.REJECT) {
                        ctx.reject(new Violation(null, "IT_HOT_REJECTED", "rejected holding the lock"));
                    } else if (input(ctx).mode() == Mode.FAIL) {
                        throw new IllegalStateException("failed holding the lock");
                    }
                });
            access.accept(pb);
        });
    }

    public static final ProcessDefinition<HotInput, HotOutput, ProcessContext> HOT =
        hot(HOT_PROCESS, FAMILY, pb -> { });
    public static final ProcessDefinition<HotInput, HotOutput, ProcessContext> SMALL =
        hot(SMALL_PROCESS, SMALL_FAMILY, pb -> { });
    /** One at a time per key (configured so by the tests): the lock is taken in the order of the permits. */
    public static final ProcessDefinition<HotInput, HotOutput, ProcessContext> ONE =
        hot(ONE_PROCESS, ONE_FAMILY, pb -> { });
    public static final ProcessDefinition<HotInput, HotOutput, ProcessContext> ITEM =
        hot(ITEM_PROCESS, SMALL_FAMILY, ProcessDefinitionBuilder::internal);
    public static final ProcessDefinition<HotInput, HotOutput, ProcessContext> EVENT_RUN =
        hot(EVENT_PROCESS, SMALL_FAMILY, ProcessDefinitionBuilder::internal);

    /** A process queueing for nothing: what any other request does meanwhile. */
    public static final ProcessDefinition<HotInput, HotOutput, ProcessContext> PLAIN =
        ProcessDefinition.single(PLAIN_PROCESS, 1, HotInput.class, HotOutput.class,
            (input, ctx) -> new HotOutput(input.tag())).withPermissions(PERMISSION);

    /** Publishes {@value #EVENT} for a key; its consumer runs {@value #EVENT_PROCESS}. */
    public static final ProcessDefinition<HotInput, HotOutput, ProcessContext> PUBLISH =
        ProcessDefinition.define(PUBLISH_PROCESS, 1, HotInput.class, HotOutput.class, ProcessContext.class, pb -> pb
            .permissions(PERMISSION)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put("input", input);
                return ctx;
            })
            .outputMapper(ctx -> new HotOutput(input(ctx).tag()))
            .step("Publish", PublishEvent.<ProcessContext>of(EVENT, ctx -> Map.of("key", input(ctx).key(),
                "tag", input(ctx).tag()))));

    /** Counts the process in, in {@link #ORDER} too, and waits until its gate is open. */
    @Component
    public static class Gate implements BlockingStep<NoMetadata, ProcessContext> {
        @Override
        public void run(NoMetadata metadata, ProcessContext ctx) throws InterruptedException {
            HotInput input = input(ctx);
            ORDER.add(String.valueOf(input.tag()));
            if (input.gate() == null) {
                return;
            }
            ARRIVED.computeIfAbsent(input.gate(), key -> new AtomicInteger()).incrementAndGet();
            CountDownLatch latch = GATES.get(input.gate());
            if (latch != null && !latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("gate " + input.gate() + " was never opened");
            }
        }
    }

    /**
     * In mode {@code COUNT}: reads the key's counter and writes it back one higher in two statements, so that only the
     * lock keeps two runs from losing an update (a platform-style step, for the test only).
     */
    @Component
    public static class Count implements StepHandler<NoMetadata, ProcessContext> {
        private final StorageAdapterRegistry storages;

        public Count(StorageAdapterRegistry storages) {
            this.storages = storages;
        }

        @Override
        public Mono<Void> execute(NoMetadata metadata, ProcessContext ctx) {
            HotInput input = input(ctx);
            if (input.mode() != Mode.COUNT) {
                return Mono.empty();
            }
            var engine = storages.getEngine("default");
            Map<String, BoundValue> key = Map.of("k", BoundValue.of(input.key()));
            return engine.select("SELECT n FROM it_admission_counter WHERE k = :k", key).singleOrEmpty()
                .map(row -> ((Number) row.get("n")).longValue())
                .defaultIfEmpty(0L)
                .flatMap(n -> engine.select("INSERT INTO it_admission_counter (k, n) VALUES (:k, :n)"
                    + " ON CONFLICT (k) DO UPDATE SET n = EXCLUDED.n RETURNING n", Map.of("k",
                    BoundValue.of(input.key()), "n", BoundValue.of(n + 1))).then());
        }
    }

    /** Clears what a test left in the shared maps (the gates, arrivals and order). */
    public static void reset() {
        GATES.clear();
        ARRIVED.clear();
        ORDER.clear();
    }

    /** {@link #ORDER} as it is now. */
    public static List<String> order() {
        return Collections.unmodifiableList(List.copyOf(ORDER));
    }

    private ItAdmissionFixtures() {}

    @Configuration
    static class Beans {

        @Bean
        ProcessDefinition<HotInput, HotOutput, ProcessContext> itHot() {
            return HOT;
        }

        @Bean
        ProcessDefinition<HotInput, HotOutput, ProcessContext> itHotSmall() {
            return SMALL;
        }

        @Bean
        ProcessDefinition<HotInput, HotOutput, ProcessContext> itHotOne() {
            return ONE;
        }

        @Bean
        ProcessDefinition<HotInput, HotOutput, ProcessContext> itHotItem() {
            return ITEM;
        }

        @Bean
        ProcessDefinition<HotInput, HotOutput, ProcessContext> itHotEvent() {
            return EVENT_RUN;
        }

        @Bean
        ProcessDefinition<HotInput, HotOutput, ProcessContext> itPlain() {
            return PLAIN;
        }

        @Bean
        ProcessDefinition<HotInput, HotOutput, ProcessContext> itHotPublish() {
            return PUBLISH;
        }

        @Bean
        EventSubscription<HotInput> itHotConsumer() {
            return EventSubscription.of(CONSUMER, EVENT, EVENT_RUN, event -> new HotInput(
                (String) event.payload().get("key"), null, Mode.OK, (String) event.payload().get("tag")));
        }
    }
}
