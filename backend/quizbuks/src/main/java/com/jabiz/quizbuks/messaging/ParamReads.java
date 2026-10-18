package com.jabiz.quizbuks.messaging;

import com.jabiz.process.ProcessContext;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.param.ParamEntities;
import com.jabiz.runtime.process.steps.QueryEntities;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads business parameters without failing while one has no value (as {@code QB_LEGAL_PUBLISH} reads its horizon):
 * the platform's {@code LoadParams} fails the whole process then, and a parameter of a new phase has none until
 * {@code QB_SETUP} ran again after the deployment. Each reader falls back to the parameter's default.
 */
final class ParamReads {

    private ParamReads() {}

    /** The query of the parameters {@code keys}' current values. */
    static QueryEntities.Metadata<ProcessContext> query(String targetKey, List<String> keys) {
        return QueryEntities.query(ParamEntities.DATASET, ctx -> EntityQuery.builder()
            .where(new QueryPredicate.In(ParamEntities.KEY, List.copyOf(keys))).limit(keys.size() + 1).build(),
            targetKey);
    }

    /** The values read by {@link #query}, by key. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> values(ProcessContext ctx, String targetKey) {
        Map<String, Object> values = new LinkedHashMap<>();
        Object rows = ctx.contains(targetKey) ? ctx.get(targetKey) : null;
        if (rows instanceof List<?> list) {
            for (EntityInstance row : (List<EntityInstance>) list) {
                values.put(String.valueOf((Object) row.get(ParamEntities.KEY)), row.get(ParamEntities.VALUE));
            }
        }
        return values;
    }

    /** A whole, positive number of a parameter's value, or {@code fallback} without one. */
    static int days(Object value, int fallback) {
        try {
            int number = value == null || value.toString().isBlank() ? fallback
                : new BigDecimal(value.toString()).intValueExact();
            return number > 0 ? number : fallback;
        } catch (ArithmeticException | NumberFormatException e) {
            return fallback;
        }
    }
}
