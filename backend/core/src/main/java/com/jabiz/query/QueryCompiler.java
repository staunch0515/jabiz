package com.jabiz.query;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dataset.DatasetPolicy;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.entity.FieldValueCoercer;
import com.jabiz.entity.SemanticKind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Compiles logical queries into physical query plans for SQL dialects.
 * All values are bound as named parameters; identifiers come from metadata only.
 */
@Component
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

    /** Compiles an entity query against the dataset, applying the dataset scope and limits. */
    public PhysicalQueryPlan compile(DatasetDefinition dataset, EntityDefinition def, EntityQuery query) {
        String physicalTable = resolveTable(dataset, def);
        int safeLimit = Math.min(query.limit(), dataset.policy().maxQueryBatchSize());

        Binder binder = new Binder("p");
        List<String> fragments = new ArrayList<>();

        String scope = scopeCondition(dataset, def, binder);
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
        if (sorts.isEmpty()) {
            // A deterministic order is required for stable pagination.
            sorts.add(new PhysicalQueryPlan.PhysicalSort(SqlIdentifiers.require(def.primaryKeyColumn()), true));
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
     * Condition enforced on every read of the dataset's target entity: the default partition
     * filter and the soft-delete exclusion. Returns an empty string when nothing applies.
     */
    public String scopeCondition(DatasetDefinition dataset, EntityDefinition def, Binder binder) {
        if (!dataset.isTarget(def.name)) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> entry : dataset.defaultPartitionFilter().entrySet()) {
            FieldDefinition fd = resolveField(def, entry.getKey());
            Object value = FieldValueCoercer.coerce(fd, entry.getValue(), false);
            parts.add(comparison("=", fd, value, binder));
        }
        DatasetPolicy policy = dataset.policy();
        if (policy.softDelete()) {
            parts.add(SqlIdentifiers.require(policy.softDeleteColumn()) + " IS NOT TRUE");
        }
        return String.join(" AND ", parts);
    }

    private String compilePredicate(QueryPredicate pred, EntityDefinition def, Binder binder) {
        return switch (pred) {
            case QueryPredicate.Eq eq -> comparison("=", resolveField(def, eq.field()), coerce(def, eq.field(), eq.value()), binder);
            case QueryPredicate.Ne ne -> comparison("<>", resolveField(def, ne.field()), coerce(def, ne.field(), ne.value()), binder);
            case QueryPredicate.Gt gt -> comparison(">", resolveField(def, gt.field()), coerce(def, gt.field(), gt.value()), binder);
            case QueryPredicate.Gte gte -> comparison(">=", resolveField(def, gte.field()), coerce(def, gte.field(), gte.value()), binder);
            case QueryPredicate.Lt lt -> comparison("<", resolveField(def, lt.field()), coerce(def, lt.field(), lt.value()), binder);
            case QueryPredicate.Lte lte -> comparison("<=", resolveField(def, lte.field()), coerce(def, lte.field(), lte.value()), binder);
            case QueryPredicate.In in -> compileIn(in, def, binder);
            case QueryPredicate.And and -> {
                List<String> children = compileChildren(and.predicates(), def, binder);
                yield children.isEmpty() ? "" : "(" + String.join(" AND ", children) + ")";
            }
            case QueryPredicate.Or or -> {
                if (or.predicates().isEmpty()) {
                    yield "1 = 0";
                }
                List<String> children = new ArrayList<>();
                for (QueryPredicate child : or.predicates()) {
                    String compiled = compilePredicate(child, def, binder);
                    if (compiled.isBlank()) {
                        // A child that is always true makes the whole disjunction true.
                        yield "";
                    }
                    children.add(compiled);
                }
                yield "(" + String.join(" OR ", children) + ")";
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
        FieldDefinition fd = resolveField(def, in.field());
        if (in.values().isEmpty()) {
            return "1 = 0";
        }
        List<Object> coerced = new ArrayList<>(in.values().size());
        for (Object value : in.values()) {
            if (value == null) {
                throw new IllegalArgumentException("IN list on field [" + in.field() + "] must not contain null");
            }
            coerced.add(FieldValueCoercer.coerce(fd, value, false));
        }
        String param = binder.bind(BoundValue.of(coerced));
        return SqlIdentifiers.require(fd.physicalColumn()) + " IN (:" + param + ")";
    }

    private String comparison(String op, FieldDefinition fd, Object value, Binder binder) {
        String column = SqlIdentifiers.require(fd.physicalColumn());

        if (!op.equals("=") && !op.equals("<>") && (fd.kind() instanceof SemanticKind.Code
                                                       || fd.kind() instanceof SemanticKind.SpatialH3)) {
            throw new IllegalArgumentException(
                "Range comparison [" + op + "] is invalid on field [" + fd.name() + "] with semantic " + fd.kind());
        }
        if (value == null) {
            return switch (op) {
                case "=" -> column + " IS NULL";
                case "<>" -> column + " IS NOT NULL";
                default -> throw new IllegalArgumentException(
                    "Comparison [" + op + "] with null is undefined on field [" + fd.name() + "]");
            };
        }
        String param = binder.bind(BoundValue.of(value));
        return column + " " + op + " :" + param;
    }

    private Object coerce(EntityDefinition def, String fieldName, Object value) {
        return FieldValueCoercer.coerce(resolveField(def, fieldName), value, false);
    }

    private FieldDefinition resolveField(EntityDefinition def, String fieldName) {
        Optional<FieldDefinition> fd = def.findField(fieldName);
        return fd.orElseThrow(() -> new IllegalArgumentException(
            "Field [" + fieldName + "] does not exist on entity [" + def.name + "]"));
    }
}
