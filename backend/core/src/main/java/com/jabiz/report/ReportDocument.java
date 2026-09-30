package com.jabiz.report;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A report ready to be written in any export format (docs/design/19-reports.md section 4): what the page header
 * shows and the rows. The writers only lay it out; everything shown is decided when the document is made, so the
 * same document always gives the same file.
 *
 * @param templateId      the SQL template
 * @param templateVersion its version (19 section 2.3)
 * @param title           the report's title in the report's language
 * @param company         who issues it
 * @param period          the period, already formatted, or null
 * @param parameters      the given parameters as label and formatted value, in declaration order
 * @param runTime         when it was run; also the creation time of files that carry one
 * @param knownAt         the recorded time it was read as of; the run time when no earlier one was asked for
 * @param landscape       whether PDF pages are landscape
 * @param columns         the columns, in result order
 * @param rows            the rows; each has one value per column (null for none)
 */
public record ReportDocument(String templateId, String templateVersion, String title, String company, String period,
    List<Parameter> parameters, Instant runTime, Instant knownAt, boolean landscape, List<ReportColumn> columns,
    List<List<Object>> rows) {

    /** A parameter as shown in the header. */
    public record Parameter(String label, String value) {}

    public ReportDocument {
        Objects.requireNonNull(templateId, "templateId must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(runTime, "runTime must not be null");
        parameters = List.copyOf(parameters);
        columns = List.copyOf(columns);
        List<List<Object>> copied = new ArrayList<>(rows.size());
        for (List<Object> row : rows) {
            if (row.size() != columns.size()) {
                throw new IllegalArgumentException("A row has " + row.size() + " values for " + columns.size()
                    + " columns");
            }
            // Values may be null, which List.copyOf refuses.
            copied.add(Collections.unmodifiableList(new ArrayList<>(row)));
        }
        rows = Collections.unmodifiableList(copied);
        knownAt = knownAt == null ? runTime : knownAt;
        company = company == null ? "" : company;
    }
}
