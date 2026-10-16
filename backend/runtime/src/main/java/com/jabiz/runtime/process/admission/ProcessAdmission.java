package com.jabiz.runtime.process.admission;

import com.jabiz.process.Admission;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.ProcessBusyException;
import com.jabiz.runtime.observability.PlatformObservations;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.ContextView;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The queue before a hot named lock (docs/design/06-process.md section 4.1; decision D45): a top-level run of a
 * process that declares {@code admission(family, input -> key)} takes a permit of its key before its transaction
 * borrows a connection, and gives it back when the transaction has ended - committed, rolled back, failed or
 * cancelled. At most {@code concurrency} runs of one key are in their transaction at once on this instance, and at
 * most {@code max-active} of all keys together; the others wait here, holding no connection, in order of arrival, at
 * most {@code max-wait}. A full queue or a wait that long ends the run with {@link ProcessBusyException} (503
 * {@code PROCESS_BUSY}) before it has read anything. An admitted run is detached from its caller: a caller that goes
 * away no longer cancels it, it ends on its own (bounded by {@code lock_timeout}) and only then gives its permit back.
 *
 * <p>Admission is not mutual exclusion: each instance queues on its own, and the process's {@code HoldLock} still
 * keeps the runs apart (decision D45 item 2). A wrong key, a restart or several instances change how well the queue
 * works, never the result. Sub-processes never queue: they run in their root's transaction and connection.
 */
@Component
public class ProcessAdmission {

    private static final Logger log = LoggerFactory.getLogger(ProcessAdmission.class);

    /** In the Reactor context of an admitted run and its sub-processes: which key it was admitted under. */
    public record Admitted(String process, String family, String key) {}

    private final AdmissionSettings settings;
    private final AdmissionGate gate;
    private final PlatformObservations observations;
    private final ObjectProvider<MeterRegistry> meters;
    private final Set<String> gauged = ConcurrentHashMap.newKeySet();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /** @param poolSize {@code spring.r2dbc.pool.max-size}, for the default of {@code max-active} (half of it) */
    @Autowired
    public ProcessAdmission(AdmissionSettings settings, @Value("${spring.r2dbc.pool.max-size:10}") int poolSize,
        PlatformObservations observations, ObjectProvider<MeterRegistry> meters) {
        int maxActive = settings.maxActiveFor(poolSize);
        this.settings = Objects.requireNonNull(settings, "settings");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.meters = meters;
        // Out-of-range settings are the startup check's to report; the gate keeps to what it can work with.
        this.gate = new AdmissionGate(Math.max(1, maxActive), Math.max(1, settings.maxKeys()),
            Math.max(0, settings.maxWaiting()), family -> {
                AdmissionSettings.Family own = settings.limitsOf(family);
                return new AdmissionGate.Limits(Math.max(1, own.concurrency()), Math.max(0, own.maxQueue()),
                    own.retryAfter() == null || own.retryAfter().isNegative() ? Duration.ZERO : own.retryAfter());
            }, System::nanoTime, Schedulers::parallel);
    }

    /**
     * Runs {@code work} - the top-level transaction of {@code definition} - once admitted under the key of
     * {@code input}, releasing the permit when it ends; runs it at once when the process declares no admission, its
     * key is null or admission is turned off.
     *
     * @param lockWait the execution's lock wait (a job's items), which shortens the wait in the queue; null for none
     */
    public <I, T> Mono<T> admit(ProcessDefinition<I, ?, ?> definition, I input, Duration lockWait, Mono<T> work) {
        Admission<I> admission = definition.admission();
        if (!settings.enabled() || admission == null) {
            return work;
        }
        return Mono.defer(() -> {
            String key = admission.keyOf(input);
            if (key == null) {
                return work;
            }
            String family = admission.family();
            gauges(family);
            Duration wait = settings.limitsOf(family).maxWait();
            if (wait == null || wait.isNegative() || wait.isZero()) {
                wait = AdmissionSettings.DEFAULT_MAX_WAIT;
            }
            if (lockWait != null && lockWait.compareTo(wait) < 0) {
                wait = lockWait;
            }
            Mono<AdmissionGate.Permit> permit = observations.monoTagged(PlatformObservations.PROCESS_ADMISSION,
                "admission " + family, KeyValues.of("family", family, "process", definition.name()),
                PlatformObservations.RESULT, "CANCELLED", gate.acquire(family, key, wait),
                admitted -> "ADMITTED",
                error -> error instanceof ProcessBusyException busy ? busy.reason().name() : "error");
            Admitted admitted = new Admitted(definition.name(), family, key);
            // Once admitted, the run is not cut short by its caller going away: a cancelled transaction rolls back
            // only once its statement ends, and a statement waiting for the lock waits up to lock_timeout, holding
            // its connection. The permit goes back when the transaction has really ended, so the permits still
            // bound the connections taken.
            return permit.flatMap(held -> Mono.deferContextual(view -> {
                CompletableFuture<T> run;
                try {
                    run = work.contextWrite(context -> context.put(Admitted.class, admitted))
                        .doFinally(signal -> held.release())
                        .contextWrite(view)
                        .toFuture();
                } catch (RuntimeException e) {
                    held.release();
                    return Mono.error(e);
                }
                return Mono.fromFuture(run, true);
            }));
        });
    }

    /** Waiters now, over all keys (tests). */
    public int waiting() {
        return gate.waiting();
    }

    /** Permits held now, over all keys (tests). */
    public int active() {
        return gate.active();
    }

    /** Keys with holders or waiters now (tests: an idle key is forgotten). */
    public int keys() {
        return gate.keys();
    }

    private void gauges(String family) {
        MeterRegistry registry = meters == null ? null : meters.getIfAvailable();
        if (registry == null || !gauged.add(family)) {
            return;
        }
        Gauge.builder(PlatformObservations.PROCESS_ADMISSION_WAITING, gate, g -> g.waiting(family))
            .tag("family", family).description("Runs waiting in the queue before a hot named lock")
            .register(registry);
        Gauge.builder(PlatformObservations.PROCESS_ADMISSION_ACTIVE, gate, g -> g.active(family))
            .tag("family", family).description("Runs admitted past the queue before a hot named lock")
            .register(registry);
    }

    /**
     * Warns once per process when a {@code HoldLock} of a run takes a lock of its admission family under another name
     * than its admission key (decision D45, A-11): the queue then does not match the lock it should stand before. The
     * result is not affected; only the family and the process are logged.
     */
    public static void checkLock(ContextView view, String lockName) {
        Admitted admitted = view.getOrDefault(Admitted.class, null);
        if (admitted == null || lockName == null || !lockName.startsWith(admitted.family() + ":")
            || lockName.equals(admitted.key())) {
            return;
        }
        if (WARNED.size() < 10_000 && WARNED.add(admitted.process())) {
            log.warn("Process {} queued under admission family {} but takes a lock of that family under another "
                + "name: the key from its input and the lock's name should be built by the same helper (decision "
                + "D45)", admitted.process(), admitted.family());
        }
    }
}
