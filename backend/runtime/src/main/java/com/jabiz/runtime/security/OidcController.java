package com.jabiz.runtime.security;

import com.jabiz.runtime.AuthenticationFailedException;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.sponsor.SponsorOidcSignInInput;
import com.jabiz.runtime.process.sponsor.SponsorOidcSignInProcess;
import com.jabiz.security.LoginOutcome;
import io.micrometer.common.KeyValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Sign-in through OpenID Connect providers (docs/design/10-security.md section 12; decision D28 item 6). Public: the
 * authorization code and the state are the credentials. Every refusal is answered alike (401 {@code LOGIN_FAILED});
 * the reason goes to the log only.
 */
@RestController
class OidcController {

    static final String BASE = "/api/auth/oidc";

    private static final Logger log = LoggerFactory.getLogger(OidcController.class);

    record ProviderEntry(String id, String label) {}

    /**
     * @param binder a secret of this browser's sign-in, kept by the page and sent back with the callback; it never
     *               appears in a URL, so a state and code sent to someone else cannot sign them in (login CSRF)
     */
    record StartResponse(String authorizationUrl, String binder) {
        @Override
        public String toString() {
            return "StartResponse[***]";
        }
    }

    /** Not a process input: masked by its own toString only (no name-based masking of "state" or "code"). */
    record CallbackRequest(String state, String code, String binder) {
        @Override
        public String toString() {
            return "CallbackRequest[***]";
        }
    }

    private final OidcProviders providers;
    private final OidcClient client;
    private final OidcStateStore states;
    private final ProcessExecutor processes;
    private final JwtService tokens;
    private final RefreshTokenStore refreshTokens;
    private final MfaSettings mfa;
    private final PlatformObservations observations;
    private final Clock clock;

    OidcController(OidcProviders providers, OidcClient client, OidcStateStore states, ProcessExecutor processes,
        JwtService tokens, RefreshTokenStore refreshTokens, MfaSettings mfa, PlatformObservations observations,
        Clock clock) {
        this.providers = providers;
        this.client = client;
        this.states = states;
        this.processes = processes;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.mfa = mfa;
        this.observations = observations;
        this.clock = clock;
    }

    @GetMapping(BASE + "/providers")
    Mono<List<ProviderEntry>> providers() {
        return RequestContexts.current().map(context -> providers.all().stream()
            .map(provider -> new ProviderEntry(provider.id(), provider.label(context.locale().getLanguage())))
            .toList());
    }

    @PostMapping(BASE + "/{id}/start")
    Mono<StartResponse> start(@PathVariable String id) {
        return Mono.defer(() -> {
            OidcProvider provider = providers.find(id)
                .orElseThrow(() -> new EntityNotFoundException("Unknown identity provider " + id));
            return states.start(provider.id()).flatMap(started -> client.authorizationUrl(provider, started)
                    .map(url -> new StartResponse(url, started.binder())))
                .onErrorMap(OidcClient.ProviderException.class, e -> {
                    log.warn("Identity provider {} unavailable: {}", provider.id(), e.getMessage());
                    return AuthController.loginFailed("Identity provider unavailable");
                });
        });
    }

    /**
     * The provider sent the user back with a code: use the state once, exchange the code, check the ID token, then
     * sign in whom the subject is linked to, like {@code POST /api/auth/login} answers.
     */
    @PostMapping(BASE + "/callback")
    Mono<AuthController.TokenResponse> callback(@RequestBody(required = false) CallbackRequest request) {
        return Mono.defer(() -> {
            if (request == null || blank(request.code())) {
                return Mono.error(AuthController.loginFailed("Missing code"));
            }
            // The state says which provider the sign-in started with; it is used up whatever follows.
            return states.consume(request.state(), request.binder()).flatMap(pending -> {
                OidcProvider provider = providers.find(pending.providerId()).orElseThrow(
                    () -> new IllegalStateException("The provider of the state is no longer configured"));
                Mono<AuthController.TokenResponse> work = client.idToken(provider, request.code(),
                        pending.codeVerifier())
                    .flatMap(idToken -> identity(provider, idToken, pending.nonceHash()))
                    .flatMap(identity -> signIn(provider, identity));
                return observations.mono(PlatformObservations.AUTH_OIDC, "oidc " + provider.id(),
                    KeyValues.of("provider", provider.id()), work);
            }).onErrorMap(e -> !(e instanceof AuthenticationFailedException), e -> {
                log.info("Sign-in through an identity provider refused: {}", e.getMessage());
                return AuthController.loginFailed("Sign-in through the identity provider refused");
            });
        });
    }

    /** Checks the ID token; reads the provider's keys again once when the token names a key not seen yet. */
    private Mono<IdTokenValidator.Identity> identity(OidcProvider provider, String idToken, String nonceHash) {
        com.nimbusds.jwt.SignedJWT token = IdTokenValidator.parse(idToken);
        return client.keys(provider, false)
            .map(keys -> IdTokenValidator.validate(token, keys, provider.issuer(), provider.clientId(), nonceHash,
                clock.instant()))
            .onErrorResume(IdTokenValidator.UnknownKeyException.class, unknown -> client.keys(provider, true)
                .map(keys -> IdTokenValidator.validate(token, keys, provider.issuer(), provider.clientId(),
                    nonceHash, clock.instant())));
    }

    private Mono<AuthController.TokenResponse> signIn(OidcProvider provider, IdTokenValidator.Identity identity) {
        java.time.Instant mfaAt = IdTokenValidator.secondFactorAt(identity, provider, clock.instant());
        return processes.execute(SponsorOidcSignInProcess.DEFINITION,
                new SponsorOidcSignInInput(provider.id(), identity.subject(), mfaAt != null))
            .flatMap(result -> switch (result.outcome()) {
                case SUCCESS -> AuthController.session(refreshTokens, tokens, AuthController.actor(result, mfaAt),
                    UUID.fromString(result.userId()), UUID.fromString(result.identityId()));
                case MFA_REQUIRED -> Mono.just(AuthController.TokenResponse.challenge(
                    AuthController.SignInStatus.MFA_REQUIRED, tokens.issueChallenge(result.userId(),
                        JwtService.Purpose.VERIFY, result.attemptNo(), mfa.challengeTtl())));
                case MFA_ENROLLMENT_REQUIRED -> Mono.just(AuthController.TokenResponse.challenge(
                    AuthController.SignInStatus.MFA_ENROLLMENT_REQUIRED, tokens.issueChallenge(result.userId(),
                        JwtService.Purpose.ENROLL, result.attemptNo(), mfa.challengeTtl())));
                default -> Mono.error(AuthController.loginFailed(result.outcome() == LoginOutcome.BAD_CREDENTIALS
                    ? "No user is linked to the subject" : "Sign-in refused: " + result.outcome()));
            });
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
