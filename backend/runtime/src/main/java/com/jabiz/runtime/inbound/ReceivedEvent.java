package com.jabiz.runtime.inbound;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A received inbound webhook event as {@link LoadInboundEvent} gives it to a process: what the platform recorded and
 * the body parsed as JSON (objects as maps, arrays as lists, numbers as {@link java.math.BigDecimal}, so amounts stay
 * exact). {@link #toString()} leaves the body out, since it may hold personal data.
 */
public record ReceivedEvent(String inboundEventId, String provider, String externalEventId, String eventType,
    Boolean livemode, Instant signedTime, Instant receivedTime, Map<String, Object> body) {

    public ReceivedEvent {
        Objects.requireNonNull(inboundEventId, "inboundEventId must not be null");
        body = body == null ? Map.of() : body;
    }

    /**
     * The value at a dotted path of the body ({@code data.object.metadata.orderNo}); null when any part is missing or
     * not an object.
     */
    public Object at(String path) {
        Object current = body;
        for (String part : path.split("\\.", -1)) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
        }
        return current;
    }

    @Override
    public String toString() {
        return "ReceivedEvent[inboundEventId=" + inboundEventId + ", provider=" + provider + ", externalEventId="
            + externalEventId + ", eventType=" + eventType + ", livemode=" + livemode + ", signedTime=" + signedTime
            + ", receivedTime=" + receivedTime + ", body=***]";
    }
}
