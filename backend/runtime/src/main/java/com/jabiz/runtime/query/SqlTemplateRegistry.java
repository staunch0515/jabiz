package com.jabiz.runtime.query;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.custom.TemplateSource;
import com.jabiz.query.template.SqlTemplateException;
import com.jabiz.query.template.TemplateChecks;
import com.jabiz.query.template.TemplateProblem;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.dictionary.SqlDictionary;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * All SQL templates of the application (docs/design/05-sql-template.md): the {@code .sql} files found by
 * {@link SqlTemplateLoader} and the {@link AdvancedQueryDefinition} beans, by id. Registration fills in the semantic
 * kinds inherited through {@code like} and {@code from} and checks everything that needs no database: the checks of
 * {@link TemplateChecks}, that each entity's dataset exists and targets it, that all datasets of a template share
 * one storage, that {@code timeoutMs} is shorter than theirs, and that permissions are declared (a warning in the
 * {@code dev} profile). The queries of {@link SqlDictionary} beans are checked the same way (they run internally,
 * so they need no permissions and cannot be called through the API).
 */
@Component
public class SqlTemplateRegistry implements PlatformCheck {

    public static final String CATEGORY = SqlTemplateLoader.CATEGORY;

    private final EntityDefinitionRegistry entities;
    private final DatasetRegistry datasets;
    private final Map<String, AdvancedQueryDefinition> byId = new LinkedHashMap<>();
    private final List<AdvancedQueryDefinition> internal = new ArrayList<>();
    private final List<CheckProblem> problems = new ArrayList<>();
    /** Queries built in code and run without registration, prepared once. */
    private final Map<AdvancedQueryDefinition, AdvancedQueryDefinition> adHoc = new ConcurrentHashMap<>();

    public SqlTemplateRegistry(SqlTemplateLoader loader, ObjectProvider<AdvancedQueryDefinition> beans,
        ObjectProvider<SqlDictionary> dictionaries, EntityDefinitionRegistry entities, DatasetRegistry datasets,
        Environment environment) {
        this.entities = entities;
        this.datasets = datasets;
        boolean development = environment.acceptsProfiles(Profiles.of("dev"));

        SqlTemplateLoader.Loaded loaded = loader.load();
        problems.addAll(loaded.problems());
        List<AdvancedQueryDefinition> declared = new ArrayList<>(loaded.queries());
        beans.orderedStream().forEach(declared::add);
        for (AdvancedQueryDefinition query : declared) {
            AdvancedQueryDefinition resolved = register(query, true, development);
            if (byId.putIfAbsent(query.queryId(), resolved) != null) {
                problems.add(CheckProblem.error(CATEGORY, query.source().path(),
                    "duplicate query id " + query.queryId()));
            }
        }
        dictionaries.orderedStream().forEach(dictionary -> {
            Optional<DatasetDefinition> dataset = datasets.findById(dictionary.datasetId());
            if (dataset.isEmpty()) {
                problems.add(CheckProblem.error(CATEGORY, dictionary.query().source().path(),
                    "SQL dictionary " + dictionary.urn() + " refers to unknown dataset " + dictionary.datasetId()));
                return;
            }
            internal.add(register(forDictionary(dictionary, dataset.get()), false, development));
        });
    }

    /** The query of an SQL dictionary, reading the dictionary's dataset for that dataset's entity. */
    public static AdvancedQueryDefinition forDictionary(SqlDictionary dictionary, DatasetDefinition dataset) {
        return dictionary.query().withDataset(dataset.targetEntityType(), dataset.resourceId());
    }

    /** The template of that id, as callable through the API. */
    public Optional<AdvancedQueryDefinition> find(String queryId) {
        return Optional.ofNullable(byId.get(queryId));
    }

    public Collection<AdvancedQueryDefinition> all() {
        return Collections.unmodifiableCollection(byId.values());
    }

    /** Every template, including the queries of SQL dictionaries; what the precompile check verifies. */
    public List<AdvancedQueryDefinition> allForChecks() {
        List<AdvancedQueryDefinition> result = new ArrayList<>(byId.values());
        result.addAll(internal);
        return result;
    }

    @Override
    public List<CheckProblem> check() {
        return List.copyOf(problems);
    }

    /**
     * The query ready to run: the registered instance, or for a query built in code and never registered, the query
     * with its inherited kinds resolved and the same static checks passed (remembered per declaration).
     *
     * @throws SqlTemplateException if its kinds cannot be resolved or a static check fails
     */
    public AdvancedQueryDefinition prepare(AdvancedQueryDefinition query) {
        AdvancedQueryDefinition registered = byId.get(query.queryId());
        if (registered != null && sameDeclaration(registered, query)) {
            return registered;
        }
        return adHoc.computeIfAbsent(query, declared -> {
            TemplateChecks.Resolved resolved = TemplateChecks.resolve(declared, entities::find);
            List<TemplateProblem> errors = new ArrayList<>(resolved.problems());
            TemplateChecks.check(resolved.query(), entities::find).stream().filter(p -> !p.warning())
                .forEach(errors::add);
            if (!errors.isEmpty()) {
                throw new SqlTemplateException(declared.queryId(), errors);
            }
            return resolved.query();
        });
    }

