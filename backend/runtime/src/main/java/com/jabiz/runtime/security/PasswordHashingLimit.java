package com.jabiz.runtime.security;

import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.StepDefinition;
import com.jabiz.runtime.AuthBusyException;
import com.jabiz.runtime.ProcessBusyException;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.process.ProcessRegistry;
import com.jabiz.runtime.process.admission.AdmissionGate;
import com.jabiz.runtime.process.steps.CallProcess;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The cap on concurrent password hashing (docs/design/10-security.md section 4.2; decision D47 item 2). BCrypt costs
 * tens to hundreds of milliseconds of CPU on purpose, so a flood of sign-ins would take every core and slow everyone
 * else down. At most {@code jabiz.security.password-hashing.max-concurrent} (the number of processors) hash at once;
 * the others wait their turn, first come first served, at most {@code .max-wait} (2 s), and are then answered 503
 * {@code AUTH_BUSY} with {@code Retry-After}.
 *
 * <p>Two places, each needed:
 * <ol>
 *   <li><b>Before the transaction</b> ({@link #around}): a top-level run of a process with a step that hashes
 *       ({@link HashingStep}: signing in, registering, changing or resetting a password, creating a user), or that
 *       calls such a process as a sub-process ({@code CallProcess}, followed statically through the registry), waits
 *       for a permit before it borrows a connection, holding nothing and blocking no thread (the queue of decision
 *       D45, one family and one key). A flood of sign-ins therefore never holds the connection pool while it waits.
 *       Once admitted, the run is no longer cut short by its caller going away: it ends on its own and only then gives
 *       the permit back, so the permits always bound the hashing actually going on (as {@code ProcessAdmission}).</li>
 *   <li><b>Around the computation</b> ({@link #compute}): the hashing itself, on the virtual thread of its
 *       {@code BlockingStep}, takes a permit of a semaphore of the same size. Runs admitted above never wait here
 *       (there are no more of them than permits); it bounds hashing reached another way (a sub-process called by
 *       name computed at run time, a test or tool calling the hasher directly).</li>
 * </ol>
 * A refused sign-in recorded nothing, so it does not count towards the lock of the account.
 */
@Component
public class PasswordHashingLimit implements PlatformCheck {

    /** Marks the steps that hash or match passwords: processes having one queue before hashing. */
    public interface HashingStep {}

    static final String PREFIX = "jabiz.security.password-hashing";
    static final String FAMILY = "auth.password-hashing";
    static final Duration DEFAULT_MAX_WAIT = Duration.ofSeconds(2);
    /** Waiters at most; more are refused at once (each is only a pending request, but not without bound). */
    static final int MAX_QUEUE = 1000;

    /** No limit: tests and tools that hash without the application context. */
    public static final PasswordHashingLimit NONE = new PasswordHashingLimit();

    private final int maxConcurrent;
    private final Duration maxWait;
    private final AdmissionGate gate;
    private final Semaphore computing;
    private final PlatformObservations observations;
    private final ObjectProvider<ProcessRegistry> registry;
    private final Map<String, Boolean> hashes = new ConcurrentHashMap<>();

    /**
     * @param maxConcurrent {@code jabiz.security.password-hashing.max-concurrent}: the number of processors unless set
     * @param maxWait       {@code jabiz.security.password-hashing.max-wait}: 2 s unless set
     */
    @Autowired
    public PasswordHashingLimit(
        @Value("${" + PREFIX + ".max-concurrent:#{T(java.lang.Runtime).getRuntime().availableProcessors()}}")
        int maxConcurrent,
        @Value("${" + PREFIX + ".max-wait:PT2S}") Duration maxWait, PlatformObservations observations,
        ObjectProvider<ProcessRegistry> registry) {
        this.registry = registry;
        this.maxConcurrent = maxConcurrent;
        this.maxWait = maxWait;
        this.observations = observations;
        // Out-of-range settings are the startup check's to report; the limit keeps to what it can work with.
        int permits = Math.max(1, maxConcurrent);
        this.gate = new AdmissionGate(permits, 1, MAX_QUEUE,
            family -> new AdmissionGate.Limits(permits, MAX_QUEUE, Duration.ZERO), System::nanoTime,
            Schedulers::parallel);
        this.computing = new Semaphore(permits, true);
    }

    private PasswordHashingLimit() {
        this.maxConcurrent = Integer.MAX_VALUE;
        this.maxWait = DEFAULT_MAX_WAIT;
        this.observations = null;
        this.registry = null;
        this.gate = null;
        this.computing = null;
    }

    /** A limit of {@code maxConcurrent} hashings at once, waiting at most {@code maxWait} (tests). */
    public static PasswordHashingLimit of(int maxConcurrent, Duration maxWait) {
        return new PasswordHashingLimit(maxConcurrent, maxWait, PlatformObservations.NOOP, null);
    }

    /** How many may hash at once. */
    public int maxConcurrent() {
        return gate == null ? Integer.MAX_VALUE : Math.max(1, maxConcurrent);
    }

    /**
     * Runs {@code work} - the top-level transaction of {@code definition} - once a permit is held, when the process
     * hashes passwords ({@link HashingStep}); otherwise at once. The permit goes back when {@code work} ends, however
     * it ends; a caller going away while waiting leaves the queue.
     */
    public <T> Mono<T> around(ProcessDefinition<?, ?, ?> definition, Mono<T> work) {
        if (gate == null || !hashesPasswords(definition)) {
            return work;
        }
        return gate.acquire(FAMILY, FAMILY, usableWait())
            .onErrorMap(ProcessBusyException.class, busy -> busy(busy.retryAfterSeconds()))
            // Detached once admitted: a caller that goes away does not end the run (a cancelled transaction would
            // roll back only once its BCrypt step returns), and the permit goes back when the run has really ended.
            .flatMap(permit -> Mono.deferContextual(view -> {
                CompletableFuture<T> run;
                try {
                    run = work.doFinally(signal -> permit.release()).contextWrite(view).toFuture();
                } catch (RuntimeException e) {
                    permit.release();
                    return Mono.error(e);
                }
                return Mono.fromFuture(run, true);
            }));
    }

    /**
     * Runs the hashing {@code computation} (blocking: on a virtual thread, never on an event loop) once one of the
     * permits is free, waiting at most {@code max-wait}; {@link AuthBusyException} after that.
     */
    public <T> T compute(Supplier<T> computation) {
        if (computing == null) {
            return computation.get();
        }
        boolean acquired;
        try {
            acquired = computing.tryAcquire(usableWait().toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw busy(retryAfter());
        }
        if (!acquired) {
            throw busy(retryAfter());
        }
        try {
            return computation.get();
        } finally {
            computing.release();
        }
    }

    /**
     * Whether a step of {@code definition} hashes or matches passwords, or a process it calls by name
     * ({@code CallProcess}, resolved through the registry as the step would resolve it now) does.
     */
    boolean hashesPasswords(ProcessDefinition<?, ?, ?> definition) {
        String key = definition.name() + "@" + definition.version();
        Boolean known = hashes.get(key);
        if (known != null) {
            return known;
        }
        boolean found = hashes(definition, new HashSet<>());
        hashes.put(key, found);
        return found;
    }

    private boolean hashes(ProcessDefinition<?, ?, ?> definition, Set<String> visited) {
        if (!visited.add(definition.name() + "@" + definition.version())) {
            return false;
        }
        ProcessRegistry processes = registry == null ? null : registry.getIfAvailable();
        for (StepDefinition<?, ?> step : definition.steps()) {
            if (HashingStep.class.isAssignableFrom(step.handlerClass())) {
                return true;
            }
            if (processes != null && step.metadata() instanceof CallProcess.Metadata<?> call) {
                ProcessDefinition<?, ?, ?> called = CallProcess.resolve(processes, call).orElse(null);
                if (called != null && hashes(called, visited)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Duration usableWait() {
        return maxWait == null || maxWait.isNegative() || maxWait.isZero() ? DEFAULT_MAX_WAIT : maxWait;
    }

    private long retryAfter() {
        return Math.max(1, (usableWait().toMillis() + 999) / 1000);
    }

    private AuthBusyException busy(long retryAfterSeconds) {
        if (observations != null) {
            observations.event(PlatformObservations.AUTH_HASHING_BUSY);
        }
        return new AuthBusyException(retryAfterSeconds);
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        if (gate != null && maxConcurrent < 1) {
            problems.add(CheckProblem.error("SECURITY", PREFIX + ".max-concurrent", "must be positive"));
        }
        if (gate != null && (maxWait == null || maxWait.isNegative() || maxWait.isZero())) {
            problems.add(CheckProblem.error("SECURITY", PREFIX + ".max-wait", "must be positive"));
        }
        return problems;
    }
}
