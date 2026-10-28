package com.jabiz.runtime.security;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.erasure.AccountDeletionProcesses;
import com.jabiz.runtime.erasure.AccountDeletionProcesses.AccountDeletions;
import com.jabiz.runtime.erasure.ErasureNotices;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.web.ClientAddresses;
import com.jabiz.security.Sensitive;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Asking for the deletion of one's account over HTTP (docs/design/10-security.md section 17.5; decision D62 item 2), at
 * a sign-in entry with {@code account-deletion} (422 {@code ACCOUNT_DELETION_UNAVAILABLE} elsewhere).
 * <ul>
 *   <li>Signed in ({@code /api/auth/account/deletion}): the state and the applications' notices; the request, after
 *       proving again who one is ({@link Reauthentication}); its withdrawal. An access token outliving its session acts
 *       on nothing here (401, section 15.9).</li>
 *   <li>Without the application ({@code /api/auth/account-deletion}): asking for a link answers 202 whatever the
 *       address, after checking every value of the request first; using it makes the request, or answers the notices
 *       to acknowledge (422 {@code ACCOUNT_DELETION_NOTICES}, the link still good).</li>
 * </ul>
 * Every call is counted (429 {@code RATE_LIMITED}): signed in per user, otherwise per client address and per e-mail
 * address.
 */
@RestController
class AccountDeletionController {

    static final String ACCOUNT = "/api/auth/account/deletion";
    static final String CANCEL = ACCOUNT + "/cancel";
    static final String REQUEST = "/api/auth/account-deletion";
    static final String CONFIRM = REQUEST + "/confirm";

    /** The caller's pending request. */
    record Pending(String requestId, Instant requestedTime, Instant dueTime, String channel) {}

    /**
     * @param available whether the caller's entry lets them ask here
     * @param pending   the pending request, or null
     * @param notices   what the application wants the caller to know first (when available and nothing pending)
     */
    record Status(boolean available, Pending pending, List<ErasureNotices.View> notices) {}

    /**
     * @param currentPassword     the caller's password, needed unless the session passed a second factor recently
     * @param acknowledgedNotices the codes of the notices the caller read
     */
    record AccountRequest(@Sensitive String currentPassword, List<String> acknowledgedNotices) {
        @Override
        public String toString() {
            return "AccountRequest[currentPassword=***, acknowledgedNotices=" + acknowledgedNotices + "]";
        }
    }

    /** @param entry the sign-in entry whose pages the link opens; it must let users ask for the deletion */
    record LinkRequest(String email, String entry) {}

    record LinkConfirm(@Sensitive String token, List<String> acknowledgedNotices) {
        @Override
        public String toString() {
            return "LinkConfirm[token=***, acknowledgedNotices=" + acknowledgedNotices + "]";
        }
    }

    private final ProcessExecutor processes;
    private final SignInEntries entries;
    private final SignUpRateLimits limits;
    private final ClientAddresses clients;
    private final RefreshTokenStore refreshTokens;
    private final Reauthentication reauthentication;
    private final AccountDeletions deletions;
    private final ErasureNotices notices;
    private final ProcessDefinition<IdentityProcesses.BeginInput, IdentityProcesses.BeginOutput, ProcessContext> check;
    private final ProcessDefinition<AccountDeletionProcesses.RequestInput, AccountDeletionProcesses.RequestOutput,
        ProcessContext> request;
    private final ProcessDefinition<AccountDeletionProcesses.UserInput, AccountDeletionProcesses.CancelOutput,
        ProcessContext> cancel;
    private final ProcessDefinition<AccountDeletionProcesses.MailInput, AccountDeletionProcesses.Done,
        ProcessContext> mail;
    private final ProcessDefinition<AccountDeletionProcesses.ConfirmInput, AccountDeletionProcesses.RequestOutput,
        ProcessContext> confirm;

    AccountDeletionController(ProcessExecutor processes, SignInEntries entries, SignUpRateLimits limits,
        ClientAddresses clients, RefreshTokenStore refreshTokens, Reauthentication reauthentication,
        AccountDeletions deletions, ErasureNotices notices,
        @Qualifier("secAccountReauthenticateProcess")
        ProcessDefinition<IdentityProcesses.BeginInput, IdentityProcesses.BeginOutput, ProcessContext> check,
        @Qualifier("secAccountDeletionRequestProcess")
        ProcessDefinition<AccountDeletionProcesses.RequestInput, AccountDeletionProcesses.RequestOutput,
            ProcessContext> request,
        @Qualifier("secAccountDeletionCancelProcess")
        ProcessDefinition<AccountDeletionProcesses.UserInput, AccountDeletionProcesses.CancelOutput,
            ProcessContext> cancel,
        @Qualifier("sponsorAccountDeletionMailProcess")
        ProcessDefinition<AccountDeletionProcesses.MailInput, AccountDeletionProcesses.Done, ProcessContext> mail,
        @Qualifier("sponsorAccountDeletionConfirmProcess")
        ProcessDefinition<AccountDeletionProcesses.ConfirmInput, AccountDeletionProcesses.RequestOutput,
            ProcessContext> confirm) {
        this.processes = processes;
        this.entries = entries;
        this.limits = limits;
        this.clients = clients;
        this.refreshTokens = refreshTokens;
        this.reauthentication = reauthentication;
        this.deletions = deletions;
        this.notices = notices;
        this.check = check;
        this.request = request;
        this.cancel = cancel;
        this.mail = mail;
        this.confirm = confirm;
    }

