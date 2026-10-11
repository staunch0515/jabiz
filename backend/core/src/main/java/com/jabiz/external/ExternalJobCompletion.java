package com.jabiz.external;

import java.time.Instant;
import java.util.Map;

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
 */
public record ExternalJobCompletion(String jobId, String jobType, String requestedBy, String reference,
    Object result, Map<String, Long> usage, int attempts, Instant finishedTime) {

    public ExternalJobCompletion {
        usage = usage == null ? Map.of() : Map.copyOf(usage);
    }

    /** The result as the job type's result record. */
    public <T> T result(Class<T> type) {
        return type.cast(result);
    }

    /** The job and its outcome only: the result may be personal. */
    @Override
    public String toString() {
        return "ExternalJobCompletion[jobId=" + jobId + ", jobType=" + jobType + ", attempts=" + attempts
            + ", usage=" + usage + "]";
    }
}
