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
import com.jabiz.security.LoginOutcome;
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

    record TokenResponse(String tokenType, String accessToken, Instant accessTokenExpiresAt, String refreshToken,
        Instant refreshTokenExpiresAt, String userId, List<String> roles, List<String> permissions) {
        @Override
        public String toString() {
            return "TokenResponse[userId=" + userId + ", tokens=***]";
        }
    }

    record Me(String userId, String tenantId, List<String> roles, List<String> permissions) {}

    private final ProcessExecutor processes;
    private final JwtService tokens;
    private final RefreshTokenStore refreshTokens;
    private final RbacService rbac;
    private final MenuService menus;

    AuthController(ProcessExecutor processes, JwtService tokens, RefreshTokenStore refreshTokens, RbacService rbac,
        MenuService menus) {
        this.processes = processes;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.rbac = rbac;
        this.menus = menus;
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
                .flatMap(result -> {
                    if (result.outcome() != LoginOutcome.SUCCESS) {
                        log.info("Sign-in of '{}' refused: {}", request.userName().trim(), result.outcome());
                        return Mono.error(loginFailed("Sign-in refused"));
                    }
                    return session(actor(result), UUID.fromString(result.userId()));
                });
        });
    }

    @PostMapping(REFRESH)
    Mono<TokenResponse> refresh(@RequestBody(required = false) RefreshRequest request) {
        // Consuming the old token, checking the user and issuing the next token form one transaction.
        return Mono.defer(() -> refreshTokens.rotate(request == null ? null : request.refreshToken(),
                grant -> rbac.currentActor(grant.userId()).flatMap(actor -> actor.map(Mono::just)
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
            context.roles().stream().sorted().toList(), context.permissions().stream().sorted().toList()));
    }

    @GetMapping("/api/auth/menus")
    Mono<List<MenuService.MenuItem>> menus() {
        return RequestContexts.current().flatMap(menus::menuOf);
    }

    /** Tokens of a new session. */
    private Mono<TokenResponse> session(Actor actor, UUID userId) {
        return refreshTokens.issue(userId).map(next -> response(tokens.issue(actor), actor, next));
    }

    private static TokenResponse response(JwtService.Issued access, Actor actor, RefreshTokenStore.Issued refresh) {
        return new TokenResponse("Bearer", access.token(), access.expiresAt(), refresh.token(), refresh.expiresAt(),
            actor.actorId(), actor.roles().stream().sorted().toList(), actor.permissions().stream().sorted().toList());
    }

    private static Actor actor(SponsorSignInOutput result) {
        return new Actor(result.userId(), result.tenantId(), Set.copyOf(result.roles()), Set.copyOf(result.permissions()));
    }

    private static AuthenticationFailedException loginFailed(String message) {
        return new AuthenticationFailedException(PlatformErrorCodes.LOGIN_FAILED, message);
    }

    private static AuthenticationFailedException invalidRefresh(String message) {
        return new AuthenticationFailedException(PlatformErrorCodes.INVALID_REFRESH_TOKEN, message);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
