package com.jabiz.runtime.security;

import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.i18n.PlatformLanguages;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.process.ProcessExecutor;
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

    /**
     * @param entry  the sign-in entry whose front end the link leads to; it must sign in by link (422
     *               {@code SIGN_UP_DISABLED} otherwise, whatever the address)
     * @param to     the path of that front end to return to (a relative path, at most 200 characters); optional
     * @param locale the language of the mail to an address with no account ({@code zh}, {@code ja}, {@code en});
     *               optional
     */
    record EmailLinkRequest(String email, String entry, String to, String locale) {}

    record EmailLinkConfirm(@Sensitive String token) {
        @Override
        public String toString() {
            return "EmailLinkConfirm[***]";
        }
    }

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
            if (!EmailLinkProcesses.returnPath(to)) {
                problems.add(SignUpController.invalid("to", "A path of the entry's front end, at most "
                    + EmailLinkProcesses.MAX_TO + " characters"));
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
            return processes.executeAtEntry(open, new EmailLinkProcesses.OpenInput(request == null ? null
                    : request.token()))
                .flatMap(opened -> {
                    SignInSource source = AuthController.source(clients, http, opened.entry());
                    SponsorEmailLinkSignInInput input = new SponsorEmailLinkSignInInput(opened.userId(), source);
                    return processes.executeAtEntry(SponsorEmailLinkSignInProcess.DEFINITION, input)
                        // Two sign-ins of one user at once: the later loses the race for the next login record.
                        .onErrorResume(EmailLinkController::raceLost, e -> processes.executeAtEntry(
                            SponsorEmailLinkSignInProcess.DEFINITION, input))
                        .flatMap(result -> AuthController.answer(result, null, null, source, refreshTokens, tokens,
                            mfa, "Sign-in with a mailed link"));
                });
        });
    }

    private static boolean raceLost(Throwable error) {
        return error instanceof ValidationException v && v.violations().stream()
            .anyMatch(violation -> PlatformErrorCodes.UNIQUE_VIOLATION.equals(violation.ruleCode()));
    }
}
