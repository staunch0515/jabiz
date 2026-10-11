package com.jabiz.runtime.inbound;

import com.jabiz.inbound.HmacSha256Signature;
import com.jabiz.inbound.InboundHeaders;
import com.jabiz.inbound.StripeSignature;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Names the event of a verified inbound request (decision D37): its external id and type, taken from the signed body
 * only, after the signature was verified. The body must be strict UTF-8 (stored as text, it then loses nothing) and one
 * JSON object, without repeated keys or anything after it.
 * <ul>
 *   <li>{@code stripe}: the top-level {@code id} and {@code type}, and {@code livemode} when present.</li>
 *   <li>{@code hmac-sha256}: the top-level {@code eventId} and {@code eventType} of the outbound format (decision D33).
 *       Its headers {@code X-Jabiz-Event-Id} and {@code X-Jabiz-Event-Type} are not covered by the signature: when
 *       sent, they must say the same, or the request is rejected.</li>
 * </ul>
 * An event id is 1 to {@value #MAX_ID} visible ASCII characters.
 */
final class InboundEventExtractor {

    static final int MAX_ID = 255;
    private static final Pattern ID = Pattern.compile("[\\x21-\\x7E]{1," + MAX_ID + "}");

    private static final JsonMapper STRICT = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();

    /**
     * @param livemode Stripe's live or test mode; null when the event does not say
     * @param text     the body as text, exactly its bytes
     */
    record Extracted(String externalEventId, String eventType, Boolean livemode, String text) {
        @Override
        public String toString() {
            return "Extracted[externalEventId=" + externalEventId + ", eventType=" + eventType + ", livemode="
                + livemode + ", text=(" + text.length() + " characters)]";
        }
    }

    private InboundEventExtractor() {
    }

    /** @throws InboundRejectedException when the body does not name an event as the scheme requires */
    static Extracted extract(String scheme, InboundHeaders headers, byte[] body) {
        String idField;
        String typeField;
        switch (scheme) {
            case StripeSignature.NAME -> {
                idField = "id";
                typeField = "type";
            }
            case HmacSha256Signature.NAME -> {
                idField = "eventId";
                typeField = "eventType";
            }
            default -> throw new IllegalArgumentException("Unknown signature scheme " + scheme);
        }
        String text = utf8(body);
        JsonNode root;
        try {
            root = STRICT.readTree(text);
        } catch (JacksonException e) {
            throw new InboundRejectedException("not_json");
        }
        if (root == null || !root.isObject()) {
            throw new InboundRejectedException("not_json");
        }
        JsonNode id = root.get(idField);
        if (id == null || !id.isString() || !ID.matcher(id.asString()).matches()) {
            throw new InboundRejectedException("no_event_id");
        }
        JsonNode type = root.get(typeField);
        if (type == null || !type.isString() || type.asString().isBlank()) {
            throw new InboundRejectedException("no_event_type");
        }
        Boolean livemode = null;
        if (scheme.equals(StripeSignature.NAME) && root.has("livemode") && !root.get("livemode").isNull()) {
            if (!root.get("livemode").isBoolean()) {
                throw new InboundRejectedException("not_json");
            }
            livemode = root.get("livemode").asBoolean();
        }
        if (scheme.equals(HmacSha256Signature.NAME)) {
            agrees(headers.values("X-Jabiz-Event-Id"), id.asString());
            agrees(headers.values("X-Jabiz-Event-Type"), type.asString());
        }
        return new Extracted(id.asString(), type.asString(), livemode, text);
    }

    private static void agrees(List<String> header, String signed) {
        if (!header.isEmpty() && (header.size() > 1 || !header.getFirst().trim().equals(signed))) {
            throw new InboundRejectedException("header_mismatch");
        }
    }

    /** The body as text; anything but well-formed UTF-8 is rejected rather than replaced. */
    static String utf8(byte[] body) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString();
        } catch (CharacterCodingException e) {
            throw new InboundRejectedException("not_utf8");
        }
    }
}
