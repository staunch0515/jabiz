package com.jabiz.runtime.security;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.sponsor.MfaContext;
import com.jabiz.runtime.process.sponsor.SignInSource;
import com.jabiz.runtime.process.sponsor.SponsorMfaVerifyInput;
import com.jabiz.runtime.web.ClientAddresses;
import org.springframework.http.server.reactive.ServerHttpRequest;
import com.jabiz.runtime.process.sponsor.SponsorMfaVerifyProcess;
import com.jabiz.runtime.process.sponsor.SponsorSignInOutput;
import com.jabiz.security.LoginOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The second factor (docs/design/10-security.md sections 9 and 10; decision D28): the second step of a sign-in and
 * enrolment under a challenge ({@value #CHALLENGE}/**, public: the challenge is the credential), and for signed-in
 * users their own status, enrolment and step-up.
 */
@RestController
class MfaController {

    static final String CHALLENGE = "/api/auth/challenge";

    private static final Logger log = LoggerFactory.getLogger(MfaController.class);

    /** Not process inputs: masked by their own toString only (no name-based masking of "code"). */
    record ChallengeRequest(String challenge, String code) {
        @Override
        public String toString() {
            return "ChallengeRequest[***]";
        }
    }

    record CodeRequest(String code) {
        @Override
        public String toString() {
            return "CodeRequest[***]";
        }
    }

    /**
     * @param enrolled          a confirmed second factor is set up
     * @param pending           an enrolment has started and awaits its code
     * @param recoveryCodesLeft unused recovery codes
     */
    record MfaStatus(boolean enrolled, boolean pending, int recoveryCodesLeft) {}

    record StepUpResponse(String accessToken, Instant accessTokenExpiresAt, Instant mfaAt) {
        @Override
        public String toString() {
            return "StepUpResponse[token=***, mfaAt=" + mfaAt + "]";
        }
    }

    private final ProcessExecutor processes;
    private final JwtService tokens;
    private final RefreshTokenStore refreshTokens;
    private final DatasetEntityManager entities;
    private final DatasetRegistry datasets;
    private final Clock clock;
    private final ClientAddresses clients;

    MfaController(ProcessExecutor processes, JwtService tokens, RefreshTokenStore refreshTokens,
        DatasetEntityManager entities, DatasetRegistry datasets, Clock clock, ClientAddresses clients) {
        this.clients = clients;
        this.processes = processes;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.entities = entities;
        this.datasets = datasets;
        this.clock = clock;
    }

    /** The second step of a sign-in: a TOTP or recovery code under the challenge the password step returned. */
    @PostMapping(CHALLENGE + "/verify")
    Mono<AuthController.TokenResponse> verify(@RequestBody(required = false) ChallengeRequest request,
        ServerHttpRequest http) {
        return Mono.defer(() -> {
            JwtService.Challenge challenge = challenge(request, JwtService.Purpose.VERIFY);
            if (blank(request.code())) {
                return Mono.error(AuthController.loginFailed("Missing code"));
            }
            // The session belongs to the entry the challenge was issued for, never to one the request names.
            return verifyCode(challenge.userId(), request.code(), challenge.attemptNo(),
                    AuthController.source(clients, http, challenge.entry()))
                .onErrorMap(MfaController::concurrentAttempt, e -> AuthController.loginFailed("Concurrent attempt"))
                .flatMap(result -> {
                    if (result.outcome() != LoginOutcome.SUCCESS) {
                        log.info("Second factor of user {} refused: {}", challenge.userId(),
                            result.refusal() != null ? result.refusal() : result.outcome());
                        return Mono.error(result.refusal() == null ? AuthController.refusal(result)
                            : AuthController.loginFailed("Second factor refused"));
                    }
                    // A sign-in through an identity provider stays tied to its account link (section 12).
                    return AuthController.session(refreshTokens, tokens,
                        AuthController.actor(result, clock.instant()), UUID.fromString(result.userId()),
                        challenge.identityId() == null ? null : UUID.fromString(challenge.identityId()));
                });
        });
    }

    /** Enrolment of a user whose role requires a second factor, under the challenge of the password step. */
    @PostMapping(CHALLENGE + "/enroll")
    Mono<MfaProcesses.Enrollment> enrollUnderChallenge(@RequestBody(required = false) ChallengeRequest request) {
        return Mono.defer(() -> {
            JwtService.Challenge challenge = challenge(request, JwtService.Purpose.ENROLL);
            return ActingUser.as(challenge.userId(), processes.execute(MfaProcesses.ENROLL_BEGIN,
                new MfaProcesses.UserInput(challenge.userId())));
        });
    }

    @PostMapping(CHALLENGE + "/enroll/confirm")
    Mono<MfaProcesses.RecoveryCodeList> confirmUnderChallenge(@RequestBody(required = false) ChallengeRequest request) {
        return Mono.defer(() -> {
            JwtService.Challenge challenge = challenge(request, JwtService.Purpose.ENROLL);
            return ActingUser.as(challenge.userId(), processes.execute(MfaProcesses.ENROLL_CONFIRM,
                new MfaProcesses.ConfirmInput(challenge.userId(), request.code())));
        });
    }

    @GetMapping("/api/auth/mfa")
    Mono<MfaStatus> status() {
        return RequestContexts.current().flatMap(context -> ActingUser.userId(context)
            .map(user -> entities.query(datasets.findById(SecurityEntities.USER_MFA_DATASET).orElseThrow(),
                    SecurityEntities.SEC_USER_MFA, EntityQuery.builder()
                        .where(new QueryPredicate.Eq("userId", user)).limit(1).build())
                .next()
                .map(mfa -> {
                    boolean confirmed = Boolean.TRUE.equals(mfa.get("confirmed"));
                    return new MfaStatus(confirmed, !confirmed,
                        confirmed ? MfaCodes.hashes(mfa.get("recoveryCodes")).size() : 0);
                })
                .defaultIfEmpty(new MfaStatus(false, false, 0)))
            .orElseGet(() -> Mono.just(new MfaStatus(false, false, 0))));
    }

    @PostMapping("/api/auth/mfa/enroll")
    Mono<MfaProcesses.Enrollment> enroll() {
        return RequestContexts.current().flatMap(context -> processes.execute(MfaProcesses.ENROLL_BEGIN,
            new MfaProcesses.UserInput(requireUser(context).toString())));
    }

    @PostMapping("/api/auth/mfa/enroll/confirm")
    Mono<MfaProcesses.RecoveryCodeList> confirm(@RequestBody(required = false) CodeRequest request) {
        return RequestContexts.current().flatMap(context -> processes.execute(MfaProcesses.ENROLL_CONFIRM,
            new MfaProcesses.ConfirmInput(requireUser(context).toString(), request == null ? null : request.code())));
    }

    /**
     * Confirms the signed-in user with a second factor for operations that require a recent one (section 10): a new
     * access token whose {@code mfa_at} is now. Wrong codes count towards the lock like at sign-in.
     */
    @PostMapping("/api/auth/step-up")
    Mono<StepUpResponse> stepUp(@RequestBody(required = false) CodeRequest request, ServerHttpRequest http) {
        return RequestContexts.current().flatMap(context -> {
            UUID user = requireUser(context);
            if (request == null || blank(request.code())) {
                return Mono.error(codeInvalid());
            }
            // A step-up stays in the entry of the caller's session (decision D36), and is recorded with its source.
            return verifyCode(user.toString(), request.code(), null,
                    AuthController.source(clients, http, context.entry()))
                .onErrorMap(MfaController::concurrentAttempt, e -> codeInvalid())
                .map(result -> {
                    if (MfaContext.NOT_ENROLLED.equals(result.refusal())) {
                        throw new BusinessRuleViolationException(new Violation(null,
                            PlatformErrorCodes.MFA_NOT_ENROLLED, "No second factor is set up"));
                    }
                    // Refusals after a right code are told apart as at sign-in (decision D36 implementation note 2).
                    if (result.outcome() == LoginOutcome.REFUSED
                        || result.outcome() == LoginOutcome.EMAIL_NOT_VERIFIED) {
                        throw AuthController.refusal(result);
                    }
                    if (result.outcome() != LoginOutcome.SUCCESS) {
                        throw codeInvalid();
                    }
                    Instant now = clock.instant();
                    JwtService.Issued access = tokens.issue(AuthController.actor(result, now));
                    return new StepUpResponse(access.token(), access.expiresAt(), now);
                });
        });
    }

    private Mono<SponsorSignInOutput> verifyCode(String userId, String code, Long attemptNo, SignInSource source) {
        return processes.execute(SponsorMfaVerifyProcess.DEFINITION, new SponsorMfaVerifyInput(userId, code,
            attemptNo, source));
    }


    private JwtService.Challenge challenge(ChallengeRequest request, JwtService.Purpose purpose) {
        if (request == null || blank(request.challenge())) {
            throw AuthController.loginFailed("Missing challenge");
        }
        try {
            JwtService.Challenge challenge = tokens.verifyChallenge(request.challenge(), purpose);
            UUID.fromString(challenge.userId());
            return challenge;
        } catch (JwtService.InvalidTokenException | IllegalArgumentException e) {
            throw AuthController.loginFailed("Invalid challenge: " + e.getMessage());
        }
    }


    private static UUID requireUser(RequestContext context) {
        return ActingUser.userId(context).orElseThrow(() -> new BusinessRuleViolationException(new Violation(null,
            PlatformErrorCodes.MFA_NOT_ENROLLED, "Only users of the platform have a second factor")));
    }

    /** Two attempts on one account at once: the later loses the race for the next login record. */
    private static boolean concurrentAttempt(Throwable e) {
        return e instanceof ValidationException v && v.violations().stream()
            .anyMatch(violation -> PlatformErrorCodes.UNIQUE_VIOLATION.equals(violation.ruleCode()));
    }

    private static BusinessRuleViolationException codeInvalid() {
        return new BusinessRuleViolationException(List.of(new Violation("code", PlatformErrorCodes.MFA_CODE_INVALID,
            "The code is not valid")));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
