package com.jabiz.entity;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * Server-side rule attached to a field. The predicate receives the value after
 * normalization to the field's canonical Java type (see {@link FieldValueCoercer}).
 */
public record FieldRule(String code, RulePredicate predicate) {

    public FieldRule {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(predicate, "predicate must not be null");
    }

    public static FieldRule of(String code, Predicate<Object> predicate) {
        Objects.requireNonNull(predicate, "predicate must not be null");
        return new FieldRule(code, (value, ctx) -> predicate.test(value));
    }

    public boolean isSatisfiedBy(Object value, ValidationContext ctx) {
        return predicate.test(value, ctx);
    }
}
