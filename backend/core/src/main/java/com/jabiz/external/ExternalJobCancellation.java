package com.jabiz.external;

import java.time.Instant;
import java.util.Map;

/**
 * The input of a job type's cancellation process ({@code ExternalJobType…onCancelled}, decision D52 item 2): the
 * platform runs it once, as the system, when a cancelled job has ended: at once when no attempt was running, otherwise
 * once the running attempt is recorded (its result dropped). Cancellations by the requester, by a process
 * ({@code CancelExternalJob}) and by an operator ({@code POST /api/external-jobs/{id}/cancel}) alike.
 *
 * @param requestedBy   who ran the process that started the job
 * @param reference     what the starting process said the job is about, or null
 * @param usage         the usage of all attempts, by metric, the cancelled one included: what the provider may bill
 * @param attempts      how many attempts were recorded
 * @param finishedTime  when the job's outcome {@code CANCELLED} was recorded
 * @param cancelledBy   who cancelled it: the actor of the process or the request that did
 * @param cancelledTime when it was cancelled
 * @param attemptRan    whether any attempt was started (claimed) before the job ended; false for a job cancelled while
 *                      it waited, which cost nothing
 * @param unrecordedAttempts how many of the attempts ended without recording what they consumed (decision D52 item 5):
 *                      its instance died ({@code LEASE_EXPIRED}) or it ran out of time ({@code TIMED_OUT}) before the
 *                      handler reported any usage. The provider may still bill them; {@link #usage} does not include
 *                      them, so an application that charges by usage charges these by its own estimate
 */
public record ExternalJobCancellation(String jobId, String jobType, String requestedBy, String reference,
    Map<String, Long> usage, int attempts, Instant finishedTime, String cancelledBy, Instant cancelledTime,
    boolean attemptRan, int unrecordedAttempts) {

    public ExternalJobCancellation {
        usage = usage == null ? Map.of() : Map.copyOf(usage);
        if (unrecordedAttempts < 0) {
            throw new IllegalArgumentException("unrecordedAttempts must not be negative");
        }
    }

    /** A cancellation without the count of unrecorded attempts, as decision D52 item 2 had it: none. */
    public ExternalJobCancellation(String jobId, String jobType, String requestedBy, String reference,
        Map<String, Long> usage, int attempts, Instant finishedTime, String cancelledBy, Instant cancelledTime,
        boolean attemptRan) {
        this(jobId, jobType, requestedBy, reference, usage, attempts, finishedTime, cancelledBy, cancelledTime,
            attemptRan, 0);
    }

    /** Whether an attempt ended without recording its usage, so that {@link #usage} may be less than was billed. */
    public boolean hasUnrecordedAttempts() {
        return unrecordedAttempts > 0;
    }

    /** The total of one metric over all attempts; zero when none was recorded. */
    public long usage(String metric) {
        return usage.getOrDefault(metric, 0L);
    }
}
