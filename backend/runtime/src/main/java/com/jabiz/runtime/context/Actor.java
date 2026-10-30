package com.jabiz.runtime.context;

import com.jabiz.context.DataPeriod;
import com.jabiz.context.EntryScope;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The identity part of a {@link com.jabiz.context.RequestContext}, as determined for one request.
 *
 * @param mfaAt         when the actor last passed a second factor in this session, or null
 *                      (docs/design/10-security.md section 9)
 * @param dataPeriod    the span of business time the actor may see, or null when not limited (section 13.2)
 * @param entry         the sign-in entry of the session (section 15; decision D36), or null for an actor that did not
 *                      sign in through one
 * @param emailVerified whether the actor's e-mail address was verified when the session's token was issued
 * @param sessionId     the session (refresh token family) the access token was issued for (section 15.9), or null:
 *                      tokens of no session (development headers, tests) and tokens issued before sessions were listed
 * @param entryScope    the session's entry scope (decision D64; section 13.4), or null for entries without one
 */
public record Actor(String actorId, String tenantId, Set<String> roles, Set<String> permissions, Instant mfaAt,
    DataPeriod dataPeriod, String entry, boolean emailVerified, UUID sessionId, EntryScope entryScope) {

    /** Actor id of requests nobody has authenticated. */
    public static final String ANONYMOUS_ID = com.jabiz.context.RequestContext.ANONYMOUS_ACTOR;

    /** An unauthenticated caller: no tenant, no roles, no permissions. */
    public static final Actor ANONYMOUS = new Actor(ANONYMOUS_ID, null, Set.of(), Set.of());

    /** An actor outside any entry scope. */
    public Actor(String actorId, String tenantId, Set<String> roles, Set<String> permissions, Instant mfaAt,
        DataPeriod dataPeriod, String entry, boolean emailVerified, UUID sessionId) {
        this(actorId, tenantId, roles, permissions, mfaAt, dataPeriod, entry, emailVerified, sessionId, null);
    }

    /** An actor of no known session. */
    public Actor(String actorId, String tenantId, Set<String> roles, Set<String> permissions, Instant mfaAt,
        DataPeriod dataPeriod, String entry, boolean emailVerified) {
        this(actorId, tenantId, roles, permissions, mfaAt, dataPeriod, entry, emailVerified, null);
    }

    /** An actor outside any sign-in entry, whose e-mail address is not known to be verified. */
    public Actor(String actorId, String tenantId, Set<String> roles, Set<String> permissions, Instant mfaAt,
        DataPeriod dataPeriod) {
        this(actorId, tenantId, roles, permissions, mfaAt, dataPeriod, null, false);
    }

    /** An actor not limited in time. */
    public Actor(String actorId, String tenantId, Set<String> roles, Set<String> permissions, Instant mfaAt) {
        this(actorId, tenantId, roles, permissions, mfaAt, null);
    }

    /** An actor who has not passed a second factor. */
    public Actor(String actorId, String tenantId, Set<String> roles, Set<String> permissions) {
        this(actorId, tenantId, roles, permissions, null);
    }

    public Actor {
        Objects.requireNonNull(actorId, "actorId must not be null");
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    /** This actor, having passed a second factor at {@code time}. */
    public Actor withMfaAt(Instant time) {
        return new Actor(actorId, tenantId, roles, permissions, time, dataPeriod, entry, emailVerified, sessionId,
            entryScope);
    }

    /** This actor in the sign-in entry {@code entry}, with its e-mail address verified or not. */
    public Actor inEntry(String entry, boolean emailVerified) {
        return new Actor(actorId, tenantId, roles, permissions, mfaAt, dataPeriod, entry, emailVerified, sessionId,
            entryScope);
    }

    /** This actor in the entry scope {@code scope} (null: none). */
    public Actor inScope(EntryScope scope) {
        return new Actor(actorId, tenantId, roles, permissions, mfaAt, dataPeriod, entry, emailVerified, sessionId,
            scope);
    }

    /** This actor in the session {@code sessionId} (null: none known). */
    public Actor inSession(UUID sessionId) {
        return new Actor(actorId, tenantId, roles, permissions, mfaAt, dataPeriod, entry, emailVerified, sessionId,
            entryScope);
    }
}
