package com.jabiz.runtime.operation;

import com.jabiz.temporal.VersionAction;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One {@code op_process_item} row: a version an operation wrote. */
public record OperationItem(
    long processSeqId,
    String entityType,
    UUID entityId,
    long versionNo,
    Long baseVersionNo,
    VersionAction action,
    Instant effectStartTime,
    List<String> changedFields
) {
    public OperationItem {
        changedFields = List.copyOf(changedFields);
    }
}
