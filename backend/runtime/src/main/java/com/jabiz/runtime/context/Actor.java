package com.jabiz.runtime.context;

import java.util.Objects;
import java.util.Set;

/** The identity part of a {@link com.jabiz.context.RequestContext}, as determined for one request. */
public record Actor(String actorId, String tenantId, Set<String> roles, Set<String> permissions) {

    /** Actor id of requests nobody has authenticated. */
    public static final String ANONYMOUS_ID = "anonymous";

    /** An unauthenticated caller: no tenant, no roles, no permissions. */
    public static final Actor ANONYMOUS = new Actor(ANONYMOUS_ID, null, Set.of(), Set.of());

    public Actor {
        Objects.requireNonNull(actorId, "actorId must not be null");
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }
}
