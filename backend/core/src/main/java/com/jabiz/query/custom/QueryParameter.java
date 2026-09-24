package com.jabiz.query.custom;

import com.jabiz.entity.SemanticKind;

/**
 * Metadata of one query input parameter.
 * Parameter names must not start with "scope_", which is reserved for dataset scope parameters.
 */
public record QueryParameter(
    String name,
    SemanticKind kind,
    boolean required,
    Object defaultValue,
    String description
) {
    public static QueryParameter of(String name, SemanticKind kind, boolean required) {
        return new QueryParameter(name, kind, required, null, "");
    }

    public static QueryParameter withDefault(String name, SemanticKind kind, Object defaultValue) {
        return new QueryParameter(name, kind, false, defaultValue, "");
    }
}
