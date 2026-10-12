package com.jabiz.runtime.security;

import com.jabiz.process.ProcessContext;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Ends every session (refresh token family) of the user in the context, in the process's transaction: after a new
 * password, sessions opened with the old one must not live on (docs/design/10-security.md section 2). A platform I/O
 * step; access tokens already issued expire by themselves.
 *
 * <p>With a key of a refresh token, the session that token is a live token of (the user's own, at the entry of the
 * request) stays: a user changing their own password keeps the session they changed it in (section 15.8). Any other
 * token, or none, keeps nothing.
 */
@Component
public class RevokeSessionsStep implements StepHandler<RevokeSessionsStep.Metadata, ProcessContext> {

    /**
     * @param userKey     context key of the user, an {@link EntityInstance} of {@code SecUser}
     * @param reason      recorded with each revocation
     * @param when        whether to end them at all (null: always)
     * @param keepToken   context key of a refresh token whose session stays (null: none); the step removes it
     */
    public record Metadata(String userKey, String reason, Predicate<ProcessContext> when, String keepToken) {

        public Metadata(String userKey, String reason) {
            this(userKey, reason, null);
        }

        public Metadata(String userKey, String reason, Predicate<ProcessContext> when) {
            this(userKey, reason, when, null);
        }

        public Metadata {
            Objects.requireNonNull(userKey, "userKey must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    private final RefreshTokenStore refreshTokens;

    public RevokeSessionsStep(RefreshTokenStore refreshTokens) {
        this.refreshTokens = refreshTokens;
    }

    @Override
    public Mono<Void> execute(Metadata metadata, ProcessContext ctx) {
        return Mono.defer(() -> {
            String token = metadata.keepToken() == null ? null : ctx.get(metadata.keepToken(), String.class);
            if (metadata.keepToken() != null) {
                // The token is a credential: it stays in the context no longer than needed.
                ctx.put(metadata.keepToken(), null);
            }
            if (metadata.when() != null && !metadata.when().test(ctx)) {
                return Mono.empty();
            }
            EntityInstance user = ctx.get(metadata.userKey(), EntityInstance.class);
            UUID userId = UUID.fromString(String.valueOf(user.id()));
            if (token == null) {
                return refreshTokens.revokeUser(userId, metadata.reason());
            }
            return refreshTokens.liveSession(token, userId, ctx.request().entry())
                .map(java.util.Optional::of)
                .defaultIfEmpty(java.util.Optional.empty())
                .flatMap(keep -> refreshTokens.revokeUser(userId, metadata.reason(), keep.orElse(null)));
        });
    }
}
