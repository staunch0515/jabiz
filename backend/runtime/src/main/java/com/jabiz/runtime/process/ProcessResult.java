package com.jabiz.runtime.process;

/**
 * Outcome of a top-level execution.
 *
 * @param processSeqId the operation that produced the output
 * @param replayed     true when the output is that of an earlier execution with the same idempotency key
 */
public record ProcessResult<O>(long processSeqId, O output, boolean replayed) {}
