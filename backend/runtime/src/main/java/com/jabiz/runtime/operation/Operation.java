package com.jabiz.runtime.operation;

import java.time.Instant;
import java.util.Objects;

/**
 * A running operation ({@code op_process} row, docs/design/04-temporal-append-only.md section 2.2): every version
 * written within it carries its {@code processSeqId} and records {@code opTime} as its creation time.
 */
public record Operation(
    long processSeqId,
    Instant opTime,
    String processName,
    int processVersion,
    String actorId,
    String reason,
    Long parentSeqId,
    Long revertsSeqId
) {
    public Operation {
        Objects.requireNonNull(opTime, "opTime must not be null");
        Objects.requireNonNull(processName, "processName must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
    }
}
