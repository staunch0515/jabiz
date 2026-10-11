package com.jabiz.runtime.process;

import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.operation.Operation;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * How a top-level execution is requested.
 *
 * @param idempotencyKey the caller's key for this request; a repeated request of the same actor with the same key
 *                       returns the first result without executing again (decision D4). Null for none.
 * @param beforeSteps    platform work that runs in the process's transaction right after its operation is recorded
 *                       and before its first step, so that it commits or rolls back with the process (the event
 *                       deliverer records a consumption this way, decision D14)
 * @param dryRun         whether to roll the transaction back once the process has completed, returning its output:
 *                       everything it would do is done and checked, nothing stays, and no after-commit step runs (an
 *                       import's preview, docs/design/20-imports.md section 5). Not with an idempotency key.
 * @param entry          the {@code entryOnly()} process the caller is the platform's own entry point for (decision
 *                       D41): only with it does such a process run, as the real caller. Null for none; the process API
 *                       never passes one.
 * @param lockWait       the longest the transaction waits for any lock - row locks, the platform's own, an idempotency
 *                       key, {@code HoldLock} steps of the process and its sub-processes - when shorter than
 *                       {@code jabiz.process.lock-timeout} (a job's items, decision D42); null for the global timeout
 *                       (docs/design/06-process.md section 4.1)
 */
public record ExecutionOptions(String idempotencyKey, Function<Operation, Mono<Void>> beforeSteps, boolean dryRun,
    String entry, Duration lockWait) {

    public static final ExecutionOptions NONE = new ExecutionOptions(null);

    /** Runs the process and rolls everything back. */
    public static final ExecutionOptions DRY_RUN = new ExecutionOptions(null, operation -> Mono.empty(), true);

    /** Keys a client may send: short, printable, no spaces. */
    public static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    public ExecutionOptions {
        Objects.requireNonNull(beforeSteps, "beforeSteps must not be null");
        if (dryRun && idempotencyKey != null) {
            throw new IllegalArgumentException("A dry run keeps no result to replay; it takes no idempotency key");
        }
        if (lockWait != null && (lockWait.isNegative() || lockWait.isZero())) {
            throw new IllegalArgumentException("lockWait must be positive");
        }
    }

    public ExecutionOptions(String idempotencyKey, Function<Operation, Mono<Void>> beforeSteps, boolean dryRun,
        String entry) {
        this(idempotencyKey, beforeSteps, dryRun, entry, null);
    }

    public ExecutionOptions(String idempotencyKey, Function<Operation, Mono<Void>> beforeSteps, boolean dryRun) {
        this(idempotencyKey, beforeSteps, dryRun, null, null);
    }

    public ExecutionOptions(String idempotencyKey, Function<Operation, Mono<Void>> beforeSteps) {
        this(idempotencyKey, beforeSteps, false);
    }

    public ExecutionOptions(String idempotencyKey) {
        this(idempotencyKey, operation -> Mono.empty());
    }

    public static ExecutionOptions idempotent(String key) {
        return new ExecutionOptions(key);
    }

    public ExecutionOptions withBeforeSteps(Function<Operation, Mono<Void>> work) {
        return new ExecutionOptions(idempotencyKey, work, dryRun, entry, lockWait);
    }

    /**
     * These options, from the platform's own entry point for {@code process} (decision D41): an {@code entryOnly()}
     * process runs only so. Only platform entry points (signing in, the entity API, uploads, imports...) pass it.
     */
    public ExecutionOptions atEntryOf(ProcessDefinition<?, ?, ?> process) {
        return new ExecutionOptions(idempotencyKey, beforeSteps, dryRun, process.name(), lockWait);
    }

    /** Whether these options come from the platform's own entry point for {@code process}. */
    public boolean isEntryOf(ProcessDefinition<?, ?, ?> process) {
        return process.name().equals(entry);
    }

    /** These options with the transaction's lock waits, {@code HoldLock} steps' included, bounded by {@code wait}. */
    public ExecutionOptions withLockWait(Duration wait) {
        return new ExecutionOptions(idempotencyKey, beforeSteps, dryRun, entry,
            Objects.requireNonNull(wait, "wait"));
    }

    /**
     * The longest wait of {@code HoldLock} steps, in the Reactor context of a running process and its sub-processes
     * (from {@link #lockWait()}).
     */
    public record LockWait(Duration atMost) {}
}
