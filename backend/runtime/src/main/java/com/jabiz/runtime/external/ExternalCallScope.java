package com.jabiz.runtime.external;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Where external calls may be made (decision D38): set by {@code ProcessExecutor} on the virtual thread of a blocking
 * step while the step runs, and cleared right after. Without one, {@link JdkExternalHttp} refuses to call.
 *
 * @param processSeqId the process the step belongs to, for {@code ExternalServices.idempotencyKey}
 * @param stepName     the step, for messages
 * @param dryRun       whether the process is a dry run, in which only services that allow it are called
 */
public record ExternalCallScope(long processSeqId, String stepName, boolean dryRun) {

    private static final ThreadLocal<ExternalCallScope> CURRENT = new ThreadLocal<>();

    public ExternalCallScope {
        Objects.requireNonNull(stepName, "stepName must not be null");
    }

    /** The scope of the running blocking step, if this thread runs one. */
    public static Optional<ExternalCallScope> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Runs {@code work} on this thread within this scope; the previous scope (normally none) is restored after. */
    public <T> T call(Callable<T> work) throws Exception {
        ExternalCallScope previous = CURRENT.get();
        CURRENT.set(this);
        try {
            return work.call();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
