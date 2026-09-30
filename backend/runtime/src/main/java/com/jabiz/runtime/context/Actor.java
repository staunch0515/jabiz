package com.jabiz.runtime.context;

import com.jabiz.context.DataPeriod;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * The identity part of a {@link com.jabiz.context.RequestContext}, as determined for one request.
 *
 * @param mfaAt      when the actor last passed a second factor in this session, or null
 *                   (docs/design/10-security.md section 9)
 * @param dataPeriod the span of business time the actor may see, or null when not limited (section 13.2)
 */
public record Actor(String actorId, String tenantId, Set<String> roles, Set<String> permissions, Instant mfaAt,
    DataPeriod dataPeriod) {

    /** Actor id of requests nobody has authenticated. */
    public static final String ANONYMOUS_ID = com.jabiz.context.RequestContext.ANONYMOUS_ACTOR;

    /** An unauthenticated caller: no tenant, no roles, no permissions. */
    public static final Actor ANONYMOUS = new Actor(ANONYMOUS_ID, null, Set.of(), Set.of());

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
        return new Actor(actorId, tenantId, roles, permissions, time, dataPeriod);
    }
}
