package com.jabiz.runtime.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
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
 * ES256 signature by a key of the provider's JWKS, issuer, audience and authorized party, expiry and issue time with a
 * little clock skew, and the nonce of the sign-in it answers. Pure apart from the signature library.
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

    /**
     * What the platform takes from an accepted token.
     *
     * @param authTime when the user authenticated at the provider ({@code auth_time}), or null if not told
     */
    public record Identity(String subject, List<String> amr, Instant authTime) {}

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
        JWSAlgorithm algorithm = token.getHeader().getAlgorithm();
        // Only asymmetric signatures: never "none", never HMAC keyed with a public key.
        if (!ALGORITHMS.contains(algorithm)) {
            throw new InvalidIdTokenException("Unexpected ID token algorithm " + algorithm);
        }
        // Without a key id, any of the provider's keys of the algorithm may have signed it (during a rotation there
        // are two): one of them must verify it.
        boolean verified = false;
        for (JWSVerifier verifier : verifiers(token, keys)) {
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
        return new Identity(subject, amr(claims), authTime);
    }

    private static List<String> amr(JWTClaimsSet claims) {
        try {
            List<String> amr = claims.getStringListClaim("amr");
            return amr == null ? List.of() : amr.stream().filter(Objects::nonNull).toList();
        } catch (ParseException e) {
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
