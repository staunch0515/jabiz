package com.jabiz.runtime.security;

import com.jabiz.entity.ValidationException;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.runtime.AuthenticationFailedException;
import com.jabiz.runtime.context.Actor;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.sponsor.SignInSource;
import com.jabiz.runtime.process.sponsor.SponsorSignInInput;
import com.jabiz.runtime.process.sponsor.SponsorSignInOutput;
import com.jabiz.runtime.process.sponsor.SponsorSignInProcess;
import com.jabiz.runtime.web.ClientAddresses;
import com.jabiz.security.Sensitive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Sessions (docs/design/10-security.md sections 2 and 15; decisions D12 and D36). Signing in runs
 * {@value SponsorSignInProcess#NAME} for a sign-in entry (the administration's when none is named) and answers every
 * refusal alike (401 {@code LOGIN_FAILED}), except the two that follow a right password: a guard's refusal (403
 * {@code SIGN_IN_REFUSED}) and an address the entry requires verified (403 {@code EMAIL_NOT_VERIFIED}). A success
 * returns a short-lived access token and a single-use refresh token, both of the entry. Refreshing consumes the refresh
 * token, re-reads the user's roles and returns a new pair of the same entry; signing out ends the session of a refresh
 * token. {@code /me} and {@code /menus} describe the authenticated actor.
 */
@RestController
class AuthController {

    static final String LOGIN = "/api/auth/login";
    static final String REFRESH = "/api/auth/refresh";
    static final String LOGOUT = "/api/auth/logout";

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    /**
     * @param userName the user name, or a verified e-mail address
     * @param entry    the sign-in entry (decision D36); none: the administration ({@code admin})
     */
    record LoginRequest(String userName, @Sensitive String password, String entry) {
        @Override
        public String toString() {
            return "LoginRequest[userName=" + userName + ", password=***, entry=" + entry + "]";
        }
    }

    /** @param entry the sign-in entry of the client; it must be the session's (none: {@code admin}) */
    record RefreshRequest(@Sensitive String refreshToken, String entry) {
        @Override
        public String toString() {
            return "RefreshRequest[***]";
        }
    }

    /** How far a sign-in got (docs/design/10-security.md section 9). */
    enum SignInStatus {
        /** Signed in: the tokens are there. */
        SIGNED_IN,
        /** The password was right; the second factor comes next, with the challenge. */
        MFA_REQUIRED,
        /** The password was right; a role requires a second factor, to be set up with the challenge first. */
        MFA_ENROLLMENT_REQUIRED
    }

    /**
     * Tokens of a session, or the challenge of its second step. Exactly one of the two is present, as
     * {@code status} tells.
     */
    record TokenResponse(SignInStatus status, String tokenType, String accessToken, Instant accessTokenExpiresAt,
        String refreshToken, Instant refreshTokenExpiresAt, String userId, List<String> roles,
        List<String> permissions, String challenge, Instant challengeExpiresAt) {
        @Override
        public String toString() {
            return "TokenResponse[status=" + status + ", userId=" + userId + ", tokens=***]";
        }

        static TokenResponse challenge(SignInStatus status, JwtService.Issued challenge) {
            return new TokenResponse(status, null, null, null, null, null, null, List.of(), List.of(),
                challenge.token(), challenge.expiresAt());
        }
    }

    /**
     * @param mfaAt              when the session last passed a second factor, or null
     * @param idleTimeoutSeconds after how long without activity the client locks the session (section 11)
     * @param dataFrom           start of the data period the session is limited to, or null (section 13.2)
     * @param dataTo             its (exclusive) end, or null; both null: not limited in time
     * @param entry              the sign-in entry of the session (section 15)
     * @param email              the user's own e-mail address, or null
     * @param emailVerified      whether it was verified when the session's access token was issued
     */
    record Me(String userId, String displayName, String tenantId, List<String> roles, List<String> permissions,
        Instant mfaAt, long idleTimeoutSeconds, Instant dataFrom, Instant dataTo, String entry, String email,
        boolean emailVerified) {}

    private final ProcessExecutor processes;
    private final JwtService tokens;
    private final RefreshTokenStore refreshTokens;
    private final RbacService rbac;
    private final MenuService menus;
    private final MfaSettings mfa;
    private final UserNames userNames;
    private final SignInEntries entries;
    private final ClientAddresses clients;
    private final PlatformObservations observations;

    AuthController(ProcessExecutor processes, JwtService tokens, RefreshTokenStore refreshTokens, RbacService rbac,
        MenuService menus, MfaSettings mfa, UserNames userNames, SignInEntries entries, ClientAddresses clients,
        PlatformObservations observations) {
        this.processes = processes;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.rbac = rbac;
        this.menus = menus;
        this.mfa = mfa;
        this.userNames = userNames;
        this.entries = entries;
        this.clients = clients;
        this.observations = observations;
    }

