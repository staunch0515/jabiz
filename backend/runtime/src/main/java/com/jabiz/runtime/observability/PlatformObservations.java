package com.jabiz.runtime.observability;

import com.jabiz.runtime.web.ProblemStatuses;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Observations of the platform's own units of work (docs/design/13-observability-ops.md): each becomes a timer
 * metric and, with tracing on, a span nested in the span of the request (or of the enclosing unit, e.g. a
 * sub-process inside its parent).
 *
 * <p>Tags are low-cardinality names only: the process, dataset, template, consumer or job, and the outcome. Never
 * keys, actors or field values (they may be personal or secret, docs/design/10-security.md section 6). The outcome is
 * {@code success}, {@code rejected} (a 4xx answer: invalid input, missing permission, conflict, business rule),
 * {@code error} (anything else) or {@code cancelled}; {@code status} is the HTTP status the failure maps to.
 */
@Component
public class PlatformObservations {

    public static final String PROCESS = "jabiz.process";
    public static final String DATASET_COMMIT = "jabiz.dataset.commit";
    public static final String DATASET_QUERY = "jabiz.dataset.query";
    public static final String DATASET_READ = "jabiz.dataset.read";
    public static final String TEMPLATE = "jabiz.query.template";
    public static final String EXPORT = "jabiz.query.export";
    public static final String DELIVERY = "jabiz.outbox.delivery";
    public static final String JOB = "jabiz.job.run";
    public static final String FILE_UPLOAD = "jabiz.file.upload";
    public static final String FILE_SERVE = "jabiz.file.serve";
    public static final String FILE_SWEEP = "jabiz.file.sweep";
    public static final String PUBLIC_QUERY = "jabiz.public.query";
    public static final String IMPORT = "jabiz.import.run";
    public static final String DATA_EXPORT = "jabiz.data.export";
    public static final String AUTH_OIDC = "jabiz.auth.oidc";
    public static final String PUBLIC_RATE_LIMITED = "jabiz.public.rate_limited";
    /** A sign-in guard refused a session at a refresh, which ended it (decision D36 item 6). */
    public static final String AUTH_REFRESH_REFUSED = "jabiz.auth.refresh.refused";
    public static final String DOCUMENT_RENDER = "jabiz.document.render";
    public static final String WEBHOOK = "jabiz.webhook.delivery";

    public static final String OUTCOME = "outcome";
    public static final String STATUS = "status";
    public static final String RESULT = "result";
    static final String NO_STATUS = "none";

    /** Without an observation registry (a context without actuator): observes nothing. */
    public static final PlatformObservations NOOP = new PlatformObservations(ObservationRegistry.NOOP);

    private final ObservationRegistry registry;

    @Autowired
    public PlatformObservations(ObjectProvider<ObservationRegistry> registry) {
        this(registry.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    public PlatformObservations(ObservationRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    /** Observes {@code source} from subscription to its end. */
    public <T> Mono<T> mono(String name, String contextualName, KeyValues tags, Mono<T> source) {
        if (registry.isNoop()) {
            return source;
        }
        return Mono.deferContextual(view -> {
            Observation observation = start(name, contextualName, tags, view);
            AtomicBoolean ended = new AtomicBoolean();
            return source
                .contextWrite(context -> context.put(ObservationThreadLocalAccessor.KEY, observation))
                .doOnSuccess(value -> end(observation, ended, null))
                .doOnError(error -> end(observation, ended, error))
                .doOnCancel(() -> cancel(observation, ended));
        });
    }

    /** Observes {@code source} from subscription to completion, error or cancellation. */
    public <T> Flux<T> flux(String name, String contextualName, KeyValues tags, Flux<T> source) {
        if (registry.isNoop()) {
            return source;
        }
        return Flux.deferContextual(view -> {
            Observation observation = start(name, contextualName, tags, view);
            AtomicBoolean ended = new AtomicBoolean();
            return source
                .contextWrite(context -> context.put(ObservationThreadLocalAccessor.KEY, observation))
                .doOnComplete(() -> end(observation, ended, null))
                .doOnError(error -> end(observation, ended, error))
                .doOnCancel(() -> cancel(observation, ended));
        });
    }

    /**
     * Observes blocking work on the calling thread (jobs run on the scheduler's thread, not on the event loop).
     *
     * @param result names the result for the {@code result} tag, e.g. whether the job ran or another instance did
     */
    public <T> T blocking(String name, String contextualName, KeyValues tags, Supplier<T> work,
        Function<? super T, String> result) {
        Observation observation = Observation.createNotStarted(name, registry)
            .contextualName(contextualName)
            .lowCardinalityKeyValues(tags)
            .start();
        try (Observation.Scope ignored = observation.openScope()) {
            T value = work.get();
            observation.lowCardinalityKeyValue(RESULT, result.apply(value));
            succeeded(observation);
            return value;
        } catch (RuntimeException e) {
            observation.lowCardinalityKeyValue(RESULT, NO_STATUS);
            tagFailure(observation, e);
            throw e;
        } finally {
            observation.stop();
        }
    }

    /** Records that something happened (a counter without tags), such as a request refused by a rate limit. */
    public void event(String name) {
        Observation.createNotStarted(name, registry).start().stop();
    }

    /** Adds a tag to the observation of the current unit, once its value is known (e.g. a job's result). */
    public static Mono<Void> tag(String key, String value) {
        return Mono.deferContextual(view -> {
            Object current = view.getOrDefault(ObservationThreadLocalAccessor.KEY, null);
            if (current instanceof Observation observation) {
                observation.lowCardinalityKeyValue(key, value);
            }
            return Mono.empty();
        });
    }

    private Observation start(String name, String contextualName, KeyValues tags, ContextView view) {
        Observation observation = Observation.createNotStarted(name, registry)
            .contextualName(contextualName)
            .lowCardinalityKeyValues(tags);
        Object parent = view.getOrDefault(ObservationThreadLocalAccessor.KEY, null);
        if (parent instanceof Observation parentObservation) {
            observation.parentObservation(parentObservation);
        }
        return observation.start();
    }

    private static void end(Observation observation, AtomicBoolean ended, Throwable error) {
        if (!ended.compareAndSet(false, true)) {
            return;
        }
        if (error == null) {
            succeeded(observation);
        } else {
            tagFailure(observation, error);
        }
        observation.stop();
    }

    private static void cancel(Observation observation, AtomicBoolean ended) {
        if (ended.compareAndSet(false, true)) {
            observation.lowCardinalityKeyValues(KeyValues.of(KeyValue.of(OUTCOME, "cancelled"),
                KeyValue.of(STATUS, NO_STATUS)));
            observation.stop();
        }
    }

    // Every observation of a name carries the same tag keys, as metric backends expect.
    private static void succeeded(Observation observation) {
        observation.lowCardinalityKeyValues(KeyValues.of(KeyValue.of(OUTCOME, "success"),
            KeyValue.of(STATUS, NO_STATUS)));
    }

    private static void tagFailure(Observation observation, Throwable error) {
        int status = ProblemStatuses.status(error);
        observation.lowCardinalityKeyValues(KeyValues.of(
            KeyValue.of(OUTCOME, status < 500 ? "rejected" : "error"),
            KeyValue.of(STATUS, String.valueOf(status))));
        if (status >= 500) {
            observation.error(error);
        }
    }
}
