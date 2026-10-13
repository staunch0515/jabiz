package com.jabiz.runtime.security;

import com.jabiz.runtime.context.ActorAuthentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.UUID;

/**
 * The session (refresh token family) the caller's access token names (docs/design/10-security.md section 15.9). Kept
 * out of the request context on purpose: only the platform's session endpoints need it, never a process or a rule.
 */
final class CurrentSession {

    private CurrentSession() {}

    /** The caller's session; empty for a caller of no session (no token, a token of no session, development). */
    static Mono<Optional<UUID>> id() {
        return ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .filter(ActorAuthentication.class::isInstance)
            .map(authentication -> Optional.ofNullable(((ActorAuthentication) authentication).actor().sessionId()))
            .defaultIfEmpty(Optional.empty());
    }
}
