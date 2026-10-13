package com.jabiz.runtime.security;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.security.UserAgentLabels;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The session list (docs/design/10-security.md section 15.9; ROADMAP phase 16g). A signed-in user lists their live
 * sessions at the sign-in entry of the one they use (decision D36: a token does not reach beyond its frontend) and ends
 * one of them, or all but their own; an administrator lists a user's sessions at every entry
 * ({@link SecurityPermissions#SESSION_READ}) and ends them with {@value SessionProcesses#USER_REVOKE_NAME} through the
 * process API. Ending a session stops its refreshes at once; its access token lives on until it expires, at most
 * {@code effectiveWithinSeconds} (decision D12), but it no longer lists or ends sessions here (401).
 */
@RestController
class SessionController {

    static final String ACCOUNT_SESSIONS = "/api/auth/account/sessions";
    static final String USER_SESSIONS = "/api/security/sessions";

    /** The most sessions listed, the most recently used first. */
    static final int MAX_SESSIONS = 50;

    /** The kind of client a session was last used from, told from its user agent ({@link UserAgentLabels}). */
    record Device(UserAgentLabels.Browser browser, UserAgentLabels.Os os, UserAgentLabels.DeviceType deviceType) {}

    /**
     * One live session.
     *
     * @param id         the session's id (no credential)
     * @param current    whether it is the caller's own (always false in an administrator's list)
     * @param lastUsedAt when it was last signed in or refreshed
     * @param expiresAt  when it ends unless refreshed (an entry's idle timeout may end it before)
     * @param clientIp   the client's address at its last use, or null
     * @param userAgent  the client's user agent at its last use, as sent (cleaned and cut), or null
     * @param mfa        whether its sign-in passed a second factor
     * @param oidc       whether it was signed in through an identity provider
     */
    record SessionView(String id, String entry, boolean current, Instant createdAt, Instant lastUsedAt,
        Instant expiresAt, String clientIp, String userAgent, Device device, boolean mfa, boolean oidc) {}

    /**
     * @param truncated              whether there are more live sessions than listed
     * @param effectiveWithinSeconds the longest an ended session's access token still works (its lifetime)
     */
    record SessionList(List<SessionView> sessions, boolean truncated, long effectiveWithinSeconds) {}

    /** @param revoked how many sessions ended */
    record Revoked(int revoked, long effectiveWithinSeconds) {}

    private final ProcessExecutor processes;
    private final RefreshTokenStore refreshTokens;
    private final JwtService tokens;
    private final SignUpRateLimits limits;

    SessionController(ProcessExecutor processes, RefreshTokenStore refreshTokens, JwtService tokens,
        SignUpRateLimits limits) {
        this.processes = processes;
        this.refreshTokens = refreshTokens;
        this.tokens = tokens;
        this.limits = limits;
    }

    /** The caller's live sessions at the entry of the one they use. */
    @GetMapping(ACCOUNT_SESSIONS)
    Mono<SessionList> mine() {
        return RequestContexts.current().flatMap(context -> CurrentSession.open(refreshTokens, context)
            .map(session -> reactor.util.function.Tuples.of(context, session))).flatMap(current -> {
            UUID user = requireUser(current.getT1());
            return list(user, entryOf(current.getT1()), current.getT2().orElse(null));
        });
    }

    /**
     * Ends one of the caller's live sessions at their entry (their own too, which signs them out): 204. Any other id
     * is 404 {@code SESSION_NOT_FOUND}. Counted per user (429 {@code RATE_LIMITED}).
     */
    @PostMapping(ACCOUNT_SESSIONS + "/{id}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> revoke(@PathVariable("id") String id) {
        // An access token outliving its own session acts on no session (10 section 15.9).
        return RequestContexts.current().flatMap(context -> CurrentSession.open(refreshTokens, context)
            .then(Mono.defer(() -> {
                UUID user = requireUser(context);
                limits.sessionRevoke(user.toString());
                UUID session = parse(id).orElseThrow(SessionNotFoundException::new);
                return processes.executeAtEntry(SessionProcesses.SESSION_REVOKE,
                    new SessionProcesses.SessionInput(user.toString(), session)).then();
            })));
    }

    /**
     * Ends every other live session of the caller at their entry. A caller whose access token names no session
     * cannot tell which one is theirs: 422 {@code SESSION_UNKNOWN} (signing in again gives one that does).
     */
    @PostMapping(ACCOUNT_SESSIONS + "/revoke-others")
    Mono<Revoked> revokeOthers() {
        return RequestContexts.current().flatMap(context -> CurrentSession.open(refreshTokens, context)
            .map(session -> reactor.util.function.Tuples.of(context, session))).flatMap(current -> {
            UUID user = requireUser(current.getT1());
            limits.sessionRevoke(user.toString());
            UUID keep = current.getT2().orElseThrow(() -> new BusinessRuleViolationException(new Violation(null,
                PlatformErrorCodes.SESSION_UNKNOWN, "The access token names no session; sign in again")));
            return processes.executeAtEntry(SessionProcesses.OTHERS_REVOKE,
                    new SessionProcesses.OthersInput(user.toString(), keep))
                .map(output -> new Revoked(output.revoked(), effectiveWithin()));
        });
    }

    /** A user's live sessions at every entry, for an administrator. */
    @GetMapping(USER_SESSIONS)
    Mono<SessionList> ofUser(@RequestParam("userId") UUID userId) {
        return RequestContexts.current().flatMap(context -> {
            Permissions.require(context, SecurityPermissions.SESSION_READ, "Listing a user's sessions");
            return list(userId, null, null);
        });
    }

    private Mono<SessionList> list(UUID user, String entry, UUID current) {
        return refreshTokens.liveSessions(user, entry)
            .take(MAX_SESSIONS + 1L)
            .map(session -> view(session, session.id().equals(current)))
            .collectList()
            .map(views -> new SessionList(List.copyOf(views.subList(0, Math.min(views.size(), MAX_SESSIONS))),
                views.size() > MAX_SESSIONS, effectiveWithin()));
    }

    private static SessionView view(RefreshTokenStore.Session session, boolean current) {
        UserAgentLabels.Labels labels = UserAgentLabels.of(session.userAgent());
        return new SessionView(session.id().toString(), session.entry(), current, session.createdAt(),
            session.lastUsedAt(), session.expiresAt(), session.clientIp(), session.userAgent(),
            new Device(labels.browser(), labels.os(), labels.deviceType()), session.mfaAt() != null, session.oidc());
    }

    private long effectiveWithin() {
        return tokens.ttl().toSeconds();
    }

    private static String entryOf(RequestContext context) {
        return context.entry() == null ? RequestContext.DEFAULT_ENTRY : context.entry();
    }

    private static UUID requireUser(RequestContext context) {
        return ActingUser.userId(context).orElseThrow(() -> new PermissionDeniedException(SecurityPermissions.ACCOUNT,
            "Only users of the platform have sessions"));
    }

    private static Optional<UUID> parse(String id) {
        try {
            return Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }
}
