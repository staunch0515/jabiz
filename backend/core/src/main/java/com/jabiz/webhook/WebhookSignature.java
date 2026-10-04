package com.jabiz.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/**
 * The signature of a webhook delivery (docs/design/11-ledger-events-jobs.md section 2.4, decision D33):
 * {@code v1=} and the hexadecimal HMAC-SHA256, keyed by the subscription's secret, of the timestamp (seconds since the
 * epoch, as sent in {@code X-Jabiz-Timestamp}), a full stop and the body's bytes. A receiver computes the same over
 * what it received, compares in constant time ({@link #matches}) and rejects old timestamps against replays. Pure.
 */
public final class WebhookSignature {

    public static final String VERSION = "v1=";
    /** The shortest secret accepted, in characters. */
    public static final int MIN_SECRET_LENGTH = 32;

    private WebhookSignature() {
    }

    public static String sign(String secret, long timestamp, byte[] body) {
        Objects.requireNonNull(secret, "secret must not be null");
        Objects.requireNonNull(body, "body must not be null");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
            return VERSION + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    /** Whether {@code signature} is the one of this timestamp and body, compared in constant time. */
    public static boolean matches(String secret, long timestamp, byte[] body, String signature) {
        if (signature == null) {
            return false;
        }
        return MessageDigest.isEqual(sign(secret, timestamp, body).getBytes(StandardCharsets.US_ASCII),
            signature.getBytes(StandardCharsets.US_ASCII));
    }
}
