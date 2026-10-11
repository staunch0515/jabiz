package com.jabiz.runtime.inbound;

import com.jabiz.event.DomainEvent;

import java.time.Instant;
import java.util.Map;
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

    /** The outbox event type of a provider's event type. */
    public static String type(String provider, String externalType) {
        return PREFIX + provider + "." + externalType;
    }

    /**
     * What an outbox event of an inbound webhook says: which received event it is.
     *
     * @param inboundEventId  the id of the row in {@code sys_inbound_event} (entity {@code InboundEvent})
     * @param externalEventId the provider's id of the event, unique per provider
     * @param eventType       the provider's type of the event
     */
    public record InboundEventRef(String inboundEventId, String provider, String externalEventId, String eventType,
        Instant receivedTime) {
        public InboundEventRef {
            Objects.requireNonNull(inboundEventId, "inboundEventId must not be null");
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
        return new InboundEventRef(String.valueOf(payload.get("inboundEventId")), text(payload.get("provider")),
            text(payload.get("externalEventId")), text(payload.get("eventType")),
            received == null ? null : Instant.parse(received.toString()));
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
