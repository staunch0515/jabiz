package com.jabiz.external;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The input of a job type's completion process (decision D38 item 3): the platform runs it once, as the system, when
 * the job has its result. The process must be {@code internal()}, and should refuse other callers, since only the
 * platform knows the result is real.
 *
 * @param requestedBy  who ran the process that started the job (to notify)
 * @param reference    what the starting process said the job is about, or null
 * @param result       the result, an instance of the job type's result record ({@link #result(Class)})
 * @param usage        the usage of all attempts, by metric
 * @param attempts     how many attempts the job took
 * @param finishedTime when the result was recorded
 * @param files        the ids of the files the successful attempt produced, by their key (decision D50 item 3); stored
 *                     as uploads of the requester and referred to by nothing yet: a file the process does not put in
 *                     a field is swept as an orphan ({@code FILE_SWEEP})
 * @param unrecordedAttempts how many of the earlier attempts ended without recording what they consumed (decision D52
 *                     item 5): {@code LEASE_EXPIRED} or {@code TIMED_OUT} without usage; {@link #usage} does not
 *                     include them
 */
public record ExternalJobCompletion(String jobId, String jobType, String requestedBy, String reference,
    Object result, Map<String, Long> usage, int attempts, Instant finishedTime, Map<String, UUID> files,
    int unrecordedAttempts) {

    public ExternalJobCompletion {
        usage = usage == null ? Map.of() : Map.copyOf(usage);
        files = files == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(files));
        if (unrecordedAttempts < 0) {
            throw new IllegalArgumentException("unrecordedAttempts must not be negative");
        }
    }

    /** A completion without the count of unrecorded attempts, as decision D50 had it: none. */
    public ExternalJobCompletion(String jobId, String jobType, String requestedBy, String reference, Object result,
        Map<String, Long> usage, int attempts, Instant finishedTime, Map<String, UUID> files) {
        this(jobId, jobType, requestedBy, reference, result, usage, attempts, finishedTime, files, 0);
    }

    /** A completion without files, as decision D38 had it. */
    public ExternalJobCompletion(String jobId, String jobType, String requestedBy, String reference, Object result,
        Map<String, Long> usage, int attempts, Instant finishedTime) {
        this(jobId, jobType, requestedBy, reference, result, usage, attempts, finishedTime, Map.of(), 0);
    }

    /** Whether an attempt ended without recording its usage, so that {@link #usage} may be less than was billed. */
    public boolean hasUnrecordedAttempts() {
        return unrecordedAttempts > 0;
    }

    /** The result as the job type's result record. */
    public <T> T result(Class<T> type) {
        return type.cast(result);
    }

    /**
     * The id of the file the handler returned under {@code key}.
     *
     * @throws IllegalArgumentException when it returned none
     */
    public UUID file(String key) {
        UUID id = files.get(key);
        if (id == null) {
            throw new IllegalArgumentException("External job " + jobId + " produced no file " + key);
        }
        return id;
    }

    /** The job and its outcome only: the result may be personal. */
    @Override
    public String toString() {
        return "ExternalJobCompletion[jobId=" + jobId + ", jobType=" + jobType + ", attempts=" + attempts
            + ", unrecordedAttempts=" + unrecordedAttempts + ", usage=" + usage + ", files=" + files.keySet() + "]";
    }
}
