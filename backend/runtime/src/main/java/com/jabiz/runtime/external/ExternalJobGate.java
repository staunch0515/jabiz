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
 * <p>A change of a type between held and open (or of why it is held) is logged at INFO by each executor instance; the gauge
 * {@value PlatformObservations#EXTERNAL_JOB_HELD} is 1 while a type is held, tagged with {@code job_type} only.
 */
final class ExternalJobGate {

    private static final Logger log = LoggerFactory.getLogger(ExternalJobGate.class);
    /** Stands for "open" in {@link #last}: a reason is never empty. */
    static final String OPEN = "";

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
     * it. Never fails: a read that fails holds the types of that key.
     */
    Mono<Map<String, String>> held(List<ExternalJobType<?, ?>> types) {
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
                .concatMap(key -> read(key, now).map(reason -> Map.entry(key, reason)))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .map(reasons -> {
                    Map<String, String> held = new LinkedHashMap<>();
                    for (ExternalJobType<?, ?> type : types) {
                        if (type.enabledBy() == null) {
                            continue;
                        }
                        String reason = reasons.get(type.enabledBy());
                        observe(type, reason);
                        if (!OPEN.equals(reason)) {
                            held.put(type.name(), reason);
                        }
                    }
                    return held;
                });
        });
    }

    /** Why a type of {@code key} is held now, or {@link #OPEN}. */
    private Mono<String> read(String key, Instant now) {
        ParamService service = params.getIfAvailable();
        if (service == null) {
            return Mono.just("parameter " + key + " cannot be read (no parameter service)");
        }
        RequestContext system = RequestContext.system(Locale.ENGLISH, "external-job-gate");
        return Mono.defer(() -> service.get(key, now))
            .map(value -> {
                String wrong = reason(value);
                return wrong.isEmpty() ? OPEN : "parameter " + key + " " + wrong;
            })
            .defaultIfEmpty("parameter " + key + " has no value in effect")
            .onErrorResume(error -> {
                if (missing(error)) {
                    return Mono.just("parameter " + key + " has no value in effect");
                }
                log.warn("Parameter {} that holds external jobs could not be read; they wait: {}", key,
                    error.toString());
                return Mono.just("parameter " + key + " cannot be read (" + error.getClass().getSimpleName() + ")");
            })
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

    private void observe(ExternalJobType<?, ?> type, String reason) {
        String before = last.put(type.name(), reason);
        if (!reason.equals(before)) {
            if (!OPEN.equals(reason)) {
                // On pausing, and when why it is held changes (missing, then false): never at every poll.
                log.info("External job type {} is paused: {}; its jobs wait", type.name(), reason);
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