    /** The caller's state: whether they may ask here, a pending request, and the notices to read first. */
    @GetMapping(ACCOUNT)
    Mono<Status> status() {
        return RequestContexts.current().flatMap(context -> CurrentSession.open(refreshTokens, context)
            .then(Mono.defer(() -> {
                UUID user = requireUser(context);
                boolean available = available(context);
                Locale locale = context.locale() == null ? Locale.ENGLISH : context.locale();
                return deletions.pending(user).flatMap(pending -> {
                    Pending view = pending.map(found -> new Pending(found.requestId().toString(),
                        found.requestedTime(), found.dueTime(), found.channel())).orElse(null);
                    if (!available || view != null) {
                        return Mono.just(new Status(available, view, List.of()));
                    }
                    return notices.of(user.toString())
                        .map(found -> new Status(true, null, notices.views(found, locale)));
                });
            })));
    }

    /**
     * Asks for the deletion of the caller's account after the cooling-off period: proof of who the caller is first
     * (a recent second factor, or the current password), then {@value AccountDeletionProcesses#REQUEST}.
     */
    @PostMapping(ACCOUNT)
    Mono<AccountDeletionProcesses.RequestOutput> requestDeletion(
        @RequestBody(required = false) AccountRequest body, ServerHttpRequest http) {
        return RequestContexts.current().flatMap(context -> CurrentSession.open(refreshTokens, context)
            .then(Mono.defer(() -> {
                UUID user = requireUser(context);
                limits.accountDeletion(user.toString());
                if (!available(context)) {
                    return Mono.error(unavailable());
                }
                AccountRequest values = body == null ? new AccountRequest(null, null) : body;
                return reauthentication.require(context, user, values.currentPassword(), http, check)
                    .then(processes.executeAtEntry(request, new AccountDeletionProcesses.RequestInput(
                        user.toString(), values.acknowledgedNotices())));
            })));
    }

    /**
     * Withdraws the caller's pending request ({@value AccountDeletionProcesses#CANCEL}); 422
     * {@code ACCOUNT_DELETION_NONE} when there is none. No proof needed: withdrawing harms nobody.
     */
    @PostMapping(CANCEL)
    Mono<AccountDeletionProcesses.CancelOutput> cancelDeletion() {
        return RequestContexts.current().flatMap(context -> CurrentSession.open(refreshTokens, context)
            .then(Mono.defer(() -> {
                UUID user = requireUser(context);
                limits.accountDeletion(user.toString());
                return processes.executeAtEntry(cancel, new AccountDeletionProcesses.UserInput(user.toString()));
            })));
    }

    /** 202 whatever the address: the account's user at its verified address gets a link. */
    @PostMapping(REQUEST)
    @ResponseStatus(HttpStatus.ACCEPTED)
    Mono<ResponseEntity<Void>> requestLink(@RequestBody(required = false) LinkRequest body, ServerHttpRequest http) {
        return Mono.defer(() -> {
            limits.client(SignUpRateLimits.Kind.ACCOUNT_DELETION, clients.clientOf(http));
            LinkRequest values = body == null ? new LinkRequest(null, null) : body;
            SignInEntries.Entry entry = entries.find(values.entry()).filter(SignInEntries.Entry::accountDeletion)
                .orElse(null);
            if (entry == null) {
                return Mono.error(unavailable());
            }
            List<Violation> problems = SignUpController.emailProblems(values.email());
            if (!problems.isEmpty()) {
                return Mono.error(new ValidationException(problems));
            }
            String email = values.email().strip();
            limits.email(SignUpRateLimits.Kind.ACCOUNT_DELETION, email);
            return processes.executeAtEntry(mail, new AccountDeletionProcesses.MailInput(entry.name(), email))
                .thenReturn(SignUpController.accepted());
        });
    }

    /**
     * Uses a link ({@value AccountDeletionProcesses#CONFIRM}): the request, or 422 {@code ACCOUNT_DELETION_NOTICES}
     * with the notices to acknowledge (each a violation of code {@code erasure.notice.<code>}), the link still good;
     * 422 {@code TOKEN_INVALID} for any link that is not good.
     */
    @PostMapping(CONFIRM)
    Mono<AccountDeletionProcesses.RequestOutput> confirmLink(@RequestBody(required = false) LinkConfirm body,
        ServerHttpRequest http) {
        return Mono.defer(() -> {
            limits.client(SignUpRateLimits.Kind.LINK, clients.clientOf(http));
            LinkConfirm values = body == null ? new LinkConfirm(null, null) : body;
            return processes.executeAtEntry(confirm, new AccountDeletionProcesses.ConfirmInput(values.token(),
                values.acknowledgedNotices()));
        });
    }

    private boolean available(RequestContext context) {
        String entry = context.entry() == null || context.entry().isBlank() ? RequestContext.DEFAULT_ENTRY
            : context.entry();
        return entries.find(entry).filter(SignInEntries.Entry::accountDeletion).isPresent();
    }

    private static BusinessRuleViolationException unavailable() {
        return new BusinessRuleViolationException(new Violation(null, PlatformErrorCodes.ACCOUNT_DELETION_UNAVAILABLE,
            "Accounts are not deleted on request at this entry"));
    }

    private static UUID requireUser(RequestContext context) {
        return ActingUser.userId(context).orElseThrow(() -> new PermissionDeniedException(SecurityPermissions.ACCOUNT,
            "Only users of the platform delete their account"));
    }
}
