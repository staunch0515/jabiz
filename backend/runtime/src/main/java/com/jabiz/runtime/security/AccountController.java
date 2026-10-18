package com.jabiz.runtime.security;

import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.web.ClientAddresses;
import com.jabiz.security.Sensitive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A signed-in user's own account settings: the language of their mail (docs/design/18-numbering-approvals-tasks.md
 * section 5.6; reading it since phase 16s, decision D49) and their password (docs/design/10-security.md section 15.8).
 * The mail preferences are {@code /api/auth/mail/preferences}.
 */
@RestController
class AccountController {

    static final String PASSWORD = "/api/auth/account/password";
    static final String LOCALE = "/api/auth/account/locale";

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    /** @param locale one of the platform's languages; empty for the platform's default */
    record LocaleRequest(String locale) {}

    /**
     * The language of the caller's mail.
     *
     * @param locale    as the user chose it; null when they did not (mail then goes in the default language)
     * @param effective the language their mail goes in: the chosen one when the application has it, else its default
     * @param languages the application's languages, which mail can be in
     * @param defaultLocale the application's default language, which mail goes in when none is chosen
     */
    record MailLanguage(String locale, String effective, List<String> languages, String defaultLocale) {}

    /**
     * @param currentPassword the password now; a wrong one counts towards the lock of the account
     * @param newPassword     the new password, under the password rules
     * @param refreshToken    the refresh token of the caller's session, which stays signed in; without one (or with
     *                        one that is not a live token of the caller's session) every session ends
     */
    record PasswordRequest(@Sensitive String currentPassword, @Sensitive String newPassword,
        @Sensitive String refreshToken) {
        @Override
        public String toString() {
            return "PasswordRequest[***]";
        }
    }

    private final ProcessExecutor processes;
    private final ProcessDefinition<AccountPasswordProcess.Input, AccountPasswordProcess.Output, ProcessContext>
        changePassword;
    private final PasswordHasher hasher;
    private final SignUpRateLimits limits;
    private final ClientAddresses clients;
    private final StorageAdapterRegistry storages;
    private final MessageCatalog messages;
    private final String poolRef;

    AccountController(ProcessExecutor processes,
        ProcessDefinition<AccountPasswordProcess.Input, AccountPasswordProcess.Output, ProcessContext> changePassword,
        PasswordHasher hasher, SignUpRateLimits limits, ClientAddresses clients, StorageAdapterRegistry storages,
        MessageCatalog messages, @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.storages = storages;
        this.messages = messages;
        this.poolRef = poolRef;
        this.processes = processes;
        this.changePassword = changePassword;
        this.hasher = hasher;
        this.limits = limits;
        this.clients = clients;
    }

    /**
     * The caller's own mail language (phase 16s): as chosen, the one mail goes in, and the choices. Read from the
     * caller's own user only; another user's is never named here.
     */
    @GetMapping(LOCALE)
    Mono<MailLanguage> mailLanguage() {
        return RequestContexts.current().flatMap(context -> {
            Optional<UUID> user = ActingUser.userId(context);
            if (user.isEmpty()) {
                return Mono.error(new PermissionDeniedException(SecurityPermissions.ACCOUNT,
                    "Only users of the platform have account settings"));
            }
            List<String> languages = messages.supportedLocales().stream().map(Locale::getLanguage).distinct()
                .toList();
            // The latest version, as sending reads it (users are never scheduled).
            return storages.getEngine(poolRef).select("SELECT locale, is_deleted FROM sec_user_version"
                    + " WHERE user_id = :user ORDER BY version_no DESC LIMIT 1",
                    Map.of("user", BoundValue.of(user.get())))
                .next()
                .map(row -> Boolean.TRUE.equals(row.get("is_deleted")) ? "" : Objects.toString(
                    Rows.string(row.get("locale")), ""))
                .defaultIfEmpty("")
                .map(stored -> {
                    String chosen = stored.isBlank() ? null : stored;
                    Locale effective = messages.supported(chosen == null ? null : Locale.of(chosen));
                    return new MailLanguage(chosen, effective.getLanguage(), languages,
                        messages.defaultLocale().getLanguage());
                });
        });
    }

    @PostMapping(LOCALE)
    Mono<UserProcesses.UserIdOutput> setLocale(@RequestBody(required = false) LocaleRequest request) {
        return RequestContexts.current().flatMap(context -> {
            Optional<UUID> user = ActingUser.userId(context);
            if (user.isEmpty()) {
                return Mono.error(new PermissionDeniedException(SecurityPermissions.ACCOUNT,
                    "Only users of the platform have account settings"));
            }
            return processes.executeAtEntry(UserProcesses.SET_LOCALE,
                new UserProcesses.LocaleInput(user.get().toString(), request == null ? null : request.locale()));
        });
    }

    /**
     * Changes the caller's own password ({@value AccountPasswordProcess#NAME}): 204. The new password is checked
     * against the rules before anything else (400, and no attempt counted); a wrong current password is 422
     * {@code PASSWORD_INCORRECT} and counts towards the lock (so does any attempt while locked, which is not even
     * checked); an account without a password is 422 {@code PASSWORD_NOT_SET}. Counted per client address and per
     * user (429 {@code RATE_LIMITED}).
     */
    @PostMapping(PASSWORD)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<ResponseEntity<Void>> changePassword(@RequestBody(required = false) PasswordRequest request,
        ServerHttpRequest http) {
        return RequestContexts.current().flatMap(context -> {
            UUID user = ActingUser.userId(context).orElseThrow(() -> new PermissionDeniedException(
                SecurityPermissions.ACCOUNT, "Only users of the platform have a password"));
            // Before any password is looked at: every attempt costs a hash.
            limits.client(SignUpRateLimits.Kind.PASSWORD_CHANGE, clients.clientOf(http));
            limits.passwordChange(user.toString());
            PasswordRequest body = request == null ? new PasswordRequest(null, null, null) : request;
            List<Violation> problems = new ArrayList<>();
            if (body.currentPassword() == null || body.currentPassword().isEmpty()) {
                problems.add(new Violation("currentPassword", PlatformErrorCodes.REQUIRED,
                    "The current password is required", Map.of("field", "currentPassword")));
            }
            problems.addAll(hasher.check("newPassword", body.newPassword()));
            if (!problems.isEmpty()) {
                return Mono.error(new ValidationException(problems));
            }
            return processes.executeAtEntry(changePassword, new AccountPasswordProcess.Input(user.toString(),
                    body.currentPassword(), body.newPassword(), blank(body.refreshToken()) ? null : body.refreshToken(),
                    AuthController.source(clients, http, context.entry())))
                // Two attempts on one account at once: the later loses the race for the next login record.
                .onErrorMap(AccountController::concurrentAttempt, e -> incorrect())
                .map(result -> {
                    if (result.outcome() != AccountPasswordProcess.Outcome.CHANGED) {
                        log.info("Password change of user {} refused: wrong current password or locked", user);
                        throw incorrect();
                    }
                    return ResponseEntity.noContent().<Void>build();
                });
        });
    }

    private static BusinessRuleViolationException incorrect() {
        return new BusinessRuleViolationException(new Violation("currentPassword",
            PlatformErrorCodes.PASSWORD_INCORRECT, "The password is not correct", Map.of("field", "currentPassword")));
    }

    private static boolean concurrentAttempt(Throwable e) {
        return e instanceof ValidationException v && v.violations().stream()
            .anyMatch(violation -> PlatformErrorCodes.UNIQUE_VIOLATION.equals(violation.ruleCode()));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
