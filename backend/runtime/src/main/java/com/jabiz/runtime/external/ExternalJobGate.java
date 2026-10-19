package com.jabiz.runtime.external;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.Violation;
import com.jabiz.external.ExternalJobType;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.param.ParamService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether the job types that declare {@link ExternalJobType#enabledBy} may be claimed now (decision D52 item 4): only
 * while their business parameter's value in effect at the clock's now is boolean {@code true}. A parameter that is
 * missing, {@code false}, of another kind, or cannot be read holds the type (deny by default). Each key is read once
 * per call, as the system, through {@link ParamService}, outside any transaction of the executor.
 *
 * <p>Each executor instance logs a type only when it changes: between held and open, or why it is held. A pause by
 * {@code false} or by a value of another kind is someone's decision and logged at INFO; a parameter with no value in
 * effect, or one that cannot be read, is unexpected and logged at WARN, once per change and not at every poll, however
 * long the database stays down. The gauge {@value PlatformObservations#EXTERNAL_JOB_HELD} is 1 while a type is held,
 * tagged with {@code job_type} only.
 */
final class ExternalJobGate {

    private static final Logger log = LoggerFactory.getLogger(ExternalJobGate.class);
    /** Stands for "open" in {@link #last}: a reason is never empty. */
    static final String OPEN = "";

    /**
     * What one look at a type's parameter found.
     *
     * @param reason     why the type is held, or {@link #OPEN}; the same text for the same cause, so that a change can
     *                   be told from a repeat
     * @param unexpected whether the cause is a missing or unreadable parameter rather than a decision to pause
     * @param detail     what failed, for the log only (not compared)
     */
    record Look(String reason, boolean unexpected, String detail) {

        static final Look OPEN_LOOK = new Look(OPEN, false, null);

        boolean held() {
            return !OPEN.equals(reason);
        }
    }

    private final ObjectProvider<ParamService> params;
    private final ObjectProvider<MeterRegistry> meters;
    private final Clock clock;
    /** Per type: why it was held at the last look, or {@link #OPEN}; absent before the first look. */
    private final Map<String, String> last = new ConcurrentHashMap<>();
    private final Set<String> gauged = ConcurrentHashMap.newKeySet();

    ExternalJobGate(ObjectProvider<ParamService> params, ObjectProvider<MeterRegistry> meters, Clock clock) {
        this.params = params;
        this.meters = meters;
        this.clock = clock;
    }

    /**
     * The types among {@code types} that are held now, each with why; types that declare no parameter are never in
     * it. Never fails: a read that fails holds the types of that key. Logs and updates the gauge on a change.
     */
    Mono<Map<String, String>> held(List<ExternalJobType<?, ?>> types) {
        return look(types).map(looks -> {
            Map<String, String> held = new LinkedHashMap<>();
            for (ExternalJobType<?, ?> type : types) {
                Look look = looks.get(type.name());
                if (look == null) {
                    continue;
                }
                observe(type, look);
                if (look.held()) {
                    held.put(type.name(), look.reason());
                }
            }
            return held;
        });
    }

    /**
     * What the parameter of each type among {@code types} that declares one says now, by type name; logs nothing.
     * For the executor through {@link #held}, and for the startup check.
     */
    Mono<Map<String, Look>> look(List<ExternalJobType<?, ?>> types) {
        Set<String> keys = new LinkedHashSet<>();
        for (ExternalJobType<?, ?> type : types) {
            if (type.enabledBy() != null) {
                keys.add(type.enabledBy());
            }
        }
        if (keys.isEmpty()) {
            return Mono.just(Map.of());
        }
        return Mono.defer(() -> {
            Instant now = clock.instant();
            return Flux.fromIterable(keys)
                .concatMap(key -> read(key, now).map(look -> Map.entry(key, look)))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .map(byKey -> {
                    Map<String, Look> looks = new LinkedHashMap<>();
                    for (ExternalJobType<?, ?> type : types) {
                        if (type.enabledBy() != null) {
                            looks.put(type.name(), byKey.get(type.enabledBy()));
                        }
                    }
                    return looks;
                });
        });
    }

    /** What the parameter {@code key} says now. */
    private Mono<Look> read(String key, Instant now) {
        ParamService service = params.getIfAvailable();
        if (service == null) {
            return Mono.just(new Look("parameter " + key + " cannot be read (no parameter service)", true, null));
        }
        RequestContext system = RequestContext.system(Locale.ENGLISH, "external-job-gate");
        Look missing = new Look("parameter " + key + " has no value in effect", true, null);
        return Mono.defer(() -> service.get(key, now))
            .map(value -> {
                String wrong = reason(value);
                return wrong.isEmpty() ? Look.OPEN_LOOK : new Look("parameter " + key + " " + wrong, false, null);
            })
            .defaultIfEmpty(missing)
            .onErrorResume(error -> Mono.just(missing(error) ? missing
                : new Look("parameter " + key + " cannot be read (" + error.getClass().getSimpleName() + ")", true,
                    error.toString())))
            .contextWrite(view -> RequestContexts.put(view, system));
    }

    /** Empty when {@code value} lets the type be claimed; otherwise what is wrong with it. */
    static String reason(Object value) {
        if (Boolean.TRUE.equals(value)) {
            return OPEN;
        }
        if (Boolean.FALSE.equals(value)) {
            return "is false";
        }
        return value == null ? "has no value in effect" : "is not a boolean";
    }

    private static boolean missing(Throwable error) {
        return error instanceof BusinessRuleViolationException rejected && rejected.violations().stream()
            .map(Violation::ruleCode).anyMatch(PlatformErrorCodes.PARAM_NOT_FOUND::equals);
    }

    /** Logs the type when it changed since the last look: on pausing, resuming and a new reason, never a repeat. */
    private void observe(ExternalJobType<?, ?> type, Look look) {
        String before = last.put(type.name(), look.reason());
        if (!look.reason().equals(before)) {
            if (look.held() && look.unexpected()) {
                log.warn("External job type {} is paused: {}; its jobs wait{}", type.name(), look.reason(),
                    look.detail() == null ? "" : " (" + look.detail() + ")");
            } else if (look.held()) {
                log.info("External job type {} is paused: {}; its jobs wait", type.name(), look.reason());
            } else if (before != null) {
                log.info("External job type {} resumes: parameter {} is true", type.name(), type.enabledBy());
            }
        }
        gauge(type.name());
    }

    /** 1 while the type was held at the last look, for the gauge. */
    private double heldNow(String typeName) {
        String reason = last.get(typeName);
        return reason != null && !OPEN.equals(reason) ? 1 : 0;
    }

    /**
     * Registers the type's gauge once per gate. Micrometer keeps one gauge per name and tags: when several executors
     * run in one JVM (tests of several instances), the first one's gate backs it and the others' registrations return
     * that gauge, so it shows the first instance's view. A deployed instance is a JVM of its own with one executor.
     */
    private void gauge(String typeName) {
        MeterRegistry registry = meters == null ? null : meters.getIfAvailable();
        if (registry == null || !gauged.add(typeName)) {
            return;
        }
        Gauge.builder(PlatformObservations.EXTERNAL_JOB_HELD, this, gate -> gate.heldNow(typeName))
            .tag("job_type", typeName).description("1 while an external job type is paused by its parameter")
            .register(registry);
    }
}
