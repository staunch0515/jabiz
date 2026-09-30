package com.jabiz.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Time-based one-time passwords (RFC 6238 with the defaults every authenticator app uses: HMAC-SHA1, 6 digits, 30
 * second steps), see docs/design/10-security.md section 9. Pure: the caller passes the time, which comes from the
 * injected clock.
 *
 * <p>A code is accepted for the current step and one step either side, to allow for clock drift between the phone and
 * the server. The caller keeps the last step it accepted and passes it back, so that a code (or an earlier one) cannot
 * be used twice.
 */
public final class Totp {

    /** Length of the shared secret: 160 bits, as RFC 4226 recommends for HMAC-SHA1. */
    public static final int SECRET_BYTES = 20;
    public static final int DIGITS = 6;
    public static final long STEP_SECONDS = 30;
    /** Steps accepted on either side of the current one. */
    public static final int DRIFT_STEPS = 1;

    private static final int[] POWERS = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000};

    private Totp() {}

    /** The time step an instant falls in. */
    public static long step(Instant time) {
        return Math.floorDiv(time.getEpochSecond(), STEP_SECONDS);
    }

    /** The code of a step (zero-padded). */
    public static String code(byte[] secret, long step) {
        return code(secret, step, DIGITS);
    }

    static String code(byte[] secret, long step, int digits) {
        Objects.requireNonNull(secret, "secret must not be null");
        byte[] hash = hmacSha1(secret, ByteBuffer.allocate(8).putLong(step).array());
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
            | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
        String code = Integer.toString(binary % POWERS[digits]);
        return "0".repeat(digits - code.length()) + code;
    }

    /**
     * The step a code matches at {@code now}, if it is valid and later than {@code lastUsedStep}.
     *
     * @param lastUsedStep the last step accepted before for this secret, or a negative number for none
     */
    public static OptionalLong verify(byte[] secret, String code, Instant now, long lastUsedStep) {
        if (code == null) {
            return OptionalLong.empty();
        }
        String submitted = code.replace(" ", "");
        if (submitted.length() != DIGITS || !submitted.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return OptionalLong.empty();
        }
        long current = step(now);
        for (long step = current - DRIFT_STEPS; step <= current + DRIFT_STEPS; step++) {
            // Constant-time comparison; every candidate step is computed whatever matched.
            boolean matches = MessageDigest.isEqual(code(secret, step).getBytes(StandardCharsets.US_ASCII),
                submitted.getBytes(StandardCharsets.US_ASCII));
            if (matches && step > lastUsedStep) {
                return OptionalLong.of(step);
            }
        }
        return OptionalLong.empty();
    }

    /**
     * The {@code otpauth://} address authenticator apps read (as a QR code or typed in): the account shows as
     * "issuer: account".
     */
    public static String uri(String issuer, String account, byte[] secret) {
        String label = encode(issuer) + ":" + encode(account);
        return "otpauth://totp/" + label + "?secret=" + Base32.encode(secret) + "&issuer=" + encode(issuer)
            + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static byte[] hmacSha1(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 is not available", e);
        }
    }
}
