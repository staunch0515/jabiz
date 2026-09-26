package com.jabiz.runtime.web;

import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.query.QueryPredicate;

import java.util.List;
import java.util.Locale;

/** Filters and sorts of list requests, shared by the dataset and SQL template APIs. */
final class ListRequests {

    /**
     * One filter condition. {@code op} is one of eq, ne, gt, gte, lt, lte, in, like, isNull, isNotNull, between;
     * {@code value} is the operand, {@code values} the list of {@code in}, {@code from}/{@code to} the bounds of
     * {@code between}.
     */
    record Filter(String field, String op, Object value, List<Object> values, Object from, Object to) {}

    record Sort(String field, Boolean asc) {}

    private ListRequests() {}

    static QueryPredicate toPredicate(Filter filter) {
        String field = filter.field();
        return switch (filter.op().toLowerCase(Locale.ROOT)) {
            case "eq" -> new QueryPredicate.Eq(field, filter.value());
            case "ne" -> new QueryPredicate.Ne(field, filter.value());
            case "gt" -> new QueryPredicate.Gt(field, filter.value());
            case "gte" -> new QueryPredicate.Gte(field, filter.value());
            case "lt" -> new QueryPredicate.Lt(field, filter.value());
            case "lte" -> new QueryPredicate.Lte(field, filter.value());
            case "in" -> new QueryPredicate.In(field, filter.values() == null ? List.of() : filter.values());
            case "like" -> {
                if (!(filter.value() instanceof String pattern)) {
                    throw invalid(field, "like needs a text pattern");
                }
                yield new QueryPredicate.Like(field, pattern);
            }
            case "isnull" -> new QueryPredicate.IsNull(field);
            case "isnotnull" -> new QueryPredicate.IsNotNull(field);
            case "between" -> new QueryPredicate.Between(field, filter.from(), filter.to());
            default -> throw invalid(field, "unknown operator " + filter.op());
        };
    }

    static ValidationException invalid(String field, String message) {
        return new ValidationException(List.of(new Violation(field, PlatformErrorCodes.INVALID_VALUE, message)));
    }
}
