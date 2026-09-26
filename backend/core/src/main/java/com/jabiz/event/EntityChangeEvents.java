package com.jabiz.event;

/**
 * Event types of entity changes (docs/design/11-ledger-events-jobs.md section 2.2): entities that declare
 * {@code eb.publishChanges()} publish {@code jabiz.entity-changed.<Entity>} on every committed write.
 */
public final class EntityChangeEvents {

    public static final String PREFIX = "jabiz.entity-changed.";

    private EntityChangeEvents() {}

    public static String eventType(String entityType) {
        return PREFIX + entityType;
    }
}
