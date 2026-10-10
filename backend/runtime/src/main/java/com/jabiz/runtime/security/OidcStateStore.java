package com.jabiz.runtime.security;

import com.jabiz.runtime.security.secret.SingleUseSecrets;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.runtime.storage.UniqueKeyViolationException;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The pending OpenID Connect sign-ins (docs/design/10-security.md section 12): state, nonce, browser binder and PKCE
 * verifier of each authorization request, kept server-side (no cookies, decision D12). Using a state inserts its use,
 * whose primary key allows it once. State, nonce and binder are stored as SHA-256 only; the verifier as is, since the
 * token request needs it (it is worthless without the authorization code). Anyone may start a sign-in, so the rows
 * are not kept: each start removes the expired ones, and the tables are not append-only.
 */
public class OidcStateStore {

    /** A new authorization request's secrets: sent to the provider, and the binder kept by the browser. */
    public record Started(String state, String nonce, String codeChallenge, String binder) {
        @Override
        public String toString() {
            return "Started[***]";
        }
    }

    /**
     * A consumed state: which provider it was for, its nonce hash and PKCE verifier, and the sign-in entry it started
     * in (decision D36; the callback cannot pick another one). States from before entries existed are of the
     * administration.
     */
    public record Pending(String providerId, String nonceHash, String codeVerifier, String entry) {

        public Pending {
            entry = entry == null || entry.isBlank() ? com.jabiz.context.RequestContext.DEFAULT_ENTRY : entry;
        }

        @Override
        public String toString() {
            return "Pending[providerId=" + providerId + ", ***]";
        }
    }

    /** Unknown, expired or used state. */
    public static final class InvalidStateException extends RuntimeException {
        InvalidStateException(String message) {
            super(message);
        }
    }

    public static final Duration LIFETIME = Duration.ofMinutes(10);
    private static final int MAX_STATE_LENGTH = 128;

    private final Supplier<StorageEngine> engine;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public OidcStateStore(Supplier<StorageEngine> engine, Clock clock) {
        this.engine = Objects.requireNonNull(engine);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Records a new authorization request, after removing the expired ones: the tables hold at most the requests of
     * the last ten minutes, whoever asks for them.
     */
    public Mono<Started> start(String providerId) {
        return start(providerId, null);
    }

    /** As above, for a sign-in into the entry {@code entry} (null: the administration). */
    public Mono<Started> start(String providerId, String entry) {
        return SingleUseSecrets.randomBytes(random, 128).flatMap(bytes -> {
            Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
            String state = base64.encodeToString(java.util.Arrays.copyOfRange(bytes, 0, 32));
            String nonce = base64.encodeToString(java.util.Arrays.copyOfRange(bytes, 32, 64));
            String verifier = base64.encodeToString(java.util.Arrays.copyOfRange(bytes, 64, 96));
            String binder = base64.encodeToString(java.util.Arrays.copyOfRange(bytes, 96, 128));
            Instant now = now();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("state_hash", hash(state));
            row.put("provider_id", providerId);
            row.put("nonce_hash", hash(nonce));
            row.put("binder_hash", hash(binder));
            row.put("code_verifier", verifier);
            row.put("created_at", now);
            row.put("expires_at", now.plus(LIFETIME));
            row.put("entry", entry == null ? com.jabiz.context.RequestContext.DEFAULT_ENTRY : entry);
            return engine.get().select("DELETE FROM sec_oidc_state WHERE expires_at < :now RETURNING state_hash",
                    Map.of("now", BoundValue.of(now)))
                .then(engine.get().insert("sec_oidc_state", row))
                .thenReturn(new Started(state, nonce, challenge(verifier), binder));
        });
    }

    /**
     * Uses a state once, in its own transaction.
     *
     * @param binder the secret the browser that started the sign-in kept (login CSRF)
     * @throws InvalidStateException (as the error of the Mono) when it is unknown, expired, used or of another
     *                               browser
     */
    public Mono<Pending> consume(String state, String binder) {
        return Mono.defer(() -> {
            if (malformed(state) || malformed(binder)) {
                return Mono.error(new InvalidStateException("Missing or malformed state"));
            }
            String hash = hash(state);
            Instant now = now();
            return engine.get().inTransaction(engine.get().select("""
                    SELECT provider_id, nonce_hash, binder_hash, code_verifier, expires_at, entry FROM sec_oidc_state
                    WHERE state_hash = :hash""", Map.of("hash", BoundValue.of(hash)))
                .next()
                .switchIfEmpty(Mono.error(() -> new InvalidStateException("Unknown state")))
                .flatMap(row -> {
                    if (!now.isBefore(Rows.instant(row.get("expires_at")))) {
                        return Mono.error(new InvalidStateException("Expired state"));
                    }
                    if (!SingleUseSecrets.sameHash(hash(binder), String.valueOf(row.get("binder_hash")))) {
                        return Mono.error(new InvalidStateException("State of another browser"));
                    }
                    Map<String, Object> use = new LinkedHashMap<>();
                    use.put("state_hash", hash);
                    use.put("used_at", now);
                    return engine.get().insert("sec_oidc_state_use", use).thenReturn(new Pending(
                        String.valueOf(row.get("provider_id")), String.valueOf(row.get("nonce_hash")),
                        String.valueOf(row.get("code_verifier")),
                        row.get("entry") == null ? null : String.valueOf(row.get("entry"))));
                }))
                .onErrorMap(UniqueKeyViolationException.class, e -> new InvalidStateException("State used twice"));
        });
    }

    /** RFC 7636 S256 challenge of a verifier. */
    static String challenge(String verifier) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(SingleUseSecrets.sha256(verifier));
    }

    /** Hex SHA-256 of a text, as state, nonce and binder are stored. */
    public static String hash(String value) {
        return SingleUseSecrets.sha256Hex(value);
    }

    private static boolean malformed(String value) {
        return value == null || value.isBlank() || value.length() > MAX_STATE_LENGTH;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
