package com.jabiz.runtime.external;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Where external calls may be made (decision D38): opened by {@code ProcessExecutor} on the virtual thread of a
 * blocking step while the step runs, and closed right after, even when the step fails. Without one,
 * {@link JdkExternalHttp} refuses to call.
 *
 * <p>Only the platform's executors open scopes: {@link #open} checks its caller, so business code cannot make a call
 * outside a blocking step look like one, or a dry run look like a real run. The shared architecture rule
 * ({@code PlatformArchRules.noPlatformInternals}) keeps business packages away from this package as well.
 */
public final class ExternalCallScope {

    private static final ThreadLocal<ExternalCallScope> CURRENT = new ThreadLocal<>();
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    /** The classes that run blocking steps (and, from 16e-2, external job handlers). */
    private static final Set<String> OPENERS = Set.of("com.jabiz.runtime.process.ProcessExecutor");

    private final long processSeqId;
    private final String stepName;
    private final boolean dryRun;

    private ExternalCallScope(long processSeqId, String stepName, boolean dryRun) {
        this.processSeqId = processSeqId;
        this.stepName = Objects.requireNonNull(stepName, "stepName must not be null");
        this.dryRun = dryRun;
    }

    /** The process the step belongs to, for {@code ExternalServices.idempotencyKey}. */
    public long processSeqId() {
        return processSeqId;
    }

    /** The step, for messages. */
    public String stepName() {
        return stepName;
    }

    /** Whether the process is a dry run, in which only services that allow it are called. */
    public boolean dryRun() {
        return dryRun;
    }

    /** The scope of the running blocking step, if this thread runs one. */
    public static Optional<ExternalCallScope> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * Runs {@code work} on this thread within a new scope; for the platform's executors only.
     *
     * @throws IllegalStateException when called from anywhere else
     */
    public static <T> T open(long processSeqId, String stepName, boolean dryRun, Callable<T> work) throws Exception {
        Class<?> caller = WALKER.getCallerClass();
        if (!OPENERS.contains(caller.getName())) {
            throw new IllegalStateException("Only the platform opens external call scopes, not " + caller.getName());
        }
        return run(new ExternalCallScope(processSeqId, stepName, dryRun), work);
    }

    /** A scope for this package's tests. */
    static ExternalCallScope of(long processSeqId, String stepName, boolean dryRun) {
        return new ExternalCallScope(processSeqId, stepName, dryRun);
    }

    /** Runs {@code work} within {@code scope}; the previous scope (normally none) is restored after. */
    static <T> T run(ExternalCallScope scope, Callable<T> work) throws Exception {
        ExternalCallScope previous = CURRENT.get();
        CURRENT.set(scope);
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

    @Override
    public String toString() {
        return "ExternalCallScope[processSeqId=" + processSeqId + ", step=" + stepName + ", dryRun=" + dryRun + "]";
    }
}
