package com.jabiz.query;

import java.util.List;

/**
 * Logical query condition over the logical fields of an entity. Every comparison is checked against the
 * operators the field's semantic kind allows ({@link com.jabiz.entity.SemanticKinds}).
 */
public sealed interface QueryPredicate {
    record Eq(String field, Object value) implements QueryPredicate {}
    /**
     * A text field equal to {@code value} regardless of case ({@code lower(column) = lower(value)}): for values such
     * as e-mail addresses that are unique regardless of case (decision D36). Not for templates.
     */
    record EqIgnoreCase(String field, String value) implements QueryPredicate {}
    record Ne(String field, Object value) implements QueryPredicate {}
    record Gt(String field, Object value) implements QueryPredicate {}
    record Gte(String field, Object value) implements QueryPredicate {}
    record Lt(String field, Object value) implements QueryPredicate {}
    record Lte(String field, Object value) implements QueryPredicate {}
    record In(String field, List<Object> values) implements QueryPredicate {}
    /** SQL LIKE: {@code %} matches any sequence, {@code _} one character; case sensitive. */
    record Like(String field, String pattern) implements QueryPredicate {}
    record IsNull(String field) implements QueryPredicate {}
    record IsNotNull(String field) implements QueryPredicate {}
    /** Inclusive range {@code low <= field <= high}. */
    record Between(String field, Object low, Object high) implements QueryPredicate {}
    /**
     * The primary key after {@code value}, in the key's own order, whatever operators its kind allows otherwise: for
     * paging by key (the open-format export, docs/design/21-audit-retention.md section 4). Not for templates.
     */
    record KeyAfter(Object value) implements QueryPredicate {}
    record And(List<QueryPredicate> predicates) implements QueryPredicate {}
    record Or(List<QueryPredicate> predicates) implements QueryPredicate {}
}
