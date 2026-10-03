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
import com.jabiz.finance.report.CashFlowProcesses;
import com.jabiz.finance.report.StatementProcesses;
import com.jabiz.runtime.event.OutboxDeliverer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.math.BigDecimal;
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
 * at the close, the trial balance is the artifact's, after February's postings too. Step 5: the balance sheet, the
 * income statement, the statement of equity and the statement of cash flows are FIN-EXP-04 to 07, Professional fees
 * drills to BILL-DC-2601, BILL-JR-014 and JE-0002, and the income statement exports to PDF and Excel.
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

        // FIN-RP-007 acceptance 1: on 2026-01-31 each control account equals its subledger.
        assertThat(report("finance.gl.subledger_reconciliation", controller, Map.of("asOf", "2026-01-31")))
            .extracting(r -> r.get("controlClass") + " " + r.get("accounts") + " " + amount(r.get("subledger")) + " "
                + amount(r.get("ledger")) + " " + amount(r.get("difference")))
            .containsExactly("AP 2000 46300.00 46300.00 0.00", "AR 1200 158735.00 158735.00 0.00",
                "FA_ACCUM 1590 62000.00 62000.00 0.00", "FA_COST 1500, 1510, 1520 202000.00 202000.00 0.00");
        // Its subledgers are the agings' and the register's totals.
        assertThat(report("finance.ar.aging", controller, Map.of("agingDate", "2026-01-31")).stream()
            .map(r -> amount(r.get("openAmountUsd"))).reduce(BigDecimal.ZERO, BigDecimal::add))
            .isEqualByComparingTo("158735.00");
        assertThat(report("finance.ap.aging", controller, Map.of("agingDate", "2026-01-31")).stream()
            .map(r -> amount(r.get("openAmountUsd"))).reduce(BigDecimal.ZERO, BigDecimal::add))
            .isEqualByComparingTo("46300.00");

        // FIN-RP-008 acceptance 1: January's register, in general ledger number order, every entry with its
        // preparer and, where one was needed, its approver.
        List<Map<String, Object>> register = report("finance.gl.posting_register", controller,
            Map.of("from", "2026-01-01", "to", "2026-01-31"));
        assertThat(register).hasSize(find(JournalEntities.POSTING_DATASET, "periodKey", "2026-01").size());
        assertThat(register).filteredOn(r -> "JE-0001".equals(r.get("documentNo"))).singleElement()
            .satisfies(r -> assertThat(r).containsEntry("preparer", "accountant").containsEntry("approver",
                "controller"));
        assertThat(register).filteredOn(r -> "JE-0003".equals(r.get("documentNo"))).singleElement()
            .satisfies(r -> assertThat(r).containsEntry("preparer", "accountant").containsEntry("approver", null));
        assertThat(register).filteredOn(r -> "FinBill".equals(r.get("sourceEntity"))).isNotEmpty()
            .allSatisfy(r -> assertThat(r).containsEntry("preparer", "ap-clerk").containsEntry("source", "AP"));
        assertThat(register).filteredOn(r -> "FinInvoice".equals(r.get("sourceEntity"))).isNotEmpty()
            .allSatisfy(r -> assertThat(r).containsEntry("preparer", "ar-clerk"));
        assertThat(report("finance.gl.journal_register", controller, Map.of("from", "2026-01-01", "to", "2026-01-31")))
            .filteredOn(r -> "JE-0002".equals(r.get("journalNo"))).singleElement()
            .satisfies(r -> assertThat(r).containsEntry("approver", "controller"));
        // The detail of Professional fees: 34,000.00 of debits, BILL-DC-2601, BILL-JR-014 and JE-0002 (FIN-RP-006).
        List<Map<String, Object>> fees = report("finance.gl.detail", controller, Map.of("from", "2026-01-01",
            "to", "2026-01-31", "accountCode", "6400"));
        assertThat(fees.stream().map(r -> amount(r.get("debit"))).reduce(BigDecimal.ZERO, BigDecimal::add))
            .isEqualByComparingTo("34000.00");
        assertThat(fees).hasSize(3).allSatisfy(r -> assertThat(r).containsEntry("accountCode", "6400"));

        // Step 5: the statements of January (FIN-EXP-04 to 07, read from the period balances the close kept).
        Map<String, Object> asOfJanuary = Map.of("asOf", "2026-01-31");
        Map<String, Object> january = Map.of("through", "2026-01-31");
        assertThat(figure(report(StatementProcesses.BALANCE_SHEET, controller, asOfJanuary), "TOTAL_ASSETS", "amount"))
            .isEqualByComparingTo("617415.00");
        assertThat(figure(report(StatementProcesses.INCOME_STATEMENT, controller, january), "NET_INCOME", "month"))
            .isEqualByComparingTo("5127.10");
        assertThat(figure(report(StatementProcesses.EQUITY, controller, january), "TOTAL", "closing"))
            .isEqualByComparingTo("464027.10");
        classifyCashFlows();
        List<Map<String, Object>> cashFlows = report(CashFlowProcesses.CASH_FLOW, controller, january);
        assertThat(figure(cashFlows, "NET_CHANGE", "amount")).isEqualByComparingTo("-38320.00");
        assertThat(figure(cashFlows, "UNEXPLAINED", "amount")).isEqualByComparingTo("0.00");
        // FIN-RP-006 acceptance 1: Professional fees, the income statement's line of 6400 (34,000.00), drills to its
        // entries and their documents.
        List<Map<String, Object>> feeLines = report(StatementProcesses.LINE_DETAIL, controller, Map.of("accounts", "6400",
            "through", "2026-01-31", "span", "MONTH"));
        // The bills are known by their vendors' invoice numbers, on the bill each line opens: BILL-DC-2601 of the
        // sample is the bill these books enter with V200's invoice DC-2026-01.
        assertThat(feeLines).extracting(r -> ("FinBill".equals(r.get("sourceEntity"))
            ? "BILL-" + read(com.jabiz.finance.ap.BillEntities.BILL_DATASET, r.get("sourceId")).get("vendorInvoiceNo")
            : r.get("documentNo")) + " " + r.get("sourceEntity"))
            .containsExactlyInAnyOrder("BILL-DC-2026-01 FinBill", "BILL-JR-014 FinBill", "JE-0002 FinJournal");
        assertThat(feeLines.stream().map(r -> amount(r.get("amount"))).reduce(BigDecimal.ZERO, BigDecimal::add))
            .isEqualByComparingTo(figure(report(StatementProcesses.INCOME_STATEMENT, controller, january),
                "OPERATING_EXPENSES.6400", "month").negate());
        assertThat(feeLines).allSatisfy(r -> assertThat(r.get("sourceId")).isNotNull());
        // A balance: the account's balance before the month and the month's lines add up to it (1010: 211,555.00).
        List<Map<String, Object>> cash = report(StatementProcesses.LINE_DETAIL, controller, Map.of("accounts", "1010",
            "through", "2026-01-31"));
        assertThat(cash).filteredOn(r -> "OPENING".equals(r.get("kind"))).singleElement()
            .satisfies(r -> assertThat(amount(r.get("amount"))).isEqualByComparingTo("250000.00"));
        assertThat(cash.stream().map(r -> amount(r.get("amount"))).reduce(BigDecimal.ZERO, BigDecimal::add))
            .isEqualByComparingTo("211555.00");
        // Exported to PDF and Excel (FIN-RP-010) through the platform's export.
        for (String format : List.of("pdf", "xlsx")) {
            post("/api/queries/" + StatementProcesses.INCOME_STATEMENT + "/export?format=" + format, controller,
                Map.of("params", january)).expectStatus().isOk();
        }

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

    /** The figure of a statement's line in a column. */
    private static BigDecimal figure(List<Map<String, Object>> rows, String lineCode, String column) {
        return rows.stream().filter(r -> lineCode.equals(r.get("lineCode"))).map(r -> amount(r.get(column)))
            .findFirst().orElseThrow(() -> new AssertionError("no line " + lineCode));
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
