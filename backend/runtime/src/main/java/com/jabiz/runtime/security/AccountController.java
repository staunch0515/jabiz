package com.jabiz.runtime.security;

import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.process.ProcessExecutor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.UUID;

/**
 * A signed-in user's own account settings (docs/design/18-numbering-approvals-tasks.md section 5.6): the language of
 * their mail. The mail preferences are {@code /api/auth/mail/preferences}.
 */
@RestController
class AccountController {

    /** @param locale one of the platform's languages; empty for the platform's default */
    record LocaleRequest(String locale) {}

    private final ProcessExecutor processes;

    AccountController(ProcessExecutor processes) {
        this.processes = processes;
    }

    @PostMapping("/api/auth/account/locale")
    Mono<UserProcesses.UserIdOutput> setLocale(@RequestBody(required = false) LocaleRequest request) {
        return RequestContexts.current().flatMap(context -> {
            Optional<UUID> user = ActingUser.userId(context);
            if (user.isEmpty()) {
                return Mono.error(new PermissionDeniedException(SecurityPermissions.ACCOUNT,
                    "Only users of the platform have account settings"));
            }
            return processes.execute(UserProcesses.SET_LOCALE,
                new UserProcesses.LocaleInput(user.get().toString(), request == null ? null : request.locale()));
        });
    }
}
