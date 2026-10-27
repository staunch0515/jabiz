package com.jabiz.runtime.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Checks an OpenID Connect ID token (docs/design/10-security.md section 12, OpenID Connect Core 3.1.3.7): RS256 or
 * ES256 signature by a key of the provider's JWKS (or, for a provider whose tokens are keyed with the client secret,
 * HS256 by that secret: LINE's web sign-in, decision D61 item 2), issuer, audience and authorized party, expiry and
 * issue time with a little clock skew, and the nonce of the sign-in it answers. Pure apart from the signature library.
 */
public final class IdTokenValidator {

    /** Clock skew tolerated between the provider and this server. */
    public static final Duration SKEW = Duration.ofSeconds(60);

    private static final Set<JWSAlgorithm> ALGORITHMS = Set.of(JWSAlgorithm.RS256, JWSAlgorithm.ES256);

    /** A token that must not be accepted. */
    public static final class InvalidIdTokenException extends RuntimeException {
        public InvalidIdTokenException(String message) {
            super(message);
        }
    }

    /** No key of the JWKS matches the token's key id: the provider may have rotated its keys. */
    public static final class UnknownKeyException extends RuntimeException {
        public UnknownKeyException(String message) {
            super(message);
        }
    }

    /** Longest display name taken from a token ({@code SecUser.displayName}). */
    static final int MAX_NAME = 200;
    /** Longest address taken from a token ({@code SecUser.email}). */
    static final int MAX_EMAIL = 320;

    /**
     * What the platform takes from an accepted token.
     *
     * @param authTime      when the user authenticated at the provider ({@code auth_time}), or null if not told
     * @param email         the address the token names ({@code email}), or null
     * @param emailVerified whether the provider says it verified the address ({@code email_verified}: a boolean, or
     *                      the text {@code "true"}, as some providers send it)
     * @param name          the person's name ({@code name}) without control characters, at most 200 characters, or
     *                      null
     * @param hostedDomain  Google's Workspace domain of the account ({@code hd}), or null
     */
    public record Identity(String subject, List<String> amr, Instant authTime, String email, boolean emailVerified,
        String name, String hostedDomain) {

        public Identity(String subject, List<String> amr, Instant authTime) {
            this(subject, amr, authTime, null, false, null, null);
        }

        /**
         * The address the provider vouches for, or null: verified by the provider, and for Google an address Google
         * itself owns (gmail) or one of a Workspace account ({@code hd}); Google verified other addresses once, when
         * the account was made, and their mailbox may belong to someone else by now. Google is told by kind or by
         * issuer ({@link OidcProvider#googleRules}) (docs/design/10-security.md
         * section 12; decision D39 implementation note).
         */
        public String trustedEmail(OidcProvider provider) {
            if (email == null || !emailVerified) {
                return null;
            }
            if (provider.googleRules()) {
                String domain = email.substring(email.lastIndexOf('@') + 1).toLowerCase(java.util.Locale.ROOT);
                boolean google = domain.equals("gmail.com") || domain.equals("googlemail.com");
                if (!google && (hostedDomain == null || hostedDomain.isBlank())) {
                    return null;
                }
            }
            return email;
        }

        @Override
        public String toString() {
            return "Identity[subject=" + subject + ", ***]";
        }
    }

    private IdTokenValidator() {}

    /** The token as signed JWT, not yet checked. */
    public static SignedJWT parse(String token) {
        try {
            return SignedJWT.parse(Objects.requireNonNull(token));
        } catch (ParseException | NullPointerException e) {
            throw new InvalidIdTokenException("Malformed ID token");
        }
    }

    /**
     * @param nonceHash hex SHA-256 of the nonce sent with the authorization request
     * @throws InvalidIdTokenException if the token must not be accepted
     * @throws UnknownKeyException     if no key of {@code keys} can have signed it
     */
    public static Identity validate(SignedJWT token, JWKSet keys, String issuer, String clientId, String nonceHash,
        Instant now) {
        return validate(token, keys, null, issuer, clientId, nonceHash, now);
    }

    /** Whether the token says it is HS256: to be checked with the client secret, not the provider's keys. */
    public static boolean hs256(SignedJWT token) {
        return JWSAlgorithm.HS256.equals(token.getHeader().getAlgorithm());
    }

