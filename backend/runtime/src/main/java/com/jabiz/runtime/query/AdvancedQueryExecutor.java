package com.jabiz.runtime.query;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.FieldValueCoercer;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.query.BoundValue;
import com.jabiz.query.QueryCompiler;
import com.jabiz.query.QueryPredicate;
import com.jabiz.query.RawQueryPlan;
import com.jabiz.query.SortOrder;
import com.jabiz.query.TimeSlice;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.custom.ProjectedField;
import com.jabiz.query.custom.QueryParameter;
import com.jabiz.query.custom.SemanticRow;
import com.jabiz.query.template.OuterQueryCompiler;
import com.jabiz.query.template.SqlTemplateRenderer;
import com.jabiz.query.template.TemplateChecks;
import com.jabiz.query.template.TemplateValues;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.runtime.observability.PlatformObservations;
import io.micrometer.common.KeyValues;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Executes SQL templates (docs/design/05-sql-template.md). Every participating entity is rendered as its dataset
 * shows it - scope, soft-delete exclusion, for temporal entities the versions in effect now without tombstones, the
 * scope applied after picking the versions (decisions D3, D10) - so hand-written SQL cannot see rows a dataset hides.
 * Scopes are resolved from the caller's {@link RequestContext}. The template is then wrapped for outer filtering,
 * sorting and paging, which the caller controls within the template's whitelist.
 */
@Component
public class AdvancedQueryExecutor {

    /** One page of a template's result. {@code total} is null when counting was not asked for. */
    public record Page(List<SemanticRow> items, Long total, int offset, int limit) {}

    /**
     * The point in time a caller asks a template to be run at (docs/design/19-reports.md section 2.1); either part may
     * be null. A template declaring {@code timeSlice} takes its point in time from its parameters instead.
     *
     * @param asOf    effective time; null means now
     * @param knownAt recorded time; null means everything recorded so far
     */
    public record At(Instant asOf, Instant knownAt) {

        /** Now, as recorded so far: the default. */
        public static final At NOW = new At(null, null);

        boolean given() {
            return asOf != null || knownAt != null;
        }
    }

    private final StorageAdapterRegistry storageRegistry;
    private final EntityDefinitionRegistry entityRegistry;
    private final SqlTemplateRegistry templates;
    private final SqlTemplateRenderer renderer;
    private final Clock clock;
    private final PlatformObservations observations;

