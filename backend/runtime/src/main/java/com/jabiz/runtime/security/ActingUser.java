package com.jabiz.runtime.security;

import com.jabiz.context.RequestContext;
import com.jabiz.runtime.context.RequestContexts;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The user behind a request, for the entry points that act on the caller's own account (second factor, language,
 * mail preferences), and running work as a user a credential names (a challenge, an unsubscribe link).
 */
public final class ActingUser {

    private ActingUser() {}

    /** The caller as a user of the platform; development header actors and the system are not users. */
    public static Optional<UUID> userId(RequestContext context) {
        if (context == null || context.actorId() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(context.actorId()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Runs work as {@code userId}, with no roles or permissions, in the language and request of the current request:
     * the operation record shows the user as who acted.
     */
    public static <T> Mono<T> as(String userId, Mono<T> work) {
        return RequestContexts.current().flatMap(started -> work.contextWrite(view -> RequestContexts.put(view,
            new RequestContext(userId, null, started.locale(), started.requestId(), Set.of(), Set.of()))));
    }
}
