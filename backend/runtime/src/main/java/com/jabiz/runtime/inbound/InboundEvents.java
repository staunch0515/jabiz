package com.jabiz.runtime.inbound;

import com.jabiz.event.DomainEvent;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.Objects;

/**
 * The outbox events of inbound webhooks (decision D37 item 2): {@code jabiz.inbound.<provider>.<type>}, whose payload
 * only refers to the received event ({@link InboundEventRef}); the body stays in {@code sys_inbound_event}, guarded by
 * {@code inbound.read}, and a consumer's process loads it with {@code LoadInboundEvent}:
 * <pre>{@code
 * EventSubscription.of("shop.payment-received", InboundEvents.type("pay", "payment_intent.succeeded"), process,
 *     event -> new Input(InboundEvents.ref(event).inboundEventId()))
 * }</pre>
 */
public final class InboundEvents {

    public static final String PREFIX = "jabiz.inbound.";

    private InboundEvents() {}

    /**
     * The outbox event type of a provider's event type. A {@code /} of the provider's type ({@code orders/create})
     * becomes a {@code .}, which an event type may hold (decision D57); a provider listing two types that meet so is a
     * startup error.
     */
    public static String type(String provider, String externalType) {
        return PREFIX + provider + "." + externalType.replace('/', '.');
    }

    /**
     * What an outbox event of an inbound webhook says: which received event it is.
     *
     * @param inboundEventId  the id of the row in {@code sys_inbound_event} (entity {@code InboundEvent})
     * @param externalEventId the provider's id of the event, unique per provider and event type (decision D57: one
     *                        action of a sender may give several types the same id, so keys of the application's own,
     *                        such as a task's source, name the type too)
     * @param eventType       the provider's type of the event, as the provider names it
     * @param metadata        the provider's configured metadata headers as received: not signed (decision D57)
     */
    public record InboundEventRef(String inboundEventId, String provider, String externalEventId, String eventType,
        Instant receivedTime, Map<String, String> metadata) {
        public InboundEventRef {
            Objects.requireNonNull(inboundEventId, "inboundEventId must not be null");
            metadata = InboundEvents.headerMap(metadata);
        }

        public InboundEventRef(String inboundEventId, String provider, String externalEventId, String eventType,
                               Instant receivedTime) {
            this(inboundEventId, provider, externalEventId, eventType, receivedTime, Map.of());
        }
    }

    /** The reference an outbox event of an inbound webhook carries. */
    public static InboundEventRef ref(DomainEvent event) {
        Map<String, Object> payload = event.payload();
        if (!event.eventType().startsWith(PREFIX) || payload.get("inboundEventId") == null) {
            throw new IllegalArgumentException("Event " + event.eventId() + " (" + event.eventType()
                + ") is not an inbound webhook event");
        }
        Object received = payload.get("receivedTime");
        Map<String, String> metadata = new LinkedHashMap<>();
        if (payload.get("metadata") instanceof Map<?, ?> values) {
            values.forEach((name, value) -> metadata.put(String.valueOf(name), text(value)));
        }
        return new InboundEventRef(String.valueOf(payload.get("inboundEventId")), text(payload.get("provider")),
            text(payload.get("externalEventId")), text(payload.get("eventType")),
            received == null ? null : Instant.parse(received.toString()), metadata);
    }

    /** Header values by name, the names in any case (as HTTP has them); unmodifiable. */
    static Map<String, String> headerMap(Map<String, String> values) {
        Map<String, String> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (values != null) {
            map.putAll(values);
        }
        return Collections.unmodifiableMap(map);
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
