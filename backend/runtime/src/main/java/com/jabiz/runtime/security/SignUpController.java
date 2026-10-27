package com.jabiz.runtime.security;

import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.i18n.PlatformLanguages;
import com.jabiz.mail.MailRecipient;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.resource.ResourceNotFoundException;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.process.ExecutionOptions;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.ProcessResult;
import com.jabiz.runtime.web.ClientAddresses;
import com.jabiz.runtime.web.JabizWebProperties;
import com.jabiz.security.Sensitive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Self-registration, address verification and password reset over HTTP (docs/design/10-security.md section 15;
 * decision D36 items 2, 3 and 5). Anonymous, except {@value #ACCOUNT_VERIFY} and the use of a verification link in a
 * session ({@value #ACCOUNT_VERIFY_CHECK}, {@value #ACCOUNT_VERIFY_CONFIRM}); each runs one process of
 * {@link SignUpProcesses}.
 *
 * <p>Nothing here tells whether an address belongs to an account: registering, resending a verification and asking
 * for a password reset answer 202 with no body whatever the address, after checking every value of the request first
 * (so that a bad value fails alike for every address) and counting the request against the rate limits of the client
 * and of the address ({@link SignUpRateLimits}). The links of the mails (joining, verifying, resetting) answer 204,
 * or 422 {@code TOKEN_INVALID} for any link that is not good, and count against the client's limit.
 */
@RestController
class SignUpController {

    static final String REGISTER = "/api/auth/register";
    static final String JOIN = "/api/auth/join";
    static final String VERIFY = "/api/auth/verify-email";
    static final String VERIFY_CHECK = "/api/auth/verify-email/check";
    static final String VERIFY_RESEND = "/api/auth/verify-email/resend";
    static final String ACCOUNT_VERIFY = "/api/auth/account/verify-email";
    /** The same link's check and use in the signed-in user's own session (decision D61 item 4). */
    static final String ACCOUNT_VERIFY_CHECK = "/api/auth/account/verify-email/check";
    static final String ACCOUNT_VERIFY_CONFIRM = "/api/auth/account/verify-email/confirm";
    static final String RESET_REQUEST = "/api/auth/password-reset/request";
    static final String RESET_CONFIRM = "/api/auth/password-reset/confirm";
    static final String ENTRIES = "/api/auth/entries";

    /** Longest display name ({@code SecUser.displayName}). */
    static final int MAX_DISPLAY_NAME = 200;
    /** Longest address ({@code SecUser.email}). */
    static final int MAX_EMAIL = 320;

    /**
     * @param entry       the sign-in entry to register with; it must allow registration
     * @param displayName optional
     * @param locale      the language of the user's mail ({@code zh}, {@code ja}, {@code en}); optional
     */
    record RegisterRequest(String entry, String email, @Sensitive String password, String displayName,
        String locale) {
        @Override
        public String toString() {
            return "RegisterRequest[entry=" + entry + ", email=" + email + ", password=***]";
        }
    }

    /**
     * @param password a new password: required for an account that registered itself and was never verified (422
     *                 {@code REQUIRED} for {@code password} without), ignored otherwise
     */
    record JoinRequest(@Sensitive String token, @Sensitive String password) {
        @Override
        public String toString() {
            return "JoinRequest[***]";
        }
    }

    record TokenRequest(@Sensitive String token) {
        @Override
        public String toString() {
            return "TokenRequest[***]";
        }
    }

    /**
     * @param password    the account's current password, or null
     * @param newPassword a new password, or null; one of the two is needed for an account that registered itself and
     *                    was never verified ({@code passwordRequired} of {@value #VERIFY_CHECK}), and ignored otherwise
     */
    record VerifyRequest(@Sensitive String token, @Sensitive String password, @Sensitive String newPassword) {
        @Override
        public String toString() {
            return "VerifyRequest[***]";
        }
    }

    /**
     * @param email the address the request is about
     * @param entry the sign-in entry whose pages the mailed link leads to (its name, as {@code GET} {@value #ENTRIES}
     *              knows it); none: the administration's ({@code admin}). An unknown entry is 400, whatever the
     *              address.
     */
    record EmailRequest(String email, String entry) {}

    record ResetConfirmRequest(@Sensitive String token, @Sensitive String password) {
        @Override
        public String toString() {
            return "ResetConfirmRequest[***]";
        }
    }

    /**
     * What a front end needs to know about its sign-in entry; nothing secret.
     *
     * @param sessionPersistence where the front end keeps the refresh token: {@code tab} ({@code sessionStorage}) or
     *                           {@code device} ({@code localStorage}; decision D43 item 2)
     * @param environment        the system the pages run on ({@code jabiz.web.environment}, phase 16zd):
     *                           {@code production}, or a test system's name, which every page shows
     */
    record EntryInfo(String name, boolean selfRegistration, boolean requireVerifiedEmail, String appPath,
        SignInEntries.Persistence sessionPersistence, boolean emailLink, String environment) {}

    private final ProcessExecutor processes;
    private final SignInEntries entries;
    private final SignUpRateLimits limits;
    private final ClientAddresses clients;
    private final PasswordHasher hasher;
    private final JabizWebProperties web;
    private final ProcessDefinition<SignUpProcesses.SignUpInput, SignUpProcesses.Done, AccountContext> signUp;
    private final ProcessDefinition<SignUpProcesses.JoinInput, SignUpProcesses.Done, AccountContext> join;
    private final ProcessDefinition<SignUpProcesses.VerifyInput, SignUpProcesses.Done, AccountContext> verify;
    private final ProcessDefinition<SignUpProcesses.VerifyInput, SignUpProcesses.VerifyCheck, AccountContext>
        verifyCheck;
    private final ProcessDefinition<SignUpProcesses.VerifySendInput, SignUpProcesses.Done, AccountContext> verifySend;
    private final ProcessDefinition<SignUpProcesses.ResetRequestInput, SignUpProcesses.Done, AccountContext>
        resetRequest;
    private final ProcessDefinition<SignUpProcesses.ResetConfirmInput, SignUpProcesses.Done, AccountContext>
        resetConfirm;

    SignUpController(ProcessExecutor processes, SignInEntries entries, SignUpRateLimits limits,
        ClientAddresses clients, PasswordHasher hasher, JabizWebProperties web,
        ProcessDefinition<SignUpProcesses.SignUpInput, SignUpProcesses.Done, AccountContext> signUp,
        ProcessDefinition<SignUpProcesses.JoinInput, SignUpProcesses.Done, AccountContext> join,
        ProcessDefinition<SignUpProcesses.VerifyInput, SignUpProcesses.Done, AccountContext> verify,
        ProcessDefinition<SignUpProcesses.VerifyInput, SignUpProcesses.VerifyCheck, AccountContext> verifyCheck,
        ProcessDefinition<SignUpProcesses.VerifySendInput, SignUpProcesses.Done, AccountContext> verifySend,
        ProcessDefinition<SignUpProcesses.ResetRequestInput, SignUpProcesses.Done, AccountContext> resetRequest,
        ProcessDefinition<SignUpProcesses.ResetConfirmInput, SignUpProcesses.Done, AccountContext> resetConfirm) {
        this.processes = processes;
        this.entries = entries;
        this.limits = limits;
        this.clients = clients;
        this.hasher = hasher;
        this.web = web;
        this.signUp = signUp;
        this.join = join;
        this.verify = verify;
        this.verifyCheck = verifyCheck;
        this.verifySend = verifySend;
        this.resetRequest = resetRequest;
        this.resetConfirm = resetConfirm;
    }

    /** 202 whether or not the address is in use; 422 {@code SIGN_UP_DISABLED} for an entry without registration. */
    @PostMapping(REGISTER)
    @ResponseStatus(HttpStatus.ACCEPTED)
    Mono<ResponseEntity<Void>> register(@RequestBody(required = false) RegisterRequest request,
        ServerHttpRequest http) {
        return Mono.defer(() -> {
            limits.client(SignUpRateLimits.Kind.REGISTER, clients.clientOf(http));
            RegisterRequest body = request == null ? new RegisterRequest(null, null, null, null, null) : request;
            if (entries.find(body.entry()).filter(SignInEntries.Entry::selfRegistration).isEmpty()) {
                return Mono.error(new SignUpDisabledException("Registration is not open at entry " + body.entry()));
            }
            List<Violation> problems = new ArrayList<>(emailProblems(body.email()));
            problems.addAll(hasher.check("password", body.password()));
            String displayName = blank(body.displayName()) ? null : body.displayName().strip();
            if (displayName != null && (displayName.length() > MAX_DISPLAY_NAME || hasControl(displayName))) {
                problems.add(invalid("displayName", "A display name has at most " + MAX_DISPLAY_NAME
                    + " characters and no control characters"));
            }
            String locale = blank(body.locale()) ? null : body.locale().strip();
            if (locale != null && !PlatformLanguages.isSupported(locale)) {
                problems.add(invalid("locale", "Not one of the platform's languages"));
            }
            if (!problems.isEmpty()) {
                return Mono.error(new ValidationException(problems));
            }
            String email = body.email().strip();
            limits.email(SignUpRateLimits.Kind.REGISTER, email);
            SignUpProcesses.SignUpInput input = new SignUpProcesses.SignUpInput(entries.find(body.entry())
                .orElseThrow().name(), email, body.password(), displayName, locale);
            return processes.executeAtEntry(signUp, input)
                // A concurrent registration of the same address got there first: once more, now as an address in
                // use, so that its user gets the mail an address in use gets.
                .onErrorResume(SignUpController::addressTaken, e -> processes.executeAtEntry(signUp, input))
                .thenReturn(accepted());
        });
    }

    @PostMapping(JOIN)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<ResponseEntity<Void>> join(@RequestBody(required = false) JoinRequest request, ServerHttpRequest http) {
        return Mono.defer(() -> {
            limits.client(SignUpRateLimits.Kind.LINK, clients.clientOf(http));
            return processes.executeAtEntry(join, new SignUpProcesses.JoinInput(request == null ? null : request.token(),
                    request == null ? null : request.password()))
                .thenReturn(ResponseEntity.noContent().<Void>build());
        });
    }

    /**
     * Proves the address with the link of a verification mail: 204. An account that registered itself and was never
     * verified also needs its current password or a new one (422 {@code REQUIRED} or {@code PASSWORD_INCORRECT} for
     * {@code password}); a new one takes it over from whoever registered it.
     */
    @PostMapping(VERIFY)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<ResponseEntity<Void>> verify(@RequestBody(required = false) VerifyRequest request, ServerHttpRequest http) {
        return Mono.defer(() -> {
            limits.client(SignUpRateLimits.Kind.LINK, clients.clientOf(http));
            VerifyRequest body = request == null ? new VerifyRequest(null, null, null) : request;
            return processes.executeAtEntry(verify, new SignUpProcesses.VerifyInput(body.token(), body.password(),
                    body.newPassword()))
                .thenReturn(ResponseEntity.noContent().<Void>build());
        });
    }

    /**
     * What verifying with a link needs, without using it (a dry run): whether a password is required. Told from the
     * link alone (422 {@code TOKEN_INVALID} for any link that is no good), so it tells nothing about other addresses;
     * the page asks before anything changes, and a mail scanner opening the link changes nothing.
     */
    @PostMapping(VERIFY_CHECK)
    Mono<SignUpProcesses.VerifyCheck> verifyCheck(@RequestBody(required = false) TokenRequest request,
        ServerHttpRequest http) {
        return Mono.defer(() -> {
            limits.client(SignUpRateLimits.Kind.LINK, clients.clientOf(http));
            return processes.run(verifyCheck, new SignUpProcesses.VerifyInput(request == null ? null
                    : request.token()), ExecutionOptions.DRY_RUN.atEntryOf(verifyCheck))
                .map(ProcessResult::output);
        });
    }

    /**
     * 202 whatever the address; only an enabled user that registered itself and was never verified gets a mail (an
     * address an administrator set is verified by its user, signed in: {@value #ACCOUNT_VERIFY}).
     */
    @PostMapping(VERIFY_RESEND)
    @ResponseStatus(HttpStatus.ACCEPTED)
    Mono<ResponseEntity<Void>> resend(@RequestBody(required = false) EmailRequest request, ServerHttpRequest http) {
        return Mono.defer(() -> {
            limits.client(SignUpRateLimits.Kind.VERIFY_RESEND, clients.clientOf(http));
            String[] valid = emailAndEntry(request);
            limits.email(SignUpRateLimits.Kind.VERIFY_RESEND, valid[0]);
            return processes.executeAtEntry(verifySend, new SignUpProcesses.VerifySendInput(valid[1], valid[0], null))
                .thenReturn(accepted());
        });
    }

    /**
     * The link of a verification mail used in a session (decision D61 item 4): when the session is the account's own,
     * it stands for the account's current password, so a pre-claimable account is verified and kept (an account opened
     * through an identity provider has no password). In anybody else's session the link needs what it needs
     * anonymously. The anonymous endpoints never look at an access token, so a stale one cannot break a link.
     */
    @PostMapping(ACCOUNT_VERIFY_CHECK)
    Mono<SignUpProcesses.VerifyCheck> verifyCheckOwn(@RequestBody(required = false) TokenRequest request,
        ServerHttpRequest http) {
        return verifyCheck(request, http);
    }

    /** As {@value #VERIFY}, in a session: 204 (see {@link #verifyCheckOwn}). */
    @PostMapping(ACCOUNT_VERIFY_CONFIRM)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<ResponseEntity<Void>> verifyOwn(@RequestBody(required = false) VerifyRequest request,
        ServerHttpRequest http) {
        return verify(request, http);
    }

    /** The signed-in user's own request for a new verification mail, for the entry of the session; 202. */
    @PostMapping(ACCOUNT_VERIFY)
    @ResponseStatus(HttpStatus.ACCEPTED)
    Mono<ResponseEntity<Void>> resendOwn() {
        return RequestContexts.current().flatMap(context -> {
            UUID user = ActingUser.userId(context).orElseThrow(() -> new PermissionDeniedException(
                SecurityPermissions.ACCOUNT, "Only users of the platform have an address to verify"));
            limits.user(SignUpRateLimits.Kind.VERIFY_RESEND, user.toString());
            return processes.executeAtEntry(verifySend, new SignUpProcesses.VerifySendInput(context.entry(), null,
                    user.toString()))
                .thenReturn(accepted());
        });
    }

    /**
     * 202 whatever the address; only an enabled user with a trusted address (verified, or registered by itself and never
     * verified) gets a mail.
     */
    @PostMapping(RESET_REQUEST)
    @ResponseStatus(HttpStatus.ACCEPTED)
    Mono<ResponseEntity<Void>> resetRequest(@RequestBody(required = false) EmailRequest request,
        ServerHttpRequest http) {
        return Mono.defer(() -> {
            limits.client(SignUpRateLimits.Kind.PASSWORD_RESET, clients.clientOf(http));
            String[] valid = emailAndEntry(request);
            limits.email(SignUpRateLimits.Kind.PASSWORD_RESET, valid[0]);
            return processes.executeAtEntry(resetRequest, new SignUpProcesses.ResetRequestInput(valid[1], valid[0]))
                .thenReturn(accepted());
        });
    }

    /** 204; the user signs in with the new password afterwards (no session is opened here). */
    @PostMapping(RESET_CONFIRM)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<ResponseEntity<Void>> resetConfirm(@RequestBody(required = false) ResetConfirmRequest request,
        ServerHttpRequest http) {
        return Mono.defer(() -> {
            limits.client(SignUpRateLimits.Kind.LINK, clients.clientOf(http));
            return processes.executeAtEntry(resetConfirm, new SignUpProcesses.ResetConfirmInput(
                    request == null ? null : request.token(), request == null ? null : request.password(),
                    AuthController.source(clients, http, null)))
                .thenReturn(ResponseEntity.noContent().<Void>build());
        });
    }

    /**
     * Whether the entry lets people register, requires a verified address, where its pages are and where its sessions
     * are kept; 404 if none.
     */
    @GetMapping(ENTRIES + "/{name}")
    Mono<EntryInfo> entry(@PathVariable String name) {
        return Mono.fromCallable(() -> entries.find(name)
            .map(entry -> new EntryInfo(entry.name(), entry.selfRegistration(), entry.requireVerifiedEmail(),
                entry.appPath(), entry.session().persistence(), entry.emailLink(), web.environment()))
            .orElseThrow(() -> new ResourceNotFoundException("No sign-in entry " + name)));
    }

    /** The checked address (without surrounding blanks) and the entry's name; 400 for either one wrong. */
    private String[] emailAndEntry(EmailRequest request) {
        EmailRequest body = request == null ? new EmailRequest(null, null) : request;
        List<Violation> problems = new ArrayList<>(emailProblems(body.email()));
        java.util.Optional<SignInEntries.Entry> entry = entries.find(body.entry());
        if (entry.isEmpty()) {
            problems.add(invalid("entry", "No such sign-in entry"));
        }
        if (!problems.isEmpty()) {
            throw new ValidationException(problems);
        }
        return new String[] {body.email().strip(), entry.get().name()};
    }

    /** 202 without a body, saying so ({@code Content-Length: 0}): the same for every address. */
    static ResponseEntity<Void> accepted() {
        return ResponseEntity.accepted().contentLength(0).build();
    }

    /** The unique violation on the address of a registration that came second. */
    private static boolean addressTaken(Throwable error) {
        return error instanceof ValidationException v && v.violations().stream().anyMatch(violation ->
            PlatformErrorCodes.UNIQUE_VIOLATION.equals(violation.ruleCode()) && "email".equals(violation.field()));
    }

    static List<Violation> emailProblems(String email) {
        if (blank(email)) {
            return List.of(new Violation("email", PlatformErrorCodes.REQUIRED, "An e-mail address is required",
                Map.of("field", "email")));
        }
        String value = email.strip();
        if (value.length() > MAX_EMAIL || !MailRecipient.ADDRESS.matcher(value).matches()) {
            return List.of(invalid("email", "Not an e-mail address"));
        }
        return List.of();
    }

    static Violation invalid(String field, String message) {
        return new Violation(field, PlatformErrorCodes.INVALID_VALUE, message, Map.of("field", field));
    }

    private static boolean hasControl(String value) {
        return value.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
