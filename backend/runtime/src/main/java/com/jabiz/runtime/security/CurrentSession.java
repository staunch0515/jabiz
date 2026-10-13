package com.jabiz.runtime.security;

import com.jabiz.context.RequestContext;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.runtime.AuthenticationFailedException;
import com.jabiz.runtime.context.ActorAuthentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.UUID;

/**
 * The session (refresh token family) the caller's access token names (docs/design/10-security.md section 15.9). Kept
 * out of the request context on purpose: only the platform's session endpoints and step-up need it, never a process
 * or a rule.
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

    /**
     * As {@link #id}, once the session named has been found not to have ended (not revoked, not expired, the
     * caller's at the caller's entry): an access token outliving its session must not get a new token (step-up) nor
     * act on sessions. 401 {@code UNAUTHENTICATED} otherwise. A token of no session passes (empty).
     */
    static Mono<Optional<UUID>> open(RefreshTokenStore refreshTokens, RequestContext context) {
        return id().flatMap(session -> {
            if (session.isEmpty()) {
                return Mono.just(session);
            }
            Optional<UUID> user = ActingUser.userId(context);
            if (user.isEmpty()) {
                return Mono.error(ended());
            }
            return refreshTokens.sessionOpen(user.get(), context.entry(), session.get())
                .flatMap(open -> open ? Mono.just(session) : Mono.<Optional<UUID>>error(ended()));
        });
    }

    private static AuthenticationFailedException ended() {
        return new AuthenticationFailedException(PlatformErrorCodes.UNAUTHENTICATED, "The session has ended");
    }
}
