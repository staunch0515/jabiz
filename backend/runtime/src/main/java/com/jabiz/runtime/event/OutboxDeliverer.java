package com.jabiz.runtime.event;

import com.jabiz.context.RequestContext;
import com.jabiz.event.DomainEvent;
import com.jabiz.event.EventSubscription;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.operation.Operation;
import com.jabiz.runtime.process.ExecutionOptions;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.runtime.storage.UniqueKeyViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Delivers outbox events to their consumers (docs/design/11-ledger-events-jobs.md section 2.3; decision D14).
 *
 * <p>Each pending (consumer, event) pair is delivered by running the consumer's process as the system actor. The
 * first thing in that process's transaction, right after its operation is recorded, is the consumption row whose
 * primary key is (consumer, event): it commits together with everything the process did, so
 * <ul>
 *   <li>a delivery that fails rolls back its consumption with its effects and is retried later (at least once);</li>
 *   <li>a concurrent delivery of the same event, by another instance or another poll, waits on that key and then
 *       fails as a duplicate without having done anything; a later one finds the consumption and never starts
 *       (processed once).</li>
 * </ul>
 * Failures are recorded one row per attempt ({@code sys_outbox_attempt}) and retried with exponential backoff until
 * the maximum number of attempts, after which the event is left for an operator (error log). Delivery order is not
 * guaranteed. Instances poll on their own; no coordination beyond the database is needed.
 */
@Component
public class OutboxDeliverer {

    private static final Logger log = LoggerFactory.getLogger(OutboxDeliverer.class);

    static final String CONSUMPTION_TABLE = "sys_event_consumption";
    static final String CONSUMPTION_KEY = "sys_event_consumption_pkey";
    static final String ATTEMPT_TABLE = "sys_outbox_attempt";
    private static final int MAX_ERROR_LENGTH = 2000;

    /** What happened to one delivery. */
    public enum Outcome { CONSUMED, DUPLICATE, FAILED }

    private final ObjectProvider<EventSubscription<?>> subscriptions;
    private final ProcessExecutor executor;
    private final StorageAdapterRegistry storages;
    private final JsonMapper json;
    private final Clock clock;
    private final String poolRef;
    private final boolean enabled;
    private final Duration pollInterval;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private volatile Disposable polling;

    public OutboxDeliverer(ObjectProvider<EventSubscription<?>> subscriptions, ProcessExecutor executor,
        StorageAdapterRegistry storages, JsonMapper json, Clock clock,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
        @Value("${jabiz.events.delivery.enabled:true}") boolean enabled,
        @Value("${jabiz.events.delivery.poll-interval:1s}") Duration pollInterval,
        @Value("${jabiz.events.delivery.batch-size:20}") int batchSize,
        @Value("${jabiz.events.delivery.max-attempts:10}") int maxAttempts,
        @Value("${jabiz.events.delivery.initial-backoff:5s}") Duration initialBackoff,
        @Value("${jabiz.events.delivery.max-backoff:1h}") Duration maxBackoff) {
        this.subscriptions = subscriptions;
        this.executor = executor;
        this.storages = storages;
        this.json = json;
        this.clock = clock;
        this.poolRef = Objects.requireNonNull(poolRef, "poolRef must not be null");
        this.enabled = enabled;
        this.pollInterval = pollInterval;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        if (batchSize <= 0 || maxAttempts <= 0 || pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("jabiz.events.delivery: batch-size, max-attempts and poll-interval "
                + "must be positive");
        }
    }

