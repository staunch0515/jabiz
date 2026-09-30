package com.jabiz.runtime.report;

import com.jabiz.report.ReportColumn;
import com.jabiz.report.ReportDocument;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An issued report as {@code sys_report_run} keeps it (docs/design/19-reports.md section 5).
 *
 * @param templateSource the template's text when it was issued
 * @param permissions    the template's permissions when it was issued: reading the run needs them
 * @param scope          by dataset id, the issuer's values of the scopes that depend on the caller: reading the run
 *                       needs the same (docs/design/19-reports.md section 5.3)
 * @param params         the parameters the template was run with, a missing {@code timeSlice} recorded time filled
 *                       in with the issue time
 * @param asOf           the effective time asked for, which the page header names; null when none was
 * @param readAt         the effective time the template's temporal entities were read at
 * @param knownAt        the recorded time they were read as of
 * @param recomputable   whether the template reads temporal entities only, so that running it again at the same
 *                       point in time must give the same rows
 * @param rows           the rows, normalized; empty when only the summary was read
 */
public record ReportRun(UUID runId, String templateId, String templateVersion, String templateSource,
    List<String> permissions, Map<String, Map<String, String>> scope, String title, String company, String period, String language,
    Map<String, Object> params, List<ReportDocument.Parameter> parameters, Instant asOf, Instant readAt,
    Instant knownAt, boolean landscape, List<ReportColumn> columns, List<List<Object>> rows, int rowCount,
    String contentHash, boolean recomputable, String issuedBy, Instant issuedTime, long processSeqId,
    UUID supersededBy) {

    /** The report as it was issued: the page header of that time, the stored rows. */
    public ReportDocument document() {
        return new ReportDocument(templateId, templateVersion, title, company, period, parameters, issuedTime,
            asOf, knownAt, landscape, columns, rows);
    }
}
