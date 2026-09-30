package com.jabiz.runtime.process;

import com.jabiz.runtime.operation.Operation;
import reactor.core.publisher.Mono;

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
 */
public record ExecutionOptions(String idempotencyKey, Function<Operation, Mono<Void>> beforeSteps, boolean dryRun) {

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
        return new ExecutionOptions(idempotencyKey, work, dryRun);
    }
}
