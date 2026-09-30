package com.jabiz.runtime.report;

/**
 * The fixed texts of an exported report's page header and footer, in the report's language
 * (docs/design/19-reports.md section 4).
 *
 * @param period  label of the period
 * @param runAt   label of the run time
 * @param knownAt label of the recorded time the data was read as of
 * @param version label of the template version
 * @param page    the page number, with {@code {page}} and {@code {pages}} placeholders
 */
public record ReportLabels(String period, String runAt, String knownAt, String version, String page) {

    /** English, for callers without a message catalog. */
    public static final ReportLabels ENGLISH = new ReportLabels("Period", "Run at", "As known on", "Version",
        "Page {page} of {pages}");

    String page(int page, int pages) {
        return this.page.replace("{page}", Integer.toString(page)).replace("{pages}", Integer.toString(pages));
    }
}
