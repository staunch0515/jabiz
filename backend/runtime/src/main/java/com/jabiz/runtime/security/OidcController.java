package com.jabiz.runtime.security;

import com.jabiz.entity.ValidationException;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.i18n.PlatformLanguages;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.AuthenticationFailedException;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.RateLimitedException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.sponsor.SignInSource;
import com.jabiz.runtime.process.sponsor.SponsorOidcSignInInput;
import com.jabiz.runtime.process.sponsor.SponsorOidcSignInProcess;
import com.jabiz.runtime.web.ClientAddresses;
import com.jabiz.security.LoginOutcome;
import io.micrometer.common.KeyValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Sign-in through OpenID Connect providers (docs/design/10-security.md section 12; decision D28 item 6). Public: the
 * authorization code and the state are the credentials. Every refusal is answered alike (401 {@code LOGIN_FAILED});
 * the reason goes to the log only. Exceptions, told only to whom the provider has just vouched for: a guard's refusal or
 * an unverified address (403, as after a right password), and an address that belongs to an account already when the
 * provider would open one (403 {@code OIDC_ACCOUNT_EXISTS}, decision D39 item 2).
 *
 * <p>A provider with {@code auto-provision} opens an account for a subject nobody linked (decision D39 items 1 and 4):
 * at an entry that lets people register, for an address the provider vouches for
 * ({@link IdTokenValidator.Identity#trustedEmail}) that no user has; counted per client address
 * ({@link SignUpRateLimits.Kind#OIDC_SIGN_UP}). Then it signs in as usual.
 */
@RestController
class OidcController {

    static final String BASE = "/api/auth/oidc";

    /** Where Apple posts its answer for the provider {@code id} (decision D67 item 1). */
    static String formPostPath(String id) {
        return BASE + "/" + id + "/form-post";
    }

    /** The longest {@code user} form field read: Apple's names and address, with room to spare. */
    static final int MAX_USER_FIELD = 4096;

    private static final Logger log = LoggerFactory.getLogger(OidcController.class);

    /**
     * @param kind {@code generic}, {@code google}, {@code facebook}, {@code line} or {@code apple}: which button to
     *             draw (Apple's with its own title, decision D67)
     */
    record ProviderEntry(String id, String label, String kind) {}

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
    private final OidcExchange exchange;
    private final OidcStateStore states;
    private final ProcessExecutor processes;
    private final JwtService tokens;
    private final RefreshTokenStore refreshTokens;
    private final MfaSettings mfa;
    private final PlatformObservations observations;
    private final Clock clock;
    private final SignInEntries entries;
    private final ClientAddresses clients;
    private final SignUpRateLimits limits;
    private final SignInRateLimits signInLimits;
    private final ProcessDefinition<OidcSignUpProcess.Input, OidcSignUpProcess.Output, AccountContext> signUp;

    OidcController(OidcProviders providers, OidcClient client, OidcExchange exchange, OidcStateStore states,
        ProcessExecutor processes, JwtService tokens, RefreshTokenStore refreshTokens, MfaSettings mfa,
        PlatformObservations observations, Clock clock, SignInEntries entries, ClientAddresses clients,
        SignUpRateLimits limits, SignInRateLimits signInLimits,
        ProcessDefinition<OidcSignUpProcess.Input, OidcSignUpProcess.Output, AccountContext> signUp) {
        this.signInLimits = signInLimits;
        this.providers = providers;
        this.client = client;
        this.exchange = exchange;
        this.states = states;
        this.processes = processes;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.mfa = mfa;
        this.observations = observations;
        this.clock = clock;
        this.entries = entries;
        this.clients = clients;
        this.limits = limits;
        this.signUp = signUp;
    }

    /**
     * The providers offered to the sign-in entry {@code entry} (none: the administration): those with a callback for it
     * (decision D43 item 5). None for an entry that does not exist.
     */
    @GetMapping(BASE + "/providers")
    Mono<List<ProviderEntry>> providers(@RequestParam(name = "entry", required = false) String entry) {
        List<OidcProvider> offered = entries.find(entry).map(e -> providers.offeredTo(e.name())).orElse(List.of());
        return RequestContexts.current().map(context -> offered.stream()
            .map(provider -> new ProviderEntry(provider.id(), provider.label(context.locale().getLanguage()),
                provider.kind()))
            .toList());
    }

    /**
     * @param entry the sign-in entry to sign in to (decision D36); none: the administration. Stored with the state:
     *              the callback cannot pick another one. A provider without a callback for the entry is refused like an
     *              unknown entry (decision D43 item 5). Counted per client address (each start writes a row).
     */
    @PostMapping(BASE + "/{id}/start")
    Mono<StartResponse> start(@PathVariable String id, @RequestParam(name = "entry", required = false) String entry,
        ServerHttpRequest http) {
        return Mono.defer(() -> {
            limits.oidcStart(clients.clientOf(http));
            OidcProvider provider = providers.find(id)
                .orElseThrow(() -> new EntityNotFoundException("Unknown identity provider " + id));
            Optional<SignInEntries.Entry> found = entries.find(entry);
            if (found.isEmpty()) {
                return Mono.error(AuthController.loginFailed("Unknown sign-in entry"));
            }
            String resolved = found.get().name();
            String redirectUri = provider.providerRedirectUri(resolved);
            if (redirectUri == null) {
                return Mono.error(AuthController.loginFailed("Identity provider " + provider.id()
                    + " has no callback for entry " + resolved));
            }
            return states.start(provider.id(), resolved).flatMap(started -> client.authorizationUrl(provider, started,
                    redirectUri).map(url -> new StartResponse(url, started.binder())))
                .onErrorMap(OidcClient.ProviderException.class, e -> {
                    log.warn("Identity provider {} unavailable: {}", provider.id(), e.getMessage());
                    return AuthController.loginFailed("Identity provider unavailable");
                });
        });
    }

    /**
     * The provider sent the user back with a code: use the state once (a sign-in's, not a link's), exchange the code,
     * check the ID token, then sign in whom the subject is linked to, like {@code POST /api/auth/login} answers; open
     * an account first where the provider may (decision D39).
     */
    @PostMapping(BASE + "/callback")
    Mono<AuthController.TokenResponse> callback(@RequestBody(required = false) CallbackRequest request,
        ServerHttpRequest http) {
        return Mono.defer(() -> {
            signInLimits.count(SignInRateLimits.Kind.OIDC_CALLBACK, http, null);
            if (request == null || blank(request.code())) {
                return Mono.error(AuthController.loginFailed("Missing code"));
            }
            // The state says which provider the sign-in started with; it is used up whatever follows.
            return states.consume(request.state(), request.binder()).flatMap(pending -> {
                if (pending.purpose() != OidcStateStore.Purpose.SIGN_IN) {
                    return Mono.error(new IllegalStateException("The state is of a link, not a sign-in"));
                }
                OidcProvider provider = providers.find(pending.providerId()).orElseThrow(
                    () -> new IllegalStateException("The provider of the state is no longer configured"));
                SignInSource source = AuthController.source(clients, http, pending.entry());
                Mono<AuthController.TokenResponse> work = exchange.identity(provider, request.code(), pending)
                    .flatMap(identity -> signIn(provider, identity, source, http, false));
                return observations.mono(PlatformObservations.AUTH_OIDC, "oidc " + provider.id(),
                    KeyValues.of("provider", provider.id()), work);
            // Refusals after the identity was proven (a guard, an unverified address, an address in use) keep their
            // 403; too many accounts opened from one address is 429.
            }).onErrorMap(e -> !(e instanceof AuthenticationFailedException) && !(e instanceof SignInRefusedException)
                && !(e instanceof SignUpRefusedException) && !(e instanceof EmailNotVerifiedException) && !(e instanceof OidcAccountExistsException)
                && !(e instanceof RateLimitedException), e -> {
                log.info("Sign-in through an identity provider refused: {}", e.getMessage());
                return AuthController.loginFailed("Sign-in through the identity provider refused");
            });
        });
    }

    /**
     * Sign in with Apple's answer (decision D67 item 1): Apple posts code and state as a form (and, the first time, the
     * person's name in {@code user}), which a single-page application cannot receive. The pending round trip keeps the
     * name ({@link OidcStateStore#posted}); the browser goes on (303) to the callback of the round trip's entry with
     * code and state in the query, as other providers send them, and the page finishes the sign-in or link with its
     * binder as usual. Only to that configured callback: never to an address the request names. Apple's refusal
     * ({@code error}, as when the person cancels) goes on without a code, and the page says it failed. An unknown,
     * expired or used state, or another provider's, is 401 {@code LOGIN_FAILED}.
     */
    @PostMapping(value = BASE + "/{id}/form-post", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    Mono<ResponseEntity<Void>> formPost(@PathVariable String id, ServerWebExchange http) {
        return Mono.defer(() -> {
            signInLimits.count(SignInRateLimits.Kind.OIDC_FORM_POST, http.getRequest(), null);
            OidcProvider provider = providers.find(id).filter(OidcProvider::isApple)
                .orElseThrow(() -> new EntityNotFoundException("Unknown identity provider " + id));
            return http.getFormData().flatMap(form -> {
                String state = form.getFirst("state");
                String code = form.getFirst("code");
                String error = form.getFirst("error");
                return states.posted(state, AppleUser.name(form.getFirst("user")))
                    .filter(posted -> posted.providerId().equals(provider.id()))
                    .switchIfEmpty(Mono.error(() -> AuthController.loginFailed("Unknown, expired or used state, or "
                        + "another provider's, posted to " + provider.id())))
                    .flatMap(posted -> {
                        String callback = provider.redirectUri(posted.entry());
                        if (callback == null) {
                            return Mono.error(AuthController.loginFailed("Identity provider " + provider.id()
                                + " has no callback for entry " + posted.entry()));
                        }
                        UriComponentsBuilder next = UriComponentsBuilder.fromUriString(callback)
                            .queryParam("state", state);
                        if (code != null && !code.isBlank() && error == null) {
                            next.queryParam("code", code);
                        } else {
                            String known = error == null || !ERROR.matcher(error).matches() ? "access_denied" : error;
                            log.info("Identity provider {} refused: {}", provider.id(), known);
                            next.queryParam("error", known);
                        }
                        return Mono.just(ResponseEntity.status(HttpStatus.SEE_OTHER)
                            .location(next.encode().build().toUri())
                            .header(HttpHeaders.CACHE_CONTROL, "no-store")
                            .header("Referrer-Policy", "no-referrer")
                            .<Void>build());
                    });
            });
        });
    }

    /** An OAuth error code as RFC 6749 allows (section 4.1.2.1); anything else is passed on as access_denied. */
    private static final java.util.regex.Pattern ERROR = java.util.regex.Pattern.compile("[a-z_]{1,64}");

    /**
     * Signs in whom the subject is linked to; a subject nobody linked may get an account ({@link #open}) and signs in
     * then. {@code again}: this is the sign-in after opening (or after losing a race to open), which opens nothing more.
     */
    private Mono<AuthController.TokenResponse> signIn(OidcProvider provider, IdTokenValidator.Identity identity,
        SignInSource source, ServerHttpRequest http, boolean again) {
        java.time.Instant mfaAt = IdTokenValidator.secondFactorAt(identity, provider, clock.instant());
        SponsorOidcSignInInput input = new SponsorOidcSignInInput(provider.id(), identity.subject(), mfaAt != null,
            source);
        return processes.executeAtEntry(SponsorOidcSignInProcess.DEFINITION, input)
            // Two sign-ins of one user at once: the later loses the race for the next login record. No password is
            // guessed here, so it simply signs in once more.
            .onErrorResume(OidcController::raceLost, e -> processes.executeAtEntry(
                SponsorOidcSignInProcess.DEFINITION, input))
            .flatMap(result -> {
                if (result.outcome() == LoginOutcome.BAD_CREDENTIALS) {
                    return again ? Mono.error(AuthController.loginFailed("No user is linked to the subject"))
                        : open(provider, identity, source, http, false);
                }
                return AuthController.answer(result, mfaAt,
                    result.identityId() == null ? null : UUID.fromString(result.identityId()),
                    source, refreshTokens, tokens, mfa,
                    "Sign-in through identity provider " + provider.id());
            });
    }

    /**
     * Opens an account for a subject nobody linked, when the provider may, the entry lets people register and the
     * provider vouches for the address (decision D39 item 1); otherwise 401 as for any unknown subject. An address in
     * use is 403 {@code OIDC_ACCOUNT_EXISTS}: nothing is linked. Losing a race (the same subject, or the address, at
     * once) asks once more ({@code retried}), which then finds the link or the address in use.
     */
    private Mono<AuthController.TokenResponse> open(OidcProvider provider, IdTokenValidator.Identity identity,
        SignInSource source, ServerHttpRequest http, boolean retried) {
        return Mono.defer(() -> {
            String entry = source.entryOrDefault();
            String trusted = identity.trustedEmail(provider);
            // An address the provider does not vouch for opens an unverified, pre-claimable account where the provider
            // allows it (decision D61 item 3); none without any address.
            String email = trusted != null ? trusted : provider.signUpUnverifiedEmail() ? identity.email() : null;
            boolean registering = entries.find(entry).filter(OidcController::opensAccounts).isPresent();
            if (!provider.autoProvision() || !registering || email == null) {
                return Mono.error(AuthController.loginFailed("No user is linked to the subject (and none is opened: "
                    + (!provider.autoProvision() ? "the provider opens no accounts"
                    : !registering ? "the entry " + entry + " takes no registrations"
                    : "the provider does not vouch for an address") + ")"));
            }
            if (!retried) {
                limits.client(SignUpRateLimits.Kind.OIDC_SIGN_UP, clients.clientOf(http));
            }
            return RequestContexts.current().flatMap(context -> {
                String language = context.locale() == null ? null : context.locale().getLanguage();
                return processes.executeAtEntry(signUp, new OidcSignUpProcess.Input(entry, provider.id(),
                    identity.subject(), email, identity.name(),
                    PlatformLanguages.isSupported(language) ? language : null, trusted != null));
            }).flatMap(opened -> switch (opened.outcome()) {
                case CREATED, EXISTS -> signIn(provider, identity, source, http, true);
                case EMAIL_IN_USE -> Mono.<AuthController.TokenResponse>error(new OidcAccountExistsException(
                    "The address belongs to an account already"));
                case DISABLED -> Mono.<AuthController.TokenResponse>error(AuthController.loginFailed(
                    "The entry " + entry + " takes no registrations"));
            }).onErrorResume(e -> !retried && raceLost(e), e -> open(provider, identity, source, http, true));
        });
    }

    /**
     * Whether a provider may open accounts at the entry: it lets people register, and it is not the administration,
     * which never opens accounts (decision D39 item 1) even if its settings said so (the startup check refuses that
     * too, 10 section 15.7).
     */
    static boolean opensAccounts(SignInEntries.Entry entry) {
        return entry.selfRegistration() && !com.jabiz.context.RequestContext.DEFAULT_ENTRY.equals(entry.name());
    }

    /** The unique violation of an account opened at the same time (the subject's link, the address or the name). */
    private static boolean raceLost(Throwable error) {
        return error instanceof ValidationException v && v.violations().stream()
            .anyMatch(violation -> PlatformErrorCodes.UNIQUE_VIOLATION.equals(violation.ruleCode()));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
