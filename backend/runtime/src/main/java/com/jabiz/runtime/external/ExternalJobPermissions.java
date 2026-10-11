package com.jabiz.runtime.external;

/** Permission codes of asynchronous external jobs (docs/design/11-ledger-events-jobs.md section 4a; decision D38). */
public final class ExternalJobPermissions {

    /** See any job's status, and the jobs, attempts and usage of the platform entities and the usage report. */
    public static final String READ = "external-job.read";
    /** Cancel anyone's job; the requester cancels their own without it. */
    public static final String CANCEL = "external-job.cancel";
    /** The declared write permission of the read-only datasets; nothing writes through them. */
    public static final String WRITE = "external-job.write";
    /** Run {@code EXTERNAL_JOB_COMPLETE}; held by no one: only the platform's event delivery (the system) runs it. */
    public static final String COMPLETE = "external-job.complete";

    private ExternalJobPermissions() {}
}
