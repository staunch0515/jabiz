package com.jabiz.external;

import java.time.Instant;
import java.util.Map;

/**
 * The input of a job type's failure process (decision D38 item 3): the platform runs it once, as the system, when the
 * job failed for good (a permanent failure, or its attempts used up). Not for cancelled jobs, which nobody waits for.
 *
 * @param requestedBy  who ran the process that started the job (to notify)
 * @param reference    what the starting process said the job is about, or null
 * @param usage        the usage of all attempts, by metric
 * @param attempts     how many attempts were made
 * @param finishedTime when the failure was recorded
 * @param errorCode    the last attempt's code: the handler's own ({@link ExternalJobException}), or the platform's
 *                     ({@code TIMEOUT}, {@code LEASE_EXPIRED}, {@code EXTERNAL_CALL_FAILED}, {@code HANDLER_ERROR},
 *                     {@code RESULT_TOO_LARGE})
 * @param errorMessage the last attempt's message, as safe to show as the handler made it
 * @param unrecordedAttempts how many of the attempts ended without recording what they consumed (decision D52 item 5):
 *                     {@code LEASE_EXPIRED} or {@code TIMED_OUT} without usage; {@link #usage} does not include them
 */
public record ExternalJobFailure(String jobId, String jobType, String requestedBy, String reference,
    Map<String, Long> usage, int attempts, Instant finishedTime, String errorCode, String errorMessage,
    int unrecordedAttempts) {

    public ExternalJobFailure {
        usage = usage == null ? Map.of() : Map.copyOf(usage);
        if (unrecordedAttempts < 0) {
            throw new IllegalArgumentException("unrecordedAttempts must not be negative");
        }
    }

    /** A failure without the count of unrecorded attempts, as decision D38 had it: none. */
    public ExternalJobFailure(String jobId, String jobType, String requestedBy, String reference,
        Map<String, Long> usage, int attempts, Instant finishedTime, String errorCode, String errorMessage) {
        this(jobId, jobType, requestedBy, reference, usage, attempts, finishedTime, errorCode, errorMessage, 0);
    }

    /** Whether an attempt ended without recording its usage, so that {@link #usage} may be less than was billed. */
    public boolean hasUnrecordedAttempts() {
        return unrecordedAttempts > 0;
    }

    /** The job and the code only: the message is the handler's, and may repeat what it was given. */
    @Override
    public String toString() {
        return "ExternalJobFailure[jobId=" + jobId + ", jobType=" + jobType + ", attempts=" + attempts
            + ", unrecordedAttempts=" + unrecordedAttempts + ", errorCode=" + errorCode + ", errorMessage="
            + (errorMessage == null ? "null" : "*** (" + errorMessage.length() + " characters)") + "]";
    }
}
