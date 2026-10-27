package com.jabiz.runtime.security;

import com.jabiz.entity.ValidationException;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.i18n.PlatformLanguages;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.sponsor.SignInSource;
import com.jabiz.runtime.process.sponsor.SponsorOidcSignInInput;
import com.jabiz.runtime.process.sponsor.SponsorOidcSignInProcess;
import com.jabiz.runtime.web.ClientAddresses;
import com.jabiz.security.ExternalTokenRules;
import com.jabiz.security.LoginOutcome;
import com.jabiz.security.Sensitive;
import io.micrometer.common.KeyValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Sign-in with a token another platform signed for the person in front of it (decision D59; docs/design/10-security.md
 * section 15.12), such as the session token of a page embedded in a shop's administration. Public: the token is the
 * credential. Only an entry that names a provider ({@code jabiz.security.entries.<name>.external-token}) takes it. The
 * token is verified (signature, times, audience, one allowed tenant), used once ({@code jti}), and signs in whom
 * {@code ext:<provider>} / {@code <tenant>/<sub>} is linked to, like {@code POST /api/auth/login} answers; a provider with
 * {@code auto-provision} opens an account first at an entry that lets people register. Every refusal of the token or
 * the identity is 401 {@code LOGIN_FAILED}, the reason in the log only; a guard's refusal stays 403, too many accounts
 * opened 429, and a failure of the server its own 5xx.
 */
@RestController
class ExternalTokenController {

    static final String PATH = "/api/auth/external-token";

    private static final Logger log = LoggerFactory.getLogger(ExternalTokenController.class);

    /** The token, and the sign-in entry it is for. */
    record ExternalTokenRequest(@Sensitive String token, String entry) {
        @Override
        public String toString() {
            return "ExternalTokenRequest[token=***, entry=" + entry + "]";
        }
    }

    private final ExternalTokenProviders providers;
    private final ExternalTokens externalTokens;
    private final ProcessExecutor processes;
    private final JwtService tokens;
    private final RefreshTokenStore refreshTokens;
    private final MfaSettings mfa;
    private final PlatformObservations observations;
    private final SignInEntries entries;
    private final ClientAddresses clients;
    private final SignUpRateLimits limits;
    private final SignInRateLimits signInLimits;
    private final ProcessDefinition<ExternalTokenSignUpProcess.Input, ExternalTokenSignUpProcess.Output,
        AccountContext> signUp;

    ExternalTokenController(ExternalTokenProviders providers, ExternalTokens externalTokens, ProcessExecutor processes,
        JwtService tokens, RefreshTokenStore refreshTokens, MfaSettings mfa, PlatformObservations observations,
        SignInEntries entries, ClientAddresses clients, SignUpRateLimits limits, SignInRateLimits signInLimits,
        ProcessDefinition<ExternalTokenSignUpProcess.Input, ExternalTokenSignUpProcess.Output, AccountContext> signUp) {
        this.providers = providers;
        this.externalTokens = externalTokens;
        this.processes = processes;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.mfa = mfa;
        this.observations = observations;
        this.entries = entries;
        this.clients = clients;
        this.limits = limits;
        this.signInLimits = signInLimits;
        this.signUp = signUp;
    }

    @PostMapping(PATH)
    Mono<AuthController.TokenResponse> externalTokenSignIn(@RequestBody(required = false) ExternalTokenRequest request,
        ServerHttpRequest http) {
        return Mono.defer(() -> {
            signInLimits.count(SignInRateLimits.Kind.EXTERNAL_TOKEN, http, null);
            if (request == null || request.token() == null || request.entry() == null) {
                return Mono.error(AuthController.loginFailed("Missing token or entry"));
            }
            SignInEntries.Entry entry = entries.find(request.entry()).orElse(null);
            if (entry == null || entry.externalToken() == null) {
                return Mono.error(AuthController.loginFailed("The entry takes no external tokens"));
            }
            ExternalTokenProvider provider = providers.find(entry.externalToken()).orElse(null);
            if (provider == null) {
                return Mono.error(AuthController.loginFailed("The entry's external token provider is unavailable"));
            }
            ExternalTokenRules.Verified verified = externalTokens.verify(provider, request.token());
            SignInSource source = AuthController.source(clients, http, entry.name());
            Mono<AuthController.TokenResponse> work = externalTokens.useOnce(provider, verified)
                .then(Mono.defer(() -> signIn(provider, ExternalTokenProvider.identitySubject(verified), source,
                    http, false)));
            return observations.mono(PlatformObservations.AUTH_EXTERNAL_TOKEN, "external token " + provider.name(),
                KeyValues.of("provider", provider.name()), work);
        }).onErrorMap(ExternalTokenController::refusal, e -> {
            log.info("Sign-in with an external token refused: {}", e.getMessage());
            return AuthController.loginFailed("Sign-in with an external token refused");
        });
    }

