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
     * administration. {@code providedName}: the name the provider posted with its answer (Apple, decision D67 item 3).
     */
    public record Pending(String providerId, String nonceHash, String codeVerifier, String entry, Purpose purpose,
        String linkUserId, String providedName) {

        public Pending {
            entry = entry == null || entry.isBlank() ? com.jabiz.context.RequestContext.DEFAULT_ENTRY : entry;
            purpose = purpose == null ? Purpose.SIGN_IN : purpose;
        }

        public Pending(String providerId, String nonceHash, String codeVerifier, String entry, Purpose purpose,
            String linkUserId) {
            this(providerId, nonceHash, codeVerifier, entry, purpose, linkUserId, null);
        }

        public Pending(String providerId, String nonceHash, String codeVerifier, String entry) {
            this(providerId, nonceHash, codeVerifier, entry, Purpose.SIGN_IN, null);
        }

        @Override
        public String toString() {
            return "Pending[providerId=" + providerId + ", purpose=" + purpose + ", ***]";
        }
    }

    /**
     * What an authorization request is for (docs/design/10-security.md section 12): signing in, or linking the
     * provider's account to the signed-in user who started it (decision D39 item 2). Each callback accepts its own
     * purpose only.
     */
    public enum Purpose { SIGN_IN, LINK }

    /** A pending round trip whose answer the provider posted to this server: where to send the browser on. */
    public record Posted(String providerId, String entry) {}

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
        return start(providerId, entry, Purpose.SIGN_IN, null);
    }

    /**
     * As above, for {@code purpose}; a {@link Purpose#LINK} request names the signed-in user it links for
     * ({@code linkUserId}), whose own callback alone may use it.
     */
    public Mono<Started> start(String providerId, String entry, Purpose purpose, String linkUserId) {
        if ((purpose == Purpose.LINK) != (linkUserId != null)) {
            return Mono.error(new IllegalArgumentException("A link request names its user; a sign-in names none"));
        }
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
            row.put("purpose", purpose.name());
            row.put("link_user_id", linkUserId == null ? null : java.util.UUID.fromString(linkUserId));
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
                    SELECT provider_id, nonce_hash, binder_hash, code_verifier, expires_at, entry, purpose,
                           link_user_id::text AS link_user_id, provided_name
                    FROM sec_oidc_state WHERE state_hash = :hash""", Map.of("hash", BoundValue.of(hash)))
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
                        row.get("entry") == null ? null : String.valueOf(row.get("entry")),
                        Purpose.valueOf(String.valueOf(row.get("purpose"))),
                        row.get("link_user_id") == null ? null : String.valueOf(row.get("link_user_id")),
                        row.get("provided_name") == null ? null : String.valueOf(row.get("provided_name"))));
                }))
                .onErrorMap(UniqueKeyViolationException.class, e -> new InvalidStateException("State used twice"));
        });
    }

    /**
     * The provider posted its answer for {@code state} to this server (Apple's {@code form_post}, decision D67 item 1):
     * keeps the name it told, the first one only, and says which provider and entry the round trip is of, so that the
     * browser goes on to that entry's callback. Does not use the state up: the callback does, with the browser's binder.
     * Empty when the state is unknown, expired or used.
     */
    public Mono<Posted> posted(String state, String providedName) {
        return Mono.defer(() -> {
            if (malformed(state)) {
                return Mono.empty();
            }
            String hash = hash(state);
            Map<String, BoundValue> params = new LinkedHashMap<>();
            params.put("hash", BoundValue.of(hash));
            params.put("now", BoundValue.of(now()));
            params.put("name", providedName == null ? BoundValue.nullOf(String.class) : BoundValue.of(providedName));
            return engine.get().select("""
                    UPDATE sec_oidc_state SET provided_name = COALESCE(provided_name, :name)
                    WHERE state_hash = :hash AND expires_at > :now
                      AND NOT EXISTS (SELECT 1 FROM sec_oidc_state_use u WHERE u.state_hash = :hash)
                    RETURNING provider_id, entry""", params)
                .next()
                .map(row -> new Posted(String.valueOf(row.get("provider_id")),
                    row.get("entry") == null ? com.jabiz.context.RequestContext.DEFAULT_ENTRY
                        : String.valueOf(row.get("entry"))));
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
