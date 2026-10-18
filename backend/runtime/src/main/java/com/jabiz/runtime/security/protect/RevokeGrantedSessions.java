package com.jabiz.runtime.security.protect;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.security.RefreshTokenStore;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Ends every session of the users an applied protected grant is for (decision D48, review M3), in the applying
 * transaction: a session opened before the grant - possibly by whoever set the account up - does not carry the new
 * power on; the users sign in again. Access tokens already issued expire by themselves (10 section 2).
 */
@Component
class RevokeGrantedSessions implements StepHandler<RevokeGrantedSessions.Metadata, ProcessContext> {

    /** The reason recorded with the revocations. */
    static final String REASON = "PROTECTED_GRANT";

    /** @param usersKey context key of the users ({@code List<UUID>}); nothing happens when absent */
    record Metadata(String usersKey) {}

    static StepSpec<Metadata, ProcessContext> of(String usersKey) {
        return StepSpec.of(RevokeGrantedSessions.class, new Metadata(usersKey));
    }

    private final RefreshTokenStore tokens;

    RevokeGrantedSessions(RefreshTokenStore tokens) {
        this.tokens = tokens;
    }

    @Override
    public Mono<Void> execute(Metadata metadata, ProcessContext ctx) {
        return Mono.defer(() -> {
            if (!ctx.contains(metadata.usersKey())) {
                return Mono.empty();
            }
            @SuppressWarnings("unchecked")
            List<UUID> users = (List<UUID>) ctx.get(metadata.usersKey());
            return Flux.fromIterable(users)
                .concatMap(user -> tokens.revokeUser(user, REASON, null, ctx.processSeqId())).then();
        });
    }
}