    /**
     * Whether a failure is the token's or the identity's: 401 alike. A failure of the server (the database, a busy
     * process) keeps its own answer, which the client may retry with a fresh token (phase 17e).
     */
    private static boolean refusal(Throwable e) {
        // A guard's refusal and an unverified address keep their 403 (they are permission denials too).
        if (e instanceof SignInRefusedException || e instanceof EmailNotVerifiedException) {
            return false;
        }
        return e instanceof ExternalTokenRules.RefusedException || e instanceof ValidationException
            || e instanceof BusinessRuleViolationException || e instanceof PermissionDeniedException
            || e instanceof EntityNotFoundException;
    }

    /**
     * Signs in whom the identity is linked to; an identity nobody linked may get an account ({@link #open}) and signs in
     * then. {@code again}: the sign-in after opening, which opens nothing more.
     */
    private Mono<AuthController.TokenResponse> signIn(ExternalTokenProvider provider, String subject,
        SignInSource source, ServerHttpRequest http, boolean again) {
        SponsorOidcSignInInput input = new SponsorOidcSignInInput(provider.identityProvider(), subject, false, source,
            SecurityEntities.FACTOR_EXTERNAL_TOKEN);
        return processes.executeAtEntry(SponsorOidcSignInProcess.EXTERNAL_TOKEN_DEFINITION, input)
            .onErrorResume(ExternalTokenController::raceLost, e -> processes.executeAtEntry(
                SponsorOidcSignInProcess.EXTERNAL_TOKEN_DEFINITION, input))
            .flatMap(result -> {
                if (result.outcome() == LoginOutcome.BAD_CREDENTIALS) {
                    return again ? Mono.error(AuthController.loginFailed("No user is linked to the identity"))
                        : open(provider, subject, source, http, false);
                }
                return AuthController.answer(result, null,
                    result.identityId() == null ? null : UUID.fromString(result.identityId()),
                    source, refreshTokens, tokens, mfa, "Sign-in with external token " + provider.name());
            });
    }

    /**
     * Opens an account for an identity nobody linked, when the provider may and the entry lets people register (never
     * the administration); otherwise 401 as for any unknown identity. Losing a race asks once more.
     */
    private Mono<AuthController.TokenResponse> open(ExternalTokenProvider provider, String subject,
        SignInSource source, ServerHttpRequest http, boolean retried) {
        return Mono.defer(() -> {
            String entry = source.entryOrDefault();
            boolean registering = entries.find(entry).filter(OidcController::opensAccounts).isPresent();
            if (!provider.autoProvision() || !registering) {
                return Mono.error(AuthController.loginFailed("No user is linked to the identity (and none is opened: "
                    + (!provider.autoProvision() ? "the provider opens no accounts"
                    : "the entry " + entry + " takes no registrations") + ")"));
            }
            if (!retried) {
                limits.client(SignUpRateLimits.Kind.EXTERNAL_TOKEN_SIGN_UP, clients.clientOf(http));
            }
            return RequestContexts.current().flatMap(context -> {
                String language = context.locale() == null ? null : context.locale().getLanguage();
                return processes.executeAtEntry(signUp, new ExternalTokenSignUpProcess.Input(entry,
                    provider.identityProvider(), subject, PlatformLanguages.isSupported(language) ? language : null));
            }).flatMap(opened -> switch (opened.outcome()) {
                case CREATED, EXISTS -> signIn(provider, subject, source, http, true);
                case DISABLED -> Mono.<AuthController.TokenResponse>error(AuthController.loginFailed(
                    "The entry " + entry + " takes no registrations"));
            }).onErrorResume(e -> !retried && raceLost(e), e -> open(provider, subject, source, http, true));
        });
    }

    /** The unique violation of an account opened at the same time (the link or the name). */
    private static boolean raceLost(Throwable error) {
        return error instanceof ValidationException v && v.violations().stream()
            .anyMatch(violation -> PlatformErrorCodes.UNIQUE_VIOLATION.equals(violation.ruleCode()));
    }
}
