package com.jabiz.runtime.report;

import com.jabiz.entity.SemanticKind;
import com.jabiz.entity.TemporalRole;
import com.jabiz.report.ReportColumn;
import com.jabiz.report.ReportDocument;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Report documents for the writer tests. */
final class ReportSamples {

    static final Instant RUN = Instant.parse("2026-02-05T14:05:09Z");
    static final List<ReportColumn> COLUMNS = List.of(
        new ReportColumn("accountCode", "Account", new SemanticKind.Text(20, false)),
        new ReportColumn("accountName", "Account name", new SemanticKind.Text(100, false)),
        new ReportColumn("balance", "Balance", new SemanticKind.Monetary("USD", 2)),
        new ReportColumn("bookedAt", "Booked", new SemanticKind.Temporal(TemporalRole.EVENT_TIME)));

    private ReportSamples() {}

    static ReportDocument trialBalance(int extraRows, boolean landscape) {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("1000", "Cash", new BigDecimal("-5000.00"), Instant.parse("2026-02-03T15:00:00Z")));
        rows.add(Arrays.asList("2000", "Accounts payable", new BigDecimal("12345.5"), null));
        rows.add(List.of("6400", "=SUM(A1:A9) " + "very long name ".repeat(20), new BigDecimal("0.05"), RUN));
        for (int i = 0; i < extraRows; i++) {
            rows.add(List.of("9" + i, "Line " + i, new BigDecimal(i).movePointLeft(2), RUN));
        }
        return new ReportDocument("jabiz.ledger.account_balances",
            "c2741d77d343f5ede1c4130251ff5848eb9b3dabcfb1e1eb4f402d774be36e9a", "Trial balance", "Acme Inc.",
            "2026-02-01 00:00:00 – 2026-02-05 23:59:59",
            List.of(new ReportDocument.Parameter("asOf", "2026-02-05 23:59:59")), RUN,
            Instant.parse("2026-02-05T23:59:59Z"), Instant.parse("2026-02-05T23:59:59Z"), landscape, COLUMNS, rows);
    }
}
