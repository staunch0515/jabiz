package com.jabiz.runtime.report;

/** Permission codes of issued reports (docs/design/19-reports.md section 5). */
public final class ReportPermissions {

    /** Run {@code REPORT_ISSUE}; issuing also needs the template's own permissions. */
    public static final String ISSUE = "report.issue";
    /** Read issued reports, reproduce and verify them; each also needs its template's permissions. */
    public static final String ARCHIVE_READ = "report.archive.read";

    private ReportPermissions() {}
}
