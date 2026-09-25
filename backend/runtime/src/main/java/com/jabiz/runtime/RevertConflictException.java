package com.jabiz.runtime;

import java.util.List;

/**
 * An operation cannot be reverted because later operations changed fields it changed (decision D2). The caller
 * can revert those first, newest first; answered with 409 listing them.
 */
public class RevertConflictException extends ConcurrentUpdateException {

    /** A later operation that changed {@code fields} of the entity. */
    public record Blocking(long processSeqId, String entityType, Object entityId, List<String> fields) {}

    private final List<Blocking> blocking;

    public RevertConflictException(String message, List<Blocking> blocking) {
        super(message);
        this.blocking = List.copyOf(blocking);
    }

    public List<Blocking> blocking() {
        return blocking;
    }
}
