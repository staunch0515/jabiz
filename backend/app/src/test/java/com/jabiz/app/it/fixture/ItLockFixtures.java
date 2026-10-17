package com.jabiz.app.it.fixture;

import com.jabiz.entity.Violation;
import com.jabiz.process.BlockingStep;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The process of the lock tests (docs/design/06-process.md section 4.1): it takes the named lock, shared or exclusive,
 * then counts itself in at a gate and waits there until the test opens it, holding the lock all the while.
 */
public final class ItLockFixtures {

    public static final String PERMISSION = "it.lock";

    /** @param gate where to wait after taking the lock; one the test has not closed is open */
    public record LockInput(String name, boolean shared, String gate, boolean fail) {}

    public record LockOutput(String gate) {}

    /** Gates the test closes before a process reaches them and opens to let it end. */
    public static final Map<String, CountDownLatch> GATES = new ConcurrentHashMap<>();

    /** How many processes have reached each gate, that is, hold the lock. */
    public static final Map<String, AtomicInteger> ARRIVED = new ConcurrentHashMap<>();

    public static int arrived(String gate) {
        AtomicInteger count = ARRIVED.get(gate);
        return count == null ? 0 : count.get();
    }

    public static final ProcessDefinition<LockInput, LockOutput, ProcessContext> LOCKED =
        ProcessDefinition.define("IT_LOCKED", 1, LockInput.class, LockOutput.class, ProcessContext.class, pb -> pb
            .permissions(PERMISSION)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put("input", input);
                return ctx;
            })
            .outputMapper(ctx -> new LockOutput(ctx.get("input", LockInput.class).gate()))
            .step("Take the lock", HoldLock.<ProcessContext>exclusive(ctx -> ctx.get("input", LockInput.class)
                .shared() ? null : ctx.get("input", LockInput.class).name()))
            .step("Share the lock", HoldLock.<ProcessContext>shared(ctx -> ctx.get("input", LockInput.class)
                .shared() ? ctx.get("input", LockInput.class).name() : null))
            .step("Wait at the gate", Gate.class, NoMetadata.INSTANCE)
            .compute("Fail when asked", (metadata, ctx) -> {
                if (ctx.get("input", LockInput.class).fail()) {
                    ctx.reject(new Violation(null, "IT_FAILED", "failed holding the lock"));
                }
            }));

    /** Takes the lock in {@link #LOCKED} run as a child (its gate open), then waits at its own gate. */
    public static final ProcessDefinition<LockInput, LockOutput, ProcessContext> PARENT =
        ProcessDefinition.define("IT_LOCKED_PARENT", 1, LockInput.class, LockOutput.class, ProcessContext.class,
            pb -> pb
                .permissions(PERMISSION)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("input", input);
                    return ctx;
                })
                .outputMapper(ctx -> new LockOutput(ctx.get("input", LockInput.class).gate()))
                .step("Lock in a child", CallProcess.of("IT_LOCKED", 1, ctx -> {
                    LockInput input = ctx.get("input", LockInput.class);
                    return new LockInput(input.name(), input.shared(), input.gate() + ":child", false);
                }, "child"))
                .step("Wait at the gate", Gate.class, NoMetadata.INSTANCE));

    /** As {@link #LOCKED} (exclusive), but the step waits at most {@link #BRIEF_WAIT} for the lock. */
    public static final java.time.Duration BRIEF_WAIT = java.time.Duration.ofMillis(300);

    public static final ProcessDefinition<LockInput, LockOutput, ProcessContext> LOCKED_BRIEFLY =
        ProcessDefinition.define("IT_LOCKED_BRIEFLY", 1, LockInput.class, LockOutput.class, ProcessContext.class,
            pb -> pb
                .permissions(PERMISSION)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("input", input);
                    return ctx;
                })
                .outputMapper(ctx -> new LockOutput(ctx.get("input", LockInput.class).gate()))
                .step("Take the lock briefly", HoldLock.<ProcessContext>exclusive(
                    ctx -> ctx.get("input", LockInput.class).name(), BRIEF_WAIT))
                .step("Wait at the gate", Gate.class, NoMetadata.INSTANCE));

    /**
     * Takes a lock nobody holds with a brief wait of its own, then reports the transaction's {@code lock_timeout}:
     * the step's wait applies to its own statement only (06 section 4.1).
     */
    public static final ProcessDefinition<LockInput, LockOutput, ProcessContext> LOCK_TIMEOUT_PROBE =
        ProcessDefinition.define("IT_LOCK_TIMEOUT", 1, LockInput.class, LockOutput.class, ProcessContext.class,
            pb -> pb
                .permissions(PERMISSION)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("input", input);
                    return ctx;
                })
                .outputMapper(ctx -> new LockOutput((String) ctx.get(ReadLockTimeout.KEY)))
                .step("Take a free lock briefly", HoldLock.<ProcessContext>exclusive(
                    ctx -> ctx.get("input", LockInput.class).name(), BRIEF_WAIT))
                .step("Read the lock timeout", ReadLockTimeout.class, NoMetadata.INSTANCE));

    /**
     * Takes a free lock with a brief wait of its own (the platform function, one statement), then the lock of the
     * input's name with the transaction's wait: after the first, the second still waits as long as the transaction
     * allows (ROADMAP phase 16p, T1).
     */
    public static final ProcessDefinition<LockInput, LockOutput, ProcessContext> BRIEFLY_THEN_HELD =
        ProcessDefinition.define("IT_LOCK_BRIEFLY_THEN_HELD", 1, LockInput.class, LockOutput.class,
            ProcessContext.class, pb -> pb
                .permissions(PERMISSION)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("input", input);
                    return ctx;
                })
                .outputMapper(ctx -> new LockOutput(ctx.get("input", LockInput.class).gate()))
                .step("Take a free lock briefly", HoldLock.<ProcessContext>exclusive(
                    ctx -> ctx.get("input", LockInput.class).name() + ":free", BRIEF_WAIT))
                .step("Take the held lock", HoldLock.<ProcessContext>exclusive(
                    ctx -> ctx.get("input", LockInput.class).name())));

    /** Puts the transaction's {@code lock_timeout} into the context (a platform-style step, for the test only). */
    @Component
    public static class ReadLockTimeout implements StepHandler<NoMetadata, ProcessContext> {
        static final String KEY = "lockTimeout";
        private final StorageAdapterRegistry storages;

        public ReadLockTimeout(StorageAdapterRegistry storages) {
            this.storages = storages;
        }

        @Override
        public Mono<Void> execute(NoMetadata metadata, ProcessContext ctx) {
            return storages.getEngine("default").select("SELECT current_setting('lock_timeout') AS t", Map.of())
                .single()
                .doOnNext(row -> ctx.put(KEY, row.get("t")))
                .then();
        }
    }

    /** Counts the process in and waits until its gate is open (a blocking step may wait). */
    @Component
    public static class Gate implements BlockingStep<NoMetadata, ProcessContext> {
        @Override
        public void run(NoMetadata metadata, ProcessContext ctx) throws InterruptedException {
            String gate = ctx.get("input", LockInput.class).gate();
            ARRIVED.computeIfAbsent(gate, key -> new AtomicInteger()).incrementAndGet();
            CountDownLatch latch = GATES.get(gate);
            if (latch != null && !latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("gate " + gate + " was never opened");
            }
        }
    }

    private ItLockFixtures() {}

    @Configuration
    static class Beans {

        @Bean
        ProcessDefinition<LockInput, LockOutput, ProcessContext> itLocked() {
            return LOCKED;
        }

        @Bean
        ProcessDefinition<LockInput, LockOutput, ProcessContext> itLockedParent() {
            return PARENT;
        }

        @Bean
        ProcessDefinition<LockInput, LockOutput, ProcessContext> itLockedBriefly() {
            return LOCKED_BRIEFLY;
        }

        @Bean
        ProcessDefinition<LockInput, LockOutput, ProcessContext> itLockTimeoutProbe() {
            return LOCK_TIMEOUT_PROBE;
        }

        @Bean
        ProcessDefinition<LockInput, LockOutput, ProcessContext> itLockBrieflyThenHeld() {
            return BRIEFLY_THEN_HELD;
        }
    }
}
