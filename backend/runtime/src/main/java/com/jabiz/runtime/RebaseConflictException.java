package com.jabiz.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * A write to a temporal entity cannot be carried over versions that take effect later, because they changed the
 * same fields (decision D1). The caller has to cancel or change those versions first; answered with 409 listing
 * them.
 */
public class RebaseConflictException extends ConcurrentUpdateException {

    /** A later version the write conflicts with and the operation that wrote it. */
    public record Conflict(String entityType, Object entityId, long versionNo, Instant effectiveFrom,
        long processSeqId, Set<String> fields) {}

    private final List<Conflict> conflicts;

    public RebaseConflictException(String message, List<Conflict> conflicts) {
        super(message);
        this.conflicts = List.copyOf(conflicts);
    }

    public List<Conflict> conflicts() {
        return conflicts;
    }
}
