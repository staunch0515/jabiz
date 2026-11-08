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
 * data they declare (rows of a dataset whose user field is the user, at most {@link SignInGuard#MAX_ROWS} each) and
 * the business parameters ({@link GuardParams}, decision D79), then asks every guard. All must allow; a guard that throws, or data that cannot be read, refuses (fail closed).
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
    /** Why the entries' roles could not be reserved to the scoped entries (decision D64), for the startup check. */
    private final List<String> reservation;
    private final DatasetEntityManager entities;
    private final DatasetRegistry datasets;
    private final EntityDefinitionRegistry definitions;
    private final QueryCompiler compiler;
    private final GuardParams params;

    public SignInGuards(ObjectProvider<SignInGuard> guards, ObjectProvider<EntryScopeResolver> resolvers,
        SignInEntries entries, DatasetEntityManager entities, DatasetRegistry datasets,
        EntityDefinitionRegistry definitions, QueryCompiler compiler, GuardParams params) {
        this.guards = guards.orderedStream().toList();
        this.resolvers = resolvers.orderedStream().toList();
        this.resolvers.forEach(resolver -> byEntry.putIfAbsent(resolver.entry(), resolver));
        this.entries = entries;
        // The roles of scoped entries count in those entries only: nowhere else would a scope limit their holders.
        this.reservation = entries == null ? List.of() : entries.reserveScoped(byEntry.keySet());
        this.entities = entities;
        this.datasets = datasets;
        this.definitions = definitions;
        this.compiler = compiler;
        this.params = params;
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
        // The parameters first (the loads' conditions take their values, decision D80), then the loads: one read after
        // the other, inside a process through the same transaction.
        return Mono.defer(() -> params.read(GuardParams.merge(guards.stream().map(SignInGuard::params).toList()), time)
                .flatMap(values -> load(userId, resolver, values)
                    .map(data -> reactor.util.function.Tuples.of(data, values))))
            .map(read -> {
                SignInAttempt attempt = new SignInAttempt(userId, userName, entry, roles, emailVerified, factor, time,
                    read.getT1(), read.getT2());
                SignInDecision decision = decide(attempt);
                return decision.allowed() && resolver != null ? scope(resolver, attempt) : decision;
            })
            .onErrorResume(e -> {
                log.warn("Sign-in guard data or parameters of user {} could not be read; refusing: {}", userId,
                    e.toString());
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

    private Mono<Map<String, List<Map<String, Object>>>> load(String userId, EntryScopeResolver resolver,
        com.jabiz.param.ParamValues values) {
        Map<String, SignInLoad> loads = new LinkedHashMap<>();
        guards.forEach(guard -> guard.loads().forEach(load -> loads.putIfAbsent(load.name(), load)));
        if (resolver != null) {
            resolver.loads().forEach(load -> loads.putIfAbsent(load.name(), load));
        }
        return read(loads.values(), userId, values);
    }

    /**
     * The rows of {@code loads} for {@code userId}, by load name (also for the sign-up guards, decision D66), their
     * conditions taking the parameters {@code values} (decision D80); an error when one cannot be read, finds more
     * than {@link SignInGuard#MAX_ROWS} or a condition's function throws. A load of the user's rows without a user (a
     * registration) and a load whose condition has no value have no rows and read nothing.
     */
    Mono<Map<String, List<Map<String, Object>>>> read(java.util.Collection<SignInLoad> loads, String userId,
        com.jabiz.param.ParamValues values) {
        return Flux.fromIterable(loads)
            .concatMap(load -> rows(load, userId, values).map(rows -> Map.entry(load.name(), rows)))
            .collectMap(Map.Entry::getKey, Map.Entry::getValue, LinkedHashMap::new)
            .map(map -> (Map<String, List<Map<String, Object>>>) map);
    }

    /** The equalities of a load: the user's id and each condition's value; empty when one of them has no value. */
    static Optional<List<QueryPredicate>> where(SignInLoad load, String userId, com.jabiz.param.ParamValues values) {
        List<QueryPredicate> where = new ArrayList<>();
        if (!load.global()) {
            if (userId == null) {
                return Optional.empty();
            }
            // The id as text: the query compiler converts it to the type of the field (a uuid reference to the user,
            // or text), as for every other comparison.
            where.add(new QueryPredicate.Eq(load.userField(), userId));
        }
        for (SignInLoad.Condition condition : load.conditions()) {
            Object paramValue = values == null ? null : values.values().get(condition.paramKey());
            Object value = condition.valueOf(paramValue);
            if (value == null) {
                return Optional.empty();
            }
            where.add(new QueryPredicate.Eq(condition.field(), value));
        }
        return Optional.of(where);
    }

    private Mono<List<Map<String, Object>>> rows(SignInLoad load, String userId, com.jabiz.param.ParamValues values) {
        Optional<List<QueryPredicate>> where = where(load, userId, values);
        if (where.isEmpty()) {
            return Mono.just(List.of());
        }
        DatasetDefinition dataset = datasets.findById(load.dataset())
            .orElseThrow(() -> new IllegalStateException("Unknown dataset " + load.dataset()));
        EntityDefinition def = definitions.getOrThrow(dataset.targetEntityType());
        List<QueryPredicate> all = where.get();
        // One past the limit, so that more rows than allowed are noticed instead of cut off.
        EntityQuery query = EntityQuery.builder()
            .where(all.size() == 1 ? all.getFirst() : new QueryPredicate.And(all))
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
        reservation.forEach(problem -> problems.add(CheckProblem.error(SessionChecks.CATEGORY,
            SignInEntries.PROPERTY, problem)));
        // A dataset limited by a scope no entry has would limit nobody: a misspelt name fails open, so it is an error.
        Set<String> scopes = new java.util.TreeSet<>();
        resolvers.forEach(resolver -> scopes.add(resolver.scope()));
        if (datasets != null) {
            for (DatasetDefinition dataset : datasets.all()) {
                for (com.jabiz.dataset.DatasetScope.Entry entry : dataset.scope().entries()) {
                    if (!(entry instanceof com.jabiz.dataset.DatasetScope.WithinEntryScope within)) {
                        continue;
                    }
                    if (!scopes.contains(within.scope())) {
                        problems.add(CheckProblem.error(SessionChecks.CATEGORY, dataset.resourceId(), "entry scope "
                            + within.scope() + " is declared by no EntryScopeResolver"));
                    }
                    // The mapping is read as its table: a soft-deleted row would still map (and grant).
                    if (within.via() != null && datasets.findForEntity(within.via().entity())
                        .filter(mapping -> mapping.policy().softDelete()).isPresent()) {
                        problems.add(CheckProblem.error(SessionChecks.CATEGORY, dataset.resourceId(), "entry scope "
                            + within.scope() + " maps through " + within.via().entity()
                            + ", whose rows are deleted softly; map through an entity whose rows are removed"));
                    }
                }
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
            problems.addAll(loadProblems(location, loads, seen, declaredParams(source)));
        }
        Map<String, com.jabiz.security.GuardParam> seenParams = new LinkedHashMap<>();
        for (SignInGuard guard : guards) {
            String location = "SignInGuard " + guard.getClass().getName();
            try {
                problems.addAll(GuardParams.problems(location, guard.params(), seenParams));
            } catch (RuntimeException e) {
                problems.add(CheckProblem.error(SessionChecks.CATEGORY, location, "params() failed: "
                    + e.getMessage()));
            }
        }
        return problems;
    }

    /** The keys of the parameters a guard declares (a resolver declares none); none when {@code params()} throws. */
    private static Set<String> declaredParams(Object source) {
        try {
            List<com.jabiz.security.GuardParam> params = source instanceof SignInGuard guard ? guard.params()
                : source instanceof com.jabiz.security.SignUpGuard guard ? guard.params() : List.of();
            return params.stream().map(com.jabiz.security.GuardParam::key).collect(java.util.stream.Collectors.toSet());
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    /** As {@link #loadProblems(String, List, Map, Set)} for a guard of {@code source}'s declared parameters. */
    List<CheckProblem> loadProblems(String location, List<SignInLoad> loads, Map<String, SignInLoad> seen,
        Object source) {
        return loadProblems(location, loads, seen, declaredParams(source));
    }

    /**
     * What is wrong with a guard's {@code loads}: an unknown dataset or field, a field that cannot hold a user id, a
     * name {@code seen} before with another meaning; decision D80: a condition on an unknown field or on a parameter
     * the guard does not declare ({@code declared}), a global load without a condition.
     */
    public List<CheckProblem> loadProblems(String location, List<SignInLoad> loads, Map<String, SignInLoad> seen,
        Set<String> declared) {
        List<CheckProblem> problems = new ArrayList<>();
        for (SignInLoad load : loads) {
            if (load.global() && load.conditions().isEmpty()) {
                problems.add(CheckProblem.error(SessionChecks.CATEGORY, location, "load " + load.name()
                    + " is global and has no condition: it would read the whole dataset"));
            }
            for (String key : load.paramKeys()) {
                if (!declared.contains(key)) {
                    problems.add(CheckProblem.error(SessionChecks.CATEGORY, location, "load " + load.name()
                        + " has a condition on parameter " + key + ", which the guard's params() does not declare"));
                }
            }
            SignInLoad earlier = seen.putIfAbsent(load.name(), load);
            if (earlier != null && !sameShape(earlier, load)) {
                problems.add(CheckProblem.error(SessionChecks.CATEGORY, location, "load " + load.name()
                    + " is declared with another dataset or field elsewhere"));
            }
            Optional<DatasetDefinition> dataset = datasets.findById(load.dataset());
            if (dataset.isEmpty()) {
                problems.add(CheckProblem.error(SessionChecks.CATEGORY, location, "load " + load.name()
                    + " reads unknown dataset " + load.dataset()));
                continue;
            }
            Optional<EntityDefinition> def = definitions.find(dataset.get().targetEntityType());
            if (def.isEmpty()) {
                continue;
            }
            if (!load.global()) {
                userFieldProblem(def.get(), load).ifPresent(problem -> problems.add(CheckProblem.error(
                    SessionChecks.CATEGORY, location, "load " + load.name() + ": " + problem)));
            }
            for (SignInLoad.Condition condition : load.conditions()) {
                if (def.get().findField(condition.field()).isEmpty()) {
                    problems.add(CheckProblem.error(SessionChecks.CATEGORY, location, "load " + load.name()
                        + ": entity " + def.get().name + " has no field " + condition.field()));
                }
            }
        }
        return problems;
    }

    /**
     * Whether two declarations of a load name mean the same read: dataset, user field and each condition's field and
     * parameter. A condition's function cannot be compared; the same name reads once, so give it one function.
     */
    static boolean sameShape(SignInLoad a, SignInLoad b) {
        return a.dataset().equals(b.dataset()) && java.util.Objects.equals(a.userField(), b.userField())
            && a.conditions().stream().map(c -> c.field() + "=" + c.paramKey()).toList()
                .equals(b.conditions().stream().map(c -> c.field() + "=" + c.paramKey()).toList());
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
