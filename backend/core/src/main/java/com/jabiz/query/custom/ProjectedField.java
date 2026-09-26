package com.jabiz.query.custom;

import com.jabiz.entity.SemanticKind;

/**
 * Metadata of one projected column of a query result.
 *
 * @param name         column name or alias in the result set; matched case-insensitively, since PostgreSQL folds
 *                     unquoted aliases to lower case
 * @param kind         semantic type of the column (keeps dimension, currency, H3 resolution and so on); null while it
 *                     is still to be inherited from the source field
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

    /** A column with the semantic type of {@code entity.field}, resolved when the query is registered. */
    public static ProjectedField inherit(String name, String entity, String field) {
        return new ProjectedField(name, null, entity, field);
    }

    public ProjectedField withKind(SemanticKind resolved) {
        return new ProjectedField(name, resolved, sourceEntity, sourceField);
    }
}
