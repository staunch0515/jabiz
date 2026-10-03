package com.jabiz.finance.it;

import com.jabiz.finance.ap.ApSettingsProcesses;
import com.jabiz.finance.ap.BillProcesses;
import com.jabiz.finance.ap.PaymentProcesses;
import com.jabiz.finance.ap.VendorBankProcesses;
import com.jabiz.finance.ar.InvoiceEntities;
import com.jabiz.finance.ar.InvoiceProcesses;
import com.jabiz.finance.ar.ReceiptProcesses;
import com.jabiz.finance.bank.BankAccountProcesses;
import com.jabiz.finance.bank.BankEntryProcesses;
import com.jabiz.finance.bank.MatchProcesses;
import com.jabiz.finance.bank.ReconciliationProcesses;
import com.jabiz.finance.calc.CloseChecks;
import com.jabiz.finance.calc.PeriodPolicy;
import com.jabiz.finance.close.CloseEntities;
import com.jabiz.finance.close.CloseProcesses;
import com.jabiz.finance.fa.AssetClassProcesses;
import com.jabiz.finance.fa.DepreciationProcesses;
import com.jabiz.finance.fx.FxRevaluationProcesses;
import com.jabiz.finance.fx.FxSettingsProcesses;
import com.jabiz.finance.gl.AccountTypes;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.finance.gl.JournalAutomation;
import com.jabiz.finance.gl.JournalEntities;
import com.jabiz.finance.gl.JournalProcesses;
import com.jabiz.finance.gl.PeriodProcesses;
import com.jabiz.finance.payroll.PayrollEntities;
import com.jabiz.finance.setup.FinanceRoles;
import com.jabiz.runtime.event.OutboxDeliverer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.jabiz.finance.it.JournalLifecycleIT.entry;
import static com.jabiz.finance.it.JournalLifecycleIT.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIN-SCN-06, the month-end close, steps 1 to 4, on all of January's books as FIN-EXP-02 has them: the depreciation
 * run posts DEP-2601 once, the revaluation posts FXR-2601 and its reversal, PAYROLL-2601 is imported and approved and
 * JE-0004 posted. The checklist then fails on the operating account not reconciled (and on unapplied cash, FIN-CT-005)
 * and passes once FIN-SCN-05's reconciliation is signed off and the receipt applied; the controller soft-closes and
 * closes January. The close artifact holds FIN-EXP-03 and the subledgers equal their control accounts; run as known
 * at the close, the trial balance is the artifact's, after February's postings too. The financial statements of
 * step 5 come with F9.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class FinScn06IT extends JanuaryBooks {

    @Test
    @SuppressWarnings("unchecked")
    void theJanuaryClose() throws IOException {
        people();
        books();
        receivables();
        payables();
        journals();

        // Step 1: DEP-2601; a second run posts nothing.
        assertThat(ok(DepreciationProcesses.RUN, accountant, Map.of("periodKey", "2026-01")))
            .containsEntry("runNo", "DEP-2601").containsEntry("posted", true);
        assertThat(ok(DepreciationProcesses.RUN, accountant, Map.of("periodKey", "2026-01")))
            .containsEntry("posted", false);
        // Step 2: FXR-2601 at the month's end, reversed on 1 February.
        assertThat(ok(FxRevaluationProcesses.REVALUE, accountant, Map.of("periodKey", "2026-01")))
            .containsEntry("runNo", "FXR-2601").containsEntry("reversalDate", "2026-02-01");
        assertThat(postingLines("FXR-2601")).isEqualTo(expectedDocuments("FXR-2601", "revaluation")
            .get("FXR-2601"));
        // Step 3: PAYROLL-2601 imported and approved; JE-0004 posted.
        payroll();
        String je4 = (String) ok(JournalProcesses.SAVE, accountant, entry("2026-01-31",
            "Estimated federal income tax provision", List.of(line("8000", "1362.90", null, null),
                line("2400", null, "1362.90", null)))).get("journalId");
        assertThat(ok(JournalProcesses.SUBMIT, accountant, Map.of("journalId", je4)))
            .containsEntry("journalNo", "JE-0004").containsEntry("status", "POSTED");
        savingsInterest();

        // Step 4: the checklist fails on the operating account not reconciled, and on RCPT-0003 not yet applied.
        assertThat(ok(CloseProcesses.START, accountant, Map.of("periodKey", "2026-01"))).containsEntry("created", 10);
        ok(CloseProcesses.CHECK, accountant, Map.of("periodKey", "2026-01"));
        Map<String, Map<String, Object>> tasks = tasks();
        assertThat(tasks.get(CloseChecks.BANK_RECONCILED)).containsEntry("status", "FAILED");
        assertThat((String) tasks.get(CloseChecks.BANK_RECONCILED).get("result"))
            .contains("OPERATING: No signed-off reconciliation of OPERATING (1010)");
        assertThat(tasks.get(CloseChecks.CLEARING_ZERO)).containsEntry("status", "FAILED");
        assertThat((String) tasks.get(CloseChecks.CLEARING_ZERO).get("result")).contains("1250", "Unapplied Cash");
        for (String check : List.of(CloseChecks.ENTRIES_POSTED, CloseChecks.SUBLEDGERS, CloseChecks.RECURRING_RUN,
            CloseChecks.AUTO_REVERSALS, CloseChecks.DEPRECIATION_RUN, CloseChecks.REVALUATION_RUN)) {
            assertThat(tasks.get(check)).as(check + ": " + tasks.get(check).get("result"))
                .containsEntry("status", "PASSED");
        }
        assertThat((String) tasks.get(CloseChecks.REVALUATION_RUN).get("result")).contains("FXR-2601");
        // The controller's close is refused and names the account (FIN-PC-004 acceptance 1).
        Map<String, Object> refusal = run(CloseProcesses.CLOSE, controller, Map.of("periodKey", "2026-01"))
            .expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody();
        assertThat(String.valueOf(refusal.get("violations"))).contains("BANK_RECONCILED", "OPERATING",
            "CLEARING_ZERO", "1250");
        assertThat(overview()).filteredOn(r -> "RECONCILIATION".equals(r.get("section")))
            .extracting(r -> r.get("code") + " " + r.get("status")).contains("OPERATING MISSING");

        // RCPT-0003 applied to INV-1003; FIN-SCN-05's reconciliation signed off.
        ok(ReceiptProcesses.APPLY, arClerk, Map.of("receiptId", rcpt3, "applicationDate", "2026-01-25",
            "applications", List.of(Map.of("invoiceId", invoice("INV-1003"), "amount", "20000.00"))));
        reconcile();
        Map<String, Object> checked = ok(CloseProcesses.CHECK, accountant, Map.of("periodKey", "2026-01"));
        tasks = tasks();
        for (String check : CloseChecks.ALL) {
            assertThat(tasks.get(check)).as(check + ": " + tasks.get(check).get("result"))
                .containsEntry("status", "PASSED");
            // FIN-PC-004 acceptance 2: each check's result, time and evidence.
            assertThat(tasks.get(check).get("checkedAt")).as(check).isEqualTo("2026-01-31T09:00:00Z");
            assertThat((String) tasks.get(check).get("evidence")).as(check).startsWith("finance.");
        }
        assertThat((String) tasks.get(CloseChecks.SUBLEDGERS).get("result")).contains(
            "Receivables aging 158735.00 against the control accounts 158735.00",
            "Payables aging 46300.00 against the control accounts 46300.00",
            "Asset register cost 202000.00 against the control accounts 202000.00",
            "Asset register accumulated depreciation 62000.00 against the control accounts 62000.00");
        assertThat(checked).containsEntry("ready", false);

        // FIN-PC-009 acceptance 1: with 2 of 10 tasks open, the overview shows them first, with owners and due dates.
        List<Map<String, Object>> overview = overview();
        assertThat(overview.getFirst()).containsEntry("section", "PROGRESS").containsEntry("name", "8 of 10 done")
            .containsEntry("status", "IN_PROGRESS");
        assertThat(overview.subList(1, 3)).extracting(r -> r.get("section") + " " + r.get("code") + " "
            + r.get("status") + " " + r.get("owner") + " " + r.get("dueDate"))
            .containsExactlyInAnyOrder("TASK ACCRUALS OPEN fin.journal.prepare 2026-02-03",
                "TASK REVIEW OPEN fin.period.close " + tasks.get("REVIEW").get("dueDate"));
        assertThat(overview.subList(3, 11)).allSatisfy(r -> assertThat(r).containsEntry("section", "TASK")
            .containsEntry("status", "PASSED"));
        assertThat(overview).filteredOn(r -> "SUBLEDGER".equals(r.get("section")))
            .extracting(r -> r.get("code") + " " + r.get("status"))
            .containsExactly("GL OPEN", "AR OPEN", "AP OPEN", "BANK OPEN", "FA OPEN");
        assertThat(overview).filteredOn(r -> "RECONCILIATION".equals(r.get("section")))
            .extracting(r -> r.get("code") + " " + r.get("status")).contains("OPERATING SIGNED_OFF");
        Map<String, Map<String, Object>> checklist = tasks;
        assertThat(overview.subList(1, 3)).allSatisfy(r -> assertThat(r.get("taskId"))
            .isEqualTo(checklist.get((String) r.get("code")).get("taskId")));
        assertThat(report(CloseProcesses.OVERVIEW, controller, Map.of("periodKey", "2099-01"))).isEmpty();

        // The manual tasks; the controller soft-closes, then closes January.
        ok(CloseProcesses.TASK_COMPLETE, accountant, Map.of("taskId", tasks.get("ACCRUALS").get("taskId"),
            "note", "JE-0002 and JE-0003 agreed to the schedules"));
        ok(PeriodProcesses.SET_STATE, controller, Map.of("periodKey", "2026-01", "status", "SOFT_CLOSED"));
        ok(CloseProcesses.TASK_COMPLETE, controller, Map.of("taskId", tasks.get("REVIEW").get("taskId"),
            "note", "Trial balance reviewed"));
        clock.advance(Duration.ofHours(2));
        people();
        Map<String, Object> closed = ok(CloseProcesses.CLOSE, controller, Map.of("periodKey", "2026-01"));
        assertThat(closed).containsEntry("status", "CLOSED").containsEntry("seq", 1);
        assertThat(find(GlEntities.PERIOD_DATASET, "periodKey", "2026-01").getFirst())
            .containsEntry("status", "CLOSED").containsEntry("arStatus", "CLOSED").containsEntry("apStatus", "CLOSED")
            .containsEntry("bankStatus", "CLOSED").containsEntry("faStatus", "CLOSED");

        // FIN-PC-005 acceptance 1: the artifact holds FIN-EXP-03, the checklist, the controller and the time.
        Map<String, Object> artifact = read(CloseEntities.ARTIFACT_DATASET, closed.get("artifactId"));
        assertThat(artifact).containsEntry("closedBy", "controller").containsEntry("closedAt", "2026-01-31T11:00:00Z")
            .containsEntry("knownAt", "2026-01-31T11:00:00Z");
        assertThat(amount(artifact.get("totalDebit"))).isEqualByComparingTo("826012.90");
        assertThat(amount(artifact.get("totalCredit"))).isEqualByComparingTo("826012.90");
        List<Map<String, Object>> lines = find(CloseEntities.ARTIFACT_LINE_DATASET, "artifactId",
            closed.get("artifactId"));
        Map<String, String> trialBalance = new TreeMap<>();
        lines.stream().filter(l -> "TRIAL_BALANCE".equals(l.get("section"))).forEach(l -> trialBalance.put(
            (String) l.get("code"), amount(l.get("debit")).toPlainString() + " " + amount(l.get("credit"))
                .toPlainString()));
        Map<String, String> expected = new TreeMap<>();
        for (String[] row : expectedRows("FIN-EXP-03")) {
            if (row[0].matches("\\d{4}")) {
                expected.put(row[0], (row[2].isBlank() ? "0.00" : money(row[2]).toPlainString()) + " "
                    + (row[3].isBlank() ? "0.00" : money(row[3]).toPlainString()));
            }
        }
        assertThat(trialBalance).isEqualTo(expected);
        assertThat(lines).filteredOn(l -> "SUBLEDGER".equals(l.get("section"))).hasSize(4)
            .allSatisfy(l -> assertThat(l).containsEntry("status", "PASSED"));
        assertThat(lines).filteredOn(l -> "CHECKLIST".equals(l.get("section"))).hasSize(10)
            .allSatisfy(l -> assertThat(l.get("status")).isIn("PASSED", "DONE"));
        // The issued trial balance is in the archive.
        String issued = new String(get("/api/reports/runs/" + closed.get("reportRunId") + "/export?format=csv",
            as("auditor", "report.archive.read", "ledger.read")).expectStatus().isOk().expectBody(byte[].class)
            .returnResult().getResponseBody(), StandardCharsets.UTF_8);
        assertThat(issued).contains("211555.00", "158735.00", "1362.90");

        // FIN-PC-005 acceptance 2: February moves on; January as known at the close is the artifact's.
        clock.advance(Duration.ofDays(5));
        people();
        String february = (String) ok(JournalProcesses.SAVE, accountant, entry("2026-02-05", "February utilities",
            List.of(line("6300", "3700.00", null, null), line("2100", null, "3700.00", null)))).get("journalId");
        ok(JournalProcesses.SUBMIT, accountant, Map.of("journalId", february));
        List<Map<String, Object>> asClosed = report(CloseProcesses.TRIAL_BALANCE, controller,
            Map.of("through", "2026-01-31", "adjustments", false, "knownAt", artifact.get("knownAt")));
        assertThat(CloseProcesses.trialBalanceHash(asClosed)).isEqualTo(artifact.get("trialBalanceHash"));
        // January takes nothing more.
        String late = (String) ok(JournalProcesses.SAVE, accountant, entry("2026-01-20", "Late utilities",
            List.of(line("6300", "10.00", null, null), line("2100", null, "10.00", null)))).get("journalId");
        assertThat(refused(JournalProcesses.SUBMIT, accountant, Map.of("journalId", late), 422))
            .isEqualTo(PeriodPolicy.PERIOD_CLOSED);
        assertOnlyInserted("fi_close_task_version", "fi_close_artifact_version", "fi_close_artifact_line_version");
    }

    /** January's close overview, in its order (FIN-PC-009). */
    private List<Map<String, Object>> overview() {
        return report(CloseProcesses.OVERVIEW, controller, Map.of("periodKey", "2026-01"));
    }

    private Map<String, Map<String, Object>> tasks() {
        return find(CloseEntities.TASK_DATASET, "periodKey", "2026-01").stream()
            .collect(Collectors.toMap(t -> (String) t.get("taskCode"), Function.identity()));
    }
}
