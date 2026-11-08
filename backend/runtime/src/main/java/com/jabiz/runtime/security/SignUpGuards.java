package com.jabiz.runtime.security;

import com.jabiz.i18n.MessageCatalog;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.security.SignInLoad;
import com.jabiz.security.SignUpAttempt;
import com.jabiz.security.SignUpDecision;
import com.jabiz.security.SignUpGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the applications' {@link SignUpGuard}s (docs/design/10-security.md section 15.15; decision D66) before an
 * account gets an entry's registration roles, on every path ({@link SignUpGuardStep}). Reads the data they declare as
 * the sign-in guards' is read ({@link SignInGuards#read}); without a user (a new account, a registration) a load of
 * the user's rows has none and is not read, while a global load (decision D80) is read alike. The business parameters they declare ({@link GuardParams}, decision D79) are read
 * alike with or without a user. All guards must allow; a guard that throws, answers nothing or names a reason
 * without a text, or data that cannot be read, refuses with {@value SignUpDecision#REFUSED} (fail closed).
 *
 * <p>The startup check reports loads naming a dataset or field that does not exist or a field that cannot hold a user
 * id, and load names used twice with different meanings, as for the sign-in guards.
 */
@Component
public class SignUpGuards implements PlatformCheck {

    private static final Logger log = LoggerFactory.getLogger(SignUpGuards.class);

    private final List<SignUpGuard> guards;
    private final SignInGuards reader;
    private final MessageCatalog messages;
    private final GuardParams params;

    public SignUpGuards(ObjectProvider<SignUpGuard> guards, SignInGuards reader, MessageCatalog messages,
        GuardParams params) {
        this.guards = guards.orderedStream().toList();
        this.reader = reader;
        this.messages = messages;
        this.params = params;
    }

    /** Whether any application declares a guard. */
    public boolean any() {
        return !guards.isEmpty();
    }

    /**
     * The guards' decision about giving the registration roles of {@code entry} to the account.
     *
     * @param userId the existing user, or null (a new account, a registration): the loads then find no rows
     */
    public Mono<SignUpDecision> check(SignUpAttempt.Path path, String entry, String userId, String email,
        boolean emailVerified, Instant time) {
        if (guards.isEmpty()) {
            return Mono.just(SignUpDecision.ALLOW);
        }
        // One read after the other: inside a process both go through the same transaction.
        // The parameters first (the loads' conditions take their values, decision D80), then the loads.
        return Mono.defer(() -> params.read(GuardParams.merge(guards.stream().map(SignUpGuard::params).toList()), time)
                .flatMap(values -> data(userId, values).map(data -> reactor.util.function.Tuples.of(data, values))))
            .map(read -> decide(new SignUpAttempt(entry, path, userId, email, emailVerified, time, read.getT1(),
                read.getT2())))
            .onErrorResume(e -> {
                log.warn("Sign-up guard data or parameters of user {} could not be read; refusing: {}", userId,
                    e.toString());
                return Mono.just(SignUpDecision.refuse(SignUpDecision.REFUSED));
            });
    }

    /** Every guard in order; the first refusal or failure decides. */
    SignUpDecision decide(SignUpAttempt attempt) {
        for (SignUpGuard guard : guards) {
            SignUpDecision decision;
            try {
                decision = guard.check(attempt);
            } catch (RuntimeException e) {
                log.warn("Sign-up guard {} failed ({}, entry {}, user {}); refusing", guard.getClass().getName(),
                    attempt.path(), attempt.entry(), attempt.userId(), e);
                return SignUpDecision.refuse(SignUpDecision.REFUSED);
            }
            if (decision == null || !decision.allowed()) {
                SignUpDecision refusal = decision == null ? SignUpDecision.refuse(SignUpDecision.REFUSED)
                    : withText(guard, decision);
                log.info("Sign-up guard {} refused ({}, entry {}, user {}): {}", guard.getClass().getSimpleName(),
                    attempt.path(), attempt.entry(), attempt.userId(), refusal.reason());
                return refusal;
            }
        }
        return SignUpDecision.ALLOW;
    }

    /** The refusal, or the general one when its reason has no text in every language: the caller always reads one. */
    private SignUpDecision withText(SignUpGuard guard, SignUpDecision refusal) {
        List<String> missing = messages.missing(List.of(PlatformErrorCodes.SIGN_UP_REFUSED + ".reason."
            + refusal.reason()));
        if (missing.isEmpty()) {
            return refusal;
        }
        log.warn("Sign-up guard {} refused for reason {}, which has no text {}; answering {}",
            guard.getClass().getName(), refusal.reason(), missing, SignUpDecision.REFUSED);
        return SignUpDecision.refuse(SignUpDecision.REFUSED);
    }

    /** The loads' rows: without a user (a new account) only the global loads (decision D80) find any. */
    private Mono<Map<String, List<Map<String, Object>>>> data(String userId, com.jabiz.param.ParamValues values) {
        Map<String, SignInLoad> loads = new LinkedHashMap<>();
        guards.forEach(guard -> guard.loads().forEach(load -> loads.putIfAbsent(load.name(), load)));
        return reader.read(loads.values(), userId, values);
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        Map<String, SignInLoad> seen = new LinkedHashMap<>();
        for (SignUpGuard guard : guards) {
            String location = "SignUpGuard " + guard.getClass().getName();
            List<SignInLoad> loads;
            try {
                loads = guard.loads();
            } catch (RuntimeException e) {
                problems.add(CheckProblem.error(SessionChecks.CATEGORY, location, "loads() failed: " + e.getMessage()));
                continue;
            }
            problems.addAll(reader.loadProblems(location, loads, seen, guard));
        }
        Map<String, com.jabiz.security.GuardParam> seenParams = new LinkedHashMap<>();
        for (SignUpGuard guard : guards) {
            String location = "SignUpGuard " + guard.getClass().getName();
            try {
                problems.addAll(GuardParams.problems(location, guard.params(), seenParams));
            } catch (RuntimeException e) {
                problems.add(CheckProblem.error(SessionChecks.CATEGORY, location, "params() failed: "
                    + e.getMessage()));
            }
        }
        return problems;
    }
}
