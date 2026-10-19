package com.jabiz.runtime.observability;

import com.jabiz.runtime.web.ProblemStatuses;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
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
    /** Statements per execution of a top-level process (ROADMAP phase 16p). */
    public static final String PROCESS_STATEMENTS = "jabiz.process.statements";
    public static final String DATASET_COMMIT = "jabiz.dataset.commit";
    public static final String DATASET_QUERY = "jabiz.dataset.query";
    public static final String DATASET_READ = "jabiz.dataset.read";
    public static final String TEMPLATE = "jabiz.query.template";
    public static final String EXPORT = "jabiz.query.export";
    public static final String DELIVERY = "jabiz.outbox.delivery";
    public static final String JOB = "jabiz.job.run";
    /** One item of a per-item job (decision D42): tags {@code job} and {@code result} only, never the item's key. */
    public static final String JOB_ITEM = "jabiz.job.item";
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
    /** A call to a declared external service (decision D38); tagged with the service and its outcome only. */
    public static final String EXTERNAL_CALL = "jabiz.external.call";
    /**
     * One attempt of an external job (decision D38), from its handler's start to its record; tagged with
     * {@code job_type} and its {@code outcome} ({@code SUCCEEDED}, {@code WAITING_RETRY}, {@code FAILED},
     * {@code CANCELLED}, {@code DISCARDED}, {@code error}) only.
     */
    public static final String EXTERNAL_JOB = "jabiz.external.job";
    /**
     * A counter of what external jobs consumed (decision D38 item 4), increased by each recorded attempt's quantity;
     * tagged with {@code job_type} and {@code metric} only.
     */
    public static final String EXTERNAL_USAGE = "jabiz.external.usage";
    /**
     * A gauge per external job type that declares {@code enabledBy} (decision D52 item 4): 1 while its parameter holds
     * it, 0 otherwise; tagged with {@code job_type} only.
     */
    public static final String EXTERNAL_JOB_HELD = "jabiz.external.job.held";
    /**
     * One inbound webhook request of a served provider (decision D37): tags {@code provider}, {@code result}
     * ({@code ACCEPTED}, {@code DUPLICATE}, {@code IGNORED}, {@code REJECTED}, {@code FAILED}) and {@code reason}
     * ({@code none}, the rejection's, or {@code error}) only.
     */
    public static final String INBOUND = "jabiz.inbound.receive";
    public static final String INBOUND_RATE_LIMITED = "jabiz.inbound.rate_limited";
    /**
     * The wait of a top-level process in the queue before its hot named lock (decision D45), nested in
     * {@link #PROCESS}: tags {@code family}, {@code process} and {@code result} ({@code ADMITTED}, {@code QUEUE_FULL},
     * {@code WAIT_TIMEOUT}, {@code CANCELLED}) only, never the key.
     */
    public static final String PROCESS_ADMISSION = "jabiz.process.admission";
    /** Gauges of the waiters and of the permits held now, by {@code family} (decision D45). */
    public static final String PROCESS_ADMISSION_WAITING = "jabiz.process.admission.waiting";
    public static final String PROCESS_ADMISSION_ACTIVE = "jabiz.process.admission.active";
    /** A request of the process API refused by its rate limit (decision D45): tags {@code process} and {@code scope}. */
    public static final String PROCESS_RATE_LIMITED = "jabiz.process.rate_limited";
    /** A sign-in request refused by the per-address limit (decision D47): tag {@code kind} only. */
    public static final String AUTH_RATE_LIMITED = "jabiz.auth.rate_limited";
    /** Password hashing refused because too many were waiting for it (decision D47): no tags. */
    public static final String AUTH_HASHING_BUSY = "jabiz.auth.hashing.busy";

    public static final String OUTCOME = "outcome";
    public static final String STATUS = "status";
    public static final String RESULT = "result";
    static final String NO_STATUS = "none";

    /** Without an observation registry (a context without actuator): observes nothing. */
    public static final PlatformObservations NOOP = new PlatformObservations(ObservationRegistry.NOOP);

    private final ObservationRegistry registry;
    private final MeterRegistry meters;

    @Autowired
    public PlatformObservations(ObjectProvider<ObservationRegistry> registry, ObjectProvider<MeterRegistry> meters) {
        this(registry.getIfAvailable(() -> ObservationRegistry.NOOP), meters.getIfAvailable());
    }

    public PlatformObservations(ObjectProvider<ObservationRegistry> registry) {
        this(registry.getIfAvailable(() -> ObservationRegistry.NOOP), null);
    }

    public PlatformObservations(ObservationRegistry registry) {
        this(registry, null);
    }

    /** @param meters where {@link #recordStatements} records; null: nowhere */
    public PlatformObservations(ObservationRegistry registry, MeterRegistry meters) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.meters = meters;
    }

    /**
     * Records how many statements a top-level process sent in its transaction ({@value #PROCESS_STATEMENTS}, a
     * distribution tagged with the process's name only, decision D16): the round trips that make up most of the time a
     * hot lock is held (ROADMAP phase 16p).
     */
    public void recordStatements(String process, long statements) {
        if (meters == null) {
            return;
        }
        DistributionSummary.builder(PROCESS_STATEMENTS)
            .description("Statements a process sent in its transaction, before its commit")
            .baseUnit("statements")
            .tag("process", process)
            .register(meters)
            .record(statements);
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

    /**
     * Observes blocking work on the calling thread whose outcome is its own (an external call: {@code 2xx},
     * {@code timeout} ...), not the HTTP status of a domain exception: the observation carries {@code tags} and
     * {@value #OUTCOME} only.
     *
     * @param outcome the outcome of a value
     * @param failure the outcome of an exception, which is rethrown
     */
    public <T> T outcomeOf(String name, String contextualName, KeyValues tags, Supplier<T> work,
        Function<? super T, String> outcome, Function<RuntimeException, String> failure) {
        Observation observation = Observation.createNotStarted(name, registry)
            .contextualName(contextualName)
            .lowCardinalityKeyValues(tags)
            .start();
        try (Observation.Scope ignored = observation.openScope()) {
            T value = work.get();
            observation.lowCardinalityKeyValue(OUTCOME, outcome.apply(value));
            return value;
        } catch (RuntimeException e) {
            observation.lowCardinalityKeyValue(OUTCOME, failure.apply(e));
            throw e;
        } finally {
            observation.stop();
        }
    }

    /**
     * Observes {@code source} from subscription to its end, as {@link #outcomeOf} does blocking work: the observation
     * carries {@code tags} and {@value #OUTCOME} only, the outcome of the value or of the error (an attempt of an
     * external job: {@code SUCCEEDED}, {@code TIMED_OUT} ...), or {@code cancelled}.
     */
    public <T> Mono<T> monoOutcome(String name, String contextualName, KeyValues tags, Mono<T> source,
        Function<? super T, String> outcome, Function<Throwable, String> failure) {
        return monoTagged(name, contextualName, tags, OUTCOME, "cancelled", source, outcome, failure);
    }

    /**
     * As {@link #monoOutcome}, with the result under {@code key} and {@code cancelled} as the result of a
     * cancellation (the admission of a process: {@value #RESULT}, {@code CANCELLED}).
     */
    public <T> Mono<T> monoTagged(String name, String contextualName, KeyValues tags, String key, String cancelled,
        Mono<T> source, Function<? super T, String> outcome, Function<Throwable, String> failure) {
        if (registry.isNoop()) {
            return source;
        }
        return Mono.deferContextual(view -> {
            Observation observation = start(name, contextualName, tags, view);
            AtomicBoolean ended = new AtomicBoolean();
            return source
                .contextWrite(context -> context.put(ObservationThreadLocalAccessor.KEY, observation))
                .doOnSuccess(value -> {
                    if (ended.compareAndSet(false, true)) {
                        observation.lowCardinalityKeyValue(key, value == null ? "none" : outcome.apply(value));
                        observation.stop();
                    }
                })
                .doOnError(error -> {
                    if (ended.compareAndSet(false, true)) {
                        observation.lowCardinalityKeyValue(key, failure.apply(error));
                        observation.error(error);
                        observation.stop();
                    }
                })
                .doOnCancel(() -> {
                    if (ended.compareAndSet(false, true)) {
                        observation.lowCardinalityKeyValue(key, cancelled);
                        observation.stop();
                    }
                });
        });
    }

    /** Records that something happened (a counter without tags), such as a request refused by a rate limit. */
    public void event(String name) {
        Observation.createNotStarted(name, registry).start().stop();
    }

    /** Records that something happened, with low-cardinality {@code tags} (names only, never keys or values). */
    public void event(String name, KeyValues tags) {
        Observation.createNotStarted(name, registry).lowCardinalityKeyValues(tags).start().stop();
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
