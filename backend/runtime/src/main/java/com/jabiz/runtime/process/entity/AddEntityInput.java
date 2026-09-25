package com.jabiz.runtime.process.entity;

import java.util.Map;

/**
 * Input of the add process.
 *
 * @param entityType name of the EntityDefinition to create an instance of
 * @param attributes values by logical field name; a generated primary key is assigned by the process
 */
public record AddEntityInput(String entityType, Map<String, Object> attributes) {

    public AddEntityInput {
        InputChecks.require("entityType", entityType);
        attributes = attributes == null ? Map.of() : attributes;
    }
}
