package com.jabiz.process.entity;

/**
 * Input of the delete process.
 *
 * @param entityType name of the EntityDefinition
 * @param id         primary key value of the instance
 * @param version    version the caller last read (optimistic lock)
 */
public record DeleteEntityInput(String entityType, Object id, Long version) {

    public DeleteEntityInput {
        InputChecks.require("entityType", entityType);
        InputChecks.require("id", id);
        InputChecks.require("version", version);
    }
}
