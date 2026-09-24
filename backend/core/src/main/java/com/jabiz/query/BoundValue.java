package com.jabiz.query;

import java.util.Objects;

/**
 * A value bound to a named query parameter together with its Java type, so that
 * NULL parameters can still be bound with a type.
 */
public record BoundValue(Object value, Class<?> type) {

    public BoundValue {
        Objects.requireNonNull(type, "type must not be null");
    }

    public static BoundValue of(Object value) {
        Objects.requireNonNull(value, "value must not be null; use nullOf(type) to bind NULL");
        return new BoundValue(value, value.getClass());
    }

    public static BoundValue nullOf(Class<?> type) {
        return new BoundValue(null, type);
    }
}
