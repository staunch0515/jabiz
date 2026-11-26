package com.jabiz.runtime.security;

import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.i18n.PlatformLanguages;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.process.ExecutionOptions;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.ProcessResult;
import com.jabiz.runtime.process.sponsor.SignInSource;
import com.jabiz.runtime.process.sponsor.SponsorEmailLinkSignInInput;
import com.jabiz.runtime.process.sponsor.SponsorEmailLinkSignInProcess;
import com.jabiz.runtime.web.ClientAddresses;
import com.jabiz.security.Sensitive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * Signing in with a mailed one-time link over HTTP (docs/design/10-security.md section 15.11; decision D54).
 * Anonymous and rate limited. Asking for a link answers 202 whatever the address, after checking every value of the
 * request first (a bad value fails alike for every address); using one answers as {@code POST /api/auth/login} does
 * (tokens, a second-factor challenge, or the refusal), or 422 {@code TOKEN_INVALID} for any link that is not good.
 */
@RestController
class EmailLinkController {

    static final String REQUEST = "/api/auth/email-link";
    static final String CONFIRM = "/api/auth/email-link/confirm";
    static final String CHECK = "/api/auth/email-link/check";

    /**
     * @param entry  the sign-in entry whose front end the link leads to; it must sign in by link (422
     *               {@code SIGN_UP_DISABLED} otherwise, whatever the address)
     * @param to     the path of that front end to return to (a relative path, at most 200 characters); optional
     * @param locale the language of the mail to an address with no account ({@code zh}, {@code ja}, {@code en});
     *               optional
     */
    record EmailLinkRequest(String email, String entry, String to, String locale) {}

    /**
     * @param displayName the name of the account a link to an address nobody has opens, at an entry with one
     *                    "continue" (required there: 422 {@code REQUIRED}, the link unused; decision D84); ignored
     *                    otherwise
     * @param locale      that account's language ({@code zh}, {@code ja}, {@code en}); optional
     */
    record EmailLinkConfirm(@Sensitive String token, String displayName, String locale) {
        @Override
        public String toString() {
            return "EmailLinkConfirm[***]";
        }
    }

    record EmailLinkToken(@Sensitive String token) {
        @Override
        public String toString() {
            return "EmailLinkToken[***]";
        }
    }

    /**
     * What a link would do, told to whoever holds it (decision D84 item 4).
     *
     * @param signUp whether using it first needs the new account's name ({@code displayName} of the confirmation)
     */
    record EmailLinkCheck(boolean signUp) {}

    private final ProcessExecutor processes;
    private final SignInEntries entries;
    private final SignUpRateLimits limits;
    private final ClientAddresses clients;
    private final JwtService tokens;
    private final RefreshTokenStore refreshTokens;
    private final MfaSettings mfa;
    private final ProcessDefinition<EmailLinkProcesses.SendInput, SignUpProcesses.Done, AccountContext> send;
    private final ProcessDefinition<EmailLinkProcesses.OpenInput, EmailLinkProcesses.OpenOutput, AccountContext> open;

    EmailLinkController(ProcessExecutor processes, SignInEntries entries, SignUpRateLimits limits,
        ClientAddresses clients, JwtService tokens, RefreshTokenStore refreshTokens, MfaSettings mfa,
        ProcessDefinition<EmailLinkProcesses.SendInput, SignUpProcesses.Done, AccountContext> send,
        ProcessDefinition<EmailLinkProcesses.OpenInput, EmailLinkProcesses.OpenOutput, AccountContext> open) {
        this.processes = processes;
        this.entries = entries;
        this.limits = limits;
        this.clients = clients;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.mfa = mfa;
        this.send = send;
        this.open = open;
    }

    /** 202 whatever the address: an account's user or an address nobody uses gets a link, as the entry allows. */
    @PostMapping(REQUEST)
    @ResponseStatus(HttpStatus.ACCEPTED)
    Mono<ResponseEntity<Void>> requestEmailLink(@RequestBody(required = false) EmailLinkRequest request,
        ServerHttpRequest http) {
        return Mono.defer(() -> {
            limits.client(SignUpRateLimits.Kind.EMAIL_LINK, clients.clientOf(http));
            EmailLinkRequest body = request == null ? new EmailLinkRequest(null, null, null, null) : request;
            SignInEntries.Entry entry = entries.find(body.entry()).filter(SignInEntries.Entry::emailLink).orElse(null);
            if (entry == null) {
                return Mono.error(new SignUpDisabledException("Signing in by link is not open at entry "
                    + body.entry()));
            }
            List<Violation> problems = new ArrayList<>(SignUpController.emailProblems(body.email()));
            String to = body.to() == null ? "" : body.to().strip();
            if (!EmailLinkProcesses.returnPath(to, entry.appPath())) {
                problems.add(SignUpController.invalid("to", "A path within the entry's front end (" + entry.appPath()
                    + "), at most " + EmailLinkProcesses.MAX_TO + " characters"));
            }
            String locale = body.locale() == null || body.locale().isBlank() ? null : body.locale().strip();
            if (locale != null && !PlatformLanguages.isSupported(locale)) {
                problems.add(SignUpController.invalid("locale", "Not one of the platform's languages"));
            }
            if (!problems.isEmpty()) {
                return Mono.error(new ValidationException(problems));
            }
            String email = body.email().strip();
            limits.email(SignUpRateLimits.Kind.EMAIL_LINK, email);
            return processes.executeAtEntry(send, new EmailLinkProcesses.SendInput(entry.name(), email, to, locale))
                .thenReturn(SignUpController.accepted());
        });
    }

