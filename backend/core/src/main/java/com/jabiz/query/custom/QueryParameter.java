package com.jabiz.query.custom;

import com.jabiz.entity.SemanticKind;

/**
 * Metadata of one query input parameter (docs/design/05-sql-template.md section 4).
 * Parameter names must not start with "scope_" or "__", which are reserved for the platform.
 *
 * @param kind        semantic type of the value; null while it is still to be inherited from {@code likeEntity}
 * @param list        whether the value is a list, bound as one array ({@code = ANY(:name)}, decision D7)
 * @param likeEntity  entity whose field the parameter is "like" (inherits its semantic type), or null
 * @param likeField   that field, or null
 */
public record QueryParameter(
    String name,
    SemanticKind kind,
    boolean required,
    Object defaultValue,
    String description,
    boolean list,
    String likeEntity,
    String likeField
) {
    public static QueryParameter of(String name, SemanticKind kind, boolean required) {
        return new QueryParameter(name, kind, required, null, "", false, null, null);
    }

    public static QueryParameter withDefault(String name, SemanticKind kind, Object defaultValue) {
        return new QueryParameter(name, kind, false, defaultValue, "", false, null, null);
    }

    public static QueryParameter listOf(String name, SemanticKind kind, boolean required) {
        return new QueryParameter(name, kind, required, null, "", true, null, null);
    }

    /** A parameter with the semantic type of {@code entity.field}, resolved when the query is registered. */
    public static QueryParameter like(String name, String entity, String field, boolean required, boolean list) {
        return new QueryParameter(name, null, required, null, "", list, entity, field);
    }

    public QueryParameter withKind(SemanticKind resolved) {
        return new QueryParameter(name, resolved, required, defaultValue, description, list, likeEntity, likeField);
    }
}
