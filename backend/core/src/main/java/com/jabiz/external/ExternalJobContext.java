package com.jabiz.external;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What an {@link ExternalJobHandler} may use during one attempt (decision D38 item 3). */
public interface ExternalJobContext {

    /** The job's id. */
    String jobId();

    /** This attempt's number, from 1. */
    int attempt();

    /** {@code jabiz-job-<jobId>}: the same for every attempt of the job, for providers that deduplicate by it. */
    String idempotencyKey();

    /** When the attempt times out; the handler should not start work it cannot finish by then. */
    Instant deadline();

    /**
     * The client of one of the job type's declared services.
     *
     * @throws IllegalArgumentException when the job type does not declare the service
     */
    ExternalHttp http(String service);

    /**
     * A secret of one of the job type's declared services; never put it in the result, a usage name or an exception.
     *
     * @throws IllegalArgumentException when the job type does not declare the service, or the service the secret
     */
    String secret(String service, String name);

    /**
     * Records usage the attempt consumed, such as tokens of a model, as it goes: kept even when the attempt then
     * fails, and added to what the result reports.
     *
     * @param metric   {@value ExternalJobResult#USAGE_NAME_PATTERN}
     * @param quantity zero or more
     */
    void recordUsage(String metric, long quantity);

    /**
     * The files the job reads (decision D50 item 2), in the order the starting process gave them; empty when it reads
     * none. They are readable during this attempt only.
     */
    default List<ExternalJobFile> files() {
        return List.of();
    }

    /**
     * One of the files the job reads.
     *
     * @throws IllegalArgumentException when the job was not given that file: a handler reads its own job's files
     *                                  and nothing else
     */
    default ExternalJobFile file(UUID fileId) {
        return files().stream().filter(file -> file.fileId().equals(fileId)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("External job " + jobId() + " was not given file "
                + fileId));
    }
}
