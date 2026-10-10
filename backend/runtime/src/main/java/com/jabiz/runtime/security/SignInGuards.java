package com.jabiz.runtime.security;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.security.SignInAttempt;
import com.jabiz.security.SignInDecision;
import com.jabiz.security.SignInGuard;
import com.jabiz.security.SignInLoad;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Runs the applications' {@link SignInGuard}s (docs/design/10-security.md section 15; decision D36 item 6): reads the
 * data they declare (rows of a dataset whose user field is the user, at most {@link SignInGuard#MAX_ROWS} each),
 * then asks every guard. All must allow; a guard that throws, or data that cannot be read, refuses (fail closed).
 * Used by the sign-in processes ({@link SignInGuardStep}) and by the refresh ({@link RbacService}).
 *
 * <p>The startup check reports loads naming a dataset or field that does not exist, and load names used twice with
 * different meanings.
 */
@Component
public class SignInGuards implements PlatformCheck {

    private static final Logger log = LoggerFactory.getLogger(SignInGuards.class);

    /** Refusal reason when the guards could not decide. */
    static final String UNDECIDED = "guard failed";

    private final List<SignInGuard> guards;
    private final DatasetEntityManager entities;
    private final DatasetRegistry datasets;
    private final EntityDefinitionRegistry definitions;

    public SignInGuards(ObjectProvider<SignInGuard> guards, DatasetEntityManager entities, DatasetRegistry datasets,
        EntityDefinitionRegistry definitions) {
        this.guards = guards.orderedStream().toList();
        this.entities = entities;
        this.datasets = datasets;
        this.definitions = definitions;
    }

    /** Whether any application declares a guard. */
    public boolean any() {
        return !guards.isEmpty();
    }

    /**
     * The guards' decision about a user who has proven themselves.
     *
     * @param roles the codes of the user's roles that the entry accepts
     */
    public Mono<SignInDecision> check(String userId, String userName, String entry, Set<String> roles,
        boolean emailVerified, SignInAttempt.Factor factor, Instant time) {
        if (guards.isEmpty()) {
            return Mono.just(SignInDecision.ALLOW);
        }
        return Mono.defer(() -> load(userId))
            .map(data -> decide(new SignInAttempt(userId, userName, entry, roles, emailVerified, factor, time, data)))
            .onErrorResume(e -> {
                log.warn("Sign-in guard data of user {} could not be read; refusing: {}", userId, e.toString());
                return Mono.just(SignInDecision.refuse(UNDECIDED));
            });
    }

    /** Every guard in order; the first refusal or failure decides. */
    SignInDecision decide(SignInAttempt attempt) {
        for (SignInGuard guard : guards) {
            SignInDecision decision;
            try {
                decision = guard.check(attempt);
            } catch (RuntimeException e) {
                log.warn("Sign-in guard {} failed for user {}; refusing", guard.getClass().getName(),
                    attempt.userId(), e);
                return SignInDecision.refuse(UNDECIDED);
            }
            if (decision == null || !decision.allowed()) {
                SignInDecision refusal = decision == null ? SignInDecision.refuse(UNDECIDED) : decision;
                log.info("Sign-in guard {} refused user {} ({}, entry {}): {}", guard.getClass().getSimpleName(),
                    attempt.userId(), attempt.factor(), attempt.entry(), refusal.reason());
                return refusal;
            }
        }
        return SignInDecision.ALLOW;
    }

    private Mono<Map<String, List<Map<String, Object>>>> load(String userId) {
        Map<String, SignInLoad> loads = new LinkedHashMap<>();
        guards.forEach(guard -> guard.loads().forEach(load -> loads.putIfAbsent(load.name(), load)));
        return Flux.fromIterable(loads.values())
            .concatMap(load -> rows(load, userId).map(rows -> Map.entry(load.name(), rows)))
            .collectMap(Map.Entry::getKey, Map.Entry::getValue, LinkedHashMap::new)
            .map(map -> (Map<String, List<Map<String, Object>>>) map);
    }

    private Mono<List<Map<String, Object>>> rows(SignInLoad load, String userId) {
        DatasetDefinition dataset = datasets.findById(load.dataset())
            .orElseThrow(() -> new IllegalStateException("Unknown dataset " + load.dataset()));
        EntityDefinition def = definitions.getOrThrow(dataset.targetEntityType());
        Object id = userId;
        try {
            id = java.util.UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            // Not a platform user id: compared as text.
        }
        // One past the limit, so that more rows than allowed are noticed instead of cut off.
        EntityQuery query = EntityQuery.builder().where(new QueryPredicate.Eq(load.userField(), id))
            .limit(SignInGuard.MAX_ROWS + 1).build();
        return entities.queryAll(dataset, def, query).map(EntityInstance::attributes).collectList()
            .flatMap(rows -> rows.size() > SignInGuard.MAX_ROWS
                ? Mono.error(new IllegalStateException("Load " + load.name() + " found more than "
                    + SignInGuard.MAX_ROWS + " rows"))
                : Mono.just(List.copyOf(rows)));
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        Map<String, SignInLoad> seen = new LinkedHashMap<>();
        for (SignInGuard guard : guards) {
            String location = "SignInGuard " + guard.getClass().getName();
            List<SignInLoad> loads;
            try {
                loads = guard.loads();
            } catch (RuntimeException e) {
                problems.add(CheckProblem.error(SessionChecks.CATEGORY, location, "loads() failed: " + e.getMessage()));
                continue;
            }
            for (SignInLoad load : loads) {
                SignInLoad earlier = seen.putIfAbsent(load.name(), load);
                if (earlier != null && !earlier.equals(load)) {
                    problems.add(CheckProblem.error(SessionChecks.CATEGORY, location, "load " + load.name()
                        + " is declared with another dataset or field elsewhere"));
                }
                Optional<DatasetDefinition> dataset = datasets.findById(load.dataset());
                if (dataset.isEmpty()) {
                    problems.add(CheckProblem.error(SessionChecks.CATEGORY, location, "load " + load.name()
                        + " reads unknown dataset " + load.dataset()));
                    continue;
                }
                definitions.find(dataset.get().targetEntityType())
                    .filter(def -> def.findField(load.userField()).isEmpty())
                    .ifPresent(def -> problems.add(CheckProblem.error(SessionChecks.CATEGORY, location, "load "
                        + load.name() + ": entity " + def.name + " has no field " + load.userField())));
            }
        }
        return problems;
    }
}