    public AdvancedQueryExecutor(StorageAdapterRegistry storageRegistry,
        EntityDefinitionRegistry entityRegistry,
        SqlTemplateRegistry templates,
        QueryCompiler queryCompiler,
        Clock clock,
        PlatformObservations observations) {
        this.observations = Objects.requireNonNull(observations);
        this.storageRegistry = Objects.requireNonNull(storageRegistry);
        this.entityRegistry = Objects.requireNonNull(entityRegistry);
        this.templates = Objects.requireNonNull(templates);
        this.renderer = new SqlTemplateRenderer(Objects.requireNonNull(queryCompiler));
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Rows of the template, at most as many as its datasets allow in one query, in the template's default order.
     *
     * @param inputParams caller-supplied parameter values by name
     */
    public Flux<SemanticRow> execute(AdvancedQueryDefinition queryDef, Map<String, Object> inputParams) {
        return execute(queryDef, inputParams, At.NOW);
    }

    /** As {@link #execute(AdvancedQueryDefinition, Map)}, at the given point in time. */
    public Flux<SemanticRow> execute(AdvancedQueryDefinition queryDef, Map<String, Object> inputParams, At at) {
        return page(queryDef, inputParams, at, null, List.of(), 0, Integer.MAX_VALUE, false, null)
            .flatMapMany(page -> Flux.fromIterable(page.items()));
    }

    /** As {@link #execute(AdvancedQueryDefinition, Map)}, reading the dataset's entity through that dataset. */
    public Flux<SemanticRow> execute(DatasetDefinition dataset, AdvancedQueryDefinition queryDef,
        Map<String, Object> inputParams) {
        return execute(queryDef.withDataset(dataset.targetEntityType(), dataset.resourceId()), inputParams);
    }

    /**
     * One page of the template's result.
     *
     * @param filter conditions on result columns (whitelisted by the template's {@code list}), or null
     * @param sorts  sorts by result columns; empty for the template's default sort
     * @param limit  requested page size; capped by the datasets' {@code maxQueryBatchSize}
     * @param count  whether to compute the total number of rows as well
     * @throws ValidationException (as the error of the returned Mono) for missing, unknown or malformed parameters
     *                             and filters or sorts the template does not allow
     */
    public Mono<Page> page(AdvancedQueryDefinition queryDef, Map<String, Object> inputParams, QueryPredicate filter,
        List<SortOrder> sorts, int offset, int limit, boolean count) {
        return page(queryDef, inputParams, filter, sorts, offset, limit, count, null);
    }

    /**
     * As {@link #page(AdvancedQueryDefinition, Map, QueryPredicate, List, int, int, boolean)}, running at most
     * {@code maxTimeout} (public reads, docs/design/15-public-access.md section 3).
     *
     * @param maxTimeout upper bound of the query timeout, or null for the datasets' and the template's own
     */
    public Mono<Page> page(AdvancedQueryDefinition queryDef, Map<String, Object> inputParams, QueryPredicate filter,
        List<SortOrder> sorts, int offset, int limit, boolean count, Duration maxTimeout) {
        return page(queryDef, inputParams, At.NOW, filter, sorts, offset, limit, count, maxTimeout);
    }

    /**
     * As {@link #page(AdvancedQueryDefinition, Map, QueryPredicate, List, int, int, boolean, Duration)}, reading the
     * template's temporal entities at the given point in time (docs/design/19-reports.md section 2.1).
     *
     * @throws ValidationException (as the error of the returned Mono) for a point in time the template's datasets do
     *                             not allow, or given besides the template's own {@code timeSlice}
     */
    public Mono<Page> page(AdvancedQueryDefinition queryDef, Map<String, Object> inputParams, At at,
        QueryPredicate filter, List<SortOrder> sorts, int offset, int limit, boolean count, Duration maxTimeout) {
        return observations.mono(PlatformObservations.TEMPLATE, "template " + queryDef.queryId(),
            KeyValues.of("template", queryDef.queryId()),
            pageOf(queryDef, inputParams, at == null ? At.NOW : at, filter, sorts, offset, limit, count, maxTimeout));
    }

    private Mono<Page> pageOf(AdvancedQueryDefinition queryDef, Map<String, Object> inputParams, At at,
        QueryPredicate filter, List<SortOrder> sorts, int offset, int limit, boolean count, Duration maxTimeout) {
        return RequestContexts.current().flatMap(request -> {
            AdvancedQueryDefinition query = templates.prepare(queryDef);
            Map<String, DatasetDefinition> datasets = templates.datasetsOf(query);
            Map<String, SqlTemplateRenderer.EntityBinding> bindings = new LinkedHashMap<>();
            datasets.forEach((entity, dataset) -> bindings.put(entity, new SqlTemplateRenderer.EntityBinding(
                entityRegistry.getOrThrow(entity), dataset, dataset.scope().resolve(request))));

            Map<String, BoundValue> params = new LinkedHashMap<>(bindInputs(query, inputParams));
            TimeSlice slice = timeSlice(query, params, at, datasets);
            QueryCompiler.Binder platform = new QueryCompiler.Binder(TemplateChecks.SCOPE_PREFIX);
            String sql = renderer.render(query, bindings, slice, platform).sql();
            params.putAll(platform.params());

            int maxRows = datasets.values().stream().mapToInt(d -> d.policy().maxQueryBatchSize()).min().orElseThrow();
            int effectiveLimit = Math.min(limit, maxRows);
            OuterQueryCompiler.OuterQuery outer = OuterQueryCompiler.compile(query, sql, filter, sorts, offset,
                effectiveLimit, entityRegistry::find);

            StorageEngine engine = storageRegistry.getEngine(pool(datasets.values().iterator().next()));
            Duration own = effectiveTimeout(datasets.values(), query);
            Duration timeout = maxTimeout != null && maxTimeout.compareTo(own) < 0 ? maxTimeout : own;
            Map<String, BoundValue> listParams = new LinkedHashMap<>(params);
            listParams.putAll(outer.listParams());
            Mono<List<SemanticRow>> rows = engine.executeRawQuery(new RawQueryPlan(outer.listSql(), listParams, timeout))
                .map(row -> toSemanticRow(query, row))
                .collectList();
            if (!count) {
                return rows.map(items -> new Page(items, null, offset, effectiveLimit));
            }
            Map<String, BoundValue> countParams = new LinkedHashMap<>(params);
            countParams.putAll(outer.countParams());
            Mono<Long> total = engine.executeRawQuery(new RawQueryPlan(outer.countSql(), countParams, timeout))
                .next()
                .map(row -> ((Number) row.get("total")).longValue());
            return rows.zipWith(total, (items, n) -> new Page(items, n, offset, effectiveLimit));
        });
    }

    /**
     * The point in time of this run: the template's {@code timeSlice} parameters, or the caller's, or now as recorded
     * so far. A point in time needs the datasets of the template's temporal entities to allow time travel.
     */
    private TimeSlice timeSlice(AdvancedQueryDefinition query, Map<String, BoundValue> params, At at,
        Map<String, DatasetDefinition> datasets) {
        At effective = at;
        if (query.timeSlice() != null) {
            if (at.given()) {
                List<Violation> violations = new ArrayList<>();
                String message = "Query " + query.queryId() + " takes its point in time from its parameters";
                if (at.asOf() != null) {
                    violations.add(new Violation("asOf", PlatformErrorCodes.INVALID_VALUE, message));
                }
                if (at.knownAt() != null) {
                    violations.add(new Violation("knownAt", PlatformErrorCodes.INVALID_VALUE, message));
                }
                throw new ValidationException(violations);
            }
            effective = new At(instant(params, query.timeSlice().asOf()), instant(params, query.timeSlice().knownAt()));
        }
        if (effective.given()) {
            String field = effective.asOf() != null ? "asOf" : "knownAt";
            if (query.publicAccess()) {
                throw new ValidationException(List.of(new Violation(field,
                    PlatformErrorCodes.TIME_TRAVEL_NOT_ALLOWED, "Public query " + query.queryId()
                    + " shows the current state only")));
            }
            datasets.forEach((entity, dataset) -> {
                if (entityRegistry.getOrThrow(entity).temporal && !dataset.policy().allowTimeTravel()) {
                    throw new ValidationException(List.of(new Violation(field,
                        PlatformErrorCodes.TIME_TRAVEL_NOT_ALLOWED,
                        "Dataset " + dataset.resourceId() + " shows the current state only")));
                }
            });
        }
        return new TimeSlice(effective.asOf() != null ? effective.asOf() : clock.instant(), effective.knownAt());
    }

    private static Instant instant(Map<String, BoundValue> params, String name) {
        if (name == null) {
            return null;
        }
        BoundValue bound = params.get(name);
        Object value = bound == null ? null : bound.value();
        return switch (value) {
            case null -> null;
            case Instant time -> time;
            case OffsetDateTime time -> time.toInstant();
            default -> throw new IllegalStateException("Parameter [" + name + "] of a timeSlice is bound as "
                + value.getClass().getSimpleName());
        };
    }

    /** Templates read the read replica when their datasets have one (all share the storage, checked at startup). */
    private static String pool(DatasetDefinition dataset) {
        String replica = dataset.storage().readReplicaRef();
        return replica == null || replica.isBlank() ? dataset.storage().connectionPoolRef() : replica;
    }

    private static Duration effectiveTimeout(Iterable<DatasetDefinition> datasets, AdvancedQueryDefinition query) {
        Duration timeout = query.timeoutOverride();
        for (DatasetDefinition dataset : datasets) {
            Duration own = dataset.policy().queryTimeout();
            timeout = timeout == null || own.compareTo(timeout) < 0 ? own : timeout;
        }
        return timeout;
    }

    /** Converts every declared parameter; all problems are reported together. */
    private Map<String, BoundValue> bindInputs(AdvancedQueryDefinition query, Map<String, Object> inputs) {
        Map<String, Object> given = inputs == null ? Map.of() : inputs;
        List<Violation> violations = new ArrayList<>();
        for (String name : given.keySet()) {
            if (query.parameters().stream().noneMatch(p -> p.name().equals(name))) {
                violations.add(new Violation(name, PlatformErrorCodes.UNKNOWN_FIELD,
                    "Query " + query.queryId() + " has no parameter [" + name + "]"));
            }
        }
        Map<String, BoundValue> result = new LinkedHashMap<>();
        for (QueryParameter spec : query.parameters()) {
            Object value = given.get(spec.name());
            if (value == null) {
                if (spec.required()) {
                    violations.add(new Violation(spec.name(), PlatformErrorCodes.REQUIRED,
                        "Missing required query parameter: " + spec.name()));
                    continue;
                }
                value = spec.defaultValue();
            }
            Class<?> type = TemplateValues.bindingType(spec, entityRegistry::find);
            if (value == null) {
                result.put(spec.name(), BoundValue.nullOf(spec.list() ? type.arrayType() : type));
                continue;
            }
            try {
                result.put(spec.name(), BoundValue.of(TemplateValues.bindable(spec, value, entityRegistry::find)));
            } catch (IllegalArgumentException e) {
                violations.add(new Violation(spec.name(), PlatformErrorCodes.INVALID_VALUE,
                    "Parameter [" + spec.name() + "]: " + e.getMessage()));
            }
        }
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
        return result;
    }

    private static SemanticRow toSemanticRow(AdvancedQueryDefinition queryDef, Map<String, Object> rawRow) {
        SemanticRow semanticRow = new SemanticRow();
        for (ProjectedField field : queryDef.resultFields()) {
            Object raw = rawRow.get(field.name());
            Object value;
            try {
                value = FieldValueCoercer.coerce(field.kind(), raw, false);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("Result column [" + field.name() + "] has an unexpected value: "
                                                + e.getMessage(), e);
            }
            semanticRow.put(field.name(), value, field.kind());
        }
        return semanticRow;
    }
}
