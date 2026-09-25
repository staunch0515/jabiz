package com.jabiz.query;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dataset.DatasetPolicy;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.entity.FieldValueCoercer;
import com.jabiz.entity.SemanticKinds;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles logical queries into physical query plans for SQL dialects.
 * All values are bound as named parameters; identifiers come from metadata only.
 *
 * <p>Caller mistakes (unknown field, operator the field's semantic kind does not allow, value of the wrong
 * type) are reported as {@link ValidationException}.
 */
public class QueryCompiler {

    /** Allocates unique named parameters and remembers their values. */
    public static final class Binder {
        private final String prefix;
        private final Map<String, BoundValue> params = new LinkedHashMap<>();
        private int next = 0;

        public Binder(String prefix) {
            this.prefix = prefix;
        }

        /** Registers the value under a fresh name and returns that name. */
        public String bind(BoundValue value) {
            String name = prefix + next++;
            params.put(name, value);
            return name;
        }

        public Map<String, BoundValue> params() {
            return params;
        }
    }

    /**
     * Compiles an entity query against the dataset, applying the dataset scope and limits.
     *
     * @param scopeValues the dataset scope resolved for the current request
     *                    ({@link com.jabiz.dataset.DatasetScope#resolve})
     */
    public PhysicalQueryPlan compile(DatasetDefinition dataset, EntityDefinition def, EntityQuery query,
        Map<String, Object> scopeValues) {
        String physicalTable = resolveTable(dataset, def);
        int safeLimit = Math.min(query.limit(), dataset.policy().maxQueryBatchSize());

        Binder binder = new Binder("p");
        List<String> fragments = new ArrayList<>();

        String scope = scopeCondition(dataset, def, scopeValues, binder);
        if (!scope.isBlank()) {
            fragments.add(scope);
        }
        if (query.predicate() != null) {
            String compiled = compilePredicate(query.predicate(), def, binder);
            if (!compiled.isBlank()) {
                fragments.add(compiled);
            }
        }

        List<PhysicalQueryPlan.PhysicalSort> sorts = new ArrayList<>();
        for (SortOrder sort : query.sorts()) {
            FieldDefinition fd = resolveField(def, sort.field());
            sorts.add(new PhysicalQueryPlan.PhysicalSort(SqlIdentifiers.require(fd.physicalColumn()), sort.ascending()));
        }
        String primaryKeyColumn = SqlIdentifiers.require(def.primaryKeyColumn());
        if (sorts.stream().noneMatch(s -> s.physicalColumn().equalsIgnoreCase(primaryKeyColumn))) {
            // A deterministic order is required for stable pagination.
            sorts.add(new PhysicalQueryPlan.PhysicalSort(primaryKeyColumn, true));
        }

        return new PhysicalQueryPlan(
            physicalTable,
            String.join(" AND ", fragments),
            binder.params(),
            sorts,
            query.offset(),
            safeLimit,
            dataset.policy().queryTimeout()
        );
    }

    /** Table for the entity within the dataset: the override applies to the target entity only. */
    public String resolveTable(DatasetDefinition dataset, EntityDefinition def) {
        if (dataset.isTarget(def.name)) {
            String override = dataset.storage().physicalTableOverride();
            if (override != null && !override.isBlank()) {
                return SqlIdentifiers.require(override);
            }
        }
        return SqlIdentifiers.require(def.physicalTable);
    }