    /**
     * @param hmacSecret the client secret, for a provider whose ID tokens are HS256 keyed with it
     *                   ({@link OidcProvider#acceptsHs256}); null for every other provider, whose HS256 tokens are
     *                   refused
     * @throws InvalidIdTokenException if the token must not be accepted
     * @throws UnknownKeyException     if no key of {@code keys} can have signed it
     */
    public static Identity validate(SignedJWT token, JWKSet keys, String hmacSecret, String issuer, String clientId,
        String nonceHash, Instant now) {
        JWSAlgorithm algorithm = token.getHeader().getAlgorithm();
        // Asymmetric signatures, or HS256 with the secret only this provider and the platform know: never "none",
        // never HMAC keyed with a public key.
        boolean hmac = JWSAlgorithm.HS256.equals(algorithm) && hmacSecret != null;
        if (!hmac && !ALGORITHMS.contains(algorithm)) {
            throw new InvalidIdTokenException("Unexpected ID token algorithm " + algorithm);
        }
        // Without a key id, any of the provider's keys of the algorithm may have signed it (during a rotation there
        // are two): one of them must verify it.
        boolean verified = false;
        for (JWSVerifier verifier : hmac ? macVerifier(hmacSecret) : verifiers(token, keys)) {
            try {
                verified |= token.verify(verifier);
            } catch (JOSEException e) {
                // Not this key.
            }
        }
        if (!verified) {
            throw new InvalidIdTokenException("Invalid ID token signature");
        }
        JWTClaimsSet claims;
        try {
            claims = token.getJWTClaimsSet();
        } catch (ParseException e) {
            throw new InvalidIdTokenException("Malformed ID token claims");
        }
        if (!issuer.equals(claims.getIssuer())) {
            throw new InvalidIdTokenException("ID token of another issuer");
        }
        List<String> audience = claims.getAudience();
        if (audience == null || !audience.contains(clientId)) {
            throw new InvalidIdTokenException("ID token for another client");
        }
        Object azp = claims.getClaim("azp");
        if ((audience.size() > 1 || azp != null) && !clientId.equals(azp)) {
            throw new InvalidIdTokenException("ID token authorized for another party");
        }
        Date expires = claims.getExpirationTime();
        if (expires == null || !now.minus(SKEW).isBefore(expires.toInstant())) {
            throw new InvalidIdTokenException("Expired ID token");
        }
        Date issued = claims.getIssueTime();
        if (issued == null || issued.toInstant().isAfter(now.plus(SKEW))) {
            throw new InvalidIdTokenException("ID token issued in the future");
        }
        Object nonce = claims.getClaim("nonce");
        if (!(nonce instanceof String text) || !OidcStateStore.hash(text).equals(nonceHash)) {
            throw new InvalidIdTokenException("ID token for another sign-in (nonce)");
        }
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new InvalidIdTokenException("ID token without subject");
        }
        Instant authTime = null;
        try {
            Date auth = claims.getDateClaim("auth_time");
            authTime = auth == null ? null : auth.toInstant();
        } catch (ParseException e) {
            // Unreadable: as if not told.
        }
        return new Identity(subject, amr(claims), authTime, email(claims), verified(claims.getClaim("email_verified")),
            name(claims.getClaim("name")), text(claims.getClaim("hd")));
    }

    /** The token's address when it looks like one and fits; otherwise none (nothing is opened with it). */
    private static String email(JWTClaimsSet claims) {
        String email = text(claims.getClaim("email"));
        if (email == null || email.length() > MAX_EMAIL
            || !com.jabiz.mail.MailRecipient.ADDRESS.matcher(email).matches()) {
            return null;
        }
        return email;
    }

    /** {@code email_verified}: true, or the text {@code "true"} (regardless of case); anything else is false. */
    static boolean verified(Object claim) {
        return Boolean.TRUE.equals(claim) || (claim instanceof String text && text.strip().equalsIgnoreCase("true"));
    }

    /** A name to show: without control or format characters, collapsed blanks, cut to {@link #MAX_NAME}. */
    static String name(Object claim) {
        if (!(claim instanceof String text)) {
            return null;
        }
        StringBuilder cleaned = new StringBuilder();
        text.codePoints().forEach(c -> cleaned.appendCodePoint(Character.isISOControl(c)
            || Character.getType(c) == Character.FORMAT ? ' ' : c));
        String name = cleaned.toString().strip().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            return null;
        }
        if (name.length() > MAX_NAME) {
            // Cut by UTF-16 units, as the field's length rule counts, never inside a surrogate pair.
            int end = Character.isHighSurrogate(name.charAt(MAX_NAME - 1)) ? MAX_NAME - 1 : MAX_NAME;
            name = name.substring(0, end).strip();
        }
        return name;
    }

    private static String text(Object claim) {
        return claim instanceof String text && !text.isBlank() ? text.strip() : null;
    }

    private static List<String> amr(JWTClaimsSet claims) {
        try {
            List<String> amr = claims.getStringListClaim("amr");
            return amr == null ? List.of() : amr.stream().filter(Objects::nonNull).toList();
        } catch (ParseException e) {
            return List.of();
        }
    }

    /** The secret as HS256 key; one too short to be a key (RFC 7518 section 3.2) verifies nothing. */
    private static List<JWSVerifier> macVerifier(String secret) {
        try {
            return List.of(new MACVerifier(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (JOSEException e) {
            return List.of();
        }
    }

    private static List<JWSVerifier> verifiers(SignedJWT token, JWKSet keys) {
        String keyId = token.getHeader().getKeyID();
        JWSAlgorithm algorithm = token.getHeader().getAlgorithm();
        List<JWK> candidates = keys.getKeys().stream()
            .filter(key -> keyId == null || keyId.equals(key.getKeyID()))
            .filter(key -> key.getKeyUse() == null || KeyUse.SIGNATURE.equals(key.getKeyUse()))
            .filter(key -> algorithm.equals(JWSAlgorithm.RS256) ? key instanceof RSAKey : key instanceof ECKey)
            .toList();
        if (candidates.isEmpty()) {
            throw new UnknownKeyException("No key of the provider matches key id " + keyId);
        }
        List<JWSVerifier> verifiers = new java.util.ArrayList<>();
        for (JWK key : candidates) {
            try {
                verifiers.add(key instanceof RSAKey rsa ? new RSASSAVerifier(rsa) : new ECDSAVerifier((ECKey) key));
            } catch (JOSEException e) {
                // An unusable key verifies nothing.
            }
        }
        return verifiers;
    }

    /**
     * When the user passed a second factor at the provider, or null: the token's authentication methods must include
     * one the provider's settings trust, and the token must say when ({@code auth_time}); that time, not now, is what
     * later step-ups compare with (docs/design/10-security.md section 10). Never later than {@code now}.
     */
    public static Instant secondFactorAt(Identity identity, OidcProvider provider, Instant now) {
        if (identity.authTime() == null || identity.amr().stream().noneMatch(provider.mfaAmr()::contains)) {
            return null;
        }
        return identity.authTime().isAfter(now) ? now : identity.authTime();
    }
}
