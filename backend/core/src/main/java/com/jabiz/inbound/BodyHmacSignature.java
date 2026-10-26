package com.jabiz.inbound;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The scheme of senders that sign the body only (decision D57): one header carries HMAC-SHA256(secret, body as
 * received), Base64- or hex-encoded. Nothing else is signed: not a time, not the event's id or type, which such
 * senders put in headers of their own. So this scheme accepts {@link InboundVerification#withoutSignedTime()} and
 * {@link #signsTime()} is false; the receiver stops replays by other means.
 *
 * <p>The defaults are those of Shopify's webhooks ({@value #DEFAULT_HEADER}, Base64); a provider may name another
 * header and encoding. The header must be there exactly once and decode cleanly; any configured secret may have
 * signed (one being replaced). Compared in constant time ({@link MessageDigest#isEqual}).
 */
public final class BodyHmacSignature implements InboundSignatureScheme {

    public static final String NAME = "body-hmac-sha256";
    public static final String DEFAULT_HEADER = "X-Shopify-Hmac-Sha256";
    /** A header name as the configuration may give it: letters, digits and '-'. */
    public static final Pattern HEADER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,63}");
    private static final int SHA256_BYTES = 32;

    /** How the signature is written in its header. */
    public enum Encoding {
        BASE64, HEX;

        /** The encoding of a configured name ({@code base64}, {@code hex}); null for anything else. */
        public static Encoding of(String name) {
            if (name == null) {
                return null;
            }
            return switch (name.toLowerCase(Locale.ROOT)) {
                case "base64" -> BASE64;
                case "hex" -> HEX;
                default -> null;
            };
        }
    }

    /** The defaults: {@value #DEFAULT_HEADER}, Base64. */
    public static final BodyHmacSignature DEFAULT = new BodyHmacSignature(DEFAULT_HEADER, Encoding.BASE64);

    private final String header;
    private final Encoding encoding;

    private BodyHmacSignature(String header, Encoding encoding) {
        this.header = Objects.requireNonNull(header, "header must not be null");
        this.encoding = Objects.requireNonNull(encoding, "encoding must not be null");
        if (!HEADER.matcher(header).matches()) {
            throw new IllegalArgumentException("Not a header name: " + header);
        }
    }

    /** The scheme with this header and encoding; the defaults for null. */
    public static BodyHmacSignature of(String header, Encoding encoding) {
        return new BodyHmacSignature(header == null ? DEFAULT_HEADER : header,
            encoding == null ? Encoding.BASE64 : encoding);
    }

    public String header() {
        return header;
    }

    public Encoding encoding() {
        return encoding;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean signsTime() {
        return false;
    }

    /** {@code now} and {@code tolerance} play no part: no time is signed. */
    @Override
    public InboundVerification verify(InboundHeaders headers, byte[] body, List<String> secrets, Instant now,
        Duration tolerance) {
        List<String> values = headers.values(header);
        if (values.isEmpty()) {
            return InboundVerification.rejected(InboundVerification.Reason.MISSING_HEADER);
        }
        if (values.size() > 1) {
            return InboundVerification.rejected(InboundVerification.Reason.MALFORMED);
        }
        byte[] given = decode(values.getFirst().trim());
        if (given == null || given.length != SHA256_BYTES) {
            return InboundVerification.rejected(InboundVerification.Reason.MALFORMED);
        }
        boolean matched = false;
        for (String secret : secrets) {
            // Every secret is tried, so the time taken does not say which matched.
            matched |= secret != null && MessageDigest.isEqual(mac(secret, body), given);
        }
        return matched ? InboundVerification.withoutSignedTime()
            : InboundVerification.rejected(InboundVerification.Reason.NO_MATCH);
    }

    /** {@code timestamp} plays no part: no time is signed. */
    @Override
    public Map<String, String> sign(String secret, long timestamp, byte[] body) {
        byte[] mac = mac(secret, body);
        return Map.of(header, encoding == Encoding.BASE64 ? Base64.getEncoder().encodeToString(mac)
            : HexFormat.of().formatHex(mac));
    }

    /**
     * The signature's bytes; null when it is not cleanly in the encoding. Base64 must be canonical, padded and without
     * stray bits (the decoder alone would take either), so that one signature has one spelling.
     */
    private byte[] decode(String text) {
        try {
            if (encoding == Encoding.HEX) {
                return HexFormat.of().parseHex(text.toLowerCase(Locale.ROOT));
            }
            byte[] bytes = Base64.getDecoder().decode(text);
            return Base64.getEncoder().encodeToString(bytes).equals(text) ? bytes : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static byte[] mac(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(body);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    @Override
    public String toString() {
        return "BodyHmacSignature[header=" + header + ", encoding=" + encoding + "]";
    }
}
