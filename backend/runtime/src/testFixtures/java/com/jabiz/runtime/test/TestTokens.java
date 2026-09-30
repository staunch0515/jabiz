package com.jabiz.runtime.test;

import com.jabiz.runtime.context.Actor;
import com.jabiz.runtime.security.JwtService;

import java.util.Set;

/**
 * Access tokens for tests that call the HTTP API outside the dev profile: signed by the application's own
 * {@link JwtService}, so they pass the real authentication.
 */
public final class TestTokens {

    private TestTokens() {}

    /**
     * The value of an {@code Authorization} header for an actor with the given permissions, who has just passed a
     * second factor (docs/design/10-security.md section 10).
     */
    public static String bearer(JwtService tokens, String actorId, String... permissions) {
        return "Bearer " + tokens.issue(new Actor(actorId, null, Set.of(), Set.of(permissions),
            tokens.clock().instant())).token();
    }

    /** As {@link #bearer}, for a session that has not passed a second factor. */
    public static String withoutMfa(JwtService tokens, String actorId, String... permissions) {
        return "Bearer " + tokens.issue(new Actor(actorId, null, Set.of(), Set.of(permissions))).token();
    }
}
