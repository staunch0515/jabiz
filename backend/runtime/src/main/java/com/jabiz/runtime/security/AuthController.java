package com.jabiz.runtime.security;

import com.jabiz.entity.ValidationException;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.runtime.AuthenticationFailedException;
import com.jabiz.runtime.context.Actor;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.sponsor.SponsorSignInInput;
import com.jabiz.runtime.process.sponsor.SponsorSignInOutput;
import com.jabiz.runtime.process.sponsor.SponsorSignInProcess;
import com.jabiz.security.Sensitive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
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
 * Sessions (docs/design/10-security.md section 2; decision D12). Signing in runs {@value SponsorSignInProcess#NAME}
 * and answers every refusal alike (401 {@code LOGIN_FAILED}); a success returns a short-lived access token and a
 * single-use refresh token. Refreshing consumes the refresh token, re-reads the user's roles and returns a new pair;
 * signing out ends the session of a refresh token. {@code /me} and {@code /menus} describe the authenticated actor.
 */
@RestController
class AuthController {

    static final String LOGIN = "/api/auth/login";
    static final String REFRESH = "/api/auth/refresh";
    static final String LOGOUT = "/api/auth/logout";

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    record LoginRequest(String userName, @Sensitive String password) {
        @Override
        public String toString() {
            return "LoginRequest[userName=" + userName + ", password=***]";
        }
    }

    record RefreshRequest(@Sensitive String refreshToken) {
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
     */
    record Me(String userId, String tenantId, List<String> roles, List<String> permissions, Instant mfaAt,
        long idleTimeoutSeconds) {}

    private final ProcessExecutor processes;
    private final JwtService tokens;
    private final RefreshTokenStore refreshTokens;
    private final RbacService rbac;
    private final MenuService menus;
    private final MfaSettings mfa;

    AuthController(ProcessExecutor processes, JwtService tokens, RefreshTokenStore refreshTokens, RbacService rbac,
        MenuService menus, MfaSettings mfa) {
        this.processes = processes;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.rbac = rbac;
        this.menus = menus;
        this.mfa = mfa;
    }

    @PostMapping(LOGIN)
    Mono<TokenResponse> login(@RequestBody(required = false) LoginRequest request) {
        return Mono.defer(() -> {
            if (request == null || blank(request.userName()) || blank(request.password())) {
                return Mono.error(loginFailed("Missing user name or password"));
            }
            return processes.execute(SponsorSignInProcess.DEFINITION,
                    new SponsorSignInInput(request.userName().trim(), request.password()))
                // Concurrent attempts on one account: the later one loses the race for the next login record.
                .onErrorMap(e -> e instanceof ValidationException v && v.violations().stream()
                        .anyMatch(violation -> PlatformErrorCodes.UNIQUE_VIOLATION.equals(violation.ruleCode())),
                    e -> loginFailed("Concurrent sign-in attempt"))
                .flatMap(result -> switch (result.outcome()) {
                    case SUCCESS -> session(refreshTokens, tokens, actor(result, null), UUID.fromString(result.userId()));
                    case MFA_REQUIRED -> Mono.just(TokenResponse.challenge(SignInStatus.MFA_REQUIRED,
                        tokens.issueChallenge(result.userId(), JwtService.Purpose.VERIFY, result.attemptNo(),
                            mfa.challengeTtl())));
                    case MFA_ENROLLMENT_REQUIRED -> Mono.just(TokenResponse.challenge(
                        SignInStatus.MFA_ENROLLMENT_REQUIRED, tokens.issueChallenge(result.userId(),
                            JwtService.Purpose.ENROLL, result.attemptNo(), mfa.challengeTtl())));
                    default -> {
                        log.info("Sign-in of '{}' refused: {}", request.userName().trim(), result.outcome());
                        yield Mono.error(loginFailed("Sign-in refused"));
                    }
                });
        });
    }

    @PostMapping(REFRESH)
    Mono<TokenResponse> refresh(@RequestBody(required = false) RefreshRequest request) {
        // Consuming the old token, checking the user and issuing the next token form one transaction.
        return Mono.defer(() -> refreshTokens.rotate(request == null ? null : request.refreshToken(),
                grant -> rbac.currentActor(grant.userId(), grant.mfaAt(), grant.identityId()).flatMap(actor -> actor.map(Mono::just)
                    .orElseGet(() -> Mono.error(invalidRefresh("The user can no longer sign in"))))))
            .onErrorMap(RefreshTokenStore.InvalidRefreshTokenException.class, e -> invalidRefresh(e.getMessage()))
            .map(rotated -> response(tokens.issue(rotated.value()), rotated.value(), rotated.next()));
    }

    @PostMapping(LOGOUT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> logout(@RequestBody(required = false) RefreshRequest request) {
        return Mono.defer(() -> refreshTokens.revoke(request == null ? null : request.refreshToken()))
            .onErrorResume(RefreshTokenStore.InvalidRefreshTokenException.class, e -> Mono.empty());
    }

    @GetMapping("/api/auth/me")
    Mono<Me> me() {
        return RequestContexts.current().map(context -> new Me(context.actorId(), context.tenantId(),
            context.roles().stream().sorted().toList(), context.permissions().stream().sorted().toList(),
            context.mfaAt(), mfa.idleTimeout().toSeconds()));
    }

    @GetMapping("/api/auth/menus")
    Mono<List<MenuService.MenuItem>> menus() {
        return RequestContexts.current().flatMap(menus::menuOf);
    }

    /** Tokens of a new session; the refresh tokens of the session remember when it passed a second factor. */
    static Mono<TokenResponse> session(RefreshTokenStore refreshTokens, JwtService tokens, Actor actor, UUID userId) {
        return session(refreshTokens, tokens, actor, userId, null);
    }

    /** As {@link #session}, for a sign-in through the provider account {@code identityId}. */
    static Mono<TokenResponse> session(RefreshTokenStore refreshTokens, JwtService tokens, Actor actor, UUID userId,
        UUID identityId) {
        return refreshTokens.issue(userId, actor.mfaAt(), identityId)
            .map(next -> response(tokens.issue(actor), actor, next));
    }

    private static TokenResponse response(JwtService.Issued access, Actor actor, RefreshTokenStore.Issued refresh) {
        return new TokenResponse(SignInStatus.SIGNED_IN, "Bearer", access.token(), access.expiresAt(),
            refresh.token(), refresh.expiresAt(), actor.actorId(), actor.roles().stream().sorted().toList(),
            actor.permissions().stream().sorted().toList(), null, null);
    }

    /** The actor a successful sign-in (or second step, passed at {@code mfaAt}) established. */
    static Actor actor(SponsorSignInOutput result, Instant mfaAt) {
        return new Actor(result.userId(), result.tenantId(), Set.copyOf(result.roles()),
            Set.copyOf(result.permissions()), mfaAt, result.dataPeriod());
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
