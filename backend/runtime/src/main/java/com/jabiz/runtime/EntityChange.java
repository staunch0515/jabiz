package com.jabiz.runtime;

import java.time.Instant;
import java.util.Objects;

/**
 * One requested change in a batch: the action to perform and the entity instance it applies to.
 * For UPDATE and DELETE the instance's version is the version the caller last read; for a temporal entity that is
 * the version in effect at the change's effective time.
 *
 * @param effectiveTime for temporal entities, the business time from which the change takes effect; null means
 *                      the time of the operation. Later times schedule the change, earlier times correct the past
 *                      (docs/design/04-temporal-append-only.md section 3.1). Must be null for other entities.
 */
public record EntityChange(EntityAction action, EntityInstance instance, Instant effectiveTime) {

    public EntityChange {
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(instance, "instance must not be null");
    }

    public EntityChange(EntityAction action, EntityInstance instance) {
        this(action, instance, null);
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
