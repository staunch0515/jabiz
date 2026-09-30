package com.jabiz.runtime.context;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;

import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Development only: takes the actor from request headers, so that permissions and scopes can be tried out without
 * signing in. Anyone can send these headers, which is why the resolver can only be enabled together with the
 * {@code dev} profile (see {@link RequestContextConfig}); a request with an access token is authenticated by the token
 * alone.
 */
public final class DevHeaderActorResolver implements ActorResolver {

    private final java.time.Clock clock;

    /** Development actors count as having just passed a second factor (docs/design/10-security.md section 10). */
    public DevHeaderActorResolver(java.time.Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock must not be null");
    }

    public static final String ACTOR_HEADER = "X-Jabiz-Actor";
    public static final String TENANT_HEADER = "X-Jabiz-Tenant";
    public static final String ROLES_HEADER = "X-Jabiz-Roles";
    public static final String PERMISSIONS_HEADER = "X-Jabiz-Permissions";
    /** Ends of the actor's data period, ISO-8601 instants (docs/design/10-security.md section 13.2). */
    public static final String DATA_FROM_HEADER = "X-Jabiz-Data-From";
    public static final String DATA_TO_HEADER = "X-Jabiz-Data-To";

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._:@*-]{1,128}");

    @Override
    public Actor resolve(ServerHttpRequest request) {
        HttpHeaders headers = request.getHeaders();
        String actorId = token(headers.getFirst(ACTOR_HEADER), ACTOR_HEADER);
        if (actorId == null) {
            return null;
        }
        return new Actor(actorId,
            token(headers.getFirst(TENANT_HEADER), TENANT_HEADER),
            tokens(headers.getFirst(ROLES_HEADER), ROLES_HEADER),
            tokens(headers.getFirst(PERMISSIONS_HEADER), PERMISSIONS_HEADER), clock.instant(),
            com.jabiz.context.DataPeriod.of(instant(headers.getFirst(DATA_FROM_HEADER), DATA_FROM_HEADER),
                instant(headers.getFirst(DATA_TO_HEADER), DATA_TO_HEADER)));
    }

    private static java.time.Instant instant(String value, String header) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.time.Instant.parse(value.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid value in header " + header);
        }
    }

    private static String token(String value, String header) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (!TOKEN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid value in header " + header);
        }
        return trimmed;
    }

    private static Set<String> tokens(String value, String header) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
            .map(part -> token(part, header))
            .filter(part -> part != null)
            .collect(Collectors.toUnmodifiableSet());
    }
}
