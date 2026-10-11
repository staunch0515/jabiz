package com.jabiz.external;

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
 * {@link #onFailed} process with an {@link ExternalJobFailure}. Both must be {@code internal()}.
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
 */
public record ExternalJobType<I, R>(String name, Class<I> inputType, Class<R> resultType,
    Class<? extends ExternalJobHandler<I, R>> handler, Set<String> services, Duration timeout, int maxAttempts,
    Duration initialBackoff, Duration maxBackoff, int maxConcurrency, String onCompleted, String onFailed,
    int maxOpenPerRequester, boolean oneOpenPerReference) {

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

    private static final int MAX_TYPE_DEPTH = 8;

    public ExternalJobType {
        services = services == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(services));
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        initialBackoff = initialBackoff == null ? DEFAULT_INITIAL_BACKOFF : initialBackoff;
        maxBackoff = maxBackoff == null ? DEFAULT_MAX_BACKOFF : maxBackoff;
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
        if (onCompleted == null || onCompleted.isBlank()) {
            problems.add("onCompleted: name the process that receives the result");
        }
        if (onFailed == null || onFailed.isBlank()) {
            problems.add("onFailed: name the process that hears of a failure");
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
            + oneOpenPerReference + ", onCompleted=" + onCompleted + ", onFailed=" + onFailed + "]";
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
        private String onCompleted;
        private String onFailed;

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

        public ExternalJobType<I, R> build() {
            return new ExternalJobType<>(name, inputType, resultType, handler, services, timeout, maxAttempts,
                initialBackoff, maxBackoff, maxConcurrency, onCompleted, onFailed, maxOpenPerRequester,
                oneOpenPerReference);
        }
    }
}
