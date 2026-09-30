package com.jabiz.query.custom;

/**
 * Header {@code report} of a SQL template (docs/design/19-reports.md section 3.1): the template is a report, listed on
 * the back office's reports page and exported with a page header.
 *
 * @param periodFrom parameter giving the start of the report's period, or null
 * @param periodTo   parameter giving the end of the report's period, or null
 * @param landscape  whether the PDF export uses landscape pages
 */
public record ReportSpec(String periodFrom, String periodTo, boolean landscape) {

    /** A report with no period, in portrait. */
    public static final ReportSpec PLAIN = new ReportSpec(null, null, false);
}
