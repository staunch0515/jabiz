package com.jabiz.query.custom;

import com.jabiz.entity.SemanticKind;

/**
 * Metadata of one projected column of a query result.
 *
 * @param name         column name or alias in the result set
 * @param kind         semantic type of the column (keeps dimension, currency, H3 resolution and so on)
 * @param sourceEntity originating entity, optional, for lineage
 * @param sourceField  originating field, optional, for lineage
 */
public record ProjectedField(
    String name,
    SemanticKind kind,
    String sourceEntity,
    String sourceField
) {
    public static ProjectedField of(String name, SemanticKind kind) {
        return new ProjectedField(name, kind, null, null);
    }

    public static ProjectedField from(String name, SemanticKind kind, String entity, String field) {
        return new ProjectedField(name, kind, entity, field);
    }
}
