package com.jabiz.security;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What a {@link SignInGuard} decides about (decision D36 item 6).
 *
 * @param userId        the user's id
 * @param userName      the user's name
 * @param entry         the sign-in entry the session is for
 * @param roles         the codes of the user's roles in effect that the entry accepts
 * @param emailVerified whether the user's current e-mail address is verified
 * @param factor        what the attempt proved the user with
 * @param time          when (the operation time, or the refresh time)
 * @param data          the rows of the guard's {@link SignInGuard#loads()}, by load name
 */
public record SignInAttempt(String userId, String userName, String entry, Set<String> roles, boolean emailVerified,
    Factor factor, Instant time, Map<String, List<Map<String, Object>>> data) {

    /** How the attempt proved the user. */
    public enum Factor {
        PASSWORD,
        OIDC,
        TOTP,
        RECOVERY_CODE,
        /** Renewing a session: no credentials, the refresh token stands for the sign-in. */
        REFRESH
    }

    public SignInAttempt {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(entry, "entry must not be null");
        Objects.requireNonNull(factor, "factor must not be null");
        Objects.requireNonNull(time, "time must not be null");
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        Map<String, List<Map<String, Object>>> copy = new LinkedHashMap<>();
        if (data != null) {
            data.forEach((name, rows) -> copy.put(name, rows == null ? List.of() : List.copyOf(rows)));
        }
        data = java.util.Collections.unmodifiableMap(copy);
    }

    /** The rows of the load {@code name}; empty when it found none. */
    public List<Map<String, Object>> rows(String name) {
        return data.getOrDefault(name, List.of());
    }
}
