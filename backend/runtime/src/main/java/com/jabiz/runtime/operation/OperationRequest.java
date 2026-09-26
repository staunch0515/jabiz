package com.jabiz.runtime.operation;

import java.time.Instant;
import java.util.Objects;

/**
 * What an operation should be recorded as.
 *
 * @param processName    name of the process (or platform function) performing it
 * @param processVersion version of the process
 * @param processSeqId   number already drawn from {@code op_process_seq}, or null to draw one
 * @param parentSeqId    operation this one runs within, if any
 * @param revertsSeqId   operation this one reverts, if any
 * @param reason         why the operation is performed; required for corrections and reverts
 * @param idempotencyKey key of the request, unique per actor
 * @param opTime         time of the operation; null for the current time of the clock. Sub-operations share the
 *                       time of their parent.
 * @param inputSummary   JSON describing the input, secrets masked ({@code op_process.input_summary}); may be null
 */
public record OperationRequest(
    String processName,
    int processVersion,
    Long processSeqId,
    Long parentSeqId,
    Long revertsSeqId,
    String reason,
    String idempotencyKey,
    Instant opTime,
    String inputSummary
) {
    /** Name of the operations created by the dataset commit API. */
    public static final String DATASET_COMMIT = "jabiz.dataset.commit";
    /** Name of the operations that revert other operations. */
    public static final String REVERT = "jabiz.revert";

    public OperationRequest {
        Objects.requireNonNull(processName, "processName must not be null");
    }

    /** A request without an input summary. */
    public OperationRequest(String processName, int processVersion, Long processSeqId, Long parentSeqId,
        Long revertsSeqId, String reason, String idempotencyKey, Instant opTime) {
        this(processName, processVersion, processSeqId, parentSeqId, revertsSeqId, reason, idempotencyKey, opTime,
            null);
    }

    public static OperationRequest named(String processName, int processVersion) {
        return new OperationRequest(processName, processVersion, null, null, null, null, null, null);
    }

    public OperationRequest withReason(String newReason) {
        return new OperationRequest(processName, processVersion, processSeqId, parentSeqId, revertsSeqId, newReason,
            idempotencyKey, opTime, inputSummary);
    }

    public OperationRequest withInputSummary(String summary) {
        return new OperationRequest(processName, processVersion, processSeqId, parentSeqId, revertsSeqId, reason,
            idempotencyKey, opTime, summary);
    }
}
