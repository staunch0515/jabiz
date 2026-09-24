package com.jabiz.runtime;

import java.util.Objects;

/**
 * One requested change in a batch: the action to perform and the entity instance it applies to.
 * For UPDATE and DELETE the instance's version is the version the caller last read.
 */
public record EntityChange(EntityAction action, EntityInstance instance) {

    public EntityChange {
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(instance, "instance must not be null");
    }

    public static EntityChange insert(EntityInstance instance) {
        return new EntityChange(EntityAction.INSERT, instance);
    }

    public static EntityChange update(EntityInstance instance) {
        return new EntityChange(EntityAction.UPDATE, instance);
    }

    public static EntityChange delete(EntityInstance instance) {
        return new EntityChange(EntityAction.DELETE, instance);
    }
}
