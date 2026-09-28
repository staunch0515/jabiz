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
        return page(queryDef, inputParams, null, List.of(), 0, Integer.MAX_VALUE, false)
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
        return observations.mono(PlatformObservations.TEMPLATE, "template " + queryDef.queryId(),
            KeyValues.of("template", queryDef.queryId()),
            pageOf(queryDef, inputParams, filter, sorts, offset, limit, count, maxTimeout));
    }

    private Mono<Page> pageOf(AdvancedQueryDefinition queryDef, Map<String, Object> inputParams,
        QueryPredicate filter, List<SortOrder> sorts, int offset, int limit, boolean count, Duration maxTimeout) {
        return RequestContexts.current().flatMap(request -> {
            AdvancedQueryDefinition query = templates.prepare(queryDef);
            Map<String, DatasetDefinition> datasets = templates.datasetsOf(query);
            Map<String, SqlTemplateRenderer.EntityBinding> bindings = new LinkedHashMap<>();
            datasets.forEach((entity, dataset) -> bindings.put(entity, new SqlTemplateRenderer.EntityBinding(
                entityRegistry.getOrThrow(entity), dataset, dataset.scope().resolve(request))));

            Map<String, BoundValue> params = new LinkedHashMap<>(bindInputs(query, inputParams));
            QueryCompiler.Binder platform = new QueryCompiler.Binder(TemplateChecks.SCOPE_PREFIX);
            String sql = renderer.render(query, bindings, TimeSlice.asOf(clock.instant()), platform).sql();
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
