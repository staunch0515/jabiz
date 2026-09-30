package com.jabiz.report;

import com.jabiz.entity.SemanticKind;
import com.jabiz.entity.TemporalRole;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** A small trial balance as the export tests use it. */
final class ReportTestData {

    static final Instant RUN = Instant.parse("2026-02-05T14:05:09Z");

    private ReportTestData() {}

    static ReportDocument trialBalance() {
        return new ReportDocument("jabiz.ledger.account_balances", "c2741d77d343f5ede1c4130251ff5848",
            "Trial balance", "Acme Inc.", "2026-02-01 – 2026-02-05",
            List.of(new ReportDocument.Parameter("asOf", "2026-02-05 23:59:59")), RUN, null, null, false,
            List.of(new ReportColumn("accountCode", "Account", new SemanticKind.Text(20, false)),
                new ReportColumn("balance", "Balance", new SemanticKind.Monetary("USD", 2)),
                new ReportColumn("bookedAt", null, new SemanticKind.Temporal(TemporalRole.EVENT_TIME))),
            List.of(
                List.of("1000", new BigDecimal("-5000.00"), Instant.parse("2026-02-03T15:00:00Z")),
                Arrays.asList("2000, payables", new BigDecimal("12345.5"), null),
                List.of("=HYPERLINK(\"x\")", new BigDecimal("0"), Instant.parse("2026-02-05T00:00:00Z")),
                List.of(Map.of("zh", "现金", "en", "Cash"), new BigDecimal("1.005"), RUN)));
    }
}
