package com.jabiz.culture;

import com.jabiz.entity.Violation;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Helpers shared by the processes of Culture, Unfiltered: queries over loaded rows and bulk field changes. */
final class Workflow {

    static final String PARAMS = "params";

    /** Rows whose {@code field} is one of {@code values}; none when there are no values. */
    static EntityQuery where(String field, Collection<?> values) {
        // Callers query required fields, which are never null: no values gives an empty result.
        QueryPredicate predicate = values.isEmpty()
            ? new QueryPredicate.IsNull(field)
            : new QueryPredicate.In(field, List.copyOf(values));
        return EntityQuery.builder().where(predicate).limit(CultureDatasets.MAX_QUERY).build();
    }

    static EntityQuery where(String field, Object value) {
        return EntityQuery.builder().where(new QueryPredicate.Eq(field, value)).limit(CultureDatasets.MAX_QUERY)
            .build();
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> rows(ProcessContext ctx, String key) {
        return ctx.contains(key) ? (List<EntityInstance>) ctx.get(key) : List.of();
    }

    static Set<Object> values(Collection<EntityInstance> rows, Function<EntityInstance, Object> value) {
        Set<Object> values = new LinkedHashSet<>();
        rows.forEach(row -> {
            Object v = value.apply(row);
            if (v != null) {
                values.add(v);
            }
        });
        return values;
    }

    static boolean switchOn(ProcessContext ctx, String key) {
        return Boolean.TRUE.equals(ctx.get(PARAMS, ParamValues.class).get(key, Boolean.class));
    }

    /** Sets {@code field} to {@code value} on every row that holds something else. */
    static void setAll(ProcessContext ctx, Collection<EntityInstance> rows, String field, Object value) {
        setAll(ctx, rows, mapOf(field, value));
    }

    /**
     * Sets the given fields on every row, one update per row with the fields it does not hold yet (a second update of
     * the same row in one process would conflict with the first).
     */
    static void setAll(ProcessContext ctx, Collection<EntityInstance> rows, Map<String, Object> values) {
        for (EntityInstance row : rows) {
            Map<String, Object> changes = new java.util.HashMap<>();
            values.forEach((field, value) -> {
                if (!Objects.equals(row.get(field), value)) {
                    changes.put(field, value);
                }
            });
            if (!changes.isEmpty()) {
                ctx.changes().update(row.entityType(), row.id(), row.version(), changes);
            }
        }
    }

    static Map<String, Object> mapOf(String field, Object value) {
        Map<String, Object> changes = new java.util.HashMap<>();
        changes.put(field, value);
        return changes;
    }

    /** Refuses a run on a row that is not in one of the expected states (422), before any change. */
    static boolean inState(ProcessContext ctx, EntityInstance row, String field, String... expected) {
        Object state = row.get(field);
        if (List.of(expected).contains(state)) {
            return true;
        }
        ctx.reject(new Violation(field, "WRONG_STATE", "The " + row.entityType() + " is " + state,
            Map.of("state", String.valueOf(state), "expected", String.join(", ", expected))));
        return false;
    }

    private Workflow() {}
}
