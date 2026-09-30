package com.jabiz.context;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Who is acting, for which tenant, in which language, as part of which request
 * (docs/design/01-core-vs-runtime.md section 5).
 *
 * <p>The runtime builds one per request and hands it to synchronous extension points through
 * {@link com.jabiz.entity.ValidationContext}. Code running outside a request (scheduled jobs, startup
 * work) uses {@link #system}; the platform never invents an identity when none was supplied.
 *
 * @param actorId     the acting user, or {@link #SYSTEM_ACTOR} for platform-initiated work
 * @param tenantId    tenant of the actor; null when the deployment is not multi-tenant
 * @param locale      language of error messages and dictionary labels
 * @param requestId   correlation id, also written to every log line of the request
 * @param roles       roles of the actor
 * @param permissions permission codes granted to the actor
 * @param mfaAt       when the actor last passed a second factor in this session, or null
 *                    (docs/design/10-security.md section 9)
 * @param dataPeriod  the span of business time whose data the actor may see in datasets declaring
 *                    {@code withinDataPeriod}, or null when the actor is not limited in time
 *                    (docs/design/10-security.md section 13.2)
 */
public record RequestContext(
    String actorId,
    String tenantId,
    Locale locale,
    String requestId,
    Set<String> roles,
    Set<String> permissions,
    Instant mfaAt,
    DataPeriod dataPeriod
) {

    /** Actor id of work the platform performs on its own behalf. */
    public static final String SYSTEM_ACTOR = "system";

    /** Actor id of requests nobody has authenticated. */
    public static final String ANONYMOUS_ACTOR = "anonymous";

    /** Permission code that grants every permission (administrators, scenario replays). */
    public static final String ALL_PERMISSIONS = "*";

    /** A context of an actor not limited in time. */
    public RequestContext(String actorId, String tenantId, Locale locale, String requestId, Set<String> roles,
        Set<String> permissions, Instant mfaAt) {
        this(actorId, tenantId, locale, requestId, roles, permissions, mfaAt, null);
    }

    /** A context of an actor who has not passed a second factor. */
    public RequestContext(String actorId, String tenantId, Locale locale, String requestId, Set<String> roles,
        Set<String> permissions) {
        this(actorId, tenantId, locale, requestId, roles, permissions, null);
    }

    public RequestContext {
        requireText(actorId, "actorId");
        Objects.requireNonNull(locale, "locale must not be null");
        requireText(requestId, "requestId");
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    /** Context for platform-initiated work: the system actor, without roles or permissions. */
    public static RequestContext system(Locale locale, String requestId) {
        return new RequestContext(SYSTEM_ACTOR, null, locale, requestId, Set.of(), Set.of());
    }

    /**
     * Context of an anonymous visitor reading public data (docs/design/15-public-access.md section 5): no tenant,
     * roles or permissions, whatever credentials the request carried.
     */
    public static RequestContext anonymous(Locale locale, String requestId) {
        return new RequestContext(ANONYMOUS_ACTOR, null, locale, requestId, Set.of(), Set.of());
    }

    /** Whether the actor holds the permission, directly or through {@link #ALL_PERMISSIONS}. */
    public boolean hasPermission(String permission) {
        return permissions.contains(permission) || permissions.contains(ALL_PERMISSIONS);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
