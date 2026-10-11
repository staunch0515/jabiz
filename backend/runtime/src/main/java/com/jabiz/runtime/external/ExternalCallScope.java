package com.jabiz.runtime.external;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Where external calls may be made (decision D38): opened by {@code ProcessExecutor} on the virtual thread of a
 * blocking step while the step runs, and by {@link ExternalJobExecutor} on the virtual thread of an external job's
 * handler while one attempt runs; closed right after, even when the work fails. Without one, {@link JdkExternalHttp}
 * refuses to call.
 *
 * <p>Only the platform's executors open scopes: {@link #open} and {@link #openJob} check their caller, so business code
 * cannot make a call outside a blocking step or a handler look like one, a dry run look like a real run, or a handler
 * call a service its job type does not declare. The shared architecture rule
 * ({@code PlatformArchRules.noPlatformInternals}) keeps business packages away from this package as well.
 */
public final class ExternalCallScope {

    private static final ThreadLocal<ExternalCallScope> CURRENT = new ThreadLocal<>();
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    /** The classes that run blocking steps and external job handlers. */
    private static final Set<String> OPENERS = Set.of("com.jabiz.runtime.process.ProcessExecutor",
        "com.jabiz.runtime.external.ExternalJobExecutor");

    private final long processSeqId;
    private final String stepName;
    private final boolean dryRun;
    private final String jobId;
    private final Set<String> services;

    private ExternalCallScope(long processSeqId, String stepName, boolean dryRun, String jobId, Set<String> services) {
        this.processSeqId = processSeqId;
        this.stepName = Objects.requireNonNull(stepName, "stepName must not be null");
        this.dryRun = dryRun;
        this.jobId = jobId;
        this.services = services == null ? null : Set.copyOf(services);
    }

    /** The process the step belongs to, for {@code ExternalServices.idempotencyKey}; 0 for a job. */
    public long processSeqId() {
        return processSeqId;
    }

    /** The step, or the job type of a job's handler, for messages. */
    public String stepName() {
        return stepName;
    }

    /** Whether the process is a dry run, in which only services that allow it are called; never for a job. */
    public boolean dryRun() {
        return dryRun;
    }

    /** The job whose handler runs here; empty within a blocking step. */
    public Optional<String> jobId() {
        return Optional.ofNullable(jobId);
    }

    /** Whether the work may call this service: any declared one in a blocking step, its type's own in a job. */
    public boolean allows(String service) {
        return services == null || services.contains(service);
    }

    /** The scope of the running blocking step or job handler, if this thread runs one. */
    public static Optional<ExternalCallScope> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * Runs {@code work} on this thread within a new scope of a blocking step; for the platform's executors only.
     *
     * @throws IllegalStateException when called from anywhere else
     */
    public static <T> T open(long processSeqId, String stepName, boolean dryRun, Callable<T> work) throws Exception {
        requirePlatform(WALKER.getCallerClass());
        return run(new ExternalCallScope(processSeqId, stepName, dryRun, null, null), work);
    }

    /**
     * Runs {@code work} on this thread within a new scope of an external job's handler, which may call only
     * {@code services}; for the platform's executors only.
     *
     * @throws IllegalStateException when called from anywhere else
     */
    public static <T> T openJob(String jobId, String jobType, Set<String> services, Callable<T> work)
        throws Exception {
        requirePlatform(WALKER.getCallerClass());
        Objects.requireNonNull(jobId, "jobId must not be null");
        Objects.requireNonNull(services, "services must not be null");
        return run(new ExternalCallScope(0L, "job " + jobType, false, jobId, services), work);
    }

    private static void requirePlatform(Class<?> caller) {
        if (!OPENERS.contains(caller.getName())) {
            throw new IllegalStateException("Only the platform opens external call scopes, not " + caller.getName());
        }
    }

    /** A scope for this package's tests. */
    static ExternalCallScope of(long processSeqId, String stepName, boolean dryRun) {
        return new ExternalCallScope(processSeqId, stepName, dryRun, null, null);
    }

    /** A job's scope for this package's tests. */
    static ExternalCallScope ofJob(String jobId, String jobType, Set<String> services) {
        return new ExternalCallScope(0L, "job " + jobType, false, jobId, services);
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
        return jobId == null
            ? "ExternalCallScope[processSeqId=" + processSeqId + ", step=" + stepName + ", dryRun=" + dryRun + "]"
            : "ExternalCallScope[jobId=" + jobId + ", " + stepName + ", services=" + services + "]";
    }
}
