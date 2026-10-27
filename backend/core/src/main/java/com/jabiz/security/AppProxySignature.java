package com.jabiz.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The signature of a request an app proxy forwards (decision D60): Shopify's scheme. The other platform adds
 * {@value #TIMESTAMP} (seconds) and {@value #SIGNATURE} to the query and signs every other query parameter, the
 * visitor's own included: sorted by name, each written {@code name=value} (several values joined by ','), all written
 * together without separators, HMAC-SHA256 with the app's secret, in lower-case hex. The body is not signed. Pure;
 * compared in constant time, every secret tried (one being replaced).
 */
public final class AppProxySignature {

    public static final String SIGNATURE = "signature";
    public static final String TIMESTAMP = "timestamp";
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SECONDS = Pattern.compile("[0-9]{1,12}");

    /** What the check found. Callers answer every failure alike. */
    public enum Result { VALID, MISSING, MALFORMED, NO_MATCH, STALE }

    private AppProxySignature() {}

    /**
     * Whether {@code query} is signed with one of {@code secrets} and its time is within {@code maxAge} of {@code now}
     * either way.
     */
    public static Result verify(Map<String, List<String>> query, List<String> secrets, Instant now,
        Duration maxAge) {
        List<String> signatures = query.get(SIGNATURE);
        List<String> timestamps = query.get(TIMESTAMP);
        if (signatures == null || signatures.isEmpty() || timestamps == null || timestamps.isEmpty()) {
            return Result.MISSING;
        }
        if (signatures.size() != 1 || timestamps.size() != 1 || !HEX.matcher(signatures.getFirst()).matches()
            || !SECONDS.matcher(timestamps.getFirst()).matches()) {
            return Result.MALFORMED;
        }
        byte[] given = HexFormat.of().parseHex(signatures.getFirst());
        byte[] message = message(query).getBytes(StandardCharsets.UTF_8);
        boolean matched = false;
        for (String secret : secrets) {
            // Every secret is tried, so the time taken does not say which matched.
            matched |= secret != null && MessageDigest.isEqual(mac(secret, message), given);
        }
        if (!matched) {
            return Result.NO_MATCH;
        }
        Instant signedAt = Instant.ofEpochSecond(Long.parseLong(timestamps.getFirst()));
        return Duration.between(signedAt, now).abs().compareTo(maxAge) > 0 ? Result.STALE : Result.VALID;
    }

    /** The signature of {@code query} (its {@value #SIGNATURE} left out) with {@code secret}: as the platform makes it. */
    public static String sign(Map<String, List<String>> query, String secret) {
        return HexFormat.of().formatHex(mac(secret, message(query).getBytes(StandardCharsets.UTF_8)));
    }

    /** What is signed: every parameter but the signature, sorted by name, {@code name=v1,v2}, joined without separator. */
    static String message(Map<String, List<String>> query) {
        StringBuilder text = new StringBuilder();
        new TreeMap<>(query).forEach((name, values) -> {
            if (!SIGNATURE.equals(name)) {
                text.append(name).append('=').append(String.join(",", values));
            }
        });
        return text.toString();
    }

    static byte[] mac(String secret, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(message);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
