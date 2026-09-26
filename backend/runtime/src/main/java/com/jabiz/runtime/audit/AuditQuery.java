package com.jabiz.runtime.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * Filters of the audit view (docs/design/11-ledger-events-jobs.md section 3); every one is optional and they
 * combine with AND.
 *
 * @param actorId     who ran the operation
 * @param from        operations at or after this time ({@code op_time})
 * @param to          operations before this time
 * @param processName the process (or {@code jabiz.dataset.commit}, a revert's process name)
 * @param entityType  operations that wrote a version of this entity type
 * @param entityId    operations that wrote a version of this instance (with or without {@code entityType})
 */
public record AuditQuery(String actorId, Instant from, Instant to, String processName, String entityType,
    UUID entityId, int offset, int limit) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 500;
}
