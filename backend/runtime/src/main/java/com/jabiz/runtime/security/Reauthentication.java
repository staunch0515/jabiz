package com.jabiz.runtime.security;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.web.ClientAddresses;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Proof, again, that the signed-in caller is the account's user, before something only the user may do (linking a
 * provider account, section 12; asking for the deletion of the account, section 17.5): a second factor within
 * {@code jabiz.security.mfa.step-up-max-age}, or the current password, checked by a process that records a wrong one
 * and counts it towards the lock. Neither is 422 {@code REAUTHENTICATION_REQUIRED}, or
 * {@code REAUTHENTICATION_UNAVAILABLE} for an account without a password (an account a provider opened): it can only
 * step up with a second factor, once it has one, or set a password through the password reset. A wrong password is
 * 422 {@code PASSWORD_INCORRECT}.
 */
@Component
public class Reauthentication {

    private final ProcessExecutor processes;
    private final MfaSettings mfa;
    private final Clock clock;
    private final ClientAddresses clients;
    private final DatasetEntityManager entities;
    private final DatasetRegistry datasets;

    public Reauthentication(ProcessExecutor processes, MfaSettings mfa, Clock clock, ClientAddresses clients,
        DatasetEntityManager entities, DatasetRegistry datasets) {
        this.processes = processes;
        this.mfa = mfa;
        this.clock = clock;
        this.clients = clients;
        this.entities = entities;
        this.datasets = datasets;
    }

    /**
     * Passes when the session's second factor is recent enough; otherwise checks {@code currentPassword} with
     * {@code check} (a {@link IdentityProcesses#passwordCheck} process, named for what it guards).
     */
    public Mono<Void> require(RequestContext context, UUID user, String currentPassword, ServerHttpRequest http,
        ProcessDefinition<IdentityProcesses.BeginInput, IdentityProcesses.BeginOutput, ProcessContext> check) {
        if (mfa.recent(context.mfaAt(), clock.instant())) {
            return Mono.empty();
        }
        return hasPassword(user).flatMap(hasPassword -> {
            if (!hasPassword) {
                return Mono.error(new BusinessRuleViolationException(new Violation(null,
                    PlatformErrorCodes.REAUTHENTICATION_UNAVAILABLE, "The account has no password: confirm with a "
                        + "second factor (set one up first) or set a password through the password reset")));
            }
            if (currentPassword == null || currentPassword.isEmpty()) {
                return Mono.error(new BusinessRuleViolationException(new Violation("currentPassword",
                    PlatformErrorCodes.REAUTHENTICATION_REQUIRED, "A recent second factor or the current password is "
                        + "required", Map.of("field", "currentPassword"))));
            }
            return processes.executeAtEntry(check, new IdentityProcesses.BeginInput(user.toString(),
                    currentPassword, AuthController.source(clients, http, context.entry())))
                // Two attempts on one account at once: the later loses the race for the next login record.
                .onErrorMap(Reauthentication::raceLost, e -> incorrect())
                .flatMap(output -> output.outcome() == IdentityProcesses.Check.VERIFIED ? Mono.<Void>empty()
                    : Mono.error(incorrect()));
        });
    }

    /** Whether the user has a password to confirm with. */
    private Mono<Boolean> hasPassword(UUID user) {
        return entities.query(datasets.findById(SecurityEntities.USER_DATASET).orElseThrow(), SecurityEntities.SEC_USER,
                EntityQuery.builder().where(new QueryPredicate.Eq("userId", user)).limit(1).build())
            .next()
            .map(found -> found.get("passwordHash") != null)
            .defaultIfEmpty(false);
    }

    static BusinessRuleViolationException incorrect() {
        return new BusinessRuleViolationException(new Violation("currentPassword",
            PlatformErrorCodes.PASSWORD_INCORRECT, "The password is not correct", Map.of("field", "currentPassword")));
    }

    private static boolean raceLost(Throwable e) {
        return e instanceof ValidationException v && v.violations().stream()
            .anyMatch(violation -> PlatformErrorCodes.UNIQUE_VIOLATION.equals(violation.ruleCode()));
    }
}
