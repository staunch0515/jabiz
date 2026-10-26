package com.jabiz.runtime.inbound;

import com.jabiz.context.RequestContext;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Loads a received inbound webhook event into the context as a {@link ReceivedEvent} (docs/design/11-ledger-events-jobs.md
 * section 2.5): the step a consumer of {@code jabiz.inbound.<provider>.<type>} starts with, since the outbox event
 * carries only the reference ({@link InboundEvents#ref}).
 * <pre>{@code
 * .step("Load the payment", LoadInboundEvent.of("pay", "payment_intent.succeeded",
 *     ctx -> ctx.get(INPUT, Input.class).inboundEventId(), "payment"))
 * }</pre>
 * A consumer names the provider and the event type it handles: an event of another provider or type fails the step,
 * so a process written for one sender's events never acts on another's (whose signature it has no reason to trust
 * for this). The body is personal data guarded by {@code inbound.read}: the step loads it for the event consumers
 * (the system) and for actors holding that permission, and refuses anyone else (403). An unknown id fails the process
 * (404).
 */
@Component
public class LoadInboundEvent<C extends ProcessContext> implements StepHandler<LoadInboundEvent.Metadata<C>, C> {

    /**
     * @param provider  the provider whose events the process handles
     * @param eventType the provider's event type it handles ({@code payment_intent.succeeded})
     * @param id        the id of the received event, from the context
     * @param targetKey the context key receiving the {@link ReceivedEvent}
     */
    public record Metadata<C>(String provider, String eventType, Function<C, String> id, String targetKey) {
        public Metadata {
            Objects.requireNonNull(provider, "provider must not be null");
            Objects.requireNonNull(eventType, "eventType must not be null");
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(targetKey, "targetKey must not be null");
        }
    }

    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> of(String provider, String eventType,
        Function<C, String> id, String targetKey) {
        return StepSpec.of(LoadInboundEvent.class, new Metadata<>(provider, eventType, id, targetKey));
    }

    private final StorageAdapterRegistry storages;
    private final String poolRef;
    private final JsonMapper json;

    LoadInboundEvent(StorageAdapterRegistry storages, @Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
        JsonMapper json) {
        this.storages = storages;
        this.poolRef = poolRef;
        this.json = json;
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        return Mono.defer(() -> {
            RequestContext request = ctx.request();
            if (!RequestContext.SYSTEM_ACTOR.equals(request.actorId())
                && !request.hasPermission(InboundPermissions.READ)) {
                return Mono.error(new PermissionDeniedException(InboundPermissions.READ,
                    "Loading an inbound event requires permission " + InboundPermissions.READ));
            }
            String id = Objects.requireNonNull(metadata.id().apply(ctx), "the inbound event id must not be null");
            return storages.getEngine(poolRef).select("SELECT e.inbound_event_id, e.provider, e.external_event_id,"
                    + " e.event_type, e.livemode, e.signed_time, e.received_time, e.metadata::text AS metadata, b.body FROM " + InboundEntities.TABLE
                    + " e JOIN " + InboundEntities.BODY_TABLE + " b ON b.inbound_event_id = e.inbound_event_id"
                    + " WHERE e.inbound_event_id = :id", Map.of("id", BoundValue.of(id)))
                .next()
                .switchIfEmpty(Mono.error(() -> new EntityNotFoundException("Inbound event " + id + " not found")))
                .filter(row -> metadata.provider().equals(Rows.string(row.get("provider")))
                    // The provider's type or its event type's spelling (orders/paid, orders.paid; decision D57).
                    && InboundEvents.type(metadata.provider(), metadata.eventType())
                        .equals(InboundEvents.type(metadata.provider(), Rows.string(row.get("event_type")))))
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("Inbound event " + id + " is not a "
                    + metadata.eventType() + " event of " + metadata.provider())))
                .doOnNext(row -> ctx.put(metadata.targetKey(), new ReceivedEvent(Rows.string(row.get("inbound_event_id")),
                    Rows.string(row.get("provider")), Rows.string(row.get("external_event_id")),
                    Rows.string(row.get("event_type")), (Boolean) row.get("livemode"),
                    Rows.instant(row.get("signed_time")), Rows.instant(row.get("received_time")),
                    parse(json, Rows.string(row.get("body"))), metadata(Rows.string(row.get("metadata"))))))
                .then();
        });
    }

    /** The metadata headers as stored: a JSON object of text values, or nothing. */
    private Map<String, String> metadata(String text) {
        if (text == null) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        json.readTree(text).properties().forEach(entry -> values.put(entry.getKey(), entry.getValue().asString()));
        return values;
    }

    /**
     * The body as maps, lists and plain values; every number a BigDecimal, so amounts stay exact. A number beyond
     * {@code 1e±1000} is refused, as the receiver refuses it (bodies kept before that check included).
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(JsonMapper json, String text) {
        JsonNode root = json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(text);
        if (!InboundEventExtractor.withinRange(root)) {
            throw new IllegalStateException("A number of the inbound event is out of range");
        }
        return (Map<String, Object>) value(root);
    }

    private static Object value(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.properties().forEach(entry -> map.put(entry.getKey(), value(entry.getValue())));
            return Collections.unmodifiableMap(map);
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(child -> list.add(value(child)));
            return Collections.unmodifiableList(list);
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.asString();
    }
}