    /**
     * Condition enforced on every read of the dataset's target entity: the scope and the soft-delete
     * exclusion. Returns an empty string when nothing applies.
     *
     * @param scopeValues the dataset scope resolved for the current request
     */
    public String scopeCondition(DatasetDefinition dataset, EntityDefinition def, Map<String, Object> scopeValues,
        Binder binder) {
        if (!dataset.isTarget(def.name)) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> entry : scopeValues.entrySet()) {
            FieldDefinition fd = resolveField(def, entry.getKey());
            Object value = FieldValueCoercer.coerce(fd, entry.getValue(), false);
            parts.add(SqlIdentifiers.require(fd.physicalColumn()) + " = :" + binder.bind(BoundValue.of(value)));
        }
        DatasetPolicy policy = dataset.policy();
        if (policy.softDelete()) {
            parts.add(SqlIdentifiers.require(def.physicalColumn(policy.softDeleteField())) + " IS NOT TRUE");
        }
        return String.join(" AND ", parts);
    }

    private String compilePredicate(QueryPredicate pred, EntityDefinition def, Binder binder) {
        return switch (pred) {
            case QueryPredicate.Eq eq -> eq.value() == null
                ? nullCheck(def, eq.field(), QueryOperator.IS_NULL)
                : comparison(QueryOperator.EQ, "=", def, eq.field(), eq.value(), binder);
            case QueryPredicate.Ne ne -> ne.value() == null
                ? nullCheck(def, ne.field(), QueryOperator.IS_NOT_NULL)
                : comparison(QueryOperator.NE, "<>", def, ne.field(), ne.value(), binder);
            case QueryPredicate.Gt gt -> comparison(QueryOperator.GT, ">", def, gt.field(), gt.value(), binder);
            case QueryPredicate.Gte gte -> comparison(QueryOperator.GTE, ">=", def, gte.field(), gte.value(), binder);
            case QueryPredicate.Lt lt -> comparison(QueryOperator.LT, "<", def, lt.field(), lt.value(), binder);
            case QueryPredicate.Lte lte -> comparison(QueryOperator.LTE, "<=", def, lte.field(), lte.value(), binder);
            case QueryPredicate.In in -> compileIn(in, def, binder);
            case QueryPredicate.Like like -> compileLike(like, def, binder);
            case QueryPredicate.IsNull isNull -> nullCheck(def, isNull.field(), QueryOperator.IS_NULL);
            case QueryPredicate.IsNotNull isNotNull -> nullCheck(def, isNotNull.field(), QueryOperator.IS_NOT_NULL);
            case QueryPredicate.Between between -> compileBetween(between, def, binder);
            case QueryPredicate.And and -> {
                List<String> children = compileChildren(and.predicates(), def, binder);
                yield children.isEmpty() ? "" : "(" + String.join(" AND ", children) + ")";
            }
            case QueryPredicate.Or or -> {
                if (or.predicates().isEmpty()) {
                    yield "1 = 0";
                }
                List<String> children = new ArrayList<>();
                boolean alwaysTrue = false;
                for (QueryPredicate child : or.predicates()) {
                    // Every child is compiled, so that each is checked even when the result is already decided.
                    String compiled = compilePredicate(child, def, binder);
                    if (compiled.isBlank()) {
                        alwaysTrue = true;
                    }
                    children.add(compiled);
                }
                // A child that is always true makes the whole disjunction true.
                yield alwaysTrue ? "" : "(" + String.join(" OR ", children) + ")";
            }
        };
    }

    private List<String> compileChildren(List<QueryPredicate> predicates, EntityDefinition def, Binder binder) {
        List<String> children = new ArrayList<>();
        for (QueryPredicate child : predicates) {
            String compiled = compilePredicate(child, def, binder);
            if (!compiled.isBlank()) {
                children.add(compiled);
            }
        }
        return children;
    }

    private String compileIn(QueryPredicate.In in, EntityDefinition def, Binder binder) {
        FieldDefinition fd = requireOperator(def, in.field(), QueryOperator.IN);
        if (in.values() == null || in.values().isEmpty()) {
            return "1 = 0";
        }
        List<Object> coerced = new ArrayList<>(in.values().size());
        for (Object value : in.values()) {
            if (value == null) {
                throw invalidValue(fd, "IN list must not contain null");
            }
            coerced.add(coerce(fd, value));
        }
        String param = binder.bind(BoundValue.of(coerced));
        return SqlIdentifiers.require(fd.physicalColumn()) + " IN (:" + param + ")";
    }

    private String compileLike(QueryPredicate.Like like, EntityDefinition def, Binder binder) {
        FieldDefinition fd = requireOperator(def, like.field(), QueryOperator.LIKE);
        if (like.pattern() == null) {
            throw invalidValue(fd, "LIKE needs a pattern");
        }
        return SqlIdentifiers.require(fd.physicalColumn()) + " LIKE :" + binder.bind(BoundValue.of(like.pattern()));
    }

    private String compileBetween(QueryPredicate.Between between, EntityDefinition def, Binder binder) {
        FieldDefinition fd = requireOperator(def, between.field(), QueryOperator.BETWEEN);
        if (between.low() == null || between.high() == null) {
            throw invalidValue(fd, "BETWEEN needs both bounds");
        }
        String low = binder.bind(BoundValue.of(coerce(fd, between.low())));
        String high = binder.bind(BoundValue.of(coerce(fd, between.high())));
        return SqlIdentifiers.require(fd.physicalColumn()) + " BETWEEN :" + low + " AND :" + high;
    }

    private String nullCheck(EntityDefinition def, String field, QueryOperator operator) {
        FieldDefinition fd = requireOperator(def, field, operator);
        return SqlIdentifiers.require(fd.physicalColumn())
            + (operator == QueryOperator.IS_NULL ? " IS NULL" : " IS NOT NULL");
    }

    private String comparison(QueryOperator operator, String sql, EntityDefinition def, String field, Object value,
        Binder binder) {
        FieldDefinition fd = requireOperator(def, field, operator);
        if (value == null) {
            throw invalidValue(fd, "comparison " + operator + " with null is undefined");
        }
        return SqlIdentifiers.require(fd.physicalColumn()) + " " + sql + " :" + binder.bind(BoundValue.of(coerce(fd, value)));
    }

    private FieldDefinition requireOperator(EntityDefinition def, String field, QueryOperator operator) {
        FieldDefinition fd = resolveField(def, field);
        if (!SemanticKinds.allows(fd.kind(), operator)) {
            throw new ValidationException(List.of(new Violation(fd.name(), PlatformErrorCodes.OPERATOR_NOT_ALLOWED,
                "Operator " + operator + " is not allowed on field [" + fd.name() + "] with semantic " + fd.kind(),
                Map.of("operator", operator.name()))));
        }
        return fd;
    }

    private static Object coerce(FieldDefinition fd, Object value) {
        try {
            return FieldValueCoercer.coerce(fd, value, false);
        } catch (IllegalArgumentException e) {
            throw invalidValue(fd, e.getMessage());
        }
    }

    private static ValidationException invalidValue(FieldDefinition fd, String message) {
        return new ValidationException(List.of(new Violation(fd.name(), PlatformErrorCodes.INVALID_VALUE,
            "Field [" + fd.name() + "]: " + message)));
    }

    private FieldDefinition resolveField(EntityDefinition def, String fieldName) {
        return def.findField(fieldName).orElseThrow(() -> new ValidationException(List.of(new Violation(
            fieldName, PlatformErrorCodes.UNKNOWN_FIELD,
            "Field [" + fieldName + "] does not exist on entity [" + def.name + "]"))));
    }
}
