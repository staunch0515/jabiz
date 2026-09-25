package com.jabiz.runtime.context;

import org.springframework.http.server.reactive.ServerHttpRequest;

/**
 * Determines who sent a request. Authentication arrives in ROADMAP phase 7; until then the platform
 * treats every caller as {@link Actor#ANONYMOUS}, except in development (see {@link DevHeaderActorResolver}).
 */
@FunctionalInterface
public interface ActorResolver {
    Actor resolve(ServerHttpRequest request);
}
