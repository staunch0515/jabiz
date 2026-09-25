package com.jabiz.runtime.operation;

import java.time.Instant;

/** One {@code op_process} row. */
public record OperationRecord(
    long processSeqId,
    Long parentSeqId,
    Long revertsSeqId,
    String processName,
    int processVersion,
    String actorId,
    String tenantId,
    String requestId,
    String idempotencyKey,
    String reason,
    Instant opTime
) {}
