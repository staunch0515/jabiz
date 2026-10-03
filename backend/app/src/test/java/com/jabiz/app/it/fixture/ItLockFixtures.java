package com.jabiz.app.it.fixture;

import com.jabiz.entity.Violation;
import com.jabiz.process.BlockingStep;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

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
    }
}
