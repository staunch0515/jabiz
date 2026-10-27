package com.jabiz.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rules of a token another platform signs for the person it embeds an application for (decision D59; a Shopify
 * session token): an HS256 JWT signed with the application's secret, whose issuer and destination both name one
 * tenant (a shop), for one audience (the application), valid for a minute or so. Pure: the caller parses the JWT's
 * JSON and gives the header and claims as maps (numbers as {@link Number}), with the signing input and signature as
 * the token carried them.
 *
 * <p>Checked in this order, the first failure answering: the algorithm (HS256 only: the secret is shared, nothing else
 * may be accepted), the signature (each configured secret, in constant time), the times ({@code exp} and {@code nbf}
 * with the skew; {@code exp} required), the audience, issuer and destination naming the same tenant of the allowed
 * form, a subject and a token id.
 */
public final class ExternalTokenRules {

    /** The placeholder of the tenant in an issuer or destination form. */
    public static final String TENANT = "{tenant}";
    /** The longest skew allowed between the platforms' clocks. */
    public static final Duration MAX_SKEW = Duration.ofSeconds(60);
    /** The longest subject and token id accepted (they are stored). */
    public static final int MAX_ID_LENGTH = 255;
    private static final Pattern ID = Pattern.compile("[\\x21-\\x7E]{1," + MAX_ID_LENGTH + "}");
    private static final int MIN_SECRET_BYTES = 32;

    /** Why a token was refused; never told to the caller, only logged and tagged. */
    public enum Failure {
        MALFORMED, ALGORITHM, SIGNATURE, EXPIRED, NOT_YET_VALID, AUDIENCE, ISSUER, DESTINATION, SUBJECT, TOKEN_ID,
        /** Its token id was used before (a sign-in uses each token once). */
        REPLAYED
    }

    /** A refused token. */
    public static final class RefusedException extends RuntimeException {
        private final Failure failure;

        public RefusedException(Failure failure) {
            super("External token refused: " + failure.name().toLowerCase(java.util.Locale.ROOT));
            this.failure = failure;
        }

        public Failure failure() {
            return failure;
        }
    }

    /**
     * What a provider's tokens must be.
     *
     * @param audience          the {@code aud} the token must carry (the application's key at the platform)
     * @param tenant            the form of a tenant, matched whole ({@code [a-z0-9][a-z0-9-]*\.myshopify\.com})
     * @param issuerFormat      the issuer with {@value #TENANT} once ({@code https://{tenant}/admin})
     * @param destinationFormat the destination with {@value #TENANT} once ({@code https://{tenant}})
     * @param skew              allowed difference of the clocks, at most {@link #MAX_SKEW}
     */
    public record Settings(String audience, Pattern tenant, String issuerFormat, String destinationFormat,
        Duration skew) {

        public Settings {
            Objects.requireNonNull(audience, "audience must not be null");
            Objects.requireNonNull(tenant, "tenant must not be null");
            requireForm(issuerFormat, "issuerFormat");
            requireForm(destinationFormat, "destinationFormat");
            skew = skew == null ? Duration.ofSeconds(10) : skew;
            if (skew.isNegative() || skew.compareTo(MAX_SKEW) > 0) {
                throw new IllegalArgumentException("skew must be between 0 and " + MAX_SKEW);
            }
        }

        private static void requireForm(String format, String what) {
            Objects.requireNonNull(format, what + " must not be null");
            int at = format.indexOf(TENANT);
            if (at < 0 || format.indexOf(TENANT, at + 1) >= 0) {
                throw new IllegalArgumentException(what + " must hold " + TENANT + " exactly once");
            }
        }

        /** The tenant a value of the form names; null when it is not of the form or the tenant not allowed. */
        String tenantOf(String format, Object value) {
            if (!(value instanceof String text)) {
                return null;
            }
            int at = format.indexOf(TENANT);
            String prefix = format.substring(0, at);
            String suffix = format.substring(at + TENANT.length());
            if (!text.startsWith(prefix) || !text.endsWith(suffix) || text.length() <= prefix.length() + suffix.length()) {
                return null;
            }
            String candidate = text.substring(prefix.length(), text.length() - suffix.length());
            Matcher matcher = tenant.matcher(candidate);
            return matcher.matches() ? candidate : null;
        }
    }

    /**
     * A verified token.
     *
     * @param tenant    the tenant both issuer and destination name
     * @param subject   the person ({@code sub}), within the tenant
     * @param tokenId   {@code jti}, to use a token once where that matters
     * @param expiresAt {@code exp}
     */
    public record Verified(String tenant, String subject, String tokenId, Instant expiresAt) {}

