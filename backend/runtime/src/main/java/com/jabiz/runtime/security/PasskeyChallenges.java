package com.jabiz.runtime.security;

import com.jabiz.query.BoundValue;
import com.jabiz.runtime.security.secret.SingleUseSecrets;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageEngine;
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
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The challenges of passkey ceremonies (decision D71 item 5; docs/design/10-security.md section 15.16), kept server-side
 * as their SHA-256 (no cookies, decision D12; no Redis). A challenge is for one purpose, entry and (but at sign-in)
 * user; answering takes its row out in one statement, so it is used at most once. Anyone may ask for a sign-in
 * challenge, so the rows are not kept: each new challenge removes the expired ones.
 */
public class PasskeyChallenges {

    /** What a challenge is for: each answer accepts its own purpose only. */
    public enum Purpose {
        /** Signing in without a name: the passkey names its user. */
        SIGN_IN,
        /** The second step after a password, for the challenge's user. */
        SECOND_FACTOR,
        /** A signed-in user confirming with a passkey (section 10). */
        STEP_UP,
        /** Registering a passkey for the user, who has just proven who they are. */
        REGISTER
    }

    /** A challenge taken out: its purpose, entry and user (null at sign-in). */
    public record Pending(Purpose purpose, String entry, UUID userId) {}

    /** Unknown, expired or used challenge. */
    public static final class InvalidChallengeException extends RuntimeException {
        InvalidChallengeException(String message) {
            super(message, null, false, false);
        }
    }

    static final int BYTES = 32;
    private static final int MAX_LENGTH = 128;

    private final Supplier<StorageEngine> engine;
    private final Clock clock;
    private final Duration lifetime;
    private final SecureRandom random = new SecureRandom();

    public PasskeyChallenges(Supplier<StorageEngine> engine, Clock clock, Duration lifetime) {
        this.engine = Objects.requireNonNull(engine);
        this.clock = Objects.requireNonNull(clock);
        this.lifetime = Objects.requireNonNull(lifetime);
    }

    /** A new challenge (Base64url, as WebAuthn's JSON carries it), after removing the expired ones. */
    public Mono<String> start(Purpose purpose, String entry, UUID userId) {
        if ((purpose == Purpose.SIGN_IN) != (userId == null)) {
            return Mono.error(new IllegalArgumentException("A sign-in challenge names no user; every other one does"));
        }
        return SingleUseSecrets.randomBytes(random, BYTES).flatMap(bytes -> {
            String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            Instant now = now();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("challenge_hash", SingleUseSecrets.sha256Hex(challenge));
            row.put("purpose", purpose.name());
            row.put("entry", entry == null ? com.jabiz.context.RequestContext.DEFAULT_ENTRY : entry);
            row.put("user_id", userId);
            row.put("created_at", now);
            row.put("expires_at", now.plus(lifetime));
            return engine.get().select("DELETE FROM sec_passkey_challenge WHERE expires_at < :now "
                    + "RETURNING challenge_hash", Map.of("now", BoundValue.of(now)))
                .then(engine.get().insert("sec_passkey_challenge", row))
                .thenReturn(challenge);
        });
    }

    /**
     * Takes a challenge out, once.
     *
     * @throws InvalidChallengeException (as the error of the Mono) when it is unknown, used or expired
     */
    public Mono<Pending> take(String challenge) {
        return Mono.defer(() -> {
            if (challenge == null || challenge.isBlank() || challenge.length() > MAX_LENGTH) {
                return Mono.error(new InvalidChallengeException("Missing or malformed challenge"));
            }
            return engine.get().select("""
                    DELETE FROM sec_passkey_challenge WHERE challenge_hash = :hash
                    RETURNING purpose, entry, user_id::text AS user_id, expires_at""",
                    Map.of("hash", BoundValue.of(SingleUseSecrets.sha256Hex(challenge))))
                .next()
                .switchIfEmpty(Mono.error(() -> new InvalidChallengeException("Unknown or used challenge")))
                .flatMap(row -> {
                    if (!now().isBefore(Rows.instant(row.get("expires_at")))) {
                        return Mono.error(new InvalidChallengeException("Expired challenge"));
                    }
                    Object user = row.get("user_id");
                    return Mono.just(new Pending(Purpose.valueOf(String.valueOf(row.get("purpose"))),
                        String.valueOf(row.get("entry")), user == null ? null : UUID.fromString(String.valueOf(user))));
                });
        });
    }

    /** How long a challenge may be answered. */
    public Duration lifetime() {
        return lifetime;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
