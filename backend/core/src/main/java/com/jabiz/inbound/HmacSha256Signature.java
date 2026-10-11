package com.jabiz.inbound;

import com.jabiz.webhook.WebhookSignature;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The generic scheme, the format of the platform's own outbound webhooks (decision D33): {@value #TIMESTAMP} in seconds
 * and {@value #SIGNATURE} {@code v1=<hex HMAC-SHA256(secret, timestamp + "." + body)>} ({@link WebhookSignature}), so
 * that one jabiz instance can receive another's webhooks. Each header must be there exactly once; any configured
 * secret may have signed. Compared in constant time.
 */
public final class HmacSha256Signature implements InboundSignatureScheme {

    public static final String NAME = "hmac-sha256";
    public static final String TIMESTAMP = "X-Jabiz-Timestamp";
    public static final String SIGNATURE = "X-Jabiz-Signature";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public InboundVerification verify(InboundHeaders headers, byte[] body, List<String> secrets, Instant now,
        Duration tolerance) {
        List<String> timestamps = headers.values(TIMESTAMP);
        List<String> signatures = headers.values(SIGNATURE);
        if (timestamps.isEmpty() || signatures.isEmpty()) {
            return InboundVerification.rejected(InboundVerification.Reason.MISSING_HEADER);
        }
        if (timestamps.size() > 1 || signatures.size() > 1) {
            return InboundVerification.rejected(InboundVerification.Reason.MALFORMED);
        }
        long signedAt = InboundSignatureScheme.seconds(timestamps.getFirst().trim());
        if (signedAt < 0) {
            return InboundVerification.rejected(InboundVerification.Reason.MALFORMED);
        }
        if (!WebhookSignature.matchesAny(secrets, signedAt, body, signatures.getFirst().trim())) {
            return InboundVerification.rejected(InboundVerification.Reason.NO_MATCH);
        }
        return InboundSignatureScheme.inTime(signedAt, now, tolerance);
    }

    @Override
    public Map<String, String> sign(String secret, long timestamp, byte[] body) {
        return Map.of(TIMESTAMP, Long.toString(timestamp), SIGNATURE, WebhookSignature.sign(secret, timestamp, body));
    }
}