    @PostMapping(LOGIN)
    Mono<TokenResponse> login(@RequestBody(required = false) LoginRequest request, ServerHttpRequest http) {
        return Mono.defer(() -> {
            if (request == null || blank(request.userName()) || blank(request.password())) {
                return Mono.error(loginFailed("Missing user name or password"));
            }
            java.util.Optional<SignInEntries.Entry> entry = entries.find(request.entry());
            if (entry.isEmpty()) {
                return Mono.error(loginFailed("Unknown sign-in entry"));
            }
            SignInSource source = source(clients, http, entry.get().name());
            return processes.execute(SponsorSignInProcess.DEFINITION,
                    new SponsorSignInInput(request.userName().trim(), request.password(), source))
                // Concurrent attempts on one account: the later one loses the race for the next login record.
                .onErrorMap(e -> e instanceof ValidationException v && v.violations().stream()
                        .anyMatch(violation -> PlatformErrorCodes.UNIQUE_VIOLATION.equals(violation.ruleCode())),
                    e -> loginFailed("Concurrent sign-in attempt"))
                .flatMap(result -> answer(result, null, null, source.entryOrDefault(), refreshTokens, tokens, mfa,
                    "Sign-in of '" + request.userName().trim() + "'"));
        });
    }

    /**
     * The answer to a finished sign-in, shared by every path (password, identity provider): tokens, a challenge, or
     * the refusal. Only refusals after a right password are told apart (403); every other one is 401
     * {@code LOGIN_FAILED}.
     *
     * @param mfaAt      when the attempt passed a second factor, or null
     * @param identityId the provider account of the sign-in, or null
     */
    static Mono<TokenResponse> answer(SponsorSignInOutput result, Instant mfaAt, UUID identityId, String entry,
        RefreshTokenStore refreshTokens, JwtService tokens, MfaSettings mfa, String what) {
        return switch (result.outcome()) {
            case SUCCESS -> session(refreshTokens, tokens, actor(result, mfaAt), UUID.fromString(result.userId()),
                identityId);
            case MFA_REQUIRED -> Mono.just(TokenResponse.challenge(SignInStatus.MFA_REQUIRED,
                tokens.issueChallenge(result.userId(), JwtService.Purpose.VERIFY, result.attemptNo(),
                    identityId == null ? null : identityId.toString(), entry, mfa.challengeTtl())));
            case MFA_ENROLLMENT_REQUIRED -> Mono.just(TokenResponse.challenge(SignInStatus.MFA_ENROLLMENT_REQUIRED,
                tokens.issueChallenge(result.userId(), JwtService.Purpose.ENROLL, result.attemptNo(), null, entry,
                    mfa.challengeTtl())));
            default -> {
                log.info("{} refused: {}", what, result.outcome());
                yield Mono.error(refusal(result));
            }
        };
    }

    /** The error a refused sign-in is answered with (decision D36 implementation note 2). */
    static RuntimeException refusal(SponsorSignInOutput result) {
        return switch (result.outcome()) {
            case REFUSED -> new SignInRefusedException("Sign-in refused");
            case EMAIL_NOT_VERIFIED -> new EmailNotVerifiedException("Signing in here requires a verified e-mail "
                + "address");
            default -> loginFailed("Sign-in refused");
        };
    }

    @PostMapping(REFRESH)
    Mono<TokenResponse> refresh(@RequestBody(required = false) RefreshRequest request) {
        // Consuming the old token, checking the user and issuing the next token form one transaction.
        return Mono.defer(() -> {
            java.util.Optional<SignInEntries.Entry> entry = entries.find(request == null ? null : request.entry());
            if (entry.isEmpty()) {
                return Mono.error(invalidRefresh("Unknown sign-in entry"));
            }
            return refreshTokens.rotate(request == null ? null : request.refreshToken(), entry.get().name(),
                    grant -> rbac.renew(grant.userId(), grant.mfaAt(), grant.identityId(), grant.entry())
                        .flatMap(renewal -> {
                            if (renewal.refused()) {
                                return Mono.error(new RefusedAtRefresh(grant));
                            }
                            return renewal.granted().map(Mono::just)
                                .orElseGet(() -> Mono.error(invalidRefresh("The user can no longer sign in")));
                        }))
                .onErrorMap(RefreshTokenStore.InvalidRefreshTokenException.class, e -> invalidRefresh(e.getMessage()))
                // A guard refused: nothing was consumed (the transaction rolled back); the session ends on its own.
                .onErrorResume(RefusedAtRefresh.class, refused -> {
                    log.info("Sign-in guard refused the session of user {} at a refresh; ending it",
                        refused.grant.userId());
                    observations.event(PlatformObservations.AUTH_REFRESH_REFUSED);
                    return refreshTokens.revokeSession(refused.grant.familyId(), RefreshTokenStore.REASON_REFUSED)
                        .then(Mono.error(invalidRefresh("Refused by a sign-in guard")));
                })
                .map(rotated -> response(tokens.issue(rotated.value()), rotated.value(), rotated.next()));
        });
    }

