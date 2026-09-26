package com.jabiz.query.template;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldValueCoercer;
import com.jabiz.entity.SemanticKind;
import com.jabiz.entity.SemanticKinds;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.query.BoundValue;
import com.jabiz.query.QueryCompiler;
import com.jabiz.query.QueryOperator;
import com.jabiz.query.QueryPredicate;
import com.jabiz.query.SortOrder;
import com.jabiz.query.SqlIdentifiers;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.custom.ProjectedField;
import com.jabiz.query.custom.ResultListSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Wraps a rendered SQL template for listing (docs/design/05-sql-template.md section 5):
 * <pre>
 * SELECT * FROM ( &lt;template&gt; ) q WHERE &lt;filters&gt; ORDER BY &lt;sorts, then the stable key&gt;
 * LIMIT :__limit OFFSET :__offset
 * </pre>
 * and the matching {@code count(*)}. Filters and sorts may only use result columns the template whitelists, with
 * operators the column's semantic kind allows; values are converted to that kind and bound.
 */
public final class OuterQueryCompiler {

    public static final String ALIAS = "q";
    public static final String LIMIT_PARAM = "__limit";
    public static final String OFFSET_PARAM = "__offset";

    /**
     * @param listParams  parameters of {@code listSql} added by the wrapper (filters, limit, offset)
     * @param countParams parameters of {@code countSql} added by the wrapper (filters)
     */
    public record OuterQuery(String listSql, String countSql, Map<String, BoundValue> listParams,
        Map<String, BoundValue> countParams) {}

    private OuterQueryCompiler() {}

    /**
     * @param filter  conditions on result columns, or null
     * @param sorts   requested sorts; empty for the template's default sort
     * @throws ValidationException for a column not whitelisted, an operator the kind does not allow, a bad value
     */
    public static OuterQuery compile(AdvancedQueryDefinition query, String renderedSql, QueryPredicate filter,
        List<SortOrder> sorts, int offset, int limit) {
        return compile(query, renderedSql, filter, sorts, offset, limit, name -> Optional.empty());
    }

    /**
     * @param entities finds the entities of the result columns' sources; identifiers of temporal entities are
     *                 normalized as UUIDs before they are compared
     */
    public static OuterQuery compile(AdvancedQueryDefinition query, String renderedSql, QueryPredicate filter,
        List<SortOrder> sorts, int offset, int limit, Function<String, Optional<EntityDefinition>> entities) {
        ResultListSpec list = query.list();
        QueryCompiler.Binder binder = new QueryCompiler.Binder("__f");
        String where = filter == null ? "" : new Filters(query, list, binder, entities).predicate(filter);

        StringBuilder from = new StringBuilder("SELECT * FROM (\n").append(renderedSql).append("\n) ").append(ALIAS);
        if (!where.isBlank()) {
            from.append(" WHERE ").append(where);
        }
        String count = "SELECT count(*) AS total FROM (" + from + ") c";

        List<String> order = new ArrayList<>();
        List<String> used = new ArrayList<>();
        List<SortOrder> requested = sorts == null || sorts.isEmpty() ? defaultSort(list) : sorts;
        for (SortOrder sort : requested) {
            ProjectedField column = column(query, sort.field());
            if (!list.allowsSort(column.name())) {
                throw new ValidationException(List.of(new Violation(sort.field(), PlatformErrorCodes.SORT_NOT_ALLOWED,
                    "Sorting query " + query.queryId() + " by [" + sort.field() + "] is not allowed")));
            }
            order.add(reference(column) + (sort.ascending() ? " ASC" : " DESC"));
            used.add(column.name().toLowerCase(Locale.ROOT));
        }
        for (ProjectedField column : stableKey(query)) {
            if (!used.contains(column.name().toLowerCase(Locale.ROOT))) {
                order.add(reference(column) + " ASC");
            }
        }
        String listSql = from + " ORDER BY " + String.join(", ", order)
            + " LIMIT :" + LIMIT_PARAM + " OFFSET :" + OFFSET_PARAM;

        Map<String, BoundValue> countParams = new LinkedHashMap<>(binder.params());
        Map<String, BoundValue> listParams = new LinkedHashMap<>(countParams);
        listParams.put(LIMIT_PARAM, BoundValue.of(limit));
        listParams.put(OFFSET_PARAM, BoundValue.of((long) offset));
        return new OuterQuery(listSql, count, listParams, countParams);
    }