    /**
     * The dataset each participating entity is read through: the one the query names, else the entity's default
     * dataset (decision D10).
     *
     * @throws IllegalStateException if an entity has no such dataset, or the named one serves another entity (its scope
     *                               would not apply)
     */
    public Map<String, DatasetDefinition> datasetsOf(AdvancedQueryDefinition query) {
        Map<String, DatasetDefinition> result = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String entity : query.participatingEntities()) {
            Optional<DatasetDefinition> dataset = datasetFor(query, entity).filter(d -> d.isTarget(entity));
            dataset.ifPresentOrElse(d -> result.put(entity, d), () -> missing.add(entity));
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Query " + query.queryId() + ": no dataset for " + missing);
        }
        return result;
    }

    private Optional<DatasetDefinition> datasetFor(AdvancedQueryDefinition query, String entity) {
        String named = query.datasets().get(entity);
        return named != null ? datasets.findById(named) : datasets.findForEntity(entity);
    }

    private AdvancedQueryDefinition register(AdvancedQueryDefinition query, boolean needsPermissions,
        boolean development) {
        TemplateSource source = query.source();
        TemplateChecks.Resolved resolved = TemplateChecks.resolve(query, entities::find);
        List<TemplateProblem> found = new ArrayList<>(resolved.problems());
        found.addAll(TemplateChecks.check(resolved.query(), entities::find));
        for (TemplateProblem problem : found) {
            String location = problem.offset() < 0 ? source.path() : source.locate(query.sqlTemplate(), problem.offset());
            problems.add(problem.warning()
                ? CheckProblem.warning(CATEGORY, location, problem.message())
                : CheckProblem.error(CATEGORY, location, problem.message()));
        }
        checkDatasets(resolved.query(), source.path());
        if (needsPermissions && query.permissions().isEmpty() && !query.publicAccess()) {
            String message = "query " + query.queryId() + " declares no permissions";
            problems.add(development
                ? CheckProblem.warning(CATEGORY, source.path(), message + " (allowed in the dev profile only)")
                : CheckProblem.error(CATEGORY, source.path(), message));
        }
        return resolved.query();
    }

    private void checkDatasets(AdvancedQueryDefinition query, String location) {
        List<DatasetDefinition> used = new ArrayList<>();
        for (String entity : query.participatingEntities()) {
            if (entities.find(entity).isEmpty()) {
                continue; // reported by TemplateChecks
            }
            String named = query.datasets().get(entity);
            Optional<DatasetDefinition> dataset = datasetFor(query, entity);
            if (dataset.isEmpty()) {
                problems.add(CheckProblem.error(CATEGORY, location, named == null
                    ? "entity " + entity + " has no default dataset"
                    : "dataset " + named + " of entity " + entity + " does not exist"));
            } else if (!dataset.get().isTarget(entity)) {
                problems.add(CheckProblem.error(CATEGORY, location, "dataset " + named + " does not serve entity "
                    + entity + " but " + dataset.get().targetEntityType()));
            } else {
                used.add(dataset.get());
            }
        }
        if (used.isEmpty()) {
            return;
        }
        DatasetDefinition first = used.getFirst();
        for (DatasetDefinition other : used) {
            if (!Objects.equals(first.storage().connectionPoolRef(), other.storage().connectionPoolRef())
                || !Objects.equals(blankToNull(first.storage().readReplicaRef()),
                    blankToNull(other.storage().readReplicaRef()))) {
                problems.add(CheckProblem.error(CATEGORY, location, "datasets " + first.resourceId() + " and "
                    + other.resourceId() + " use different storage; one query runs on one connection"));
            }
        }
        Duration shortest = used.stream().map(d -> d.policy().queryTimeout()).min(Duration::compareTo).orElseThrow();
        if (query.timeoutOverride() != null && query.timeoutOverride().compareTo(shortest) >= 0) {
            problems.add(CheckProblem.error(CATEGORY, location, "timeoutMs (" + query.timeoutOverride().toMillis()
                + ") must be shorter than the datasets' query timeout (" + shortest.toMillis() + " ms)"));
        }
    }

    private static boolean sameDeclaration(AdvancedQueryDefinition registered, AdvancedQueryDefinition query) {
        return registered.sqlTemplate().equals(query.sqlTemplate())
            && registered.participatingEntities().equals(query.participatingEntities())
            && registered.datasets().equals(query.datasets());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
