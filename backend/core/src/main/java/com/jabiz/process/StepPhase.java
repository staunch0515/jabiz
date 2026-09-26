package com.jabiz.process;

/** When a step runs relative to the transaction of its process (docs/design/06-process.md section 4). */
public enum StepPhase {
    /** Inside the transaction; the default. A failure rolls the whole process back. */
    IN_TX,
    /**
     * After the transaction of the root process has committed. A failure no longer affects the committed data: the
     * platform records it and retries according to the step's {@link RetryPolicy}.
     */
    AFTER_COMMIT
}
