package com.jabiz.runtime.security;

import com.jabiz.query.BoundValue;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.runtime.storage.UniqueKeyViolationException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The pending OpenID Connect sign-ins (docs/design/10-security.md section 12): state, nonce and PKCE verifier of each
 * authorization request, kept server-side (no cookies, decision D12). Both tables are append-only: using a state
 * inserts its use, whose primary key allows it once. State and nonce are stored as SHA-256 only; the verifier as is,
 * since the token request needs it (it is worthless without the authorization code, and lives ten minutes).
 */
public class OidcStateStore {

    /** A new authorization request's secrets, as sent to the provider. */
    public record Started(String state, String nonce, String codeChallenge) {
        @Override
        public String toString() {
            return "Started[***]";
        }
    }

    /** A consumed state: which provider it was for, its nonce hash and PKCE verifier. */
    public record Pending(String providerId, String nonceHash, String codeVerifier) {
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

    public Mono<Started> start(String providerId) {
        return randomBytes(96).flatMap(bytes -> {
            Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
            String state = base64.encodeToString(java.util.Arrays.copyOfRange(bytes, 0, 32));
            String nonce = base64.encodeToString(java.util.Arrays.copyOfRange(bytes, 32, 64));
            String verifier = base64.encodeToString(java.util.Arrays.copyOfRange(bytes, 64, 96));
            Instant now = now();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("state_hash", hash(state));
            row.put("provider_id", providerId);
            row.put("nonce_hash", hash(nonce));
            row.put("code_verifier", verifier);
            row.put("created_at", now);
            row.put("expires_at", now.plus(LIFETIME));
            return engine.get().insert("sec_oidc_state", row)
                .thenReturn(new Started(state, nonce, challenge(verifier)));
        });
    }

    /**
     * Uses a state once, in its own transaction.
     *
     * @throws InvalidStateException (as the error of the Mono) when it is unknown, expired or used
     */
    public Mono<Pending> consume(String state) {
        return Mono.defer(() -> {
            if (state == null || state.isBlank() || state.length() > MAX_STATE_LENGTH) {
                return Mono.error(new InvalidStateException("Missing or malformed state"));
            }
            String hash = hash(state);
            Instant now = now();
            return engine.get().inTransaction(engine.get().select("""
                    SELECT provider_id, nonce_hash, code_verifier, expires_at FROM sec_oidc_state
                    WHERE state_hash = :hash""", Map.of("hash", BoundValue.of(hash)))
                .next()
                .switchIfEmpty(Mono.error(() -> new InvalidStateException("Unknown state")))
                .flatMap(row -> {
                    if (!now.isBefore(Rows.instant(row.get("expires_at")))) {
                        return Mono.error(new InvalidStateException("Expired state"));
                    }
                    Map<String, Object> use = new LinkedHashMap<>();
                    use.put("state_hash", hash);
                    use.put("used_at", now);
                    return engine.get().insert("sec_oidc_state_use", use).thenReturn(new Pending(
                        String.valueOf(row.get("provider_id")), String.valueOf(row.get("nonce_hash")),
                        String.valueOf(row.get("code_verifier"))));
                }))
                .onErrorMap(UniqueKeyViolationException.class, e -> new InvalidStateException("State used twice"));
        });
    }

    /** RFC 7636 S256 challenge of a verifier. */
    static String challenge(String verifier) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier));
    }

    /** Hex SHA-256 of a text, as state and nonce are stored. */
    public static String hash(String value) {
        return HexFormat.of().formatHex(sha256(value));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** SecureRandom may block on the entropy source: never on an event loop. */
    private Mono<byte[]> randomBytes(int length) {
        return Mono.fromCallable(() -> {
            byte[] bytes = new byte[length];
            random.nextBytes(bytes);
            return bytes;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