    /**
     * Uses a link: signs in the user it stands for (opening the account first for an address nobody used), as
     * {@code POST /api/auth/login} answers, at the entry the link was mailed for.
     */
    @PostMapping(CONFIRM)
    Mono<AuthController.TokenResponse> confirmEmailLink(@RequestBody(required = false) EmailLinkConfirm request,
        ServerHttpRequest http) {
        return Mono.defer(() -> {
            limits.client(SignUpRateLimits.Kind.LINK, clients.clientOf(http));
            EmailLinkConfirm body = request == null ? new EmailLinkConfirm(null, null, null) : request;
            List<Violation> problems = new ArrayList<>();
            String displayName = body.displayName() == null || body.displayName().isBlank() ? null
                : body.displayName().strip();
            if (displayName != null && (displayName.length() > SignUpController.MAX_DISPLAY_NAME
                || SignUpController.hasControl(displayName))) {
                problems.add(SignUpController.invalid(EmailLinkProcesses.DISPLAY_NAME, "A display name has at most "
                    + SignUpController.MAX_DISPLAY_NAME + " characters and no control characters"));
            }
            String locale = body.locale() == null || body.locale().isBlank() ? null : body.locale().strip();
            if (locale != null && !PlatformLanguages.isSupported(locale)) {
                problems.add(SignUpController.invalid("locale", "Not one of the platform's languages"));
            }
            if (!problems.isEmpty()) {
                return Mono.error(new ValidationException(problems));
            }
            EmailLinkProcesses.OpenInput input = new EmailLinkProcesses.OpenInput(body.token(), displayName, locale,
                false);
            return processes.executeAtEntry(open, input)
                // Another link of the address opened the account at the same moment: this one, rolled back and still
                // unused, now finds that account (and stands for it, or is refused as used).
                .onErrorResume(EmailLinkController::raceLost, e -> processes.executeAtEntry(open, input))
                .flatMap(opened -> {
                    SignInSource source = AuthController.source(clients, http, opened.entry());
                    SponsorEmailLinkSignInInput signIn = new SponsorEmailLinkSignInInput(opened.userId(), source);
                    return processes.executeAtEntry(SponsorEmailLinkSignInProcess.DEFINITION, signIn)
                        // Two sign-ins of one user at once: the later loses the race for the next login record.
                        .onErrorResume(EmailLinkController::raceLost, e -> processes.executeAtEntry(
                            SponsorEmailLinkSignInProcess.DEFINITION, signIn))
                        .flatMap(result -> AuthController.answer(result, null, null, source, refreshTokens, tokens,
                            mfa, "Sign-in with a mailed link"));
                });
        });
    }

    /**
     * Whether using a link first needs the new account's name, without using it (a dry run): told from the link alone
     * (422 {@code TOKEN_INVALID} for any link that is no good, the refusals of the sign-up guards as on using it), so
     * it tells nothing about other addresses; a mail scanner opening the link changes nothing.
     */
    @PostMapping(CHECK)
    Mono<EmailLinkCheck> checkEmailLink(@RequestBody(required = false) EmailLinkToken request,
        ServerHttpRequest http) {
        return Mono.defer(() -> {
            limits.client(SignUpRateLimits.Kind.LINK, clients.clientOf(http));
            EmailLinkProcesses.OpenInput input = new EmailLinkProcesses.OpenInput(request == null ? null
                : request.token(), null, null, true);
            return processes.run(open, input, ExecutionOptions.DRY_RUN.atEntryOf(open))
                .map(ProcessResult::output)
                .map(opened -> new EmailLinkCheck(opened.created() && entries.find(opened.entry())
                    .filter(SignInEntries.Entry::unifiedSignIn).isPresent()));
        });
    }

    private static boolean raceLost(Throwable error) {
        return error instanceof ValidationException v && v.violations().stream()
            .anyMatch(violation -> PlatformErrorCodes.UNIQUE_VIOLATION.equals(violation.ruleCode()));
    }
}
