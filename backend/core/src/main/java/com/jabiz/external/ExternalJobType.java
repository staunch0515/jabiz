package com.jabiz.external;

import com.jabiz.param.ControlledParams;
import com.jabiz.security.Sensitive;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * A kind of long call to another system, run as an asynchronous external job (decision D38 item 3,
 * docs/design/11-ledger-events-jobs.md section 4a), declared as a bean. A process registers a job with the platform
 * step {@code StartExternalJob} and gets its id at once; the platform's executor runs the {@link #handler} outside any
 * transaction, at most {@link #maxConcurrency} at a time across all instances, each attempt bounded by
 * {@link #timeout} and failed attempts retried after a backoff; once the job has a result, the platform runs the
 * {@link #onCompleted} process with an {@link ExternalJobCompletion} as the system, or once it has failed for good the
 * {@link #onFailed} process with an {@link ExternalJobFailure}; a type that declares {@link #onCancelled} has that
 * process run with an {@link ExternalJobCancellation} once a cancelled job has ended (decision D52 item 2). All must be
 * {@code internal()}. A type that declares {@link #enabledBy} is held while that business parameter is not
 * {@code true} (decision D52 item 4): its jobs wait without using attempts.
 *
 * <p>The declaration is not validated when built: the startup check {@code EXTERNAL} reports every problem at once
 * ({@link #problems()} and the references the runtime resolves).
 *
 * @param name           {@value #NAME_PATTERN}, unique within the application
 * @param inputType      a record without {@link Sensitive} components: the input is stored as JSON with the job
 * @param resultType     a record: the result is stored as JSON (at most 256 KiB) and handed to the completion process
 * @param handler        the bean class that runs an attempt
 * @param services       the external services the handler may call, and nothing else
 * @param timeout        the longest one attempt may run
 * @param maxAttempts    attempts in all, the first included
 * @param initialBackoff the wait after the first failed attempt; it doubles after each further one
 * @param maxBackoff     the longest wait between attempts
 * @param maxConcurrency attempts of this type running at the same time, across all instances
 * @param onCompleted    the process run with the result
 * @param onFailed       the process run when the job failed for good
 * @param maxOpenPerRequester jobs of this type one requester may have open (neither finished nor cancelled) at a
 *                       time; starting one more is refused with 422 {@code EXTERNAL_JOB_LIMIT}
 * @param oneOpenPerReference whether a reference may have only one open job of this type at a time (the same
 *                       refusal); every start then needs a reference
 * @param maxInputBytes  the largest input stored, in bytes of JSON (decision D50 item 1):
 *                       {@value #DEFAULT_MAX_INPUT_BYTES} unless declared, at most {@value #MAX_INPUT_BYTES}
 * @param inputFiles     the files a job may read (decision D50 item 2), or null when it reads none
 * @param outputFiles    the files a job may produce (decision D50 item 3), or null when it produces none
 * @param onCancelled    the process run once a cancelled job has ended (decision D52 item 2), or null to run none (as
 *                       before)
 * @param enabledBy      the key of the boolean business parameter that must be {@code true} now for jobs of the type to
 *                       be claimed (decision D52 item 4), or null when the type is never held (as before)
 */
public record ExternalJobType<I, R>(String name, Class<I> inputType, Class<R> resultType,
    Class<? extends ExternalJobHandler<I, R>> handler, Set<String> services, Duration timeout, int maxAttempts,
    Duration initialBackoff, Duration maxBackoff, int maxConcurrency, String onCompleted, String onFailed,
    int maxOpenPerRequester, boolean oneOpenPerReference, int maxInputBytes, InputFiles inputFiles,
    OutputFiles outputFiles, String onCancelled, String enabledBy) {

    static final String NAME_PATTERN = "[a-z][a-z0-9._-]{0,99}";
    public static final Pattern NAME = Pattern.compile(NAME_PATTERN);

    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(1);
    /** Longer work is better split: a lost instance holds its jobs until the lease ends. */
    public static final Duration MAX_TIMEOUT = Duration.ofHours(1);
    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    public static final int MAX_ATTEMPTS = 100;
    public static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofSeconds(10);
    public static final Duration DEFAULT_MAX_BACKOFF = Duration.ofMinutes(10);
    public static final int DEFAULT_MAX_CONCURRENCY = 4;
    public static final int MAX_CONCURRENCY = 1000;
    /** A requester's open jobs of one type; what one person can start before the first ones finish. */
    public static final int DEFAULT_MAX_OPEN_PER_REQUESTER = 10;
    public static final int MAX_OPEN_PER_REQUESTER = 10_000;
    /** The input stored unless the type declares otherwise: 64 KiB of JSON (decision D38). */
    public static final int DEFAULT_MAX_INPUT_BYTES = 64 * 1024;
    /** The largest input a type may declare: 1 MiB of JSON, kept forever with the job (decision D50 item 1). */
    public static final int MAX_INPUT_BYTES = 1024 * 1024;
    /** The smallest input limit a type may declare. */
    public static final int MIN_INPUT_BYTES = 1024;
    /** The most files one job may read, or one attempt produce (decision D50). */
    public static final int MAX_FILES = 20;
    /** The largest file a job may read: copied to the executor's disk for the attempt, not held in memory. */
    public static final long MAX_INPUT_FILE_BYTES = 100L * 1024 * 1024;
    /** The largest file a job may produce: the handler holds it in memory. */
    public static final long MAX_OUTPUT_FILE_BYTES = 50L * 1024 * 1024;
    /** The most all files of one attempt may come to together (decision D50 item 3). */
    public static final long MAX_OUTPUT_TOTAL_BYTES = 200L * 1024 * 1024;
    /** What all files of one attempt may come to unless the type declares otherwise. */
    public static final long DEFAULT_MAX_OUTPUT_TOTAL_BYTES = 16L * 1024 * 1024;

    private static final int MAX_TYPE_DEPTH = 8;

    /**
     * The files a job of the type may read (decision D50 item 2): the starting process names them
     * ({@code StartExternalJob.files}), the requester must be allowed to read each one, and the handler reads them,
     * and only them, during an attempt ({@link ExternalJobContext#files()}).
     *
     * @param policies the file policies the files may be of; a file of any other is refused
     * @param maxFiles the most files one job reads
     * @param maxBytes the largest file one job reads
     */
    public record InputFiles(Set<String> policies, int maxFiles, long maxBytes) {
        public InputFiles {
            policies = policies == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(policies));
        }
    }

    /**
     * The files a job of the type may produce (decision D50 item 3): the handler returns them with its result
     * ({@link ExternalJobResult#file}); the platform stores each as an upload of the requester under {@code policy},
     * through the same checks and processing as {@code /api/files}, and hands their ids to the completion process.
     *
     * @param policy   the file policy they are stored under; the requester must hold its upload permission
     * @param maxFiles the most files one attempt produces
     * @param maxBytes the largest file one attempt produces (the policy's own limit applies too)
     * @param maxTotalBytes all files of one attempt together: what the handler may hold in memory at the end of an
     *                 attempt, and what the executor reserves of its output budget while the attempt runs
     */
    public record OutputFiles(String policy, int maxFiles, long maxBytes, long maxTotalBytes) {

        /**
         * The files with the default total: all of them at their largest, but no more than
         * {@value ExternalJobType#DEFAULT_MAX_OUTPUT_TOTAL_BYTES} bytes unless one file alone is larger.
         */
        public OutputFiles(String policy, int maxFiles, long maxBytes) {
            this(policy, maxFiles, maxBytes, defaultTotal(maxFiles, maxBytes));
        }

        static long defaultTotal(int maxFiles, long maxBytes) {
            if (maxFiles <= 0 || maxBytes <= 0) {
                return maxBytes;
            }
            long all = maxBytes > Long.MAX_VALUE / maxFiles ? Long.MAX_VALUE : maxFiles * maxBytes;
            return Math.max(maxBytes, Math.min(all, DEFAULT_MAX_OUTPUT_TOTAL_BYTES));
        }
    }

    public ExternalJobType {
        services = services == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(services));
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        initialBackoff = initialBackoff == null ? DEFAULT_INITIAL_BACKOFF : initialBackoff;
        maxBackoff = maxBackoff == null ? DEFAULT_MAX_BACKOFF : maxBackoff;
    }

    /**
     * A declaration as decision D38 had it, before a type could declare its input limit and its files: the default
     * limit, no files. Kept for code of line 1.2 that calls the canonical constructor of then (decision D50 item 6).
     */
    public ExternalJobType(String name, Class<I> inputType, Class<R> resultType,
        Class<? extends ExternalJobHandler<I, R>> handler, Set<String> services, Duration timeout, int maxAttempts,
        Duration initialBackoff, Duration maxBackoff, int maxConcurrency, String onCompleted, String onFailed,
        int maxOpenPerRequester, boolean oneOpenPerReference) {
        this(name, inputType, resultType, handler, services, timeout, maxAttempts, initialBackoff, maxBackoff,
            maxConcurrency, onCompleted, onFailed, maxOpenPerRequester, oneOpenPerReference, DEFAULT_MAX_INPUT_BYTES,
            null, null, null, null);
    }

    /**
     * A declaration as decision D50 had it, before a type could declare a cancellation process: none is run. Kept for
     * code of line 1.2 that calls the canonical constructor of then (decision D52 item 2).
     */
    public ExternalJobType(String name, Class<I> inputType, Class<R> resultType,
        Class<? extends ExternalJobHandler<I, R>> handler, Set<String> services, Duration timeout, int maxAttempts,
        Duration initialBackoff, Duration maxBackoff, int maxConcurrency, String onCompleted, String onFailed,
        int maxOpenPerRequester, boolean oneOpenPerReference, int maxInputBytes, InputFiles inputFiles,
        OutputFiles outputFiles) {
        this(name, inputType, resultType, handler, services, timeout, maxAttempts, initialBackoff, maxBackoff,
            maxConcurrency, onCompleted, onFailed, maxOpenPerRequester, oneOpenPerReference, maxInputBytes, inputFiles,
            outputFiles, null, null);
    }

    /**
     * A declaration as decision D52 item 2 had it, before a type could be held by a business parameter: never held.
     * Kept for code of line 1.2 that calls the canonical constructor of then (decision D52 item 4).
     */
    public ExternalJobType(String name, Class<I> inputType, Class<R> resultType,
        Class<? extends ExternalJobHandler<I, R>> handler, Set<String> services, Duration timeout, int maxAttempts,
        Duration initialBackoff, Duration maxBackoff, int maxConcurrency, String onCompleted, String onFailed,
        int maxOpenPerRequester, boolean oneOpenPerReference, int maxInputBytes, InputFiles inputFiles,
        OutputFiles outputFiles, String onCancelled) {
        this(name, inputType, resultType, handler, services, timeout, maxAttempts, initialBackoff, maxBackoff,
            maxConcurrency, onCompleted, onFailed, maxOpenPerRequester, oneOpenPerReference, maxInputBytes, inputFiles,
            outputFiles, onCancelled, null);
    }

    public static <I, R> ExternalJobType<I, R> define(String name, Class<I> inputType, Class<R> resultType,
        Consumer<Builder<I, R>> configuration) {
        Builder<I, R> builder = new Builder<>(name, inputType, resultType);
        configuration.accept(builder);
        return builder.build();
    }

    /**
     * How long to wait after {@code failures} failed attempts before the next one: {@code initialBackoff × 2^(n−1)},
     * at most {@code maxBackoff}; zero before the first attempt.
     */
    public Duration backoffAfter(int failures) {
        if (failures <= 0) {
            return Duration.ZERO;
        }
        Duration wait = initialBackoff;
        for (int i = 1; i < failures; i++) {
            if (wait.compareTo(maxBackoff) >= 0) {
                break;
            }
            wait = wait.multipliedBy(2);
        }
        return wait.compareTo(maxBackoff) > 0 ? maxBackoff : wait;
    }

    /**
     * Every problem of the declaration the core can tell, as {@code "location: message"}; that the handler, the
     * services and the processes exist is the runtime's to check.
     */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (name == null || !NAME.matcher(name).matches()) {
            problems.add("name: '" + name + "' must match " + NAME_PATTERN);
        }
        if (inputType == null || !inputType.isRecord()) {
            problems.add("inputType: must be a record");
        } else {
            sensitiveComponents(inputType, "", new HashSet<>(), 0).forEach(path ->
                problems.add("inputType: component " + path + " is @Sensitive; a job's input is stored, secrets are "
                    + "read by the handler from its service instead"));
        }
        if (resultType == null || !resultType.isRecord()) {
            problems.add("resultType: must be a record");
        } else {
            sensitiveComponents(resultType, "", new HashSet<>(), 0).forEach(path ->
                problems.add("resultType: component " + path + " is @Sensitive; a job's result is stored and handed "
                    + "to a process, it cannot carry secrets"));
        }
        if (handler == null) {
            problems.add("handler: missing");
        }
        for (String service : services) {
            if (service == null || !ExternalService.NAME.matcher(service).matches()) {
                problems.add("services: '" + service + "' is not a service name");
            }
        }
        if (!timeout.isPositive() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            problems.add("timeout: must be positive and at most " + MAX_TIMEOUT);
        }
        if (maxAttempts < 1 || maxAttempts > MAX_ATTEMPTS) {
            problems.add("retry: attempts must be between 1 and " + MAX_ATTEMPTS);
        }
        if (!initialBackoff.isPositive() || !maxBackoff.isPositive() || maxBackoff.compareTo(initialBackoff) < 0) {
            problems.add("retry: backoffs must be positive, the maximum at least the initial one");
        }
        if (maxConcurrency < 1 || maxConcurrency > MAX_CONCURRENCY) {
            problems.add("maxConcurrency: must be between 1 and " + MAX_CONCURRENCY);
        }
        if (maxOpenPerRequester < 1 || maxOpenPerRequester > MAX_OPEN_PER_REQUESTER) {
            problems.add("maxOpenPerRequester: must be between 1 and " + MAX_OPEN_PER_REQUESTER);
        }
        if (maxInputBytes < MIN_INPUT_BYTES || maxInputBytes > MAX_INPUT_BYTES) {
            problems.add("maxInputSize: must be between " + MIN_INPUT_BYTES + " and " + MAX_INPUT_BYTES + " bytes");
        }
        if (inputFiles != null) {
            if (inputFiles.policies().isEmpty()) {
                problems.add("inputFiles: name the file policies the files may be of");
            }
            for (String policy : inputFiles.policies()) {
                if (policy == null || policy.isBlank()) {
                    problems.add("inputFiles: a policy name is blank");
                }
            }
            if (inputFiles.maxFiles() < 1 || inputFiles.maxFiles() > MAX_FILES) {
                problems.add("inputFiles: the most files must be between 1 and " + MAX_FILES);
            }
            if (inputFiles.maxBytes() < 1 || inputFiles.maxBytes() > MAX_INPUT_FILE_BYTES) {
                problems.add("inputFiles: the largest file must be between 1 and " + MAX_INPUT_FILE_BYTES + " bytes");
            }
        }
        if (outputFiles != null) {
            if (outputFiles.policy() == null || outputFiles.policy().isBlank()) {
                problems.add("outputFiles: name the file policy the files are stored under");
            }
            if (outputFiles.maxFiles() < 1 || outputFiles.maxFiles() > MAX_FILES) {
                problems.add("outputFiles: the most files must be between 1 and " + MAX_FILES);
            }
            if (outputFiles.maxBytes() < 1 || outputFiles.maxBytes() > MAX_OUTPUT_FILE_BYTES) {
                problems.add("outputFiles: the largest file must be between 1 and " + MAX_OUTPUT_FILE_BYTES
                    + " bytes");
            }
            if (outputFiles.maxTotalBytes() < Math.max(1, outputFiles.maxBytes())
                || outputFiles.maxTotalBytes() > MAX_OUTPUT_TOTAL_BYTES) {
                problems.add("outputFiles: all files together must be between the largest file and "
                    + MAX_OUTPUT_TOTAL_BYTES + " bytes");
            }
        }
        if (onCompleted == null || onCompleted.isBlank()) {
            problems.add("onCompleted: name the process that receives the result");
        }
        if (onFailed == null || onFailed.isBlank()) {
            problems.add("onFailed: name the process that hears of a failure");
        }
        if (onCancelled != null && onCancelled.isBlank()) {
            problems.add("onCancelled: name the process that hears of a cancellation, or declare none");
        }
        if (enabledBy != null && !ControlledParams.validKey(enabledBy)) {
            problems.add("enabledBy: '" + enabledBy + "' is not a parameter key (lower case words of letters and "
                + "digits joined by '.', '-' or '_', at most 200 characters)");
        }
        return List.copyOf(problems);
    }

    /** Paths of the {@link Sensitive} components of a record, nested records and their collections included. */
    private static List<String> sensitiveComponents(Class<?> type, String prefix, Set<Class<?>> seen, int depth) {
        List<String> found = new ArrayList<>();
        if (type == null || !type.isRecord() || depth > MAX_TYPE_DEPTH || !seen.add(type)) {
            return found;
        }
        for (RecordComponent component : type.getRecordComponents()) {
            String path = prefix + component.getName();
            if (component.isAnnotationPresent(Sensitive.class)) {
                found.add(path);
            }
            for (Class<?> nested : classesOf(component.getGenericType())) {
                found.addAll(sensitiveComponents(nested, path + ".", seen, depth + 1));
            }
        }
        seen.remove(type);
        return found;
    }

    private static List<Class<?>> classesOf(Type type) {
        List<Class<?>> classes = new ArrayList<>();
        if (type instanceof Class<?> c) {
            classes.add(c.isArray() ? c.getComponentType() : c);
        } else if (type instanceof ParameterizedType p) {
            for (Type argument : p.getActualTypeArguments()) {
                classes.addAll(classesOf(argument));
            }
        }
        return classes;
    }

    @Override
    public String toString() {
        return "ExternalJobType[name=" + name + ", handler=" + (handler == null ? null : handler.getSimpleName())
            + ", services=" + services + ", timeout=" + timeout + ", maxAttempts=" + maxAttempts + ", maxConcurrency="
            + maxConcurrency + ", maxOpenPerRequester=" + maxOpenPerRequester + ", oneOpenPerReference="
            + oneOpenPerReference + ", maxInputBytes=" + maxInputBytes + ", inputFiles=" + inputFiles
            + ", outputFiles=" + outputFiles + ", onCompleted=" + onCompleted + ", onFailed=" + onFailed
            + ", onCancelled=" + onCancelled + ", enabledBy=" + enabledBy + "]";
    }

    public static final class Builder<I, R> {
        static final int DEFAULT_MAX_OPEN = DEFAULT_MAX_OPEN_PER_REQUESTER;
        private final String name;
        private final Class<I> inputType;
        private final Class<R> resultType;
        private Class<? extends ExternalJobHandler<I, R>> handler;
        private final Set<String> services = new LinkedHashSet<>();
        private Duration timeout;
        private int maxAttempts = DEFAULT_MAX_ATTEMPTS;
        private Duration initialBackoff;
        private Duration maxBackoff;
        private int maxConcurrency = DEFAULT_MAX_CONCURRENCY;
        private int maxOpenPerRequester = DEFAULT_MAX_OPEN_PER_REQUESTER;
        private boolean oneOpenPerReference;
        private int maxInputBytes = DEFAULT_MAX_INPUT_BYTES;
        private InputFiles inputFiles;
        private OutputFiles outputFiles;
        private String onCompleted;
        private String onFailed;
        private String onCancelled;
        private String enabledBy;

        private Builder(String name, Class<I> inputType, Class<R> resultType) {
            this.name = name;
            this.inputType = inputType;
            this.resultType = resultType;
        }

        public Builder<I, R> handler(Class<? extends ExternalJobHandler<I, R>> type) {
            this.handler = type;
            return this;
        }

        /** The declared external services the handler calls; it may call no other. */
        public Builder<I, R> services(String... names) {
            services.addAll(List.of(names));
            return this;
        }

        public Builder<I, R> timeout(Duration limit) {
            this.timeout = limit;
            return this;
        }

        /**
         * @param attempts attempts in all, the first included
         * @param initial  the wait after the first failure, doubled after each further one
         * @param max      the longest wait
         */
        public Builder<I, R> retry(int attempts, Duration initial, Duration max) {
            this.maxAttempts = attempts;
            this.initialBackoff = initial;
            this.maxBackoff = max;
            return this;
        }

        public Builder<I, R> maxConcurrency(int limit) {
            this.maxConcurrency = limit;
            return this;
        }

        /** Jobs of this type one requester may have open at a time (default {@value #DEFAULT_MAX_OPEN}). */
        public Builder<I, R> maxOpenPerRequester(int limit) {
            this.maxOpenPerRequester = limit;
            return this;
        }

        /**
         * Only one open job of this type per reference (a product's copy is written once at a time); every start
         * needs a reference.
         */
        public Builder<I, R> oneOpenPerReference() {
            this.oneOpenPerReference = true;
            return this;
        }

        /**
         * The largest input stored, in bytes of JSON: {@value ExternalJobType#DEFAULT_MAX_INPUT_BYTES} unless declared,
         * from {@value ExternalJobType#MIN_INPUT_BYTES} to {@value ExternalJobType#MAX_INPUT_BYTES} (decision D50
         * item 1). The input is kept forever with the job: put nothing in it the job does not need.
         */
        public Builder<I, R> maxInputSize(int bytes) {
            this.maxInputBytes = bytes;
            return this;
        }

        /**
         * Jobs read files (decision D50 item 2): at most {@code maxFiles}, each at most {@code maxBytes}, each of one
         * of {@code policies}. The starting process names them; the requester must be allowed to read every one.
         */
        public Builder<I, R> inputFiles(int maxFiles, long maxBytes, String... policies) {
            this.inputFiles = new InputFiles(new LinkedHashSet<>(List.of(policies)), maxFiles, maxBytes);
            return this;
        }

        /**
         * Jobs produce files (decision D50 item 3): at most {@code maxFiles} per attempt, each at most
         * {@code maxBytes}, stored under {@code policy} as uploads of the requester, who must hold its upload
         * permission when the job starts.
         */
        public Builder<I, R> outputFiles(String policy, int maxFiles, long maxBytes) {
            this.outputFiles = new OutputFiles(policy, maxFiles, maxBytes);
            return this;
        }

        /**
         * As {@link #outputFiles(String, int, long)}, with all files of one attempt together at most
         * {@code maxTotalBytes} (at most {@value ExternalJobType#MAX_OUTPUT_TOTAL_BYTES}): the handler holds them in
         * memory, and the executor reserves that much of its output budget for each attempt it runs.
         */
        public Builder<I, R> outputFiles(String policy, int maxFiles, long maxBytes, long maxTotalBytes) {
            this.outputFiles = new OutputFiles(policy, maxFiles, maxBytes, maxTotalBytes);
            return this;
        }

        /** The {@code internal()} process run with an {@link ExternalJobCompletion} once the job has its result. */
        public Builder<I, R> onCompleted(String process) {
            this.onCompleted = process;
            return this;
        }

        /** The {@code internal()} process run with an {@link ExternalJobFailure} once the job has failed for good. */
        public Builder<I, R> onFailed(String process) {
            this.onFailed = process;
            return this;
        }

        /**
         * The {@code internal()} process run once with an {@link ExternalJobCancellation} when a cancelled job has
         * ended (decision D52 item 2): at once for a job no attempt of which was running, otherwise once the running
         * attempt is recorded, with the usage of every attempt. Without it nothing is run for a cancelled job.
         */
        public Builder<I, R> onCancelled(String process) {
            this.onCancelled = process;
            return this;
        }

        /**
         * Holds the type unless the boolean business parameter {@code paramKey} is {@code true} now (decision D52
         * item 4): before each claim the executor reads the value in effect at the clock's now, as the system. While
         * it is missing, {@code false} or not a boolean, queued and retry-waiting jobs wait without using an attempt;
         * a running attempt runs to its end; jobs can still be started and cancelled. Declare the key in a
         * {@link ControlledParams} bean, so that pausing and resuming take four eyes (the startup check warns
         * otherwise).
         */
        public Builder<I, R> enabledBy(String paramKey) {
            this.enabledBy = paramKey;
            return this;
        }

        public ExternalJobType<I, R> build() {
            return new ExternalJobType<>(name, inputType, resultType, handler, services, timeout, maxAttempts,
                initialBackoff, maxBackoff, maxConcurrency, onCompleted, onFailed, maxOpenPerRequester,
                oneOpenPerReference, maxInputBytes, inputFiles, outputFiles, onCancelled, enabledBy);
        }
    }
}