    private ExternalTokenRules() {}

    /**
     * Verifies a parsed token.
     *
     * @param signingInput the token's first two parts with their dot, as carried
     * @param signature    the third part, decoded
     * @param secrets      the provider's secrets (one being replaced: any may have signed), each at least 32 bytes
     * @throws RefusedException naming the first rule it breaks
     */
    public static Verified verify(Map<String, ?> header, Map<String, ?> claims, String signingInput, byte[] signature,
        List<byte[]> secrets, Settings settings, Instant now) {
        Objects.requireNonNull(settings, "settings must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (header == null || claims == null || signingInput == null || signature == null) {
            throw new RefusedException(Failure.MALFORMED);
        }
        if (!"HS256".equals(header.get("alg")) || (header.get("typ") != null && !"JWT".equals(header.get("typ")))
            || header.containsKey("crit")) {
            throw new RefusedException(Failure.ALGORITHM);
        }
        byte[] input = signingInput.getBytes(StandardCharsets.US_ASCII);
        boolean matched = false;
        for (byte[] secret : secrets) {
            if (secret == null || secret.length < MIN_SECRET_BYTES) {
                throw new IllegalArgumentException("A secret of at least " + MIN_SECRET_BYTES + " bytes is required");
            }
            // Every secret is tried, so the time taken does not say which matched.
            matched |= MessageDigest.isEqual(hmac(secret, input), signature);
        }
        if (!matched) {
            throw new RefusedException(Failure.SIGNATURE);
        }
        Instant expires = seconds(claims.get("exp"));
        if (expires == null) {
            throw new RefusedException(Failure.MALFORMED);
        }
        if (!now.isBefore(expires.plus(settings.skew()))) {
            throw new RefusedException(Failure.EXPIRED);
        }
        if (claims.containsKey("nbf")) {
            Instant notBefore = seconds(claims.get("nbf"));
            if (notBefore == null) {
                throw new RefusedException(Failure.MALFORMED);
            }
            if (now.plus(settings.skew()).isBefore(notBefore)) {
                throw new RefusedException(Failure.NOT_YET_VALID);
            }
        }
        if (!audienceMatches(claims.get("aud"), settings.audience())) {
            throw new RefusedException(Failure.AUDIENCE);
        }
        String issuer = settings.tenantOf(settings.issuerFormat(), claims.get("iss"));
        if (issuer == null) {
            throw new RefusedException(Failure.ISSUER);
        }
        String destination = settings.tenantOf(settings.destinationFormat(), claims.get("dest"));
        if (destination == null || !destination.equals(issuer)) {
            throw new RefusedException(Failure.DESTINATION);
        }
        String subject = id(claims.get("sub"));
        if (subject == null) {
            throw new RefusedException(Failure.SUBJECT);
        }
        String tokenId = id(claims.get("jti"));
        if (tokenId == null) {
            throw new RefusedException(Failure.TOKEN_ID);
        }
        return new Verified(issuer, subject, tokenId, expires);
    }

    /** Whether a value names a tenant of the settings' form (an embedding request's tenant parameter). */
    public static boolean isTenant(Settings settings, String value) {
        return value != null && settings.tenant().matcher(value).matches();
    }

    /** HMAC-SHA256 of the input, for signing tokens in tests and fixtures. */
    public static byte[] hmac(byte[] secret, byte[] input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(input);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    private static boolean audienceMatches(Object aud, String audience) {
        if (aud instanceof String text) {
            return text.equals(audience);
        }
        if (aud instanceof List<?> list) {
            return list.contains(audience);
        }
        return false;
    }

    private static String id(Object value) {
        return value instanceof String text && ID.matcher(text).matches() ? text : null;
    }

    /** The latest time a token may name (the end of 9999 UTC): beyond it no clock is, and arithmetic would overflow. */
    public static final long MAX_TIME = 253_402_300_799L;

    /**
     * Seconds since the epoch as JSON gives them, from 0 to {@link #MAX_TIME}; null for anything else, fractions
     * included.
     */
    private static Instant seconds(Object value) {
        long seconds;
        if (value instanceof Integer || value instanceof Long) {
            seconds = ((Number) value).longValue();
        } else if (value instanceof BigDecimal decimal) {
            try {
                seconds = decimal.longValueExact();
            } catch (ArithmeticException e) {
                return null;
            }
        } else {
            return null;
        }
        return seconds < 0 || seconds > MAX_TIME ? null : Instant.ofEpochSecond(seconds);
    }
}