    /** Starts polling once the application is ready (not in the platform check, which never becomes ready). */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled || subscriptions.orderedStream().findAny().isEmpty()) {
            return;
        }
        polling = Flux.interval(pollInterval)
            .onBackpressureDrop()
            .concatMap(tick -> deliverPending()
                .onErrorResume(error -> {
                    log.error("Delivering outbox events failed", error);
                    return Mono.just(0);
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

    /** Delivers what is due now to every consumer, one batch per consumer; returns the number of deliveries tried. */
    public Mono<Integer> deliverPending() {
        return Flux.fromStream(subscriptions.orderedStream())
            .concatMap(subscription -> pending(subscription)
                .concatMap(pending -> deliver(subscription, pending.event(), pending.failures() + 1))
                .count())
            .reduce(0L, Long::sum)
            .map(Math::toIntExact);
    }

    record Pending(DomainEvent event, int failures) {}

    /** Events of the subscription's type that its consumer has not consumed and that are due for an attempt. */
    private Flux<Pending> pending(EventSubscription<?> subscription) {
        Map<String, BoundValue> params = new LinkedHashMap<>();
        params.put("type", BoundValue.of(subscription.eventType()));
        params.put("consumer", BoundValue.of(subscription.consumer()));
        params.put("max", BoundValue.of((long) maxAttempts));
        params.put("now", BoundValue.of(clock.instant()));
        params.put("initial", BoundValue.of(seconds(initialBackoff)));
        params.put("cap", BoundValue.of(seconds(maxBackoff)));
        params.put("batch", BoundValue.of((long) batchSize));
        return engine().select("""
                SELECT * FROM (
                    SELECT e.event_seq, e.event_id, e.event_type, e.payload, e.process_seq_id, e.created_time,
                           (SELECT count(*) FROM sys_outbox_attempt a
                             WHERE a.consumer = :consumer AND a.event_id = e.event_id) AS failures,
                           (SELECT max(a.attempted_at) FROM sys_outbox_attempt a
                             WHERE a.consumer = :consumer AND a.event_id = e.event_id) AS last_failure
                    FROM sys_outbox_event e
                    WHERE e.event_type = :type
                      AND NOT EXISTS (SELECT 1 FROM sys_event_consumption c
                                       WHERE c.consumer = :consumer AND c.event_id = e.event_id)
                ) p
                WHERE p.failures < :max
                  AND (p.failures = 0 OR p.last_failure
                       + make_interval(secs => LEAST(:cap, :initial * power(2, p.failures - 1))) <= :now)
                ORDER BY p.event_seq
                LIMIT :batch""", params)
            .map(row -> new Pending(toEvent(row), Math.toIntExact(Rows.longValue(row.get("failures")))));
    }

    private static double seconds(Duration duration) {
        return duration.toMillis() / 1000.0;
    }

    private DomainEvent toEvent(Map<String, Object> row) {
        String payload = Rows.string(row.get("payload"));
        return new DomainEvent(Rows.uuid(row.get("event_id")), Rows.string(row.get("event_type")),
            // Decimals stay exact (amounts), rather than becoming doubles.
            json.readerFor(new TypeReference<Map<String, Object>>() {})
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .<Map<String, Object>>readValue(payload),
            Rows.longValue(row.get("process_seq_id")), Rows.instant(row.get("created_time")));
    }

    /**
     * Delivers one event to one consumer: runs its process with the consumption recorded in the same transaction.
     *
     * @param attempt number of this attempt, counting earlier failures
     */
    public <I> Mono<Outcome> deliver(EventSubscription<I> subscription, DomainEvent event, int attempt) {
        RequestContext system = RequestContext.system(Locale.ENGLISH, "evt-" + event.eventId());
        return Mono.fromCallable(() -> subscription.input().apply(event))
            .flatMap(input -> executor.run(subscription.process(), input, ExecutionOptions.NONE
                .withBeforeSteps(operation -> consume(subscription, event, operation))))
            .thenReturn(Outcome.CONSUMED)
            .onErrorResume(error -> {
                if (error instanceof UniqueKeyViolationException unique
                    && CONSUMPTION_KEY.equals(unique.constraintName())) {
                    log.debug("Event {} was consumed by {} meanwhile", event.eventId(), subscription.consumer());
                    return Mono.just(Outcome.DUPLICATE);
                }
                return recordFailure(subscription, event, attempt, error).thenReturn(Outcome.FAILED);
            })
            .contextWrite(view -> RequestContexts.put(view, system));
    }

    private Mono<Void> consume(EventSubscription<?> subscription, DomainEvent event, Operation operation) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("consumer", subscription.consumer());
        row.put("event_id", event.eventId());
        row.put("process_seq_id", operation.processSeqId());
        row.put("consumed_time", operation.opTime());
        return engine().insert(CONSUMPTION_TABLE, row);
    }

    private Mono<Void> recordFailure(EventSubscription<?> subscription, DomainEvent event, int attempt,
        Throwable error) {
        if (attempt >= maxAttempts) {
            log.error("Event {} ({}) could not be delivered to {} after {} attempts; it is no longer retried",
                event.eventId(), event.eventType(), subscription.consumer(), attempt, error);
        } else {
            log.warn("Delivering event {} ({}) to {} failed (attempt {}): {}", event.eventId(), event.eventType(),
                subscription.consumer(), attempt, error.toString());
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("consumer", subscription.consumer());
        row.put("event_id", event.eventId());
        row.put("attempt", attempt);
        row.put("error", describe(error));
        row.put("attempted_at", clock.instant().truncatedTo(ChronoUnit.MICROS));
        StorageEngine engine = engine();
        return engine.inTransaction(engine.insert(ATTEMPT_TABLE, row))
            // Another instance recorded the same attempt of a concurrent failure: one row is enough.
            .onErrorResume(UniqueKeyViolationException.class, duplicate -> Mono.empty());
    }

    private static String describe(Throwable error) {
        String text = error.getClass().getName() + (error.getMessage() == null ? "" : ": " + error.getMessage());
        return text.length() > MAX_ERROR_LENGTH ? text.substring(0, MAX_ERROR_LENGTH) : text;
    }

    private StorageEngine engine() {
        return storages.getEngine(poolRef);
    }

    /** Subscriptions as declared, for checks and listings. */
    public List<EventSubscription<?>> subscriptions() {
        return subscriptions.orderedStream().toList();
    }
}
