package com.jabiz.runtime.external;

import com.jabiz.external.ExternalCallException;
import com.jabiz.external.ExternalHttp;
import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobException;
import com.jabiz.external.ExternalJobFile;
import com.jabiz.external.ExternalJobHandler;
import com.jabiz.external.ExternalJobResult;
import com.jabiz.external.ExternalJobType;
import com.jabiz.external.ExternalServices;
import com.jabiz.external.GeneratedFile;
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
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs asynchronous external jobs (decision D38 item 3; docs/design/11-ledger-events-jobs.md section 4a). Each
 * instance polls ({@code jabiz.external-jobs.executor.enabled}, {@code poll-interval}, at most {@code max-in-flight}
 * attempts at a time); tests call {@link #runPending()} instead. One attempt goes through three steps:
 * <ol>
 *   <li><b>Claim</b>, in a short transaction per job type: the type's advisory lock, so that the running attempts of
 *       the type can be counted against its {@code maxConcurrency} across instances, and only then the time. Claims
 *       whose lease ran out without a result are recorded as {@code LEASE_EXPIRED} (retryable); jobs that can have no
 *       further attempt (attempts used up, or cancelled) get their outcome; then, when the instance has room, the due
 *       jobs are taken ({@code FOR UPDATE OF job SKIP LOCKED}) and a claim row with a lease (the timeout and
 *       {@value #LEASE_MARGIN_SECONDS} seconds) is inserted for each. The claim's primary key (job, attempt) is what
 *       guarantees one run per attempt: an instance that loses the race inserts nothing and skips the job. The first
 *       two run for every type on every poll, whether the instance has room or not.</li>
 *   <li><b>Run</b>: the handler, on a virtual thread of {@code boundedElastic} within an {@link ExternalCallScope} of
 *       the job (only its type's services), bounded by {@code Mono.timeout} (the thread is interrupted, which is best
 *       effort). No transaction or connection is held meanwhile. For a type with files (decision D50) the files the
 *       job reads are copied for the attempt before it and deleted after it, and the files the handler returned are
 *       stored as uploads of the requester before the attempt is recorded ({@link ExternalJobFiles}).</li>
 *   <li><b>Record</b>, in a transaction of its own that locks the job's row (as a cancellation does), retried a few
 *       times on a failure of the database: the attempt and its usage, then the job's final outcome when it has one
 *       (succeeded; failed for good; cancelled meanwhile, its result dropped) with the event
 *       {@value ExternalJobRecorder#FINISHED_EVENT}. An instance that comes back after its lease expired finds its
 *       attempt recorded and drops what it has.</li>
 * </ol>
 * The types take turns: each poll starts with the next one, so that one busy type cannot take a full instance's every
 * slot. The queries look at the jobs of a type from its watermark on (the lowest one without an outcome); every
 * {@code watermark-refresh} a sweep looks at all of them, refreshes the watermarks and fails the open jobs of types no
 * longer declared ({@code TYPE_REMOVED}).
 *
 * <p>On shutdown ({@link SmartLifecycle}, before the database goes) the instance stops claiming and waits up to
 * {@code shutdown-grace} for the attempts it runs to be recorded; what is not is taken again once its lease is over.
 *
 * <p>Times (leases, backoffs) come from the injected {@link Clock}; the durations of attempts and the cadence of the
 * sweeps are measured, not business time. Each attempt is observed as {@value PlatformObservations#EXTERNAL_JOB}, and
 * once committed its usage is counted as {@value PlatformObservations#EXTERNAL_USAGE}.
 */
@Component
public class ExternalJobExecutor implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ExternalJobExecutor.class);

    static final long LEASE_MARGIN_SECONDS = 30;
    /** The largest result stored, in bytes of JSON (Q16). */
    public static final int MAX_RESULT_BYTES = 256 * 1024;
    /** Recording an attempt is tried this many more times when the database fails. */
    static final int RECORD_RETRIES = 3;

    /** How a recorded attempt left its job. */
    public enum Outcome { SUCCEEDED, WAITING_RETRY, FAILED, CANCELLED, DISCARDED }

    /**
     * Settings of one executor instance.
     *
     * @param shutdownGrace     how long a shutdown waits for running attempts to be recorded
     * @param watermarkRefresh  how often a poll is a sweep over all jobs (zero: every poll)
     * @param simulatedResults  whether {@link #completeWithoutHandler} may be used: tests and scenario replay only
     */
    public record Settings(boolean enabled, Duration pollInterval, int maxInFlight, String instanceId,
        Duration shutdownGrace, Duration watermarkRefresh, boolean simulatedResults) {

        public static final Duration DEFAULT_SHUTDOWN_GRACE = Duration.ofSeconds(30);
        public static final Duration DEFAULT_WATERMARK_REFRESH = Duration.ofMinutes(1);

        public Settings {
            Objects.requireNonNull(pollInterval, "pollInterval must not be null");
            shutdownGrace = shutdownGrace == null ? DEFAULT_SHUTDOWN_GRACE : shutdownGrace;
            watermarkRefresh = watermarkRefresh == null ? DEFAULT_WATERMARK_REFRESH : watermarkRefresh;
            if (maxInFlight <= 0 || !pollInterval.isPositive() || shutdownGrace.isNegative()
                || watermarkRefresh.isNegative()) {
                throw new IllegalArgumentException("jabiz.external-jobs.executor: max-in-flight and poll-interval "
                    + "must be positive, shutdown-grace and watermark-refresh not negative");
            }
            if (instanceId == null || instanceId.isBlank() || instanceId.length() > 100) {
                throw new IllegalArgumentException("jabiz.external-jobs.executor.instance-id: 1 to 100 characters");
            }
        }

        public Settings withInstanceId(String id) {
            return new Settings(enabled, pollInterval, maxInFlight, id, shutdownGrace, watermarkRefresh,
                simulatedResults);
        }

        public Settings withMaxInFlight(int limit) {
            return new Settings(enabled, pollInterval, limit, instanceId, shutdownGrace, watermarkRefresh,
                simulatedResults);
        }

        public Settings withShutdownGrace(Duration grace) {
            return new Settings(enabled, pollInterval, maxInFlight, instanceId, grace, watermarkRefresh,
                simulatedResults);
        }

        public Settings withWatermarkRefresh(Duration refresh) {
            return new Settings(enabled, pollInterval, maxInFlight, instanceId, shutdownGrace, refresh,
                simulatedResults);
        }
    }

    /** One claimed attempt. */
    record Claimed(ExternalJobType<?, ?> type, String jobId, String input, int attemptNo, Instant deadline,
        String requestedBy) {}

    /**
     * How an attempt ended, before it is recorded.
     *
     * @param produced the files the handler returned, to be stored (decision D50 item 3)
     * @param stored   the files stored, recorded with the attempt whatever its outcome
     */
    record Attempt(ExternalJobRecorder.AttemptOutcome outcome, boolean retryable, String errorCode, String errorDetail,
        String resultJson, List<GeneratedFile> produced, List<ExternalJobFiles.Output> stored) {

        Attempt {
            produced = produced == null ? List.of() : List.copyOf(produced);
            stored = stored == null ? List.of() : List.copyOf(stored);
        }

        static Attempt succeeded(String resultJson) {
            return new Attempt(ExternalJobRecorder.AttemptOutcome.SUCCEEDED, false, null, null, resultJson, null,
                null);
        }

        static Attempt failed(boolean retryable, String code, String detail) {
            return new Attempt(ExternalJobRecorder.AttemptOutcome.FAILED, retryable, code, detail, null, null, null);
        }

        Attempt producing(List<GeneratedFile> files) {
            return new Attempt(outcome, retryable, errorCode, errorDetail, resultJson, files, stored);
        }

        /** This attempt once its files went into storage: as it was, or failed when one did not. */
        Attempt storing(ExternalJobFiles.Outputs outputs) {
            if (outputs.failure() == null) {
                return new Attempt(outcome, retryable, errorCode, errorDetail, resultJson, List.of(), outputs.stored());
            }
            ExternalJobException failure = outputs.failure();
            return new Attempt(ExternalJobRecorder.AttemptOutcome.FAILED, !failure.isPermanent(), failure.code(),
                failure.getMessage(), null, List.of(), outputs.stored());
        }

        boolean success() {
            return outcome == ExternalJobRecorder.AttemptOutcome.SUCCEEDED;
        }
    }

    /** What recording an attempt did: the job's outcome, and whether the attempt (and its usage) went in. */
    private record Recorded(Outcome outcome, boolean attemptRecorded) {}

    private final ExternalJobRegistry jobs;
    private final ExternalJobRecorder recorder;
    private final ApplicationContext beans;
    private final ExternalServices services;
    private final JsonMapper json;
    private final Clock clock;
    private final PlatformObservations observations;
    private final ObjectProvider<MeterRegistry> meters;
    private final Settings settings;
    /** The jobs' files (decision D50), found when a job has some: they depend on the uploads and their processes. */
    private final ObjectProvider<ExternalJobFiles> files;
    private final AtomicInteger inFlight = new AtomicInteger();
    /** Where the next poll starts in the list of types: after the one that took the last slot. */
    private final AtomicInteger nextType = new AtomicInteger();
    private final Map<String, Long> watermarks = new ConcurrentHashMap<>();
    /** {@code System.nanoTime()} of the last sweep; {@code Long.MIN_VALUE} before the first. */
    private final AtomicLong lastSweep = new AtomicLong(Long.MIN_VALUE);
    private volatile Disposable polling;
    private volatile boolean running;
    private volatile boolean stopping;

    @Autowired
    public ExternalJobExecutor(ExternalJobRegistry jobs, ExternalJobRecorder recorder, ApplicationContext beans,
        ExternalServices services, JsonMapper json, Clock clock, PlatformObservations observations,
        ObjectProvider<MeterRegistry> meters,
        @Value("${jabiz.external-jobs.executor.enabled:true}") boolean enabled,
        @Value("${jabiz.external-jobs.executor.poll-interval:1s}") Duration pollInterval,
        @Value("${jabiz.external-jobs.executor.max-in-flight:8}") int maxInFlight,
        @Value("${jabiz.external-jobs.executor.instance-id:}") String instanceId,
        @Value("${jabiz.external-jobs.executor.shutdown-grace:30s}") Duration shutdownGrace,
        @Value("${jabiz.external-jobs.executor.watermark-refresh:1m}") Duration watermarkRefresh,
        @Value("${jabiz.external-jobs.simulated-results:false}") boolean simulatedResults) {
        this(jobs, recorder, beans, services, json, clock, observations, meters,
            new Settings(enabled, pollInterval, maxInFlight, instanceId == null || instanceId.isBlank()
                ? defaultInstanceId() : instanceId, shutdownGrace, watermarkRefresh, simulatedResults));
    }

    /** An executor with settings of its own: another instance, in tests of several. */
    public ExternalJobExecutor(ExternalJobRegistry jobs, ExternalJobRecorder recorder, ApplicationContext beans,
        ExternalServices services, JsonMapper json, Clock clock, PlatformObservations observations,
        ObjectProvider<MeterRegistry> meters, Settings settings) {
        this.files = beans.getBeanProvider(ExternalJobFiles.class);
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

    // ------------------------------------------------------------------ lifecycle

    /** Starts polling once the application is ready (not in the platform check, which never becomes ready). */
    @EventListener(ApplicationReadyEvent.class)
    public void startPolling() {
        if (!settings.enabled() || jobs.declared().isEmpty() || stopping) {
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

    @Override
    public void start() {
        running = true;
    }

    /**
     * Stops claiming and waits up to {@code shutdown-grace} for the attempts this instance runs to be recorded. Runs
     * before the database connections close (the default phase stops first).
     */
    @Override
    public void stop() {
        stopping = true;
        Disposable current = polling;
        if (current != null) {
            current.dispose();
        }
        long until = System.nanoTime() + settings.shutdownGrace().toNanos();
        while (inFlight.get() > 0 && System.nanoTime() < until) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (inFlight.get() > 0) {
            log.warn("{} external job attempt(s) of {} were still running at shutdown; they are taken again once "
                + "their lease is over", inFlight.get(), instanceId());
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // ------------------------------------------------------------------ polling

    /** Claims what is due within this instance's free capacity and starts the attempts without waiting for them. */
    private Mono<Void> pollOnce() {
        return claimAll(Math.max(0, settings.maxInFlight() - inFlight.get()))
            .doOnNext(claimed -> execute(claimed)
                .subscribe(null, error -> log.error("External job {} attempt {} could not be recorded",
                    claimed.jobId(), claimed.attemptNo(), error)))
            .then();
    }

    /**
     * Claims what is due now within this instance's free capacity, runs it and records it; completes when every
     * attempt it started is recorded, with their number. Without room it still records expired leases and settles
     * jobs that can have no further attempt. For tests and scenario replay; the poller does the same without waiting.
     */
    public Mono<Integer> runPending() {
        return claimAll(Math.max(0, settings.maxInFlight() - inFlight.get()))
            .collectList()
            .flatMapMany(Flux::fromIterable)
            .flatMap(this::execute, settings.maxInFlight())
            .count()
            .map(Math::toIntExact);
    }

    /**
     * One claim transaction per type, at most {@code capacity} attempts in all, starting with the next type in turn;
     * a sweep first fails the jobs of undeclared types.
     */
    private Flux<Claimed> claimAll(int capacity) {
        if (stopping) {
            return Flux.empty();
        }
        List<String> names = jobs.names();
        if (names.isEmpty()) {
            return Flux.empty();
        }
        boolean sweep = sweepDue();
        // The poll starts after the type that took the last slot, so a busy type cannot take every poll.
        List<Integer> order = new ArrayList<>();
        int start = Math.floorMod(nextType.get(), names.size());
        for (int i = 0; i < names.size(); i++) {
            order.add((start + i) % names.size());
        }
        AtomicInteger left = new AtomicInteger(capacity);
        Mono<Void> removed = sweep ? failRemovedTypes(names) : Mono.empty();
        return removed.thenMany(Flux.fromIterable(order)
            .concatMap(index -> claim(jobs.find(names.get(index)).orElseThrow(), left.get(), sweep)
                .doOnNext(claimed -> {
                    left.decrementAndGet();
                    inFlight.incrementAndGet();
                    nextType.set(index + 1);
                })));
    }

    private boolean sweepDue() {
        long now = System.nanoTime();
        long last = lastSweep.get();
        if (last != Long.MIN_VALUE && now - last < settings.watermarkRefresh().toNanos()) {
            return false;
        }
        return lastSweep.compareAndSet(last, now);
    }

    /** Fails the open jobs of types no longer declared, in a transaction of their own. */
    private Mono<Void> failRemovedTypes(List<String> declared) {
        return Mono.defer(() -> recorder.engine().inTransaction(recorder.jobsOfOtherTypes(declared)
            .collectList()
            .flatMapMany(Flux::fromIterable)
            .concatMap(job -> {
                log.warn("External job {} is of type {}, which is no longer declared; it is failed", job.jobId(),
                    job.jobType());
                return recorder.finish(job, "FAILED", null, "TYPE_REMOVED", "the job type is no longer declared",
                    clock.instant());
            })
            .then()));
    }

    /** One claim transaction of one type; with no capacity it only records expired leases and settles jobs. */
    Flux<Claimed> claim(ExternalJobType<?, ?> type, int capacity, boolean sweep) {
        return Mono.defer(() -> recorder.engine().inTransaction(recorder.lockType(type.name())
                // The time is read once the lock is held: a claim that waited for it does not judge leases by the
                // time it began to wait.
                .then(Mono.fromCallable(() -> ExternalJobRecorder.micros(clock.instant())))
                .flatMap(now -> {
                    long mark = sweep ? Long.MIN_VALUE : watermarks.getOrDefault(type.name(), Long.MIN_VALUE);
                    Mono<List<Claimed>> claims = capacity <= 0 ? Mono.just(List.of())
                        : recorder.activeClaims(type.name(), now, mark).flatMap(active -> {
                            int slots = Math.min(type.maxConcurrency() - active, capacity);
                            return slots <= 0 ? Mono.just(List.<Claimed>of()) : take(type, now, slots, mark);
                        });
                    Mono<Void> watermark = sweep ? recorder.watermark(type.name())
                        .doOnNext(value -> watermarks.put(type.name(), value)).then() : Mono.empty();
                    return expire(type, now, mark).then(settle(type, now, mark)).then(claims)
                        .flatMap(taken -> watermark.thenReturn(taken));
                })))
            .flatMapMany(Flux::fromIterable);
    }

    /** Takes up to {@code slots} due jobs of the type. */
    private Mono<List<Claimed>> take(ExternalJobType<?, ?> type, Instant now, int slots, long mark) {
        Instant lease = leaseUntil(type, now);
        return recorder.dueJobs(type.name(), type.maxAttempts(), waits(type), now, slots, mark)
            .collectList()
            .flatMapMany(Flux::fromIterable)
            .concatMap(due -> recorder.insertClaim(due.jobId(), due.attempts() + 1, instanceId(), now, lease)
                .filter(Boolean::booleanValue)
                .map(claimed -> new Claimed(type, due.jobId(), due.input(), due.attempts() + 1,
                    now.plus(type.timeout()), due.requestedBy())))
            .collectList();
    }

    /**
     * The end of an attempt's lease: its timeout and a margin for recording it, and for a type with files the time
     * copying and storing them may take besides (decision D50).
     */
    static Instant leaseUntil(ExternalJobType<?, ?> type, Instant now) {
        Instant lease = now.plus(type.timeout()).plusSeconds(LEASE_MARGIN_SECONDS);
        if (type.inputFiles() != null) {
            lease = lease.plus(ExternalJobFiles.FILE_WORK_TIMEOUT);
        }
        if (type.outputFiles() != null) {
            lease = lease.plus(ExternalJobFiles.FILE_WORK_TIMEOUT);
        }
        return lease;
    }

    /** The wait after 1, 2, … failed attempts, in milliseconds: {@link ExternalJobType#backoffAfter}, for the SQL. */
    static Long[] waits(ExternalJobType<?, ?> type) {
        Long[] waits = new Long[Math.max(1, type.maxAttempts())];
        for (int failures = 1; failures <= waits.length; failures++) {
            waits[failures - 1] = type.backoffAfter(failures).toMillis();
        }
        return waits;
    }

    /**
     * Records the claims of the type whose lease ran out without a result as {@code LEASE_EXPIRED}; a job that was
     * cancelled, or has no attempts left, gets its outcome.
     */
    private Mono<Void> expire(ExternalJobType<?, ?> type, Instant now, long mark) {
        return recorder.expiredClaims(type.name(), now, mark)
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

    /**
     * Gives an outcome to the jobs of the type that can have no further attempt: cancelled ones get
     * {@code CANCELLED}; those whose attempts are used up (the type now allows fewer than when they ran) get
     * {@code FAILED} with {@code ATTEMPTS_EXHAUSTED}.
     */
    private Mono<Void> settle(ExternalJobType<?, ?> type, Instant now, long mark) {
        return recorder.unsettledJobs(type.name(), type.maxAttempts(), mark)
            .collectList()
            .flatMapMany(Flux::fromIterable)
            .concatMap(settle -> settle.cancelled()
                ? recorder.finish(settle.job(), "CANCELLED", null, null, null, now)
                : recorder.finish(settle.job(), "FAILED", null, "ATTEMPTS_EXHAUSTED",
                    "no attempts left (the job type allows " + type.maxAttempts() + ")", now))
            .then();
    }

    // ------------------------------------------------------------------ attempts

    /** Runs one claimed attempt and records it. */
    Mono<Outcome> execute(Claimed claimed) {
        ExternalJobType<?, ?> type = claimed.type();
        Mono<Outcome> attempt = Mono.defer(() -> {
            JobContext ctx = new JobContext(claimed, services);
            long started = System.nanoTime();
            // The job's input files are copied for the attempt and deleted once it is over, whatever its end
            // (decision D50 item 2); its output files are stored before it is recorded (item 3).
            return Mono.usingWhen(inputs(claimed),
                    inputs -> {
                        ctx.files(inputs.files());
                        return run(claimed, ctx);
                    },
                    inputs -> inputs.close(files.getObject().storage()).onErrorResume(error -> {
                        log.warn("The copies of external job {}'s files could not be deleted: {}", claimed.jobId(),
                            error.toString());
                        return Mono.empty();
                    }))
                .onErrorResume(error -> Mono.just(failure(type, error)))
                .flatMap(result -> storeOutputs(claimed, result))
                .flatMap(result -> record(claimed, result,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), ctx.usage()));
        });
        // The slot is free before the caller hears of the end: a runPending that follows sees the room.
        AtomicBoolean freed = new AtomicBoolean();
        Runnable free = () -> {
            if (freed.compareAndSet(false, true)) {
                inFlight.decrementAndGet();
            }
        };
        return observations.monoOutcome(PlatformObservations.EXTERNAL_JOB, "external job " + type.name(),
                KeyValues.of("job_type", type.name()), attempt, Outcome::name, error -> "error")
            .doOnTerminate(free)
            .doOnCancel(free)
            .doFinally(signal -> free.run());
    }

    /** Copies of the files the job reads, for this attempt; none for a type that reads none. */
    private Mono<ExternalJobFiles.Inputs> inputs(Claimed claimed) {
        return claimed.type().inputFiles() == null ? Mono.just(ExternalJobFiles.Inputs.NONE)
            : files.getObject().open(claimed.type(), claimed.jobId());
    }

    /**
     * Stores the files a successful attempt produced as uploads of the job's requester (decision D50 item 3); the
     * attempt fails when one is refused. A job cancelled meanwhile stores nothing: its result is dropped anyway.
     */
    private Mono<Attempt> storeOutputs(Claimed claimed, Attempt attempt) {
        if (!attempt.success() || attempt.produced().isEmpty()) {
            return Mono.just(attempt);
        }
        return recorder.isCancelled(claimed.jobId()).flatMap(cancelled -> cancelled
            ? Mono.just(attempt.producing(List.of()))
            : files.getObject().store(claimed.type(), claimed.jobId(), claimed.requestedBy(), attempt.produced())
                .map(attempt::storing));
    }

    /**
     * The handler, on a virtual thread, bounded by the type's timeout. An input that cannot be read, or a handler that
     * cannot be found, is a permanent {@code BAD_INPUT}; a handler that returns nothing a permanent {@code NO_RESULT}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Mono<Attempt> run(Claimed claimed, JobContext ctx) {
        ExternalJobType type = claimed.type();
        return Mono.fromCallable(() -> {
                Object input;
                ExternalJobHandler handler;
                try {
                    input = json.readValue(claimed.input(), type.inputType());
                    handler = (ExternalJobHandler) beans.getBean(type.handler());
                } catch (RuntimeException e) {
                    throw ExternalJobException.permanent("BAD_INPUT", "the input could not be read as "
                        + type.inputType().getSimpleName() + ", or the handler found (" + e.getClass().getSimpleName()
                        + ")");
                }
                if (input == null) {
                    throw ExternalJobException.permanent("BAD_INPUT", "the input is empty");
                }
                return (ExternalJobResult<?>) ExternalCallScope.openJob(claimed.jobId(), type.name(), type.services(),
                    () -> handler.run(input, ctx));
            })
            .subscribeOn(Schedulers.boundedElastic())
            .timeout(type.timeout())
            .map(result -> succeeded(type, result, ctx))
            .switchIfEmpty(Mono.fromSupplier(() -> Attempt.failed(false, "NO_RESULT",
                "the handler returned no result")));
    }

    /**
     * A handler's result as stored: an instance of the type's result record whose JSON is at most
     * {@value #MAX_RESULT_BYTES} bytes and reads back as that record, so that the completion process can be given it.
     */
    private Attempt succeeded(ExternalJobType<?, ?> type, ExternalJobResult<?> result, JobContext ctx) {
        if (!type.resultType().isInstance(result.value())) {
            return Attempt.failed(false, "BAD_RESULT", "the handler returned " + result.value().getClass().getName()
                + ", not " + type.resultType().getName());
        }
        result.usage().forEach(ctx::recordUsage);
        Attempt refused = refusedFiles(type, result.files());
        if (refused != null) {
            return refused;
        }
        String text;
        try {
            text = json.writeValueAsString(result.value());
            json.readValue(text, type.resultType());
        } catch (RuntimeException e) {
            return Attempt.failed(false, "BAD_RESULT", "the result does not read back as "
                + type.resultType().getSimpleName() + " (" + e.getClass().getSimpleName() + ")");
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_BYTES) {
            return Attempt.failed(false, "RESULT_TOO_LARGE", "the result is larger than " + MAX_RESULT_BYTES
                + " bytes");
        }
        return Attempt.succeeded(text).producing(result.files());
    }

    /**
     * Why the files a handler returned cannot be stored, before anything is (decision D50 item 3): a type that
     * produces none ({@code BAD_RESULT}), more files ({@code OUTPUT_FILE_LIMIT}) or a larger one
     * ({@code OUTPUT_FILE_TOO_LARGE}) than it declares. Each is a permanent failure: trying again gives the same.
     * Null when they may be stored.
     */
    static Attempt refusedFiles(ExternalJobType<?, ?> type, List<GeneratedFile> produced) {
        if (produced.isEmpty()) {
            return null;
        }
        if (type.outputFiles() == null) {
            return Attempt.failed(false, "BAD_RESULT", "the handler returned files, but the job type declares no "
                + "outputFiles");
        }
        if (produced.size() > type.outputFiles().maxFiles()) {
            return Attempt.failed(false, "OUTPUT_FILE_LIMIT", "the handler returned " + produced.size()
                + " files; the job type allows " + type.outputFiles().maxFiles());
        }
        for (GeneratedFile file : produced) {
            if (file.size() > type.outputFiles().maxBytes()) {
                return Attempt.failed(false, "OUTPUT_FILE_TOO_LARGE", "file " + file.key() + " has " + file.size()
                    + " bytes; the job type allows " + type.outputFiles().maxBytes());
            }
        }
        return null;
    }

    /**
     * The attempt's failure as recorded: the handler's own code and message, the platform's for a timeout or an
     * external call, and for anything else the exception's class only, since its message may repeat a request.
     */
    static Attempt failure(ExternalJobType<?, ?> type, Throwable error) {
        if (error instanceof TimeoutException) {
            return new Attempt(ExternalJobRecorder.AttemptOutcome.TIMED_OUT, true, "TIMEOUT",
                "no result within " + type.timeout(), null, null, null);
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

    /**
     * Records an attempt and, when the job is over, its outcome: one transaction holding the job's row, tried again
     * after a failure of the database (a repeat that finds the attempt recorded drops nothing it has not recorded).
     * The usage is counted once the transaction has committed.
     */
    private Mono<Outcome> record(Claimed claimed, Attempt attempt, Long durationMs, Map<String, Long> usage) {
        ExternalJobType<?, ?> type = claimed.type();
        return Mono.defer(() -> {
                Instant now = clock.instant();
                return recorder.engine().inTransaction(recordAttempt(claimed, attempt, durationMs, usage, now));
            })
            .retryWhen(Retry.backoff(RECORD_RETRIES, Duration.ofMillis(200)).maxBackoff(Duration.ofSeconds(2))
                .doBeforeRetry(signal -> log.warn("Recording external job {} attempt {} failed; trying again: {}",
                    claimed.jobId(), claimed.attemptNo(), signal.failure().toString())))
            .doOnNext(recorded -> {
                if (recorded.attemptRecorded()) {
                    count(type, usage);
                }
            })
            .map(Recorded::outcome);
    }

    private Mono<Recorded> recordAttempt(Claimed claimed, Attempt attempt, Long durationMs, Map<String, Long> usage,
        Instant now) {
        ExternalJobType<?, ?> type = claimed.type();
        // Whatever becomes of the attempt, the files it stored are recorded as its own (decision D50): which job
        // produced a file stays known after it is swept.
        Mono<Void> outputs = Flux.fromIterable(attempt.stored())
            .concatMap(output -> recorder.insertOutputFile(claimed.jobId(), claimed.attemptNo(), output.position(),
                output.key(), output.file(), now))
            .then();
        return recorder.lockJob(claimed.jobId())
            .flatMap(job -> recorder.insertAttempt(claimed.jobId(), claimed.attemptNo(), attempt.outcome(),
                    attempt.retryable(), attempt.errorCode(), attempt.errorDetail(), durationMs, now)
                .flatMap(inserted -> outputs.thenReturn(inserted))
                .flatMap(inserted -> {
                    if (!inserted) {
                        log.warn("External job {} attempt {} ({}) ended after its lease had expired; its result "
                            + "is dropped", claimed.jobId(), claimed.attemptNo(), type.name());
                        return Mono.just(new Recorded(Outcome.DISCARDED, false));
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
                        .map(outcome -> new Recorded(outcome, true));
                }));
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
     * so that an application's scenarios do not depend on the other system (Q17). Refused unless
     * {@code jabiz.external-jobs.simulated-results} is on, which only the test configurations set.
     *
     * @param status {@code SUCCEEDED} with {@code result} (converted to the type's result record), or {@code FAILED}:
     *               a permanent failure with {@code errorCode} and {@code errorMessage}
     * @return the outcome, or empty when no such job is open
     * @throws IllegalStateException when simulated results are not allowed
     */
    public Mono<Outcome> completeWithoutHandler(String jobType, String reference, String status, Object result,
        Map<String, Long> usage, String errorCode, String errorMessage) {
        if (!settings.simulatedResults()) {
            throw new IllegalStateException("External job results are only simulated in tests and scenario replay "
                + "(jabiz.external-jobs.simulated-results)");
        }
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
        return Mono.defer(() -> recorder.engine().inTransaction(recorder.lockType(type.name())
                .then(Mono.fromCallable(() -> ExternalJobRecorder.micros(clock.instant())))
                .flatMap(now -> recorder.pendingJob(type.name(), reference)
                    .flatMap(due -> recorder.insertClaim(due.jobId(), due.attempts() + 1, instanceId(), now,
                            leaseUntil(type, now))
                        .filter(Boolean::booleanValue)
                        .map(claimed -> new Claimed(type, due.jobId(), due.input(), due.attempts() + 1,
                            now.plus(type.timeout()), due.requestedBy()))))))
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
        private volatile List<ExternalJobFile> files = List.of();

        JobContext(Claimed claimed, ExternalServices services) {
            this.claimed = claimed;
            this.services = services;
        }

        /** The copies of the job's input files for this attempt (decision D50 item 2). */
        void files(List<ExternalJobFile> copies) {
            this.files = List.copyOf(copies);
        }

        @Override
        public List<ExternalJobFile> files() {
            return files;
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
