package com.jabiz.runtime.security;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.BoundValue;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.sponsor.PasskeyContext;
import com.jabiz.runtime.process.sponsor.SignInSource;
import com.jabiz.runtime.process.sponsor.SponsorPasskeySignInInput;
import com.jabiz.runtime.process.sponsor.SponsorPasskeySignInProcess;
import com.jabiz.runtime.process.sponsor.SponsorSignInOutput;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.web.ClientAddresses;
import com.jabiz.security.LoginOutcome;
import com.jabiz.security.Sensitive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Passkeys over HTTP (decision D71; docs/design/10-security.md section 15.16): signing in without a name, the second
 * step after a password and enrolment under a challenge, step-up, and the signed-in user's own passkeys. Each ceremony
 * asks for options first: a challenge the platform keeps (for one purpose, entry and user) and takes back when the
 * browser answers, before the answer is checked in a process. Signing in is counted per address
 * ({@link SignInRateLimits.Kind#PASSKEY}); the second step and step-up with the user
 * ({@link SignInRateLimits.Kind#SECOND_FACTOR}).
 */
@RestController
class PasskeyController {

    static final String SIGN_IN = "/api/auth/passkey";
    static final String CHALLENGE = MfaController.CHALLENGE + "/passkey";
    static final String STEP_UP = "/api/auth/step-up/passkey";
    static final String ACCOUNT = "/api/auth/account/passkeys";

    private static final Logger log = LoggerFactory.getLogger(PasskeyController.class);

    /** The options to hand to {@code navigator.credentials.get} or {@code .create}, as WebAuthn's JSON. */
    record PasskeyOptions(Map<String, Object> publicKey) {}

    /** @param entry the sign-in entry (none: the administration) */
    record EntryRequest(String entry) {}

    /** @param credential the browser's answer: {@code PublicKeyCredential.toJSON()} */
    record SignInRequest(String entry, @Sensitive JsonNode credential) {
        @Override
        public String toString() {
            return "SignInRequest[entry=" + entry + ", ***]";
        }
    }

    record PasskeyChallengeRequest(String challenge) {
        @Override
        public String toString() {
            return "PasskeyChallengeRequest[***]";
        }
    }

    /** @param name what the user calls the passkey (registration only) */
    record ChallengeAnswer(String challenge, @Sensitive JsonNode credential, String name) {
        @Override
        public String toString() {
            return "ChallengeAnswer[***]";
        }
    }

    record CredentialRequest(@Sensitive JsonNode credential, String name) {
        @Override
        public String toString() {
            return "CredentialRequest[name=" + name + ", ***]";
        }
    }

    /**
     * How the signed-in user proves who they are before adding a passkey: a recent second factor (nothing to send),
     * the current password, or the confirmation mail's token.
     */
    record AddOptionsRequest(@Sensitive String currentPassword, @Sensitive String passkeyToken) {
        @Override
        public String toString() {
            return "AddOptionsRequest[***]";
        }
    }

    record RenameRequest(String name) {}

    record Registered(String passkeyId) {}

    /**
     * @param backedUp whether the passkey is synced (to other devices of the user's account at Apple, Google or a
     *                 password manager)
     */
    record PasskeyView(String id, String name, Instant createdAt, Instant lastUsedAt, boolean backedUp) {}

    private final ProcessExecutor processes;
    private final JwtService tokens;
    private final RefreshTokenStore refreshTokens;
    private final MfaSettings mfa;
    private final SignInEntries entries;
    private final ClientAddresses clients;
    private final SignInRateLimits limits;
    private final PasskeyChallenges challenges;
    private final PasskeyVerifier verifier;
    private final Reauthentication reauthentication;
    private final DatasetEntityManager entities;
    private final DatasetRegistry datasets;
    private final StorageAdapterRegistry storages;
    private final String poolRef;
    private final Clock clock;
    private final ProcessDefinition<PasskeyProcesses.RegisterInput, PasskeyProcesses.RegisterOutput, ProcessContext>
        register;
    private final ProcessDefinition<PasskeyProcesses.RenameInput, PasskeyProcesses.PasskeyOutput, ProcessContext>
        rename;
    private final ProcessDefinition<PasskeyProcesses.RemoveInput, PasskeyProcesses.PasskeyOutput, ProcessContext>
        remove;
    private final ProcessDefinition<PasskeyProcesses.UserInput, PasskeyProcesses.ConfirmationOutput, ProcessContext>
        confirmationSend;
    private final ProcessDefinition<PasskeyProcesses.ConfirmationInput, PasskeyProcesses.ConfirmationOutput,
        ProcessContext> confirmationUse;
    private final ProcessDefinition<IdentityProcesses.BeginInput, IdentityProcesses.BeginOutput, ProcessContext>
        passwordCheck;

    @SuppressWarnings("checkstyle:ParameterNumber")
    PasskeyController(ProcessExecutor processes, JwtService tokens, RefreshTokenStore refreshTokens, MfaSettings mfa,
        SignInEntries entries, ClientAddresses clients, SignInRateLimits limits, PasskeyChallenges challenges,
        PasskeyVerifier verifier, Reauthentication reauthentication, DatasetEntityManager entities,
        DatasetRegistry datasets, StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef, Clock clock,
        ProcessDefinition<PasskeyProcesses.RegisterInput, PasskeyProcesses.RegisterOutput, ProcessContext> register,
        ProcessDefinition<PasskeyProcesses.RenameInput, PasskeyProcesses.PasskeyOutput, ProcessContext> rename,
        ProcessDefinition<PasskeyProcesses.RemoveInput, PasskeyProcesses.PasskeyOutput, ProcessContext> remove,
        ProcessDefinition<PasskeyProcesses.UserInput, PasskeyProcesses.ConfirmationOutput, ProcessContext>
            confirmationSend,
        ProcessDefinition<PasskeyProcesses.ConfirmationInput, PasskeyProcesses.ConfirmationOutput, ProcessContext>
            confirmationUse,
        @org.springframework.beans.factory.annotation.Qualifier("secPasskeyPasswordCheckProcess")
        ProcessDefinition<IdentityProcesses.BeginInput, IdentityProcesses.BeginOutput, ProcessContext> passwordCheck) {
        this.processes = processes;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.mfa = mfa;
        this.entries = entries;
        this.clients = clients;
        this.limits = limits;
        this.challenges = challenges;
        this.verifier = verifier;
        this.reauthentication = reauthentication;
        this.entities = entities;
        this.datasets = datasets;
        this.storages = storages;
        this.poolRef = poolRef;
        this.clock = clock;
        this.register = register;
        this.rename = rename;
        this.remove = remove;
        this.confirmationSend = confirmationSend;
        this.confirmationUse = confirmationUse;
        this.passwordCheck = passwordCheck;
    }

    // ---- signing in without a name ------------------------------------------------------------------------------

    /** The options of a sign-in at the entry: any of the user's passkeys answers. */
    @PostMapping(SIGN_IN + "/options")
    Mono<PasskeyOptions> signInOptions(@RequestBody(required = false) EntryRequest request, ServerHttpRequest http) {
        return Mono.defer(() -> {
            limits.count(SignInRateLimits.Kind.PASSKEY, http, null);
            SignInEntries.Entry entry = passkeyEntry(request == null ? null : request.entry())
                .orElseThrow(PasskeyController::disabled);
            return challenges.start(PasskeyChallenges.Purpose.SIGN_IN, entry.name(), null)
                .map(challenge -> new PasskeyOptions(verifier.requestOptions(challenge, List.of())));
        });
    }

    /** Signs in with a passkey's answer: the answer as to a password sign-in; every refusal but two 401. */
    @PostMapping(SIGN_IN)
    Mono<AuthController.TokenResponse> signIn(@RequestBody(required = false) SignInRequest request,
        ServerHttpRequest http) {
        return Mono.defer(() -> {
            limits.count(SignInRateLimits.Kind.PASSKEY, http, null);
            Optional<SignInEntries.Entry> entry = passkeyEntry(request == null ? null : request.entry());
            if (entry.isEmpty() || request.credential() == null) {
                return Mono.error(AuthController.loginFailed("No passkey sign-in here"));
            }
            SignInSource source = AuthController.source(clients, http, entry.get().name());
            return answer(request.credential(), PasskeyChallenges.Purpose.SIGN_IN, entry.get().name(), null, source)
                .flatMap(result -> AuthController.answer(result, clock.instant(), null, source, refreshTokens, tokens,
                    mfa, "Passkey sign-in"));
        });
    }

    // ---- under a challenge: the second step, enrolment ----------------------------------------------------------

    /** The options of the second step after a password: one of the challenge's user's passkeys answers. */
    @PostMapping(CHALLENGE + "/options")
    Mono<PasskeyOptions> secondStepOptions(@RequestBody(required = false) PasskeyChallengeRequest request) {
        return Mono.defer(() -> {
            JwtService.Challenge challenge = challenge(request == null ? null : request.challenge(),
                JwtService.Purpose.VERIFY);
            passkeyEntry(challenge.entry()).orElseThrow(PasskeyController::disabled);
            UUID user = UUID.fromString(challenge.userId());
            // An empty list would let any passkey answer: a user without one completes the step with TOTP.
            return stored(user).flatMap(stored -> stored.isEmpty()
                ? Mono.error(new BusinessRuleViolationException(new Violation(null,
                    PlatformErrorCodes.MFA_NOT_ENROLLED, "The user has no passkey")))
                : challenges.start(PasskeyChallenges.Purpose.SECOND_FACTOR, challenge.entry(), user)
                    .map(next -> new PasskeyOptions(verifier.requestOptions(next, stored))));
        });
    }

    /** The second step with a passkey: signs in the challenge's user. */
    @PostMapping(CHALLENGE)
    Mono<AuthController.TokenResponse> secondStep(@RequestBody(required = false) ChallengeAnswer request,
        ServerHttpRequest http) {
        return Mono.defer(() -> {
            JwtService.Challenge challenge;
            try {
                challenge = challenge(request == null ? null : request.challenge(), JwtService.Purpose.VERIFY);
            } catch (RuntimeException invalid) {
                limits.count(SignInRateLimits.Kind.SECOND_FACTOR, http, null);
                throw invalid;
            }
            limits.count(SignInRateLimits.Kind.SECOND_FACTOR, http, challenge.userId());
            if (request.credential() == null || passkeyEntry(challenge.entry()).isEmpty()) {
                return Mono.error(AuthController.loginFailed("No passkey"));
            }
            // The session belongs to the entry the challenge was issued for, never to one the request names.
            SignInSource source = AuthController.source(clients, http, challenge.entry());
            UUID identity = challenge.identityId() == null ? null : UUID.fromString(challenge.identityId());
            return answer(request.credential(), PasskeyChallenges.Purpose.SECOND_FACTOR, challenge.entry(),
                    UUID.fromString(challenge.userId()), source)
                .flatMap(result -> AuthController.answer(result, clock.instant(), identity, source, refreshTokens,
                    tokens, mfa, "Second step with a passkey of user " + challenge.userId()));
        });
    }

    /**
     * The options of a passkey's registration under the challenge of a user whose role requires a second factor and
     * who has none: a passkey instead of TOTP (decision D71 item 3).
     */
    @PostMapping(CHALLENGE + "/register/options")
    Mono<PasskeyOptions> enrolOptions(@RequestBody(required = false) PasskeyChallengeRequest request) {
        return Mono.defer(() -> {
            JwtService.Challenge challenge = challenge(request == null ? null : request.challenge(),
                JwtService.Purpose.ENROLL);
            passkeyEntry(challenge.entry()).orElseThrow(PasskeyController::disabled);
            return creationOptions(UUID.fromString(challenge.userId()), challenge.entry());
        });
    }

    /** Registers the passkey under the challenge; the user signs in again afterwards (as after enrolling TOTP). */
    @PostMapping(CHALLENGE + "/register")
    @ResponseStatus(HttpStatus.CREATED)
    Mono<Registered> enrol(@RequestBody(required = false) ChallengeAnswer request) {
        return Mono.defer(() -> {
            JwtService.Challenge challenge = challenge(request == null ? null : request.challenge(),
                JwtService.Purpose.ENROLL);
            passkeyEntry(challenge.entry()).orElseThrow(PasskeyController::disabled);
            UUID user = UUID.fromString(challenge.userId());
            return ActingUser.as(challenge.userId(), registerAnswer(user, challenge.entry(),
                request.credential(), request.name()));
        });
    }

    // ---- step-up ------------------------------------------------------------------------------------------------

    /** The options of a step-up: one of the signed-in user's passkeys answers. */
    @PostMapping(STEP_UP + "/options")
    Mono<PasskeyOptions> stepUpOptions() {
        return RequestContexts.current().flatMap(context -> CurrentSession.open(refreshTokens, context)
            .flatMap(session -> {
                UUID user = requireUser(context);
                String entry = entryOf(context);
                passkeyEntry(entry).orElseThrow(PasskeyController::disabled);
                return stored(user).flatMap(stored -> {
                    if (stored.isEmpty()) {
                        return Mono.error(new BusinessRuleViolationException(new Violation(null,
                            PlatformErrorCodes.MFA_NOT_ENROLLED, "The user has no passkey")));
                    }
                    return challenges.start(PasskeyChallenges.Purpose.STEP_UP, entry, user)
                        .map(next -> new PasskeyOptions(verifier.requestOptions(next, stored)));
                });
            }));
    }

    /**
     * Confirms the signed-in user with a passkey (section 10): a new access token whose {@code mfa_at} is now, in the
     * same session; 422 {@code PASSKEY_INVALID} when the answer does not hold.
     */
    @PostMapping(STEP_UP)
    Mono<MfaController.StepUpResponse> stepUp(@RequestBody(required = false) CredentialRequest request,
        ServerHttpRequest http) {
        return RequestContexts.current()
            .doOnNext(context -> limits.count(SignInRateLimits.Kind.SECOND_FACTOR, http, context.actorId()))
            .flatMap(context -> CurrentSession.open(refreshTokens, context).flatMap(session -> {
                UUID user = requireUser(context);
                String entry = entryOf(context);
                if (request == null || request.credential() == null || passkeyEntry(entry).isEmpty()) {
                    return Mono.error(invalid("No passkey"));
                }
                return answer(request.credential(), PasskeyChallenges.Purpose.STEP_UP, entry, user,
                        AuthController.source(clients, http, entry))
                    .onErrorMap(com.jabiz.runtime.AuthenticationFailedException.class, e -> invalid(e.getMessage()))
                    .map(result -> {
                        if (result.outcome() == LoginOutcome.REFUSED
                            || result.outcome() == LoginOutcome.EMAIL_NOT_VERIFIED) {
                            throw AuthController.refusal(result);
                        }
                        if (result.outcome() != LoginOutcome.SUCCESS) {
                            throw invalid("Step-up refused: " + result.outcome());
                        }
                        Instant now = clock.instant();
                        JwtService.Issued access = tokens.issue(AuthController.actor(result, now)
                            .inSession(session.orElse(null)));
                        return new MfaController.StepUpResponse(access.token(), access.expiresAt(), now);
                    });
            }));
    }

    // ---- the signed-in user's own passkeys ----------------------------------------------------------------------

    @GetMapping(ACCOUNT)
    Mono<List<PasskeyView>> list() {
        return RequestContexts.current().flatMap(context -> {
            UUID user = requireUser(context);
            return storages.getEngine(poolRef).select("""
                    SELECT passkey_id, name, created_at, last_used_at, backed_up
                    FROM sec_user_passkey WHERE user_id = :user ORDER BY created_at""",
                    Map.of("user", BoundValue.of(user)))
                .map(row -> new PasskeyView(Rows.string(row.get("passkey_id")), Rows.string(row.get("name")),
                    Rows.instant(row.get("created_at")),
                    row.get("last_used_at") == null ? null : Rows.instant(row.get("last_used_at")),
                    Boolean.TRUE.equals(row.get("backed_up"))))
                .collectList();
        });
    }

    /**
     * The options of adding a passkey at the session's entry, once the user proved who they are (decision D71
     * item 4): a recent second factor, the current password, or the confirmation mail's token. Without any, 422
     * {@code REAUTHENTICATION_REQUIRED} (or {@code REAUTHENTICATION_UNAVAILABLE} for a user without a password, who
     * asks for the mail).
     */
    @PostMapping(ACCOUNT + "/options")
    Mono<PasskeyOptions> addOptions(@RequestBody(required = false) AddOptionsRequest request,
        ServerHttpRequest http) {
        return RequestContexts.current().flatMap(context -> CurrentSession.open(refreshTokens, context)
            .then(Mono.defer(() -> {
                UUID user = requireUser(context);
                String entry = entryOf(context);
                passkeyEntry(entry).orElseThrow(PasskeyController::disabled);
                String token = request == null ? null : request.passkeyToken();
                Mono<Void> proven = token != null && !token.isBlank()
                    ? processes.executeAtEntry(confirmationUse, new PasskeyProcesses.ConfirmationInput(
                        user.toString(), token)).then()
                    : reauthentication.require(context, user, request == null ? null : request.currentPassword(),
                        http, passwordCheck);
                return proven.then(creationOptions(user, entry));
            })));
    }

    /** Mails the confirmation link (for users without a password): 202. */
    @PostMapping(ACCOUNT + "/confirmation")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Mono<Void> confirmation() {
        return RequestContexts.current().flatMap(context -> CurrentSession.open(refreshTokens, context)
            .then(Mono.defer(() -> {
                UUID user = requireUser(context);
                passkeyEntry(entryOf(context)).orElseThrow(PasskeyController::disabled);
                return processes.executeAtEntry(confirmationSend, new PasskeyProcesses.UserInput(user.toString()))
                    .then();
            })));
    }

    /** Adds the passkey the browser created for the options: 201 with its id. */
    @PostMapping(ACCOUNT)
    @ResponseStatus(HttpStatus.CREATED)
    Mono<Registered> add(@RequestBody(required = false) CredentialRequest request) {
        return RequestContexts.current().flatMap(context -> CurrentSession.open(refreshTokens, context)
            .then(Mono.defer(() -> {
                UUID user = requireUser(context);
                String entry = entryOf(context);
                passkeyEntry(entry).orElseThrow(PasskeyController::disabled);
                return registerAnswer(user, entry, request == null ? null : request.credential(),
                    request == null ? null : request.name());
            })));
    }

    @PostMapping(ACCOUNT + "/{passkeyId}/rename")
    Mono<PasskeyProcesses.PasskeyOutput> rename(@PathVariable String passkeyId,
        @RequestBody(required = false) RenameRequest request) {
        return RequestContexts.current().flatMap(context -> CurrentSession.open(refreshTokens, context)
            .then(Mono.defer(() -> processes.executeAtEntry(rename, new PasskeyProcesses.RenameInput(
                requireUser(context).toString(), passkeyId, request == null ? null : request.name())))));
    }

    @PostMapping(ACCOUNT + "/{passkeyId}/remove")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> remove(@PathVariable String passkeyId) {
        return RequestContexts.current().flatMap(context -> CurrentSession.open(refreshTokens, context)
            .then(Mono.defer(() -> processes.executeAtEntry(remove, new PasskeyProcesses.RemoveInput(
                requireUser(context).toString(), passkeyId)).then())));
    }

    // ---- shared -------------------------------------------------------------------------------------------------

    /**
     * Takes back the challenge an answer is for and runs the passkey sign-in for it: the challenge must be of the
     * purpose, entry and user expected. Every failure is 401 {@code LOGIN_FAILED}.
     */
    private Mono<SponsorSignInOutput> answer(JsonNode credential, PasskeyChallenges.Purpose purpose, String entry,
        UUID expectedUser, SignInSource source) {
        PasskeyVerifier.Claimed claimed;
        try {
            claimed = PasskeyVerifier.claimed(credential.toString());
        } catch (PasskeyVerifier.InvalidPasskeyException e) {
            return Mono.error(AuthController.loginFailed("Not a passkey's answer: " + e.getMessage()));
        }
        return challenges.take(claimed.challenge())
            .onErrorMap(PasskeyChallenges.InvalidChallengeException.class,
                e -> AuthController.loginFailed(e.getMessage()))
            .flatMap(pending -> {
                if (pending.purpose() != purpose || !pending.entry().equals(entry)
                    || !java.util.Objects.equals(pending.userId(), expectedUser)) {
                    return Mono.error(AuthController.loginFailed("A challenge of another ceremony"));
                }
                return processes.executeAtEntry(SponsorPasskeySignInProcess.DEFINITION,
                        new SponsorPasskeySignInInput(claimed.credentialId(), credential.toString(),
                            claimed.challenge(), expectedUser == null ? null : expectedUser.toString(), source))
                    // Two answers with one passkey at once: the later loses the race for the login record or the
                    // passkey's counter.
                    .onErrorMap(PasskeyController::raceLost, e -> AuthController.loginFailed("Concurrent attempt"));
            });
    }

    /** Takes back a registration's challenge and registers the answer in {@value PasskeyProcesses#REGISTER}. */
    private Mono<Registered> registerAnswer(UUID user, String entry, JsonNode credential, String name) {
        if (credential == null) {
            return Mono.error(invalid("No passkey"));
        }
        PasskeyVerifier.Claimed claimed;
        try {
            claimed = PasskeyVerifier.claimed(credential.toString());
        } catch (PasskeyVerifier.InvalidPasskeyException e) {
            return Mono.error(invalid(e.getMessage()));
        }
        return challenges.take(claimed.challenge())
            .onErrorMap(PasskeyChallenges.InvalidChallengeException.class, e -> invalid(e.getMessage()))
            .flatMap(pending -> {
                if (pending.purpose() != PasskeyChallenges.Purpose.REGISTER || !pending.entry().equals(entry)
                    || !user.equals(pending.userId())) {
                    return Mono.error(invalid("A challenge of another ceremony"));
                }
                return processes.executeAtEntry(register, new PasskeyProcesses.RegisterInput(user.toString(),
                        claimed.credentialId(), credential.toString(), claimed.challenge(), name))
                    .map(output -> new Registered(output.passkeyId()));
            });
    }

    /** The options of a registration for {@code user} at {@code entry}: their name, and their passkeys excluded. */
    private Mono<PasskeyOptions> creationOptions(UUID user, String entry) {
        Mono<EntityInstance> found = entities.query(datasets.findById(SecurityEntities.USER_DATASET).orElseThrow(),
                SecurityEntities.SEC_USER, EntityQuery.builder().where(new QueryPredicate.Eq("userId", user)).limit(1)
                    .build())
            .next()
            .switchIfEmpty(Mono.error(() -> new PermissionDeniedException(SecurityPermissions.ACCOUNT,
                "Only users of the platform have passkeys")));
        return Mono.zip(found, stored(user)).flatMap(both -> challenges.start(PasskeyChallenges.Purpose.REGISTER,
                entry, user)
            .map(challenge -> {
                EntityInstance account = both.getT1();
                String email = account.get("email");
                String userName = email != null && SecurityEntities.emailVerified(account) ? email
                    : account.<String>get("userName");
                return new PasskeyOptions(verifier.creationOptions(challenge, user, userName,
                    account.get("displayName"), both.getT2()));
            }));
    }

    /** The user's passkeys as options name them. */
    private Mono<List<PasskeyVerifier.StoredCredential>> stored(UUID user) {
        return storages.getEngine(poolRef).select("""
                SELECT credential_id, transports FROM sec_user_passkey WHERE user_id = :user ORDER BY created_at""",
                Map.of("user", BoundValue.of(user)))
            .map(row -> new PasskeyVerifier.StoredCredential(Rows.string(row.get("credential_id")),
                row.get("transports") == null ? null : Rows.string(row.get("transports"))))
            .collectList();
    }

    /** The entry, if it takes passkeys and passkeys are configured. */
    private Optional<SignInEntries.Entry> passkeyEntry(String name) {
        return entries.find(name).filter(entry -> entry.passkeys() && verifier.usable());
    }

    private JwtService.Challenge challenge(String token, JwtService.Purpose purpose) {
        if (token == null || token.isBlank()) {
            throw AuthController.loginFailed("Missing challenge");
        }
        try {
            JwtService.Challenge challenge = tokens.verifyChallenge(token, purpose);
            UUID.fromString(challenge.userId());
            return challenge;
        } catch (JwtService.InvalidTokenException | IllegalArgumentException e) {
            throw AuthController.loginFailed("Invalid challenge: " + e.getMessage());
        }
    }

    private static String entryOf(RequestContext context) {
        return context.entry() == null ? RequestContext.DEFAULT_ENTRY : context.entry();
    }

    private static UUID requireUser(RequestContext context) {
        return ActingUser.userId(context).orElseThrow(() -> new PermissionDeniedException(SecurityPermissions.ACCOUNT,
            "Only users of the platform have passkeys"));
    }

    private static boolean raceLost(Throwable e) {
        return e instanceof ValidationException v && v.violations().stream()
            .anyMatch(violation -> PlatformErrorCodes.UNIQUE_VIOLATION.equals(violation.ruleCode()))
            || e instanceof com.jabiz.runtime.ConcurrentUpdateException;
    }

    private static BusinessRuleViolationException disabled() {
        return new BusinessRuleViolationException(new Violation(null, PlatformErrorCodes.PASSKEY_DISABLED,
            "Passkeys are not used here"));
    }

    private static BusinessRuleViolationException invalid(String message) {
        log.info("Passkey refused: {}", message);
        return new BusinessRuleViolationException(new Violation(null, PlatformErrorCodes.PASSKEY_INVALID, message));
    }
}
