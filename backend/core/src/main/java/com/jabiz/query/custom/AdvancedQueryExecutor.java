package com.jabiz.query.custom;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.EntityDefinitionRegistry;
import com.jabiz.entity.FieldValueCoercer;
import com.jabiz.query.BoundValue;
import com.jabiz.query.QueryCompiler;
import com.jabiz.query.RawQueryPlan;
import com.jabiz.query.SqlIdentifiers;
import com.jabiz.storage.StorageAdapterRegistry;
import com.jabiz.storage.StorageEngine;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes custom multi-entity queries.
 *
 * Dataset rules (default partition filter, soft-delete exclusion, table override) are applied to
 * the dataset's target entity by rendering its placeholder as a scoped sub-select, so the custom
 * SQL cannot see rows the dataset hides.
 */
@Component
public class AdvancedQueryExecutor {

    private static final Pattern PLACEHOLDER =
        Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)(?:\\.([A-Za-z0-9_]+))?\\s*\\}\\}");
    private static final String SCOPE_PARAM_PREFIX = "scope_";

    private final StorageAdapterRegistry storageRegistry;
    private final EntityDefinitionRegistry entityRegistry;
    private final QueryCompiler queryCompiler;

    public AdvancedQueryExecutor(StorageAdapterRegistry storageRegistry,
        EntityDefinitionRegistry entityRegistry,
        QueryCompiler queryCompiler) {
        this.storageRegistry = Objects.requireNonNull(storageRegistry);
        this.entityRegistry = Objects.requireNonNull(entityRegistry);
        this.queryCompiler = Objects.requireNonNull(queryCompiler);
    }

    /**
     * @param dataset     dataset context (storage routing, scope, limits)
     * @param queryDef    the query to run
     * @param inputParams caller-supplied parameter values by name
     */
    public Flux<SemanticRow> execute(
        DatasetDefinition dataset,
        AdvancedQueryDefinition queryDef,
        Map<String, Object> inputParams
    ) {
        return Flux.defer(() -> {
            Map<String, EntityDefinition> entities = resolveParticipatingEntities(queryDef);

            QueryCompiler.Binder scopeBinder = new QueryCompiler.Binder(SCOPE_PARAM_PREFIX);
            String sql = renderTemplate(queryDef.sqlTemplate(), dataset, entities, scopeBinder);

            Map<String, BoundValue> params = new LinkedHashMap<>(bindInputs(queryDef, inputParams));
            params.putAll(scopeBinder.params());

            String pool = dataset.storage().readReplicaRef();
            if (pool == null || pool.isBlank()) {
                pool = dataset.storage().connectionPoolRef();
            }
            StorageEngine engine = storageRegistry.getEngine(pool);

            RawQueryPlan plan = new RawQueryPlan(
                sql, params, dataset.policy().maxQueryBatchSize(), effectiveTimeout(dataset, queryDef));

            return engine.executeRawQuery(plan).map(row -> toSemanticRow(queryDef, row));
        });
    }

    private Duration effectiveTimeout(DatasetDefinition dataset, AdvancedQueryDefinition queryDef) {
        Duration datasetTimeout = dataset.policy().queryTimeout();
        Duration override = queryDef.timeoutOverride();
        return override != null && override.compareTo(datasetTimeout) < 0 ? override : datasetTimeout;
    }

    private Map<String, EntityDefinition> resolveParticipatingEntities(AdvancedQueryDefinition queryDef) {
        Map<String, EntityDefinition> entities = new LinkedHashMap<>();
        for (String name : queryDef.participatingEntities()) {
            entities.put(name, entityRegistry.getOrThrow(name));
        }
        return entities;
    }

    private String renderTemplate(
        String template,
        DatasetDefinition dataset,
        Map<String, EntityDefinition> entities,
        QueryCompiler.Binder scopeBinder
    ) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String entityName = matcher.group(1);
            String fieldName = matcher.group(2);
            EntityDefinition def = entities.get(entityName);
            if (def == null) {
                throw new IllegalArgumentException("Template references entity [" + entityName
                                                   + "] that is not declared in fromEntities");
            }
            String replacement = fieldName == null
                ? tableExpression(dataset, def, scopeBinder)
                : SqlIdentifiers.require(def.physicalColumn(fieldName));
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String tableExpression(DatasetDefinition dataset, EntityDefinition def, QueryCompiler.Binder scopeBinder) {
        String table = queryCompiler.resolveTable(dataset, def);
        String scope = queryCompiler.scopeCondition(dataset, def, scopeBinder);
        return scope.isBlank() ? table : "(SELECT * FROM " + table + " WHERE " + scope + ")";
    }

    private Map<String, BoundValue> bindInputs(AdvancedQueryDefinition queryDef, Map<String, Object> inputs) {
        Map<String, BoundValue> result = new LinkedHashMap<>();
        for (QueryParameter spec : queryDef.parameters()) {
            String name = spec.name();
            if (name.startsWith(SCOPE_PARAM_PREFIX)) {
                throw new IllegalArgumentException("Parameter name [" + name + "] uses the reserved prefix "
                                                   + SCOPE_PARAM_PREFIX);
            }
            Object value = inputs != null ? inputs.get(name) : null;
            if (value == null) {
                if (spec.required()) {
                    throw new IllegalArgumentException("Missing required query parameter: " + name);
                }
                value = spec.defaultValue();
            }
            if (value == null) {
                result.put(name, BoundValue.nullOf(FieldValueCoercer.javaType(spec.kind())));
            } else {
                result.put(name, BoundValue.of(coerceParameter(spec, value)));
            }
        }
        return result;
    }

    private Object coerceParameter(QueryParameter spec, Object value) {
        try {
            if (value instanceof Collection<?> collection) {
                List<Object> coerced = new ArrayList<>(collection.size());
                for (Object item : collection) {
                    if (item == null) {
                        throw new IllegalArgumentException("list must not contain null");
                    }
                    coerced.add(FieldValueCoercer.coerce(spec.kind(), item, true));
                }
                return coerced;
            }
            return FieldValueCoercer.coerce(spec.kind(), value, true);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Parameter [" + spec.name() + "]: " + e.getMessage(), e);
        }
    }

    private SemanticRow toSemanticRow(AdvancedQueryDefinition queryDef, Map<String, Object> rawRow) {
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
