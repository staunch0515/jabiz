package com.jabiz.runtime.security;

import com.jabiz.context.RequestContext;
import com.jabiz.runtime.process.sponsor.SignInSource;
import com.jabiz.runtime.security.secret.SingleUseSecrets;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.runtime.storage.UniqueKeyViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Refresh tokens (decision D12): random, opaque, single use. The database keeps their SHA-256 only, so a leaked
 * table does not leak usable tokens. Every refresh consumes a token and issues the next one of the same family (the
 * family is one sign-in); presenting a consumed token again means it was stolen or replayed, and revokes the whole
 * family. All three tables are append-only. How long a token lives and whether its session ends when idle is the
 * policy of the sign-in entry of its family (decision D43 item 2).
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
     *
     * @param entry the sign-in entry of the family (decision D36): kept by every refresh; tokens issued before entries
     *              existed are of the administration
     */
    public record Grant(UUID userId, UUID familyId, Instant mfaAt, UUID identityId, String entry) {

        public Grant {
            entry = entry == null || entry.isBlank() ? RequestContext.DEFAULT_ENTRY : entry;
        }

        public Grant(UUID userId, UUID familyId, Instant mfaAt, UUID identityId) {
            this(userId, familyId, mfaAt, identityId, null);
        }
    }

    /**
     * A live session of a user (docs/design/10-security.md section 15.9): a token family not revoked whose newest token
     * is neither used nor expired, nor idle for longer than its entry allows.
     *
     * @param id         the family: the session's id, no credential
     * @param createdAt  when it was signed in (its first token)
     * @param lastUsedAt when it was last signed in or refreshed (its newest token)
     * @param expiresAt  when its newest token expires, unless refreshed
     * @param clientIp   where the newest token was issued to, or null
     * @param userAgent  the user agent the newest token was issued to, or null
     * @param mfaAt      when its sign-in passed a second factor, or null
     * @param oidc       whether it was signed in through an identity provider
     */
    public record Session(UUID id, String entry, Instant createdAt, Instant lastUsedAt, Instant expiresAt,
        String clientIp, String userAgent, Instant mfaAt, boolean oidc) {}

    /** The token is unknown, expired, consumed or of a revoked family. */
    public static final class InvalidRefreshTokenException extends RuntimeException {
        InvalidRefreshTokenException(String message) {
            super(message);
        }
    }

    static final String REASON_LOGOUT = "LOGOUT";
    static final String REASON_REUSE = "REUSE";
    public static final String REASON_PASSWORD = "PASSWORD";
    /** A sign-in guard refused the session at a refresh (decision D36 item 6). */
    public static final String REASON_REFUSED = "REFUSED";
    /** The password was reset through a mailed link (decision D36 item 5). */
    public static final String REASON_PASSWORD_RESET = "PASSWORD_RESET";
    /**
     * Joining an entry through a mailed link set a new password on a pre-claimable account (decision D36
     * implementation note 6): whoever registered it before may hold sessions.
     */
    public static final String REASON_JOIN = "JOIN";
    /** The user changed their own password (section 15.8): every other session of theirs ends. */
    public static final String REASON_PASSWORD_CHANGE = "PASSWORD_CHANGE";
    /** Verifying an address with a new password took over an account somebody else may have registered. */
    public static final String REASON_CLAIM = "CLAIM";
    /** The user ended one or all other sessions of theirs from the session list (section 15.9). */
    public static final String REASON_SELF_REVOKE = "SELF_REVOKE";
    /** An administrator ended sessions of the user (section 15.9). */
    public static final String REASON_ADMIN_REVOKE = "ADMIN_REVOKE";

    private static final int TOKEN_BYTES = 32;
    private static final int MAX_TOKEN_LENGTH = 128;

    /**
     * How the sessions of a sign-in entry live (decision D43 item 2).
     *
     * @param ttl        lifetime of each token, from its issue: every refresh starts it afresh (sliding)
     * @param idleWindow how long after its issue a token can be used at most: the access token issued with it plus the
     *                   idle timeout (docs/design/10-security.md section 11), a session idle for longer ends; null for
     *                   no idle end (only the lifetime ends the session)
     */
    public record Policy(Duration ttl, Duration idleWindow) {
        public Policy {
            if (ttl == null || !ttl.isPositive()) {
                throw new IllegalArgumentException("The refresh token lifetime must be positive");
            }
            if (idleWindow != null && !idleWindow.isPositive()) {
                throw new IllegalArgumentException("The idle window must be positive");
            }
        }
    }

    private final Supplier<StorageEngine> engine;
    private final Function<String, Policy> policies;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /** The same lifetime and idle window for every entry. */
    public RefreshTokenStore(Supplier<StorageEngine> engine, Duration ttl, Duration idleWindow, Clock clock) {
        this(engine, constant(new Policy(ttl, Objects.requireNonNull(idleWindow, "idleWindow must not be null"))),
            clock);
    }

    /**
     * @param policies the policy of the sessions of an entry, by its name (never null: the administration's is
     *                 {@value RequestContext#DEFAULT_ENTRY}); a token's family keeps the entry it was signed in at, and
     *                 the policy of that entry applies to it
     */
    public RefreshTokenStore(Supplier<StorageEngine> engine, Function<String, Policy> policies, Clock clock) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.policies = Objects.requireNonNull(policies, "policies must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    private static Function<String, Policy> constant(Policy policy) {
        return entry -> policy;
    }

    /** The policy of the sessions of the entry (null: the administration). */
    public Policy policy(String entry) {
        return Objects.requireNonNull(policies.apply(entry == null || entry.isBlank() ? RequestContext.DEFAULT_ENTRY
            : entry), "no session policy");
    }

    /**
     * A token of a new family: one per sign-in.
     *
     * @param mfaAt      when the sign-in passed a second factor, or null
     * @param identityId the provider account the sign-in came through (docs/design/10-security.md section 12), or
     *                   null: its link must still exist at each refresh
     */
    public Mono<Issued> issue(UUID userId, Instant mfaAt, UUID identityId) {
        return issue(userId, mfaAt, identityId, null);
    }

    /** As above, for a session of the sign-in entry {@code entry} (null: the administration). */
    public Mono<Issued> issue(UUID userId, Instant mfaAt, UUID identityId, String entry) {
        return issue(userId, mfaAt, identityId, entry, SignInSource.NONE);
    }

    /**
     * As above, recording where the sign-in came from: the address and user agent of {@code source} (its entry is not
     * read; {@code entry} is the session's).
     */
    public Mono<Issued> issue(UUID userId, Instant mfaAt, UUID identityId, String entry, SignInSource source) {
        return randomBytes(16).flatMap(bytes -> {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            return issue(new Grant(userId, new UUID(buffer.getLong(), buffer.getLong()), mfaAt, identityId, entry),
                source);
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
        return rotate(token, null, check);
    }

    /**
     * As above, for a client of the sign-in entry {@code entry} (null: the administration): a token of another entry's
     * session is refused without being consumed (decision D36).
     */
    public <T> Mono<Rotated<T>> rotate(String token, String entry, Function<Grant, Mono<T>> check) {
        return rotate(token, entry, SignInSource.NONE, check);
    }

    /** As above, recording the client's address and user agent with the next token (the source's entry is not read). */
    public <T> Mono<Rotated<T>> rotate(String token, String entry, SignInSource source,
        Function<Grant, Mono<T>> check) {
        String expected = entry == null || entry.isBlank() ? RequestContext.DEFAULT_ENTRY : entry;
        return inTransaction(token, hash -> use(hash, expected).flatMap(grant -> check.apply(grant)
            .flatMap(value -> issue(grant, source).map(next -> new Rotated<>(grant, value, next)))));
    }

    /**
     * Runs work that consumes the token in a transaction. A second use of a token breaks the primary key of its use;
     * that aborts the transaction, and the family is revoked afterwards, on its own.
     */
    private <T> Mono<T> inTransaction(String token, Function<String, Mono<T>> work) {
        return Mono.defer(() -> {
            String hash = hash(token);
            return engine.get().inTransaction(work.apply(hash))
                .onErrorResume(e -> e instanceof UniqueKeyViolationException || e instanceof Reused, reused -> find(hash)
                    .flatMap(found -> {
                        log.warn("Refresh token of user {} presented twice; revoking its session",
                            found.grant().userId());
                        return revokeSession(found.grant().familyId(), REASON_REUSE);
                    })
                    .then(Mono.error(new InvalidRefreshTokenException("Refresh token used twice"))));
        });
    }

    /** A consumed token presented again; the family is revoked once the transaction has rolled back. */
    private static final class Reused extends RuntimeException {
        Reused() {
            super("Refresh token used twice", null, false, false);
        }
    }

    private Mono<Grant> use(String hash, String entry) {
        Instant now = now();
        return find(hash).flatMap(found -> {
            // A revoked session stays a plain 401: it has ended already, there is nothing left to revoke.
            if (found.revoked()) {
                return Mono.<Grant>error(new InvalidRefreshTokenException("Refresh token of a revoked session"));
            }
            // Replayed: whenever it comes back (also after its expiry or idle window) and whatever entry it names, the
            // session ends (decision D12 item 1).
            if (found.used()) {
                return Mono.<Grant>error(new Reused());
            }
            if (!now.isBefore(found.expiresAt())) {
                return Mono.<Grant>error(new InvalidRefreshTokenException("Expired refresh token"));
            }
            // Idle: the access token issued with this one expired longer than the idle timeout ago. Not consumed. The
            // family's entry says how long that is, if idleness ends its sessions at all (decision D43 item 2).
            Duration idleWindow = policy(found.grant().entry()).idleWindow();
            if (idleWindow != null && !now.isBefore(found.issuedAt().plus(idleWindow))) {
                return Mono.<Grant>error(new InvalidRefreshTokenException("Session idle for too long"));
            }
            // Another front end's live session: not this client's to renew. Not consumed, not revoked.
            if (!entry.equals(found.grant().entry())) {
                return Mono.<Grant>error(new InvalidRefreshTokenException("Refresh token of another sign-in entry"));
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
        return revokeUser(userId, reason, null);
    }

    /**
     * Ends every session of a user but the one of the family {@code keep} (null: every one), for example the session
     * that changed the password itself. Runs in the caller's transaction when there is one.
     */
    public Mono<Void> revokeUser(UUID userId, String reason, UUID keep) {
        return revokeUser(userId, reason, keep, null);
    }

    /**
     * As above, by the operation {@code processSeqId} (null: none). Only sessions that could still be renewed are
     * revoked: a family whose tokens have all expired has ended already and gets no revocation (an expired token is
     * refused whether its family is revoked or not).
     */
    public Mono<Void> revokeUser(UUID userId, String reason, UUID keep, Long processSeqId) {
        return Flux.defer(() -> engine.get().select("""
                SELECT DISTINCT t.family_id FROM sec_refresh_token t
                WHERE t.user_id = :user AND t.expires_at > :now AND NOT EXISTS (
                    SELECT 1 FROM sec_refresh_family_revocation r WHERE r.family_id = t.family_id)""",
                Map.of("user", BoundValue.of(userId), "now", BoundValue.of(now()))))
            .map(row -> Rows.uuid(row.get("family_id")))
            .filter(family -> !family.equals(keep))
            .concatMap(family -> revokeSession(family, reason, processSeqId))
            .then();
    }

    /**
     * The live sessions of a user, the most recently used first: at the entry {@code entry} only, or at every entry
     * (null). Live as at a refresh: not revoked, the newest token neither used nor expired, nor idle for longer than
     * the policy of the session's entry allows. Runs in the caller's transaction when there is one.
     */
    public Flux<Session> liveSessions(UUID userId, String entry) {
        return liveSessions(userId, entry, null);
    }

    /** The live session {@code familyId} of the user (at the entry {@code entry}; any: null), if there is one. */
    public Mono<Session> liveSession(UUID userId, String entry, UUID familyId) {
        return liveSessions(userId, entry, Objects.requireNonNull(familyId, "familyId must not be null")).next();
    }

    private Flux<Session> liveSessions(UUID userId, String entry, UUID familyId) {
        return Flux.defer(() -> {
            Instant now = now();
            Map<String, BoundValue> params = new LinkedHashMap<>();
            params.put("user", BoundValue.of(userId));
            params.put("now", BoundValue.of(now));
            String family = "";
            if (familyId != null) {
                params.put("family", BoundValue.of(familyId));
                family = " AND t.family_id = :family";
            }
            // The newest token of a live family is its only unused one: every refresh uses the one before. Should a
            // family ever show two, its newest counts.
            Set<UUID> seen = new HashSet<>();
            return engine.get().select("""
                    SELECT t.family_id, t.issued_at, t.expires_at, t.mfa_at, t.identity_id, t.entry, t.client_ip,
                        t.user_agent,
                        (SELECT min(f.issued_at) FROM sec_refresh_token f WHERE f.family_id = t.family_id) AS created_at
                    FROM sec_refresh_token t
                    WHERE t.user_id = :user AND t.expires_at > :now%s
                        AND NOT EXISTS (SELECT 1 FROM sec_refresh_token_use u WHERE u.token_hash = t.token_hash)
                        AND NOT EXISTS (SELECT 1 FROM sec_refresh_family_revocation r
                            WHERE r.family_id = t.family_id)
                    ORDER BY t.issued_at DESC, t.family_id""".formatted(family), params)
                .map(RefreshTokenStore::session)
                .filter(session -> seen.add(session.id()))
                .filter(session -> entry == null || entry.equals(session.entry()))
                .filter(session -> {
                    Duration idleWindow = policy(session.entry()).idleWindow();
                    return idleWindow == null || now.isBefore(session.lastUsedAt().plus(idleWindow));
                });
        });
    }

    private static Session session(Map<String, Object> row) {
        String entry = row.get("entry") == null ? RequestContext.DEFAULT_ENTRY : String.valueOf(row.get("entry"));
        return new Session(Rows.uuid(row.get("family_id")), entry, Rows.instant(row.get("created_at")),
            Rows.instant(row.get("issued_at")), Rows.instant(row.get("expires_at")),
            row.get("client_ip") == null ? null : String.valueOf(row.get("client_ip")),
            row.get("user_agent") == null ? null : String.valueOf(row.get("user_agent")),
            row.get("mfa_at") == null ? null : Rows.instant(row.get("mfa_at")), row.get("identity_id") != null);
    }

    /**
     * The session (token family) a token stands for, when it is a live token of {@code userId}'s session at
     * {@code entry}: not consumed, not expired, not of a revoked family. Empty otherwise, also for a malformed token;
     * nothing is consumed or revoked, so a stale or foreign token only fails to name a session.
     */
    public Mono<UUID> liveSession(String token, UUID userId, String entry) {
        String expected = entry == null || entry.isBlank() ? RequestContext.DEFAULT_ENTRY : entry;
        return Mono.defer(() -> {
            String hash;
            try {
                hash = hash(token);
            } catch (InvalidRefreshTokenException malformed) {
                return Mono.empty();
            }
            Instant now = now();
            return find(hash).filter(found -> !found.revoked() && !found.used() && now.isBefore(found.expiresAt())
                    && found.grant().userId().equals(userId) && expected.equals(found.grant().entry()))
                .map(found -> found.grant().familyId());
        });
    }

    /** Ends the session the token belongs to. Unknown or already revoked tokens are ignored. */
    public Mono<Void> revoke(String token) {
        return Mono.defer(() -> find(hash(token))
            .flatMap(found -> revokeSession(found.grant().familyId(), REASON_LOGOUT)));
    }

    /**
     * The next token of the grant's family, carrying its user, second factor, account link and entry, and where it
     * was issued to.
     */
    private Mono<Issued> issue(Grant grant, SignInSource source) {
        return randomBytes(TOKEN_BYTES).flatMap(bytes -> {
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            Instant now = now();
            Instant expires = now.plus(policy(grant.entry()).ttl());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("token_hash", hash(token));
            row.put("family_id", grant.familyId());
            row.put("user_id", grant.userId());
            row.put("issued_at", now);
            row.put("expires_at", expires);
            row.put("mfa_at", grant.mfaAt());
            row.put("identity_id", grant.identityId());
            row.put("entry", grant.entry());
            row.put("client_ip", source == null ? null : source.clientIp());
            row.put("user_agent", source == null ? null : source.userAgent());
            return engine.get().insert("sec_refresh_token", row)
                .thenReturn(new Issued(token, grant.familyId(), expires));
        });
    }

    /**
     * Ends one session (token family): on reuse, sign-out, a password change, or when a sign-in guard refuses it at a
     * refresh. Revoked already: the first revocation stands.
     */
    public Mono<Void> revokeSession(UUID familyId, String reason) {
        return revokeSession(familyId, reason, null);
    }

    /**
     * As above, by the operation {@code processSeqId} (null: none). By an operation, the insert runs behind a
     * savepoint of the process's transaction: a concurrent revocation of the same family (which stands) must not
     * abort the rest of the process.
     */
    public Mono<Void> revokeSession(UUID familyId, String reason, Long processSeqId) {
        return Mono.defer(() -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("family_id", familyId);
            row.put("revoked_at", now());
            row.put("reason", reason);
            row.put("process_seq_id", processSeqId);
            Mono<Void> insert = engine.get().insert("sec_refresh_family_revocation", row);
            return (processSeqId == null ? insert : engine.get().inSavepoint(insert))
                // Revoked already: the first revocation stands.
                .onErrorResume(UniqueKeyViolationException.class, e -> Mono.empty());
        });
    }

    /** @param used whether the token was consumed already (presenting it again is reuse) */
    private record Found(Grant grant, Instant issuedAt, Instant expiresAt, boolean revoked, boolean used) {}

    private Mono<Found> find(String hash) {
        return engine.get().select("""
                SELECT t.user_id, t.family_id, t.issued_at, t.expires_at, t.mfa_at, t.identity_id, t.entry,
                    r.family_id IS NOT NULL AS revoked,
                    EXISTS (SELECT 1 FROM sec_refresh_token_use u WHERE u.token_hash = t.token_hash) AS used
                FROM sec_refresh_token t LEFT JOIN sec_refresh_family_revocation r ON r.family_id = t.family_id
                WHERE t.token_hash = :hash""", Map.of("hash", BoundValue.of(hash)))
            .next()
            .map(row -> new Found(new Grant(Rows.uuid(row.get("user_id")), Rows.uuid(row.get("family_id")),
                    row.get("mfa_at") == null ? null : Rows.instant(row.get("mfa_at")),
                    row.get("identity_id") == null ? null : Rows.uuid(row.get("identity_id")),
                    row.get("entry") == null ? null : String.valueOf(row.get("entry"))),
                Rows.instant(row.get("issued_at")), Rows.instant(row.get("expires_at")),
                Boolean.TRUE.equals(row.get("revoked")), Boolean.TRUE.equals(row.get("used"))));
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
