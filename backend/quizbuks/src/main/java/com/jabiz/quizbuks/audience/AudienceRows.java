package com.jabiz.quizbuks.audience;

import com.jabiz.process.ProcessContext;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Function;

/**
 * The stored rows of matching read by a process, for {@link AudienceMatch}: the start's check (Q39) reads a
 * publication's conditions and the caller's own attributes in the statement it reads the card with.
 */
public final class AudienceRows {

    /** The publication's conditions, through the default dataset; none for a null publication. */
    public static <C extends ProcessContext> QueryEntities.Metadata<C> conditions(Function<C, Object> publicationId,
        String targetKey) {
        return QueryEntities.query(QbAudience.CRITERION_DATASET, ctx -> {
            Object id = publicationId.apply(ctx);
            return EntityQuery.builder().where(new QueryPredicate.In("publicationId",
                id == null ? List.of() : List.of(id))).limit(QbAudience.MAX_ROWS).build();
        }, targetKey);
    }

    /** The caller's own attributes (the {@code me:} dataset holds nothing else). */
    public static <C extends ProcessContext> QueryEntities.Metadata<C> callers(String targetKey) {
        return QueryEntities.query(QbAudience.MY_ATTRIBUTE_DATASET,
            ctx -> EntityQuery.builder().limit(QbAudience.MAX_ROWS).build(), targetKey);
    }

    /** Whether the attributes meet the conditions at {@code at} (years in UTC, as the templates). */
    public static boolean matches(List<EntityInstance> conditions, List<EntityInstance> attributes, Instant at) {
        return AudienceMatch.matches(
            conditions.stream().map(row -> new AudienceMatch.ConditionRow(row.get("criterionKey"),
                row.get("valueCode"), integer(row.get("minValue")), integer(row.get("maxValue")))).toList(),
            attributes.stream().map(row -> new AudienceMatch.ProfileRow(row.get("criterionKey"), row.get("valueCode"),
                integer(row.get("valueNumber")))).toList(),
            at.atZone(ZoneOffset.UTC).getYear());
    }

    private static Integer integer(Object value) {
        return value instanceof BigDecimal d ? d.intValueExact() : value instanceof Number n ? n.intValue() : null;
    }

    private AudienceRows() {}
}
