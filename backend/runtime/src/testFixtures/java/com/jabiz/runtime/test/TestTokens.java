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

    /** The value of an {@code Authorization} header for an actor with the given permissions. */
    public static String bearer(JwtService tokens, String actorId, String... permissions) {
        return "Bearer " + tokens.issue(new Actor(actorId, null, Set.of(), Set.of(permissions))).token();
    }
}
