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
 * .step("Load the payment", LoadInboundEvent.of(ctx -> ctx.get(INPUT, Input.class).inboundEventId(), "payment"))
 * }</pre>
 * The body is personal data guarded by {@code inbound.read}: the step loads it for the event consumers (the system)
 * and for actors holding that permission, and refuses anyone else (403). An unknown id fails the process (404).
 */
@Component
public class LoadInboundEvent<C extends ProcessContext> implements StepHandler<LoadInboundEvent.Metadata<C>, C> {

    /**
     * @param id        the id of the received event, from the context
     * @param targetKey the context key receiving the {@link ReceivedEvent}
     */
    public record Metadata<C>(Function<C, String> id, String targetKey) {
        public Metadata {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(targetKey, "targetKey must not be null");
        }
    }

    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> of(Function<C, String> id, String targetKey) {
        return StepSpec.of(LoadInboundEvent.class, new Metadata<>(id, targetKey));
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
            return storages.getEngine(poolRef).select("SELECT inbound_event_id, provider, external_event_id,"
                    + " event_type, livemode, signed_time, received_time, body FROM " + InboundEntities.TABLE
                    + " WHERE inbound_event_id = :id", Map.of("id", BoundValue.of(id)))
                .next()
                .switchIfEmpty(Mono.error(() -> new EntityNotFoundException("Inbound event " + id + " not found")))
                .doOnNext(row -> ctx.put(metadata.targetKey(), new ReceivedEvent(Rows.string(row.get("inbound_event_id")),
                    Rows.string(row.get("provider")), Rows.string(row.get("external_event_id")),
                    Rows.string(row.get("event_type")), (Boolean) row.get("livemode"),
                    Rows.instant(row.get("signed_time")), Rows.instant(row.get("received_time")),
                    body(json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                        .readTree(Rows.string(row.get("body")))))))
                .then();
        });
    }

    /** The body as maps, lists and plain values; every number a BigDecimal, so amounts stay exact. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> body(JsonNode root) {
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
