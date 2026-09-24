package com.jabiz.entity;

/**
 * Many-to-one relationship declared by an entity: the value of {@code sourceField} is the
 * primary key of an instance of {@code targetEntity}.
 *
 * @param sourceField  logical name of the field, on the declaring entity, that holds the key
 * @param targetEntity name of the referenced entity definition
 */
public record ReferenceDefinition(String sourceField, String targetEntity) {

    public ReferenceDefinition {
        if (sourceField == null || sourceField.isBlank()) {
            throw new IllegalArgumentException("reference source field must not be blank");
        }
        if (targetEntity == null || targetEntity.isBlank()) {
            throw new IllegalArgumentException("reference target entity must not be blank");
        }
    }
}
