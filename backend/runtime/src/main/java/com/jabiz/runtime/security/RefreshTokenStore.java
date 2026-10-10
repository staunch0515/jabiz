package com.jabiz.runtime.security;

import com.jabiz.runtime.security.secret.SingleUseSecrets;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.runtime.storage.UniqueKeyViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Refresh tokens (decision D12): random, opaque, single use. The database keeps their SHA-256 only, so a leaked
 * table does not leak usable tokens. Every refresh consumes a token and issues the next one of the same family (the
 * family is one sign-in); presenting a consumed token again means it was stolen or replayed, and revokes the whole
 * family. All three tables are append-only.
 */
public class RefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenStore.class);

    /** A token as handed to the client, and when it expires. */
    public record Issued(String token, UUID familyId, Instant expiresAt) {
        @Override
        public String toString() {
            return "Issued[token=***, familyId=" + familyId + ", expiresAt=" + expiresAt + "]";
        }
    }

    /**
     * The user a valid token was issued to, its family, and when the sign-in of the family passed a second factor
     * (null if it did not).
     */
    public record Grant(UUID userId, UUID familyId, Instant mfaAt, UUID identityId) {}

    /** The token is unknown, expired, consumed or of a revoked family. */
    public static final class InvalidRefreshTokenException extends RuntimeException {
        InvalidRefreshTokenException(String message) {
            super(message);
        }
    }

    static final String REASON_LOGOUT = "LOGOUT";
    static final String REASON_REUSE = "REUSE";
    public static final String REASON_PASSWORD = "PASSWORD";

    private static final int TOKEN_BYTES = 32;
    private static final int MAX_TOKEN_LENGTH = 128;

    private final Supplier<StorageEngine> engine;
    private final Duration ttl;
    private final Duration idleWindow;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param idleWindow how long after its issue a token can be used at most: the access token issued with it plus the
     *                   idle timeout (docs/design/10-security.md section 11); a session idle for longer ends
     */
    public RefreshTokenStore(Supplier<StorageEngine> engine, Duration ttl, Duration idleWindow, Clock clock) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("The refresh token lifetime must be positive");
        }
        if (idleWindow == null || idleWindow.isNegative() || idleWindow.isZero()) {
            throw new IllegalArgumentException("The idle window must be positive");
        }
        this.ttl = ttl;
        this.idleWindow = idleWindow;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public Duration ttl() {
        return ttl;
    }

    /**
     * A token of a new family: one per sign-in.
     *
     * @param mfaAt      when the sign-in passed a second factor, or null
     * @param identityId the provider account the sign-in came through (docs/design/10-security.md section 12), or
     *                   null: its link must still exist at each refresh
     */
    public Mono<Issued> issue(UUID userId, Instant mfaAt, UUID identityId) {
        return randomBytes(16).flatMap(bytes -> {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            return issue(userId, new UUID(buffer.getLong(), buffer.getLong()), mfaAt, identityId);
        });
    }

    /** A consumed token, what the check made of it, and the next token of its family. */
    public record Rotated<T>(Grant grant, T value, Issued next) {}

    /**
     * Consumes a token and issues the next one of its family, in one transaction: each token can be used once. The
     * check runs in between (for example: may the user still sign in?); when it fails, nothing is consumed or issued.
     * A token presented a second time was stolen or replayed, and its whole family is revoked.
     *
     * @throws InvalidRefreshTokenException (as the error of the Mono) when the token cannot be used
     */
    public <T> Mono<Rotated<T>> rotate(String token, Function<Grant, Mono<T>> check) {
        return inTransaction(token, hash -> use(hash).flatMap(grant -> check.apply(grant)
            .flatMap(value -> issue(grant.userId(), grant.familyId(), grant.mfaAt(), grant.identityId())
                .map(next -> new Rotated<>(grant, value, next)))));
    }

    /**
     * Runs work that consumes the token in a transaction. A second use of a token breaks the primary key of its use;
     * that aborts the transaction, and the family is revoked afterwards, on its own.
     */
    private <T> Mono<T> inTransaction(String token, Function<String, Mono<T>> work) {
        return Mono.defer(() -> {
            String hash = hash(token);
            return engine.get().inTransaction(work.apply(hash))
                .onErrorResume(UniqueKeyViolationException.class, reused -> find(hash)
                    .flatMap(found -> {
                        log.warn("Refresh token of user {} presented twice; revoking its session",
                            found.grant().userId());
                        return revokeFamily(found.grant().familyId(), REASON_REUSE);
                    })
                    .then(Mono.error(new InvalidRefreshTokenException("Refresh token used twice"))));
        });
    }

    private Mono<Grant> use(String hash) {
        Instant now = now();
        return find(hash).flatMap(found -> {
            if (found.revoked()) {
                return Mono.<Grant>error(new InvalidRefreshTokenException("Refresh token of a revoked session"));
            }
            if (!now.isBefore(found.expiresAt())) {
                return Mono.<Grant>error(new InvalidRefreshTokenException("Expired refresh token"));
            }
            // Idle: the access token issued with this one expired longer than the idle timeout ago. Not consumed.
            if (!now.isBefore(found.issuedAt().plus(idleWindow))) {
                return Mono.<Grant>error(new InvalidRefreshTokenException("Session idle for too long"));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("token_hash", hash);
            row.put("used_at", now);
            return engine.get().insert("sec_refresh_token_use", row).thenReturn(found.grant());
        }).switchIfEmpty(Mono.error(() -> new InvalidRefreshTokenException("Unknown refresh token")));
    }

    /**
     * Ends every session of a user, for example when the password changes. Runs in the caller's transaction when
     * there is one.
     */
    public Mono<Void> revokeUser(UUID userId, String reason) {
        return engine.get().select("""
                SELECT DISTINCT t.family_id FROM sec_refresh_token t
                WHERE t.user_id = :user AND NOT EXISTS (
                    SELECT 1 FROM sec_refresh_family_revocation r WHERE r.family_id = t.family_id)""",
                Map.of("user", BoundValue.of(userId)))
            .concatMap(row -> revokeFamily(Rows.uuid(row.get("family_id")), reason))
            .then();
    }

    /** Ends the session the token belongs to. Unknown or already revoked tokens are ignored. */
    public Mono<Void> revoke(String token) {
        return Mono.defer(() -> find(hash(token))
            .flatMap(found -> revokeFamily(found.grant().familyId(), REASON_LOGOUT)));
    }

    private Mono<Issued> issue(UUID userId, UUID familyId, Instant mfaAt, UUID identityId) {
        return randomBytes(TOKEN_BYTES).flatMap(bytes -> {
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            Instant now = now();
            Instant expires = now.plus(ttl);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("token_hash", hash(token));
            row.put("family_id", familyId);
            row.put("user_id", userId);
            row.put("issued_at", now);
            row.put("expires_at", expires);
            row.put("mfa_at", mfaAt);
            row.put("identity_id", identityId);
            return engine.get().insert("sec_refresh_token", row).thenReturn(new Issued(token, familyId, expires));
        });
    }

    private Mono<Void> revokeFamily(UUID familyId, String reason) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("family_id", familyId);
        row.put("revoked_at", now());
        row.put("reason", reason);
        return engine.get().insert("sec_refresh_family_revocation", row)
            // Revoked already: the first revocation stands.
            .onErrorResume(UniqueKeyViolationException.class, e -> Mono.empty());
    }

    private record Found(Grant grant, Instant issuedAt, Instant expiresAt, boolean revoked) {}

    private Mono<Found> find(String hash) {
        return engine.get().select("""
                SELECT t.user_id, t.family_id, t.issued_at, t.expires_at, t.mfa_at, t.identity_id,
                    r.family_id IS NOT NULL AS revoked
                FROM sec_refresh_token t LEFT JOIN sec_refresh_family_revocation r ON r.family_id = t.family_id
                WHERE t.token_hash = :hash""", Map.of("hash", BoundValue.of(hash)))
            .next()
            .map(row -> new Found(new Grant(Rows.uuid(row.get("user_id")), Rows.uuid(row.get("family_id")),
                    row.get("mfa_at") == null ? null : Rows.instant(row.get("mfa_at")),
                    row.get("identity_id") == null ? null : Rows.uuid(row.get("identity_id"))),
                Rows.instant(row.get("issued_at")), Rows.instant(row.get("expires_at")),
                Boolean.TRUE.equals(row.get("revoked"))));
    }

    private Mono<byte[]> randomBytes(int length) {
        return SingleUseSecrets.randomBytes(random, length);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** Hex SHA-256; tokens that cannot be ours (absent, oversized) are refused before they reach the database. */
    static String hash(String token) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            throw new InvalidRefreshTokenException("Missing or malformed refresh token");
        }
        return SingleUseSecrets.sha256Hex(token);
    }
}
