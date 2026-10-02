package com.jabiz.finance.it;

import com.jabiz.finance.gl.GlEntities;
import com.jabiz.finance.gl.JournalEntities;
import com.jabiz.finance.gl.JournalProcesses;
import com.jabiz.finance.gl.OpeningProcesses;
import com.jabiz.finance.gl.PeriodProcesses;
import com.jabiz.finance.migration.MigrationEntities;
import com.jabiz.finance.migration.MigrationProcesses;
import com.jabiz.finance.setup.FinanceRoles;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The opening of the books (FIN-PC-002, FIN-DI-002, FIN-DI-003): an unbalanced opening file is refused with the
 * difference and posts nothing; a legacy account code is read through a recorded decision; the sample company's
 * opening file then makes the trial balance at 2025-12-31 equal FIN-EXP-01, read from the expected results; the
 * migration report shows every account at 0.00 difference and the decision with who made it; the opening is made
 * once, its period takes no other entry, and once closed it stays closed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class OpeningIT extends FinanceItSupport {

    private static final String OPENING = "finance.opening_balances";

    @Test
    @SuppressWarnings("unchecked")
    void theSampleCompanyIsOpenedAsItsOpeningFileSays() throws IOException {
        loadSampleChart();
        ok("FIN_FISCAL_YEAR_CREATE", controller(), Map.of("fiscalYear", 2026, "adjustmentPeriod", true));
        String controller = inRoles("migrating-controller", FinanceRoles.CONTROLLER);
        String sampleFile = sampleText("opening-balances.csv");

        // FIN-PC-002 acceptance 2: debits and credits differ, nothing is posted and the difference is shown.
        String unbalanced = sampleFile.replace("2025-12-31,2400,,8000.00", "2025-12-31,2400,,8000.50");
        Map<String, Object> refused = importCsv(OPENING, controller, unbalanced, "commit", null, null, 422);
        assertThat(issues(refused)).contains("0:FIN_JOURNAL_UNBALANCED");
        assertThat(((List<Map<String, Object>>) refused.get("issues")).getFirst().get("message").toString())
            .contains("0.50");
        assertThat(find(GlEntities.PERIOD_DATASET, "periodKey", "2026-00")).isEmpty();

        // A legacy code the chart does not know is refused until a decision says how to read it (FIN-DI-003).
        String legacy = sampleFile.replace("2025-12-31,1200,", "2025-12-31,1199,");
        assertThat(issues(importCsv(OPENING, controller, legacy, "preview", null, null, 200)))
            .containsExactly("1:FIN_JOURNAL_ACCOUNT_UNKNOWN");
        assertThat(refused(MigrationProcesses.DECIDE, controller, Map.of("kind", "ACCOUNT", "legacyValue", "1210",
            "decidedValue", "1200", "reason", "x"), 422)).isEqualTo(MigrationProcesses.LEGACY_IS_ACCOUNT);
        ok(MigrationProcesses.DECIDE, controller, Map.of("kind", "ACCOUNT", "legacyValue", "1199",
            "decidedValue", "1200", "reason", "The legacy ledger kept trade receivables under 1199"));
        Map<String, Object> mapped = importCsv(OPENING, controller, legacy, "preview", null, null, 200);
        assertThat(mapped).containsEntry("accepted", true);
        // A preview posts nothing.
        assertThat(find(GlEntities.PERIOD_DATASET, "periodKey", "2026-00")).isEmpty();

        // Without the migration permission the opening is not posted.
        importCsv(OPENING, as("importer", "fin.import"), sampleFile, "preview", null, null, 403);

        // The sample company's opening file as it is.
        Map<String, Object> committed = importCsv(OPENING, controller, sampleFile, "commit", null, null, 200);
        assertThat(committed).containsEntry("committed", true).containsEntry("units", 1);
        Map<String, Object> totals = (Map<String, Object>) committed.get("totals");
        assertThat(amount(totals.get("debit"))).isEqualByComparingTo("638500.00");
        assertThat(amount(totals.get("credit"))).isEqualByComparingTo("638500.00");

        Map<String, Object> period = find(GlEntities.PERIOD_DATASET, "periodKey", "2026-00").getFirst();
        assertThat(period).containsEntry("opening", true).containsEntry("startDate", "2025-12-31")
            .containsEntry("endDate", "2025-12-31").containsEntry("status", "OPEN");
        Map<String, Object> journal = find(JournalEntities.JOURNAL_DATASET, "journalNo", "OPENING-2026").getFirst();
        assertThat(journal).containsEntry("source", "OPENING").containsEntry("status", "POSTED")
            .containsEntry("postingDate", "2025-12-31").containsEntry("periodKey", "2026-00")
            .containsEntry("glNo", "GJ-OPN-2026-000001");
        assertThat(find(JournalEntities.POSTING_DATASET, "glNo", "GJ-OPN-2026-000001")).singleElement()
            .satisfies(posting -> assertThat(posting).containsEntry("source", "OPN").containsEntry("periodNo", 0));

        // FIN-PC-002 acceptance 1 (general ledger): the trial balance at 2025-12-31 is FIN-EXP-01.
        Map<String, BigDecimal[]> expected = expectedOpeningTrialBalance();
        assertThat(expected).hasSize(16);
        List<Map<String, Object>> trialBalance = report("finance.gl.trial_balance", as("reader", "ledger.read"),
            Map.of("through", "2025-12-31"));
        Map<String, BigDecimal[]> actual = new LinkedHashMap<>();
        for (Map<String, Object> row : trialBalance) {
            if (amount(row.get("balance")).signum() != 0) {
                actual.put((String) row.get("accountCode"), new BigDecimal[] {amount(row.get("debit")),
                    amount(row.get("credit"))});
            }
        }
        assertThat(actual.keySet()).containsExactlyInAnyOrderElementsOf(expected.keySet());
        expected.forEach((code, sides) -> {
            assertThat(actual.get(code)[0]).as(code + " debit").isEqualByComparingTo(sides[0]);
            assertThat(actual.get(code)[1]).as(code + " credit").isEqualByComparingTo(sides[1]);
        });

        // FIN-DI-002: every control total matches the source with 0.00 difference; the decision is listed.
        List<Map<String, Object>> reconciliation = report("finance.migration.reconciliation",
            as("auditor", "fin.journal.read", "ledger.read"), Map.of());
        List<Map<String, Object>> accounts = reconciliation.stream()
            .filter(r -> "ACCOUNT".equals(r.get("section"))).toList();
        assertThat(accounts).hasSize(16).allSatisfy(r -> assertThat(amount(r.get("difference"))).isZero());
        assertThat(reconciliation.stream().filter(r -> "TOTAL".equals(r.get("section"))))
            .hasSize(2).allSatisfy(r -> {
                assertThat(amount(r.get("sourceAmount"))).isEqualByComparingTo("638500.00");
                assertThat(amount(r.get("ledgerAmount"))).isEqualByComparingTo("638500.00");
            });
        assertThat(reconciliation.stream().filter(r -> "DECISION".equals(r.get("section")))).singleElement()
            .satisfies(r -> assertThat(r).containsEntry("item", "ACCOUNT 1199 -> 1200")
                .containsEntry("decidedBy", "migrating-controller"));

        // Opened once: another opening file is refused.
        Map<String, Object> again = importCsv(OPENING, controller, sampleFile + "2025-12-31,1300,1.00,\n"
            + "2025-12-31,2100,,1.00\n", "commit", null, null, 422);
        assertThat(issues(again)).contains("1:" + OpeningProcesses.EXISTS);

        // No other entry finds the opening period, not even on its day.
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("postingDate", "2025-12-31");
        entry.put("description", "Late accrual");
        entry.put("lines", List.of(Map.of("accountCode", "6400", "debit", "100.00"),
            Map.of("accountCode", "2100", "credit", "100.00")));
        String accountant = as("accountant", "fin.journal.prepare", "fin.journal.read");
        String draft = (String) ok(JournalProcesses.SAVE, accountant, entry).get("journalId");
        assertThat(refused(JournalProcesses.SUBMIT, accountant, Map.of("journalId", draft), 422))
            .isEqualTo(JournalProcesses.NO_PERIOD);

        // The opening period changes only through the migration; no year starts before it.
        assertThat(refused(PeriodProcesses.SET_STATE, controller(), Map.of("periodKey", "2026-00",
            "status", "CLOSED"), 422)).isEqualTo(PeriodProcesses.OPENING_PERIOD);
        assertThat(refused(PeriodProcesses.FISCAL_YEAR_CREATE, controller(), Map.of("fiscalYear", 2025), 422))
            .isEqualTo(PeriodProcesses.BEFORE_OPENING);

        assertThat(ok(OpeningProcesses.CLOSE_OPENING, controller, Map.of())).containsEntry("status", "CLOSED")
            .containsEntry("changed", true);
        assertThat(ok(OpeningProcesses.CLOSE_OPENING, controller, Map.of())).containsEntry("changed", false);
        assertThat(find(GlEntities.PERIOD_DATASET, "periodKey", "2026-00").getFirst())
            .containsEntry("status", "CLOSED").containsEntry("arStatus", "CLOSED");

        assertOnlyInserted("fi_period_version", "fi_journal_version", "fi_journal_line_version",
            "fi_posting_version", "fi_migration_decision_version");
        assertThat(find(MigrationEntities.DECISION_DATASET, "legacyValue", "1199")).singleElement()
            .satisfies(decision -> assertThat(decision).containsEntry("decidedValue", "1200")
                .containsEntry("decidedBy", "migrating-controller"));
    }
}
