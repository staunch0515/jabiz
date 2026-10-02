package com.jabiz.finance.fa;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;

import java.util.List;
import java.util.Locale;

/** What the asset processes share: reading the context and the queries they load by. */
final class FaSupport {

    static final String INPUT = "input";

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> found = (List<EntityInstance>) ctx.get(key);
        return found == null ? List.of() : found;
    }

    static EntityInstance first(ProcessContext ctx, String key) {
        return list(ctx, key).stream().findFirst().orElse(null);
    }

    /** {@code field = value}, one; nothing when there is no value. */
    static EntityQuery eq(String field, Object value) {
        if (value == null) {
            return EntityQuery.builder().where(new QueryPredicate.In(field, List.of())).limit(1).build();
        }
        return EntityQuery.builder().where(new QueryPredicate.Eq(field, value)).limit(1).build();
    }

    static EntityQuery in(String field, List<?> values, int limit) {
        return EntityQuery.builder().where(new QueryPredicate.In(field, List.copyOf(values))).limit(limit).build();
    }

    static String code(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static ProcessContext withInput(ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private FaSupport() {}
}
