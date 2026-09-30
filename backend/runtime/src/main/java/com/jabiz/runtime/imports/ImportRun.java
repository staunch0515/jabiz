package com.jabiz.runtime.imports;

import com.jabiz.imports.ImportIssue;
import com.jabiz.imports.ImportMapping;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A committed import, or a rejected attempt (docs/design/20-imports.md section 5), as kept in {@code sys_import_run}.
 *
 * @param outcome    {@link #COMMITTED} or {@link #REJECTED}
 * @param issues     the problems found, at most {@link #MAX_ISSUES} (of {@code issueCount})
 * @param notes      what the person committing said about the data (decisions on data quality); may be null
 */
public record ImportRun(UUID runId, String importId, int importVersion, String outcome, UUID fileId, String sha256,
    ImportMapping mapping, Map<String, Object> params, int recordCount, int rowCount, int unitCount,
    int processedCount, int duplicateCount, int issueCount, Map<String, String> columns,
    Map<String, BigDecimal> totals, List<ImportIssue> issues, String notes, String importedBy, Instant importedTime,
    long processSeqId) {

    public static final String COMMITTED = "committed";
    public static final String REJECTED = "rejected";
    /** Problems kept with a run; the rest are counted. */
    public static final int MAX_ISSUES = 1000;

    public ImportRun {
        params = params == null ? Map.of() : params;
        columns = columns == null ? Map.of() : Map.copyOf(columns);
        totals = totals == null ? Map.of() : totals;
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
