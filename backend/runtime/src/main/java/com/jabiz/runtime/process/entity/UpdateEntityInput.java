package com.jabiz.runtime.process.entity;

import java.util.Map;

/**
 * Input of the update process.
 *
 * @param entityType name of the EntityDefinition
 * @param id         primary key value of the instance
 * @param version    version the caller last read (optimistic lock)
 * @param attributes the fields to change by logical field name; a null value clears the field
 */
public record UpdateEntityInput(String entityType, Object id, Long version, Map<String, Object> attributes) {

    public UpdateEntityInput {
        InputChecks.require("entityType", entityType);
        InputChecks.require("id", id);
        InputChecks.require("version", version);
        attributes = attributes == null ? Map.of() : attributes;
    }
}
