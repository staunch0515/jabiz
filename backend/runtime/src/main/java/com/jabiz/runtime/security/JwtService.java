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
    public record Issued(String token, Instant expiresAt) {
        @Override
        public String toString() {
            return "Issued[token=***, expiresAt=" + expiresAt + "]";
        }
    }

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
    /** When the actor last passed a second factor (docs/design/10-security.md section 9). */
    static final String MFA_AT = "mfa_at";
    static final String PURPOSE = "purpose";
    static final String ATTEMPT = "attempt";
    static final String IDENTITY = "idn";
    /** Type of challenge tokens: never accepted where an access token is expected, nor the other way round. */
    static final JOSEObjectType CHALLENGE_TYPE = new JOSEObjectType("jabiz-mfa+jwt");

    /** What a challenge token lets its holder do (docs/design/10-security.md section 9). */
    public enum Purpose {
        /** Complete the sign-in with a second factor. */
        VERIFY,
        /** Set up a second factor, then sign in again. */
        ENROLL
    }

    /** A verified challenge: whose, and the login record it followed. */
    /**
     * @param identityId the provider account the sign-in came through (docs/design/10-security.md section 12), or
     *                   null: the session the challenge leads to depends on it as one signed in directly would
     */
    public record Challenge(String userId, Purpose purpose, long attemptNo, String identityId) {

        public Challenge(String userId, Purpose purpose, long attemptNo) {
            this(userId, purpose, attemptNo, null);
        }
    }

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

    /** The clock tokens are issued and checked by. */
    public Clock clock() {
        return clock;
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
            .claim(MFA_AT, actor.mfaAt() == null ? null : actor.mfaAt().getEpochSecond())
            .build();
        return new Issued(sign(claims, JOSEObjectType.JWT), expires);
    }

    /**
     * A challenge token for the second step of a sign-in: same key, another type, a short lifetime. It carries the
     * attempt number of the login record it follows, so that a later sign-in makes it stale.
     */
    public Issued issueChallenge(String userId, Purpose purpose, long attemptNo, Duration lifetime) {
        return issueChallenge(userId, purpose, attemptNo, null, lifetime);
    }

    /** As above, for a sign-in through the provider account {@code identityId}. */
    public Issued issueChallenge(String userId, Purpose purpose, long attemptNo, String identityId,
        Duration lifetime) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expires = now.plus(lifetime);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .subject(userId)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(expires))
            .claim(PURPOSE, purpose.name())
            .claim(ATTEMPT, attemptNo)
            .claim(IDENTITY, identityId)
            .build();
        return new Issued(sign(claims, CHALLENGE_TYPE), expires);
    }

    /**
     * The challenge of a valid challenge token for {@code purpose}.
     *
     * @throws InvalidTokenException if the token is not a valid, unexpired challenge of that purpose
     */
    public Challenge verifyChallenge(String token, Purpose purpose) {
        JWTClaimsSet claims = verified(token, CHALLENGE_TYPE, "challenge");
        try {
            if (!purpose.name().equals(claims.getStringClaim(PURPOSE))) {
                throw new InvalidTokenException("Challenge of another purpose");
            }
            Long attempt = claims.getLongClaim(ATTEMPT);
            if (attempt == null) {
                throw new InvalidTokenException("Challenge without attempt");
            }
            return new Challenge(claims.getSubject(), purpose, attempt, claims.getStringClaim(IDENTITY));
        } catch (ParseException e) {
            throw new InvalidTokenException("Malformed challenge claims");
        }
    }

    private String sign(JWTClaimsSet claims, JOSEObjectType type) {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(type).build(), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign a token", e);
        }
        return jwt.serialize();
    }

    /**
     * The actor of a valid token.
     *
     * @throws InvalidTokenException if the token is malformed, not signed with this service's key by HS256, of
     *                               another issuer, or expired
     */
    public Actor verify(String token) {
        JWTClaimsSet claims = verified(token, JOSEObjectType.JWT, "access");
        try {
            Long mfaAt = claims.getLongClaim(MFA_AT);
            return new Actor(claims.getSubject(), claims.getStringClaim(TENANT),
                Set.copyOf(strings(claims.getStringListClaim(ROLES))),
                Set.copyOf(strings(claims.getStringListClaim(PERMISSIONS))),
                mfaAt == null ? null : Instant.ofEpochSecond(mfaAt));
        } catch (ParseException e) {
            throw new InvalidTokenException("Malformed token claims");
        }
    }

    /** The claims of a token of the given type, signed by this service with HS256, of this issuer, unexpired. */
    private JWTClaimsSet verified(String token, JOSEObjectType type, String what) {
        SignedJWT jwt;
        JWTClaimsSet claims;
        try {
            jwt = SignedJWT.parse(token);
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException | NullPointerException e) {
            throw new InvalidTokenException("Malformed " + what + " token");
        }
        // Only the algorithm this service signs with: never "none", never a key confusion.
        if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) {
            throw new InvalidTokenException("Unexpected token algorithm");
        }
        // Access and challenge tokens share the key; the type keeps one from standing in for the other.
        if (!type.equals(jwt.getHeader().getType())) {
            throw new InvalidTokenException("Wrong token type for a(n) " + what + " token");
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
            throw new InvalidTokenException("Expired " + what + " token");
        }
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new InvalidTokenException("Token without subject");
        }
        return claims;
    }

    private static List<String> strings(List<String> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
    }
}
