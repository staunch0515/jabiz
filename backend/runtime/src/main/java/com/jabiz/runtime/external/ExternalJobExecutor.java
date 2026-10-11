package com.jabiz.runtime.external;

import com.jabiz.external.ExternalCallException;
import com.jabiz.external.ExternalHttp;
import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobException;
import com.jabiz.external.ExternalJobHandler;
import com.jabiz.external.ExternalJobResult;
import com.jabiz.external.ExternalJobType;
import com.jabiz.external.ExternalServices;
import com.jabiz.runtime.observability.PlatformObservations;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs asynchronous external jobs (decision D38 item 3; docs/design/11-ledger-events-jobs.md section 4a). Each
 * instance polls ({@code jabiz.external-jobs.executor.enabled}, {@code poll-interval}, at most {@code max-in-flight}
 * attempts at a time); tests call {@link #runPending()} instead. One attempt goes through three steps:
 * <ol>
 *   <li><b>Claim</b>, in a short transaction per job type: the type's advisory lock, so that the running attempts of
 *       the type can be counted against its {@code maxConcurrency} across instances; claims whose lease ran out
 *       without a result are recorded as {@code LEASE_EXPIRED} (retryable); then the due jobs are taken
 *       ({@code FOR UPDATE OF job SKIP LOCKED}) and a claim row with a lease (the timeout and
 *       {@value #LEASE_MARGIN_SECONDS} seconds) is inserted for each. The claim's primary key (job, attempt) is what
 *       guarantees one run per attempt: an instance that loses the race inserts nothing and skips the job.</li>
 *   <li><b>Run</b>: the handler, on a virtual thread of {@code boundedElastic} within an {@link ExternalCallScope} of
 *       the job (only its type's services), bounded by {@code Mono.timeout} (the thread is interrupted, which is best
 *       effort). No transaction or connection is held meanwhile.</li>
 *   <li><b>Record</b>, in a transaction of its own that locks the job's row (as a cancellation does): the attempt and
 *       its usage, then the job's final outcome when it has one (succeeded; failed for good; cancelled meanwhile,
 *       its result dropped) with the event {@value ExternalJobRecorder#FINISHED_EVENT}. An instance that comes back
 *       after its lease expired finds its attempt recorded and drops what it has.</li>
 * </ol>
 * Times (leases, backoffs) come from the injected {@link Clock}; the durations of attempts are measured, not
 * business time. Each attempt is observed as {@value PlatformObservations#EXTERNAL_JOB} and its usage counted as
 * {@value PlatformObservations#EXTERNAL_USAGE}.
 */
@Component
public class ExternalJobExecutor {

    private static final Logger log = LoggerFactory.getLogger(ExternalJobExecutor.class);

    static final long LEASE_MARGIN_SECONDS = 30;
    /** The largest result stored, in bytes of JSON (Q16). */
    public static final int MAX_RESULT_BYTES = 256 * 1024;

    /** How a recorded attempt left its job. */
    public enum Outcome { SUCCEEDED, WAITING_RETRY, FAILED, CANCELLED, DISCARDED }

    /** Settings of one executor instance. */
    public record Settings(boolean enabled, Duration pollInterval, int maxInFlight, String instanceId) {
        public Settings {
            Objects.requireNonNull(pollInterval, "pollInterval must not be null");
            if (maxInFlight <= 0 || !pollInterval.isPositive()) {
                throw new IllegalArgumentException("jabiz.external-jobs.executor: max-in-flight and poll-interval "
                    + "must be positive");
            }
            if (instanceId == null || instanceId.isBlank() || instanceId.length() > 100) {
                throw new IllegalArgumentException("jabiz.external-jobs.executor.instance-id: 1 to 100 characters");
            }
        }

        public Settings withInstanceId(String id) {
            return new Settings(enabled, pollInterval, maxInFlight, id);
        }
    }

    /** One claimed attempt. */
    record Claimed(ExternalJobType<?, ?> type, String jobId, String input, int attemptNo, Instant deadline) {}

    /** How an attempt ended, before it is recorded. */
    record Attempt(ExternalJobRecorder.AttemptOutcome outcome, boolean retryable, String errorCode, String errorDetail,
        String resultJson) {

        static Attempt succeeded(String resultJson) {
            return new Attempt(ExternalJobRecorder.AttemptOutcome.SUCCEEDED, false, null, null, resultJson);
        }

        static Attempt failed(boolean retryable, String code, String detail) {
            return new Attempt(ExternalJobRecorder.AttemptOutcome.FAILED, retryable, code, detail, null);
        }

        boolean success() {
            return outcome == ExternalJobRecorder.AttemptOutcome.SUCCEEDED;
        }
    }

    private final ExternalJobRegistry jobs;
    private final ExternalJobRecorder recorder;
    private final ApplicationContext beans;
    private final ExternalServices services;
    private final JsonMapper json;
    private final Clock clock;
    private final PlatformObservations observations;
    private final ObjectProvider<MeterRegistry> meters;
    private final Settings settings;
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile Disposable polling;

    @Autowired
    public ExternalJobExecutor(ExternalJobRegistry jobs, ExternalJobRecorder recorder, ApplicationContext beans,
        ExternalServices services, JsonMapper json, Clock clock, PlatformObservations observations,
        ObjectProvider<MeterRegistry> meters,
        @Value("${jabiz.external-jobs.executor.enabled:true}") boolean enabled,
        @Value("${jabiz.external-jobs.executor.poll-interval:1s}") Duration pollInterval,
        @Value("${jabiz.external-jobs.executor.max-in-flight:8}") int maxInFlight,
        @Value("${jabiz.external-jobs.executor.instance-id:}") String instanceId) {
        this(jobs, recorder, beans, services, json, clock, observations, meters,
            new Settings(enabled, pollInterval, maxInFlight, instanceId == null || instanceId.isBlank()
                ? defaultInstanceId() : instanceId));
    }

    /** An executor with settings of its own: another instance, in tests of several. */
    public ExternalJobExecutor(ExternalJobRegistry jobs, ExternalJobRecorder recorder, ApplicationContext beans,
        ExternalServices services, JsonMapper json, Clock clock, PlatformObservations observations,
        ObjectProvider<MeterRegistry> meters, Settings settings) {
        this.jobs = jobs;
        this.recorder = recorder;
        this.beans = beans;
        this.services = services;
        this.json = json;
        this.clock = clock;
        this.observations = observations;
        this.meters = meters;
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
    }

    /** {@code <host>-<random>}: tells instances apart in the claims, nothing more. */
    private static String defaultInstanceId() {
        String host = System.getenv("HOSTNAME");
        String prefix = host == null || host.isBlank() ? "jabiz" : host.strip();
        String id = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        return id.length() <= 100 ? id : id.substring(id.length() - 100);
    }

    public Settings settings() {
        return settings;
    }

    /** This instance's id, as its claims record it. */
    public String instanceId() {
        return settings.instanceId();
    }

    /** Starts polling once the application is ready (not in the platform check, which never becomes ready). */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!settings.enabled() || jobs.declared().isEmpty()) {
            return;
        }
        polling = Flux.interval(settings.pollInterval())
            .onBackpressureDrop()
            .concatMap(tick -> pollOnce()
                .onErrorResume(error -> {
                    log.error("Claiming external jobs failed", error);
                    return Mono.empty();
                }), 1)
            .subscribe();
    }

    @jakarta.annotation.PreDestroy
    public void stop() {
        Disposable current = polling;
        if (current != null) {
            current.dispose();
        }
    }

    /** Claims what is due within this instance's free capacity and starts the attempts without waiting for them. */
    private Mono<Void> pollOnce() {
        int free = settings.maxInFlight() - inFlight.get();
        if (free <= 0) {
            return Mono.empty();
        }
        return claimAll(free)
            .doOnNext(claimed -> execute(claimed)
                .subscribe(null, error -> log.error("External job {} attempt {} could not be recorded",
                    claimed.jobId(), claimed.attemptNo(), error)))
            .then();
    }

    /**
     * Claims what is due now within this instance's free capacity, runs it and records it; completes when every
     * attempt it started is recorded, with their number. For tests and scenario replay; the poller does the same
     * without waiting.
     */
    public Mono<Integer> runPending() {
        int free = settings.maxInFlight() - inFlight.get();
        if (free <= 0) {
            return Mono.just(0);
        }
        return claimAll(free)
            .collectList()
            .flatMapMany(Flux::fromIterable)
            .flatMap(this::execute, settings.maxInFlight())
            .count()
            .map(Math::toIntExact);
    }

    /** Claims due attempts of every type, one type after another, together at most {@code capacity}. */
    private Flux<Claimed> claimAll(int capacity) {
        AtomicInteger left = new AtomicInteger(capacity);
        return Flux.fromIterable(jobs.names())
            .concatMap(name -> {
                int room = left.get();
                if (room <= 0) {
                    return Flux.empty();
                }
                return claim(jobs.find(name).orElseThrow(), room)
                    .doOnNext(claimed -> {
                        left.decrementAndGet();
                        inFlight.incrementAndGet();
                    });
            });
    }

    /** One claim transaction of one type. */
    Flux<Claimed> claim(ExternalJobType<?, ?> type, int capacity) {
        return Mono.defer(() -> {
                Instant now = ExternalJobRecorder.micros(clock.instant());
                var engine = recorder.engine();
                return engine.inTransaction(recorder.lockType(type.name())
                    .then(expire(type, now))
                    .then(recorder.activeClaims(type.name(), now))
                    .flatMap(active -> {
                        int slots = Math.min(type.maxConcurrency() - active, capacity);
                        if (slots <= 0) {
                            return Mono.just(List.<Claimed>of());
                        }
                        Instant lease = now.plus(type.timeout()).plusSeconds(LEASE_MARGIN_SECONDS);
                        return recorder.dueJobs(type.name(), type.maxAttempts(), type.initialBackoff(),
                                type.maxBackoff(), now, slots)
                            .collectList()
                            .flatMapMany(Flux::fromIterable)
                            .concatMap(due -> recorder.insertClaim(due.jobId(), due.attempts() + 1, instanceId(),
                                    now, lease)
                                .filter(Boolean::booleanValue)
                                .map(claimed -> new Claimed(type, due.jobId(), due.input(), due.attempts() + 1,
                                    now.plus(type.timeout()))))
                            .collectList();
                    }));
            })
            .flatMapMany(Flux::fromIterable);
    }

    /**
     * Records the claims of the type whose lease ran out without a result as {@code LEASE_EXPIRED}; a job that was
     * cancelled, or has no attempts left, gets its outcome.
     */
    private Mono<Void> expire(ExternalJobType<?, ?> type, Instant now) {
        return recorder.expiredClaims(type.name(), now)
            .collectList()
            .flatMapMany(Flux::fromIterable)
            .concatMap(expired -> recorder.lockJob(expired.jobId()).flatMap(job -> recorder.insertAttempt(
                    expired.jobId(), expired.attemptNo(), ExternalJobRecorder.AttemptOutcome.LEASE_EXPIRED, true,
                    "LEASE_EXPIRED", "no result before the lease expired", null, now)
                .flatMap(inserted -> {
                    if (!inserted) {
                        return Mono.empty();
                    }
                    log.warn("External job {} attempt {} ({}) gave no result before its lease expired",
                        expired.jobId(), expired.attemptNo(), type.name());
                    return recorder.isCancelled(expired.jobId()).flatMap(cancelled -> {
                        if (cancelled) {
                            return recorder.finish(job, "CANCELLED", null, null, null, now);
                        }
                        if (expired.attemptNo() >= type.maxAttempts()) {
                            return recorder.finish(job, "FAILED", null, "LEASE_EXPIRED",
                                "no result before the lease expired", now);
                        }
                        return Mono.just(false);
                    });
                })))
            .then();
    }

    /** Runs one claimed attempt and records it. */
    Mono<Outcome> execute(Claimed claimed) {
        ExternalJobType<?, ?> type = claimed.type();
        Mono<Outcome> attempt = Mono.defer(() -> {
            JobContext ctx = new JobContext(claimed, services);
            long started = System.nanoTime();
            return run(claimed, ctx)
                .onErrorResume(error -> Mono.just(failure(type, error)))
                .flatMap(result -> record(claimed, result,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), ctx.usage()));
        });
        return observations.monoOutcome(PlatformObservations.EXTERNAL_JOB, "external job " + type.name(),
                KeyValues.of("job_type", type.name()), attempt, Outcome::name, error -> "error")
            .doFinally(signal -> inFlight.decrementAndGet());
    }

    /** The handler, on a virtual thread, bounded by the type's timeout. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Mono<Attempt> run(Claimed claimed, JobContext ctx) {
        ExternalJobType type = claimed.type();
        return Mono.fromCallable(() -> {
                Object input = json.readValue(claimed.input(), type.inputType());
                ExternalJobHandler handler = (ExternalJobHandler) beans.getBean(type.handler());
                return (ExternalJobResult<?>) ExternalCallScope.openJob(claimed.jobId(), type.name(), type.services(),
                    () -> handler.run(input, ctx));
            })
            .subscribeOn(Schedulers.boundedElastic())
            .timeout(type.timeout())
            .map(result -> succeeded(type, result, ctx));
    }

    private Attempt succeeded(ExternalJobType<?, ?> type, ExternalJobResult<?> result, JobContext ctx) {
        if (result == null) {
            return Attempt.failed(false, "NO_RESULT", "the handler returned no result");
        }
        if (!type.resultType().isInstance(result.value())) {
            return Attempt.failed(false, "BAD_RESULT", "the handler returned " + result.value().getClass().getName()
                + ", not " + type.resultType().getName());
        }
        result.usage().forEach(ctx::recordUsage);
        String text = json.writeValueAsString(result.value());
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_BYTES) {
            return Attempt.failed(false, "RESULT_TOO_LARGE", "the result is larger than " + MAX_RESULT_BYTES
                + " bytes");
        }
        return Attempt.succeeded(text);
    }

    /**
     * The attempt's failure as recorded: the handler's own code and message, the platform's for a timeout or an
     * external call, and for anything else the exception's class only, since its message may repeat a request.
     */
    static Attempt failure(ExternalJobType<?, ?> type, Throwable error) {
        if (error instanceof TimeoutException) {
            return new Attempt(ExternalJobRecorder.AttemptOutcome.TIMED_OUT, true, "TIMEOUT",
                "no result within " + type.timeout(), null);
        }
        if (error instanceof ExternalJobException job) {
            return Attempt.failed(!job.isPermanent(), job.code(), job.getMessage());
        }
        if (error instanceof ExternalCallException call) {
            // A refused call (a host or service not allowed) does not pass by trying again.
            return Attempt.failed(call.kind() != ExternalCallException.Kind.REFUSED, "EXTERNAL_CALL_FAILED",
                call.getMessage());
        }
        return Attempt.failed(true, "HANDLER_ERROR", error.getClass().getSimpleName());
    }

    /** Records an attempt and, when the job is over, its outcome; one transaction holding the job's row. */
    private Mono<Outcome> record(Claimed claimed, Attempt attempt, Long durationMs, Map<String, Long> usage) {
        ExternalJobType<?, ?> type = claimed.type();
        return Mono.defer(() -> {
            Instant now = clock.instant();
            var engine = recorder.engine();
            return engine.inTransaction(recorder.lockJob(claimed.jobId())
                .flatMap(job -> recorder.insertAttempt(claimed.jobId(), claimed.attemptNo(), attempt.outcome(),
                        attempt.retryable(), attempt.errorCode(), attempt.errorDetail(), durationMs, now)
                    .flatMap(inserted -> {
                        if (!inserted) {
                            log.warn("External job {} attempt {} ({}) ended after its lease had expired; its result "
                                + "is dropped", claimed.jobId(), claimed.attemptNo(), type.name());
                            return Mono.just(Outcome.DISCARDED);
                        }
                        return recorder.insertUsage(claimed.jobId(), claimed.attemptNo(), usage)
                            .then(recorder.isCancelled(claimed.jobId()))
                            .flatMap(cancelled -> {
                                if (cancelled) {
                                    return recorder.finish(job, "CANCELLED", null, null, null, now)
                                        .map(done -> done ? Outcome.CANCELLED : Outcome.DISCARDED);
                                }
                                if (attempt.success()) {
                                    return recorder.finish(job, "SUCCEEDED", attempt.resultJson(), null, null, now)
                                        .map(done -> done ? Outcome.SUCCEEDED : Outcome.DISCARDED);
                                }
                                if (!attempt.retryable() || claimed.attemptNo() >= type.maxAttempts()) {
                                    return recorder.finish(job, "FAILED", null, attempt.errorCode(),
                                            attempt.errorDetail(), now)
                                        .map(done -> done ? Outcome.FAILED : Outcome.DISCARDED);
                                }
                                return Mono.just(Outcome.WAITING_RETRY);
                            })
                            .doOnNext(outcome -> count(type, usage));
                    })));
        });
    }

    private void count(ExternalJobType<?, ?> type, Map<String, Long> usage) {
        MeterRegistry registry = meters.getIfAvailable();
        if (registry == null) {
            return;
        }
        usage.forEach((metric, quantity) -> Counter.builder(PlatformObservations.EXTERNAL_USAGE)
            .tag("job_type", type.name()).tag("metric", metric)
            .register(registry).increment(quantity));
    }

    /**
     * Writes a result for the oldest open job of the type (with that reference, if given) as if an attempt of its
     * handler had returned it, without running the handler, through the same claim and record: for scenario replay,
     * so that an application's scenarios do not depend on the other system (Q17).
     *
     * @param status       {@code SUCCEEDED} with {@code result} (converted to the type's result record), or
     *                     {@code FAILED}: a permanent failure with {@code errorCode} and {@code errorMessage}
     * @return the outcome, or empty when no such job is open
     */
    public Mono<Outcome> completeWithoutHandler(String jobType, String reference, String status, Object result,
        Map<String, Long> usage, String errorCode, String errorMessage) {
        ExternalJobType<?, ?> type = jobs.find(jobType).orElseThrow(() -> new IllegalArgumentException(
            "No external job type " + jobType + " is declared"));
        Map<String, Long> consumed = new LinkedHashMap<>();
        (usage == null ? Map.<String, Long>of() : usage).forEach((metric, quantity) ->
            ExternalJobResult.add(consumed, metric, quantity));
        Attempt attempt = switch (status == null ? "SUCCEEDED" : status) {
            case "SUCCEEDED" -> Attempt.succeeded(json.writeValueAsString(json.convertValue(
                Objects.requireNonNull(result, "a result is needed"), type.resultType())));
            case "FAILED" -> Attempt.failed(false, errorCode == null ? "FAILED" : errorCode,
                errorMessage == null ? "failed" : errorMessage);
            default -> throw new IllegalArgumentException("status must be SUCCEEDED or FAILED");
        };
        return Mono.defer(() -> {
                Instant now = ExternalJobRecorder.micros(clock.instant());
                return recorder.engine().inTransaction(recorder.lockType(type.name())
                    .then(recorder.pendingJob(type.name(), reference))
                    .flatMap(due -> recorder.insertClaim(due.jobId(), due.attempts() + 1, instanceId(), now,
                            now.plus(type.timeout()).plusSeconds(LEASE_MARGIN_SECONDS))
                        .filter(Boolean::booleanValue)
                        .map(claimed -> new Claimed(type, due.jobId(), due.input(), due.attempts() + 1,
                            now.plus(type.timeout())))));
            })
            .flatMap(claimed -> record(claimed, attempt, 0L, consumed));
    }

    @Override
    public String toString() {
        return "ExternalJobExecutor[" + settings.instanceId() + "]";
    }

    /**
     * What the handler of one attempt sees. Calls go through the platform's {@link ExternalServices}, which find the
     * job's scope on the thread and refuse other services there too.
     */
    static final class JobContext implements ExternalJobContext {

        private final Claimed claimed;
        private final ExternalServices services;
        private final Map<String, Long> usage = new LinkedHashMap<>();

        JobContext(Claimed claimed, ExternalServices services) {
            this.claimed = claimed;
            this.services = services;
        }

        @Override
        public String jobId() {
            return claimed.jobId();
        }

        @Override
        public int attempt() {
            return claimed.attemptNo();
        }

        @Override
        public String idempotencyKey() {
            return "jabiz-job-" + claimed.jobId();
        }

        @Override
        public Instant deadline() {
            return claimed.deadline();
        }

        @Override
        public ExternalHttp http(String service) {
            return services.http(declared(service));
        }

        @Override
        public String secret(String service, String name) {
            return services.secret(declared(service), name);
        }

        private String declared(String service) {
            if (!claimed.type().services().contains(service)) {
                throw new IllegalArgumentException("External job type " + claimed.type().name()
                    + " does not declare the service " + service);
            }
            return service;
        }

        @Override
        public synchronized void recordUsage(String metric, long quantity) {
            ExternalJobResult.add(usage, metric, quantity);
        }

        synchronized Map<String, Long> usage() {
            return Map.copyOf(new LinkedHashMap<>(usage));
        }

        @Override
        public String toString() {
            return "ExternalJobContext[jobId=" + claimed.jobId() + ", attempt=" + claimed.attemptNo() + "]";
        }
    }
}
