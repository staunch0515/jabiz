package com.jabiz.runtime.imports;

import com.jabiz.imports.ImportIssue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * What an import did or would do (docs/design/20-imports.md section 5): each row's outcome, every problem, the
 * control totals. A preview's report describes what a commit of the same file would do at that moment.
 *
 * @param records    records read from the file (blank ones left out)
 * @param rows       rows that would be (or were) imported: read, and not left out as duplicates
 * @param processed  process calls that succeeded (rows, or groups of rows)
 * @param units      process calls made or planned
 * @param duplicates rows left out because their reference was imported before or appears earlier in the file
 * @param columns    import field to the file column it was read from
 * @param constants  import field to the constant used for every row
 * @param totals     control totals over the imported rows
 * @param results    each record's outcome, in file order
 * @param issues     every problem found; the import can be committed only when there is none
 */
public record ImportReport(String importId, int importVersion, String fileId, String sha256, boolean committed,
    int records, int rows, int processed, int units, int duplicates, Map<String, String> columns,
    Map<String, String> constants, Map<String, BigDecimal> totals, List<RowResult> results, List<ImportIssue> issues) {

    public static final String OK = "ok";
    public static final String DUPLICATE = "duplicate";
    public static final String ERROR = "error";

    /**
     * @param status {@link #OK}, {@link #DUPLICATE} or {@link #ERROR}
     * @param values the row's values by import field: as read, or the cell's text where it could not be read
     */
    public record RowResult(int number, String location, String status, Map<String, Object> values) {}

    public boolean accepted() {
        return issues.isEmpty();
    }
}