    /** Columns appended to every sort: the declared key, or all result columns that can be ordered. */
    static List<ProjectedField> stableKey(AdvancedQueryDefinition query) {
        if (!query.list().key().isEmpty()) {
            return query.list().key().stream().map(name -> column(query, name)).toList();
        }
        // json(b) values have no ordering of their own worth relying on; json has none at all.
        return query.resultFields().stream()
            .filter(f -> f.kind() == null || !Map.class.isAssignableFrom(FieldValueCoercer.javaType(f.kind())))
            .toList();
    }

    private static List<SortOrder> defaultSort(ResultListSpec list) {
        if (list.defaultSort() == null) {
            return List.of();
        }
        return List.of(new SortOrder(list.defaultSort().field(), list.defaultSort().ascending()));
    }

    /** Compiles the filters of one request against the result columns. */
    private record Filters(AdvancedQueryDefinition query, ResultListSpec list, QueryCompiler.Binder binder,
        Function<String, Optional<EntityDefinition>> entities) {

        String predicate(QueryPredicate pred) {
            return switch (pred) {
                case QueryPredicate.And and -> {
                    List<String> parts = new ArrayList<>();
                    for (QueryPredicate child : and.predicates()) {
                        String compiled = predicate(child);
                        if (!compiled.isBlank()) {
                            parts.add(compiled);
                        }
                    }
                    yield parts.isEmpty() ? "" : "(" + String.join(" AND ", parts) + ")";
                }
                case QueryPredicate.Or or -> {
                    if (or.predicates().isEmpty()) {
                        yield "1 = 0";
                    }
                    List<String> parts = new ArrayList<>();
                    boolean alwaysTrue = false;
                    for (QueryPredicate child : or.predicates()) {
                        String compiled = predicate(child);
                        alwaysTrue |= compiled.isBlank();
                        parts.add(compiled);
                    }
                    yield alwaysTrue ? "" : "(" + String.join(" OR ", parts) + ")";
                }
                case QueryPredicate.Eq eq -> eq.value() == null
                    ? nullCheck(eq.field(), QueryOperator.IS_NULL)
                    : comparison(QueryOperator.EQ, "=", eq.field(), eq.value(), binder);
                case QueryPredicate.Ne ne -> ne.value() == null
                    ? nullCheck(ne.field(), QueryOperator.IS_NOT_NULL)
                    : comparison(QueryOperator.NE, "<>", ne.field(), ne.value(), binder);
                case QueryPredicate.Gt gt -> comparison(QueryOperator.GT, ">", gt.field(), gt.value(), binder);
                case QueryPredicate.Gte gte ->
                    comparison(QueryOperator.GTE, ">=", gte.field(), gte.value(), binder);
                case QueryPredicate.Lt lt -> comparison(QueryOperator.LT, "<", lt.field(), lt.value(), binder);
                case QueryPredicate.Lte lte ->
                    comparison(QueryOperator.LTE, "<=", lte.field(), lte.value(), binder);
                case QueryPredicate.Like like -> {
                    ProjectedField column = filterable(like.field(), QueryOperator.LIKE);
                    if (like.pattern() == null) {
                        throw invalidValue(column, "LIKE needs a pattern");
                    }
                    yield reference(column) + " LIKE :" + binder.bind(BoundValue.of(like.pattern()));
                }
                case QueryPredicate.IsNull isNull -> nullCheck(isNull.field(), QueryOperator.IS_NULL);
                case QueryPredicate.IsNotNull notNull ->
                    nullCheck(notNull.field(), QueryOperator.IS_NOT_NULL);
                case QueryPredicate.In in -> {
                    ProjectedField column = filterable(in.field(), QueryOperator.IN);
                    if (in.values() == null) {
                        throw invalidValue(column, "IN needs a list of values");
                    }
                    List<Object> values = new ArrayList<>(in.values().size());
                    for (Object value : in.values()) {
                        if (value == null) {
                            throw invalidValue(column, "IN list must not contain null");
                        }
                        values.add(coerce(column, value));
                    }
                    // One array parameter, as in templates (decision D7); an empty array matches nothing.
                    Object array = TemplateValues.toArray(FieldValueCoercer.javaType(column.kind()), values);
                    yield reference(column) + " = ANY(:" + binder.bind(BoundValue.of(array)) + ")";
                }
                case QueryPredicate.Between between -> {
                    ProjectedField column = filterable(between.field(), QueryOperator.BETWEEN);
                    if (between.low() == null || between.high() == null) {
                        throw invalidValue(column, "BETWEEN needs both bounds");
                    }
                    String low = binder.bind(BoundValue.of(coerce(column, between.low())));
                    String high = binder.bind(BoundValue.of(coerce(column, between.high())));
                    yield reference(column) + " BETWEEN :" + low + " AND :" + high;
                }
            };
        }

        private String comparison(QueryOperator operator, String sql, String field, Object value,
            QueryCompiler.Binder binder) {
            ProjectedField column = filterable(field, operator);
            if (value == null) {
                throw invalidValue(column, "comparison " + operator + " with null is undefined");
            }
            return reference(column) + " " + sql + " :" + binder.bind(BoundValue.of(coerce(column, value)));
        }

        private String nullCheck(String field, QueryOperator operator) {
            ProjectedField column = filterable(field, operator);
            return reference(column) + (operator == QueryOperator.IS_NULL ? " IS NULL" : " IS NOT NULL");
        }

        private ProjectedField filterable(String field, QueryOperator operator) {
            ProjectedField column = column(query, field);
            if (!list.allowsFilter(column.name())) {
                throw new ValidationException(List.of(new Violation(field, PlatformErrorCodes.FILTER_NOT_ALLOWED,
                    "Filtering query " + query.queryId() + " by [" + field + "] is not allowed")));
            }
            if (!SemanticKinds.allows(column.kind(), operator)) {
                throw new ValidationException(List.of(new Violation(column.name(), PlatformErrorCodes.OPERATOR_NOT_ALLOWED,
                    "Operator " + operator + " is not allowed on column [" + column.name() + "] with semantic "
                        + column.kind(), Map.of("operator", operator.name()))));
            }
            return column;
        }

        private Object coerce(ProjectedField column, Object value) {
            try {
                Object coerced = FieldValueCoercer.coerce(column.kind(), value, false);
                if (!isIdentifier(column.kind())) {
                    return coerced;
                }
                Optional<EntityDefinition> identified = TemplateValues.identifiedEntity(column.kind(),
                    column.sourceEntity(), column.sourceField(), entities);
                return (identified.isPresent() ? identified.get().normalizeId(coerced) : coerced).toString();
            } catch (IllegalArgumentException e) {
                throw invalidValue(column, e.getMessage());
            }
        }
    }

    private static ProjectedField column(AdvancedQueryDefinition query, String name) {
        return query.result(name == null ? "" : name).orElseThrow(() -> new ValidationException(List.of(new Violation(
            name, PlatformErrorCodes.UNKNOWN_FIELD,
            "Query " + query.queryId() + " has no result column [" + name + "]"))));
    }

    /**
     * The column in the outer query. Identifiers may be {@code uuid} columns while their kind binds strings, so they
     * are compared as text.
     */
    private static String reference(ProjectedField column) {
        String ref = ALIAS + "." + SqlIdentifiers.require(column.name().toLowerCase(Locale.ROOT));
        return isIdentifier(column.kind()) ? "CAST(" + ref + " AS text)" : ref;
    }

    private static boolean isIdentifier(SemanticKind kind) {
        return kind instanceof SemanticKind.SemanticIdentity || kind instanceof SemanticKind.Reference;
    }

    private static ValidationException invalidValue(ProjectedField column, String message) {
        return new ValidationException(List.of(new Violation(column.name(), PlatformErrorCodes.INVALID_VALUE,
            "Column [" + column.name() + "]: " + message)));
    }
}
