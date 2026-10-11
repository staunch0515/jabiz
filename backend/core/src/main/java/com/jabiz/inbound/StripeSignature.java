package com.jabiz.inbound;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Stripe's webhook signature (decision D37 item 1): the header {@value #HEADER} is {@code t=<seconds>} and one or more
 * {@code v1=<hex>}, comma separated, each {@code v1} the HMAC-SHA256, keyed by the endpoint's signing secret, of
 * {@code t}, a full stop and the body's bytes. Any {@code v1} matching any configured secret passes, so a secret can be
 * rotated while both are configured; {@code v0} (Stripe's test scheme) and unknown schemes are ignored. A missing,
 * repeated or non-numeric {@code t} is malformed. Comparisons are in constant time.
 */
public final class StripeSignature implements InboundSignatureScheme {

    public static final String NAME = "stripe";
    public static final String HEADER = "Stripe-Signature";
    /** Most comma-separated entries one header may have: bounds the work a forged header can cause. */
    static final int MAX_ENTRIES = 16;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public InboundVerification verify(InboundHeaders headers, byte[] body, List<String> secrets, Instant now,
        Duration tolerance) {
        Objects.requireNonNull(body, "body must not be null");
        List<String> values = headers.values(HEADER);
        if (values.isEmpty()) {
            return InboundVerification.rejected(InboundVerification.Reason.MISSING_HEADER);
        }
        if (values.size() > 1) {
            return InboundVerification.rejected(InboundVerification.Reason.MALFORMED);
        }
        String[] entries = values.getFirst().split(",", -1);
        if (entries.length > MAX_ENTRIES) {
            return InboundVerification.rejected(InboundVerification.Reason.MALFORMED);
        }
        String timestamp = null;
        List<byte[]> candidates = new ArrayList<>();
        for (String raw : entries) {
            String entry = raw.trim();
            int equals = entry.indexOf('=');
            if (equals <= 0) {
                return InboundVerification.rejected(InboundVerification.Reason.MALFORMED);
            }
            String key = entry.substring(0, equals).trim();
            String value = entry.substring(equals + 1).trim();
            if (key.equals("t")) {
                if (timestamp != null) {
                    return InboundVerification.rejected(InboundVerification.Reason.MALFORMED);
                }
                timestamp = value;
            } else if (key.equals("v1")) {
                candidates.add(value.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
            }
        }
        long signedAt = InboundSignatureScheme.seconds(timestamp);
        if (signedAt < 0) {
            return InboundVerification.rejected(InboundVerification.Reason.MALFORMED);
        }
        boolean matched = false;
        for (String secret : secrets) {
            byte[] expected = hex(secret, signedAt, body);
            for (byte[] candidate : candidates) {
                // Every pair is compared, each in constant time: how many matched or where tells nothing.
                matched |= MessageDigest.isEqual(expected, candidate);
            }
        }
        if (!matched) {
            return InboundVerification.rejected(InboundVerification.Reason.NO_MATCH);
        }
        return InboundSignatureScheme.inTime(signedAt, now, tolerance);
    }

    @Override
    public Map<String, String> sign(String secret, long timestamp, byte[] body) {
        return Map.of(HEADER, "t=" + timestamp + ",v1="
            + new String(hex(secret, timestamp, body), StandardCharsets.US_ASCII));
    }

    /** The lower-case hexadecimal signature as ASCII bytes. */
    private static byte[] hex(String secret, long timestamp, byte[] body) {
        Objects.requireNonNull(secret, "secret must not be null");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(mac.doFinal(body)).getBytes(StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
