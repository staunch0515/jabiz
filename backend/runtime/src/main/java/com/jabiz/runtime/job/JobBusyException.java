package com.jabiz.runtime.job;

import com.jabiz.runtime.ConcurrentUpdateException;

/**
 * 409 {@code JOB_BUSY}: a run of the job holds its cluster lock (another instance's, a scheduled one or a run by hand),
 * so it cannot be run by hand now (decision D77). Nothing was recorded; the request may be made again.
 */
public class JobBusyException extends ConcurrentUpdateException {

    private final String job;

    public JobBusyException(String job) {
        super("Job " + job + " is running");
        this.job = job;
    }

    public String job() {
        return job;
    }
}
