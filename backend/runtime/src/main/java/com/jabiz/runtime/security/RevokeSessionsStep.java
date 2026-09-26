package com.jabiz.runtime.security;

import com.jabiz.process.ProcessContext;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.UUID;

/**
 * Ends every session (refresh token family) of the user in the context, in the process's transaction: after a new
 * password, sessions opened with the old one must not live on (docs/design/10-security.md section 2). A platform I/O
 * step; access tokens already issued expire by themselves.
 */
@Component
public class RevokeSessionsStep implements StepHandler<RevokeSessionsStep.Metadata, ProcessContext> {

    /**
     * @param userKey context key of the user, an {@link EntityInstance} of {@code SecUser}
     * @param reason  recorded with each revocation
     */
    public record Metadata(String userKey, String reason) {
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
            EntityInstance user = ctx.get(metadata.userKey(), EntityInstance.class);
            return refreshTokens.revokeUser(UUID.fromString(String.valueOf(user.id())), metadata.reason());
        });
    }
}