    /** A guard refused a refresh: carries the session to end once the transaction has rolled back. */
    private static final class RefusedAtRefresh extends RuntimeException {
        private final RefreshTokenStore.Grant grant;

        RefusedAtRefresh(RefreshTokenStore.Grant grant) {
            super("Refused by a sign-in guard", null, false, false);
            this.grant = grant;
        }
    }

    @PostMapping(LOGOUT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> logout(@RequestBody(required = false) RefreshRequest request) {
        return Mono.defer(() -> refreshTokens.revoke(request == null ? null : request.refreshToken()))
            .onErrorResume(RefreshTokenStore.InvalidRefreshTokenException.class, e -> Mono.empty());
    }

    @GetMapping("/api/auth/me")
    Mono<Me> me() {
        // The name shown for the signed-in user; none for an actor that is no user (the development headers').
        return RequestContexts.current().flatMap(context -> userNames.self(context.actorId())
            // A name is a convenience: the identity is still answered when it cannot be read.
            .onErrorReturn(new UserNames.Self("", null))
            .map(self -> new Me(context.actorId(), self.name().isEmpty() ? null : self.name(), context.tenantId(),
                context.roles().stream().sorted().toList(), context.permissions().stream().sorted().toList(),
                context.mfaAt(), mfa.idleTimeout().toSeconds(),
                context.dataPeriod() == null ? null : context.dataPeriod().from(),
                context.dataPeriod() == null ? null : context.dataPeriod().to(),
                context.entry(), self.email(), context.emailVerified())));
    }

    @GetMapping("/api/auth/menus")
    Mono<List<MenuService.MenuItem>> menus() {
        return RequestContexts.current().flatMap(menus::menuOf);
    }

    /** Where a sign-in comes from: the entry asked for, the client's address and user agent (decision D36 item 7). */
    static SignInSource source(ClientAddresses clients, ServerHttpRequest http, String entry) {
        return new SignInSource(entry, http == null ? null : clients.addressOf(http), userAgent(http));
    }

    /**
     * The user agent, or null; a header the firewall rejects (control characters) is left out rather than failing
     * the sign-in.
     */
    private static String userAgent(ServerHttpRequest http) {
        if (http == null) {
            return null;
        }
        try {
            return http.getHeaders().getFirst(HttpHeaders.USER_AGENT);
        } catch (RuntimeException rejected) {
            return null;
        }
    }

    /** Tokens of a new session; the refresh tokens of the session remember when it passed a second factor. */
    static Mono<TokenResponse> session(RefreshTokenStore refreshTokens, JwtService tokens, Actor actor, UUID userId) {
        return session(refreshTokens, tokens, actor, userId, null);
    }

    /** As {@link #session}, for a sign-in through the provider account {@code identityId}. */
    static Mono<TokenResponse> session(RefreshTokenStore refreshTokens, JwtService tokens, Actor actor, UUID userId,
        UUID identityId) {
        return refreshTokens.issue(userId, actor.mfaAt(), identityId, actor.entry())
            .map(next -> response(tokens.issue(actor), actor, next));
    }

    private static TokenResponse response(JwtService.Issued access, Actor actor, RefreshTokenStore.Issued refresh) {
        return new TokenResponse(SignInStatus.SIGNED_IN, "Bearer", access.token(), access.expiresAt(),
            refresh.token(), refresh.expiresAt(), actor.actorId(), actor.roles().stream().sorted().toList(),
            actor.permissions().stream().sorted().toList(), null, null);
    }

    /** The actor a successful sign-in (or second step, passed at {@code mfaAt}) established, in its entry. */
    static Actor actor(SponsorSignInOutput result, Instant mfaAt) {
        return new Actor(result.userId(), result.tenantId(), Set.copyOf(result.roles()),
            Set.copyOf(result.permissions()), mfaAt, result.dataPeriod(), result.entry(), result.emailVerified());
    }

    static AuthenticationFailedException loginFailed(String message) {
        return new AuthenticationFailedException(PlatformErrorCodes.LOGIN_FAILED, message);
    }

    private static AuthenticationFailedException invalidRefresh(String message) {
        return new AuthenticationFailedException(PlatformErrorCodes.INVALID_REFRESH_TOKEN, message);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
