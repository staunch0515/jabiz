package com.jabiz.runtime.security;

import com.jabiz.context.EntryScope;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.entity.ValidationException;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryCompiler;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.security.SignInAttempt;
import com.jabiz.security.SignInDecision;
import com.jabiz.security.EntryScopeResolver;
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
 * <p>The user id is given to the query as text and converted to the type of the load's field like every other compared
 * value (a uuid reference to the user, or text). The startup check reports loads naming a dataset or field that does
 * not exist or a field that cannot hold a user id, and load names used twice with different meanings.
 *
 * <p>After the guards allowed, the entry's {@link EntryScopeResolver} (decision D64), if it has one, works out the
 * session's scope value from the same data; none refuses like a guard.
 */
@Component
public class SignInGuards implements PlatformCheck {

    private static final Logger log = LoggerFactory.getLogger(SignInGuards.class);

    /** Refusal reason when the guards could not decide. */
    static final String UNDECIDED = "guard failed";

    /** A user id, as the startup check tries it against the loads' fields. */
    private static final String SAMPLE_USER_ID = "00000000-0000-7000-8000-000000000000";

    private final List<SignInGuard> guards;
    private final List<EntryScopeResolver> resolvers;
    private final Map<String, EntryScopeResolver> byEntry = new LinkedHashMap<>();
    private final SignInEntries entries;
    private final DatasetEntityManager entities;
    private final DatasetRegistry datasets;
    private final EntityDefinitionRegistry definitions;
    private final QueryCompiler compiler;

    public SignInGuards(ObjectProvider<SignInGuard> guards, ObjectProvider<EntryScopeResolver> resolvers,
        SignInEntries entries, DatasetEntityManager entities, DatasetRegistry datasets,
        EntityDefinitionRegistry definitions, QueryCompiler compiler) {
        this.guards = guards.orderedStream().toList();
        this.resolvers = resolvers.orderedStream().toList();
        this.resolvers.forEach(resolver -> byEntry.putIfAbsent(resolver.entry(), resolver));
        this.entries = entries;
        this.entities = entities;
        this.datasets = datasets;
        this.definitions = definitions;
        this.compiler = compiler;
    }

    /** Whether any application declares a guard or an entry scope. */
    public boolean any() {
        return !guards.isEmpty() || !resolvers.isEmpty();
    }

    /** The scope the sessions of {@code entry} carry (decision D64), or empty for an entry without one. */
    public Optional<String> scopeOf(String entry) {
        return Optional.ofNullable(entry == null ? null : byEntry.get(entry)).map(EntryScopeResolver::scope);
    }

