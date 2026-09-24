package com.jabiz.query;

import java.util.List;

public sealed interface QueryPredicate {
    record Eq(String field, Object value) implements QueryPredicate {}
    record Ne(String field, Object value) implements QueryPredicate {}
    record Gt(String field, Object value) implements QueryPredicate {}
    record Gte(String field, Object value) implements QueryPredicate {}
    record Lt(String field, Object value) implements QueryPredicate {}
    record Lte(String field, Object value) implements QueryPredicate {}
    record In(String field, List<Object> values) implements QueryPredicate {}
    record And(List<QueryPredicate> predicates) implements QueryPredicate {}
    record Or(List<QueryPredicate> predicates) implements QueryPredicate {}
}
