package com.jabiz.finance.it;

import com.jabiz.finance.gl.JournalEntities;
import com.jabiz.finance.gl.JournalProcesses;
import com.jabiz.finance.migration.MigrationProcesses;
import com.jabiz.finance.setup.FinanceRoles;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What keeps the opening of the books to the migration (FIN-PC-002, FIN-DI-003): a legacy code that has become an
 * account of the chart is read as itself, not as decided earlier; the opening entry is not reversed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class OpeningGuardsIT extends FinanceItSupport {

    @Test
    void aCodeOfTheChartIsReadAsItselfAndTheOpeningIsNotReversed() {
        loadSampleChart();
        ok("FIN_FISCAL_YEAR_CREATE", controller(), Map.of("fiscalYear", 2026, "adjustmentPeriod", true));
        String controller = inRoles("migrating-controller", FinanceRoles.CONTROLLER);
        ok(MigrationProcesses.DECIDE, controller, Map.of("kind", "ACCOUNT", "legacyValue", "1199",
            "decidedValue", "1200", "reason", "Trade receivables in the legacy ledger"));
        // The account is opened afterwards: from now on 1199 means it.
        ok("FIN_ACCOUNT_CREATE", controller(), Map.of("accountCode", "1199", "accountName", "Other receivables",
            "financialType", "ASSET", "normalBalance", "DEBIT", "statementLine", "Other receivables"));
        importCsv("finance.opening_balances", controller, """
            date,account,debit,credit
            2025-12-31,1199,500.00,
            2025-12-31,3000,,500.00
            """, "commit", null, null, 200);
        Map<String, Object> journal = find(JournalEntities.JOURNAL_DATASET, "journalNo", "OPENING-2026").getFirst();
        assertThat(find(JournalEntities.LINE_DATASET, "journalId", journal.get("journalId")))
            .extracting(line -> line.get("accountCode")).containsExactlyInAnyOrder("1199", "3000");

        Map<String, Object> reversal = new java.util.LinkedHashMap<>();
        reversal.put("journalId", journal.get("journalId"));
        reversal.put("postingDate", "2026-01-15");
        assertThat(refused(JournalProcesses.REVERSE, as("accountant", "fin.journal.prepare", "fin.journal.read"),
            reversal, 422)).isEqualTo(JournalProcesses.OPENING_NOT_REVERSED);
    }
}