    /**
     * The guards' decision about a user who has proven themselves.
     *
     * @param roles the codes of the user's roles that the entry accepts
     */
    public Mono<SignInDecision> check(String userId, String userName, String entry, Set<String> roles,
        boolean emailVerified, SignInAttempt.Factor factor, Instant time) {
        EntryScopeResolver resolver = byEntry.get(entry);
        if (guards.isEmpty() && resolver == null) {
            return Mono.just(SignInDecision.ALLOW);
        }
        return Mono.defer(() -> load(userId, resolver))
            .map(data -> {
                SignInAttempt attempt = new SignInAttempt(userId, userName, entry, roles, emailVerified, factor, time,
                    data);
                SignInDecision decision = decide(attempt);
                return decision.allowed() && resolver != null ? scope(resolver, attempt) : decision;
            })
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

    /** The session's scope by the entry's resolver; none, or a failure, refuses (fail closed). */
    static SignInDecision scope(EntryScopeResolver resolver, SignInAttempt attempt) {
        String value;
        try {
            value = resolver.resolve(attempt);
        } catch (RuntimeException e) {
            log.warn("Entry scope resolver {} failed for user {}; refusing", resolver.getClass().getName(),
                attempt.userId(), e);
            return SignInDecision.refuse(UNDECIDED);
        }
        if (value == null || value.isBlank() || value.length() > EntryScope.MAX_VALUE) {
            log.info("Entry scope {} of entry {} has no value for user {} ({}); refusing", resolver.scope(),
                attempt.entry(), attempt.userId(), attempt.factor());
            return SignInDecision.refuse("no " + resolver.scope());
        }
        return SignInDecision.allow(new EntryScope(resolver.scope(), value));
    }

    private Mono<Map<String, List<Map<String, Object>>>> load(String userId, EntryScopeResolver resolver) {
        Map<String, SignInLoad> loads = new LinkedHashMap<>();
        guards.forEach(guard -> guard.loads().forEach(load -> loads.putIfAbsent(load.name(), load)));
        if (resolver != null) {
            resolver.loads().forEach(load -> loads.putIfAbsent(load.name(), load));
        }
        return Flux.fromIterable(loads.values())
            .concatMap(load -> rows(load, userId).map(rows -> Map.entry(load.name(), rows)))
            .collectMap(Map.Entry::getKey, Map.Entry::getValue, LinkedHashMap::new)
            .map(map -> (Map<String, List<Map<String, Object>>>) map);
    }

    private Mono<List<Map<String, Object>>> rows(SignInLoad load, String userId) {
        DatasetDefinition dataset = datasets.findById(load.dataset())
            .orElseThrow(() -> new IllegalStateException("Unknown dataset " + load.dataset()));
        EntityDefinition def = definitions.getOrThrow(dataset.targetEntityType());
        // The id as text: the query compiler converts it to the type of the field (a uuid reference to the user, or
        // text), as for every other comparison.
        // One past the limit, so that more rows than allowed are noticed instead of cut off.
        EntityQuery query = EntityQuery.builder().where(new QueryPredicate.Eq(load.userField(), userId))
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
        Map<String, EntryScopeResolver> scoped = new LinkedHashMap<>();
        for (EntryScopeResolver resolver : resolvers) {
            String location = "EntryScopeResolver " + resolver.getClass().getName();
            if (entries.find(resolver.entry()).isEmpty()) {
                problems.add(CheckProblem.error(SessionChecks.CATEGORY, location, "entry " + resolver.entry()
                    + " does not exist"));
            }
            if (resolver.scope() == null || !EntryScope.NAME.matcher(resolver.scope()).matches()) {
                problems.add(CheckProblem.error(SessionChecks.CATEGORY, location, "scope name " + resolver.scope()
                    + " must match " + EntryScope.NAME.pattern()));
            }
            EntryScopeResolver earlier = scoped.putIfAbsent(resolver.entry(), resolver);
            if (earlier != null) {
                problems.add(CheckProblem.error(SessionChecks.CATEGORY, location, "entry " + resolver.entry()
                    + " has another resolver already: " + earlier.getClass().getName()));
            }
        }
        List<Object> sources = new ArrayList<>(guards);
        sources.addAll(resolvers);
        for (Object source : sources) {
            String location = (source instanceof SignInGuard ? "SignInGuard " : "EntryScopeResolver ")
                + source.getClass().getName();
            List<SignInLoad> loads;
            try {
                loads = source instanceof SignInGuard guard ? guard.loads() : ((EntryScopeResolver) source).loads();
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
                    .flatMap(def -> userFieldProblem(def, load))
                    .ifPresent(problem -> problems.add(CheckProblem.error(SessionChecks.CATEGORY, location, "load "
                        + load.name() + ": " + problem)));
            }
        }
        return problems;
    }

    /**
     * Why the load's field cannot hold a user id, if it cannot: it does not exist, or a user id (a UUID, as text) does
     * not convert to its type, so that reading the load would fail and refuse everybody.
     */
    private Optional<String> userFieldProblem(EntityDefinition def, SignInLoad load) {
        Optional<FieldDefinition> field = def.findField(load.userField());
        if (field.isEmpty()) {
            return Optional.of("entity " + def.name + " has no field " + load.userField());
        }
        try {
            compiler.fieldValue(def, field.get(), SAMPLE_USER_ID);
            return Optional.empty();
        } catch (ValidationException e) {
            return Optional.of("field " + def.name + "." + load.userField() + " ("
                + field.get().kind().getClass().getSimpleName() + ") cannot hold a user id, which is a UUID");
        }
    }
}
