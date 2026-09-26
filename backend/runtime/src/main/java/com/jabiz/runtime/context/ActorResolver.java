package com.jabiz.runtime.context;

import org.springframework.http.server.reactive.ServerHttpRequest;

/**
 * Development only: names the actor of a request that carries no access token (see
 * {@link DevHeaderActorResolver}). Outside development the platform's resolver names nobody, so such requests stay
 * anonymous; authenticated actors come from access tokens (docs/design/10-security.md).
 */
@FunctionalInterface
public interface ActorResolver {

    /** Resolves no actor at all. */
    ActorResolver NONE = request -> null;

    /**
     * The actor the request names, or null when it names none.
     *
     * @throws IllegalArgumentException when the request names an actor in a malformed way
     */
    Actor resolve(ServerHttpRequest request);
}
