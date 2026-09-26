package com.jabiz.runtime.security;

import com.jabiz.runtime.context.Actor;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Issues and verifies the short-lived access tokens (decision D12): HS256-signed JWTs carrying the actor, tenant,
 * roles and permissions. Nothing about them is stored; they expire after {@code ttl} by the application clock.
 */
public final class JwtService {

    /** An access token and when it expires. */
    public record Issued(String token, Instant expiresAt) {}

    /** A token that is malformed, forged, of another issuer or expired. */
    public static final class InvalidTokenException extends RuntimeException {
        InvalidTokenException(String message) {
            super(message);
        }
    }

    /** Minimum key length of HS256 (RFC 7518 section 3.2). */
    public static final int MIN_SECRET_BYTES = 32;

    static final String ISSUER = "jabiz";
    static final String TENANT = "tenant";
    static final String ROLES = "roles";
    static final String PERMISSIONS = "perms";

    private final MACSigner signer;
    private final MACVerifier verifier;
    private final Duration ttl;
    private final Clock clock;

    public JwtService(byte[] secret, Duration ttl, Clock clock) {
        Objects.requireNonNull(secret, "secret must not be null");
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("The JWT secret must have at least " + MIN_SECRET_BYTES + " bytes");
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("The access token lifetime must be positive");
        }
        try {
            this.signer = new MACSigner(secret);
            this.verifier = new MACVerifier(secret);
        } catch (JOSEException e) {
            throw new IllegalArgumentException("Unusable JWT secret: " + e.getMessage(), e);
        }
        this.ttl = ttl;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public Duration ttl() {
        return ttl;
    }

    public Issued issue(Actor actor) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expires = now.plus(ttl);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .subject(actor.actorId())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(expires))
            .claim(TENANT, actor.tenantId())
            .claim(ROLES, actor.roles().stream().sorted().toList())
            .claim(PERMISSIONS, actor.permissions().stream().sorted().toList())
            .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign an access token", e);
        }
        return new Issued(jwt.serialize(), expires);
    }

    /**
     * The actor of a valid token.
     *
     * @throws InvalidTokenException if the token is malformed, not signed with this service's key by HS256, of
     *                               another issuer, or expired
     */
    public Actor verify(String token) {
        SignedJWT jwt;
        JWTClaimsSet claims;
        try {
            jwt = SignedJWT.parse(token);
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            throw new InvalidTokenException("Malformed access token");
        }
        // Only the algorithm this service signs with: never "none", never a key confusion.
        if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) {
            throw new InvalidTokenException("Unexpected token algorithm");
        }
        try {
            if (!jwt.verify(verifier)) {
                throw new InvalidTokenException("Invalid token signature");
            }
        } catch (JOSEException e) {
            throw new InvalidTokenException("Invalid token signature");
        }
        if (!ISSUER.equals(claims.getIssuer())) {
            throw new InvalidTokenException("Token of another issuer");
        }
        Date expires = claims.getExpirationTime();
        if (expires == null || !clock.instant().isBefore(expires.toInstant())) {
            throw new InvalidTokenException("Expired access token");
        }
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new InvalidTokenException("Token without subject");
        }
        try {
            return new Actor(subject, claims.getStringClaim(TENANT),
                Set.copyOf(strings(claims.getStringListClaim(ROLES))),
                Set.copyOf(strings(claims.getStringListClaim(PERMISSIONS))));
        } catch (ParseException e) {
            throw new InvalidTokenException("Malformed token claims");
        }
    }

    private static List<String> strings(List<String> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
    }
}
