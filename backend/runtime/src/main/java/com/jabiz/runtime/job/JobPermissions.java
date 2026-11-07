package com.jabiz.runtime.job;

/** Permissions of scheduled jobs (docs/design/11-ledger-events-jobs.md section 4). */
public final class JobPermissions {

    /** Lists the jobs and their runs ({@code GET /api/jobs}). */
    public static final String READ = "job.read";

    /**
     * Runs a past scheduled time again by hand ({@code POST /api/jobs/{job}/runs}, decision D77); protected by default
     * (decision D48): granting it needs another administrator's approval.
     */
    public static final String RUN = "job.run";

    private JobPermissions() {}
}
