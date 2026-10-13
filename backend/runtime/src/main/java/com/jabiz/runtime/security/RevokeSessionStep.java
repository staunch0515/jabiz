package com.jabiz.runtime.security;

import com.jabiz.context.RequestContext;
import com.jabiz.process.ProcessContext;
import com.jabiz.runtime.process.StepHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.UUID;

/**
 * Ends sessions picked from the session list (docs/design/10-security.md section 15.9), in the process's transaction:
 * one live session of a user, or all of them but one (or all). Each revocation records the reason and the operation
 * ({@code process_seq_id}). Only live sessions count; a session that is not one of the user's live ones (at the
 * caller's entry, when limited to it) is 404 {@code SESSION_NOT_FOUND}, whatever the reason. A platform I/O step; the
 * access tokens already issued expire by themselves.
 */
@Component
public class RevokeSessionStep implements StepHandler<RevokeSessionStep.Metadata, ProcessContext> {

    private static final Logger log = LoggerFactory.getLogger(RevokeSessionStep.class);

    /** Which sessions end. */
    public enum Scope {
        /** The session named. */
        ONE,
        /** Every live session but the one named (none named: every one). */
        ALL_BUT,
        /** The session named, or every live session when none is named. */
        ONE_OR_ALL
    }

    /**
     * @param userKey     context key of the user's id ({@link UUID} or its text)
     * @param sessionKey  context key of the session named ({@link UUID}; absent or null: none)
     * @param scope       which sessions end
     * @param callerEntry whether only sessions of the caller's sign-in entry count (a user's own; decision D36)
     * @param reason      recorded with each revocation
     * @param countKey    context key the number of sessions ended is put under
     */
    public record Metadata(String userKey, String sessionKey, Scope scope, boolean callerEntry, String reason,
        String countKey) {
        public Metadata {
            Objects.requireNonNull(userKey, "userKey must not be null");
            Objects.requireNonNull(scope, "scope must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(countKey, "countKey must not be null");
            if (scope == Scope.ONE && sessionKey == null) {
                throw new IllegalArgumentException("Ending one session needs the key of its id");
            }
        }
    }

    private final RefreshTokenStore refreshTokens;

    public RevokeSessionStep(RefreshTokenStore refreshTokens) {
        this.refreshTokens = refreshTokens;
    }

    @Override
    public Mono<Void> execute(Metadata metadata, ProcessContext ctx) {
        return Mono.defer(() -> {
            UUID user = uuid(ctx.get(metadata.userKey()));
            UUID session = metadata.sessionKey() == null ? null : uuid(ctx.get(metadata.sessionKey()));
            String entry = !metadata.callerEntry() ? null
                : ctx.request().entry() == null ? RequestContext.DEFAULT_ENTRY : ctx.request().entry();
            long operation = ctx.processSeqId();
            if (metadata.scope() == Scope.ONE && session == null) {
                return Mono.error(new SessionNotFoundException());
            }
            if (metadata.scope() == Scope.ONE || (metadata.scope() == Scope.ONE_OR_ALL && session != null)) {
                return refreshTokens.liveSession(user, entry, session)
                    .switchIfEmpty(Mono.error(SessionNotFoundException::new))
                    .flatMap(live -> refreshTokens.revokeSession(live.id(), metadata.reason(), operation))
                    .then(Mono.fromRunnable(() -> {
                        log.info("Session {} of user {} ended ({})", session, user, metadata.reason());
                        ctx.put(metadata.countKey(), 1);
                    }));
            }
            UUID keep = metadata.scope() == Scope.ALL_BUT ? session : null;
            return refreshTokens.liveSessions(user, entry)
                .filter(live -> !live.id().equals(keep))
                .concatMap(live -> refreshTokens.revokeSession(live.id(), metadata.reason(), operation)
                    .thenReturn(live.id()))
                .count()
                .doOnNext(count -> {
                    log.info("{} sessions of user {} ended ({})", count, user, metadata.reason());
                    ctx.put(metadata.countKey(), count.intValue());
                })
                .then();
        });
    }

    private static UUID uuid(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof UUID id ? id : UUID.fromString(String.valueOf(value));
    }
}
