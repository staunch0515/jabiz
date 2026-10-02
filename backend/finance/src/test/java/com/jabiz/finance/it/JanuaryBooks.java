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
 * January's books as FIN-EXP-02 has them, entered by their people through the processes, for the scenarios that start
 * from them (FIN-SCN-06, FIN-SCN-07; the statements of F9).
 */
abstract class JanuaryBooks extends FinanceItSupport {

    @Autowired
    OutboxDeliverer deliverer;

    protected String controller;
    protected String accountant;
    protected String arClerk;
    protected String apClerk;
    protected String treasurer;

    /** Signed in now: tokens expire as the clock moves on. */
    protected void people() {
        controller = inRoles("controller", FinanceRoles.CONTROLLER);
        accountant = inRoles("accountant", FinanceRoles.ACCOUNTANT);
        arClerk = inRoles("ar-clerk", FinanceRoles.RECEIVABLES_CLERK);
        apClerk = inRoles("ap-clerk", FinanceRoles.PAYABLES_CLERK);
        treasurer = inRoles("treasurer", FinanceRoles.TREASURER);
    }

    protected void decide(Object requestId) {
        ok("APPROVAL_DECIDE", controller, Map.of("requestId", requestId, "decision", "APPROVE"));
        deliverer.deliverPending().block();
    }

    // ---- January's books (FIN-EXP-02) -------------------------------------------------------------------------------

    protected String rcpt3;

    /** FIN-SCN-01's books: the sample opened, its open items, assets and settings. */
    protected void books() {
        openReceivables();
        String migrator = as("migrator", "fin.migration", "fin.import", "fin.ap.read");
        importCsv("finance.vendors", apClerk, sampleText("vendors.csv"), "commit", null, null, 200);
        ok(AssetClassProcesses.CLASS_SAVE, controller, assetClass("MACH", "Machinery and equipment", "1500", "SL", 60));
        ok(AssetClassProcesses.CLASS_SAVE, controller, assetClass("VEH", "Vehicles", "1510", "DDB", 84));
        ok(AssetClassProcesses.CLASS_SAVE, controller, assetClass("COMP", "Computer equipment", "1520", "SL", 36));
        account("5900", "Purchase Discounts", "Expense", "C");
        account("2210", "Use Tax Payable", "Liability", "C");
        account("1310", "Vendor Prepayments", "Asset", "D");
        account("7400", "Gain or Loss on Disposal of Assets", "Other", "D");
        ok(BankAccountProcesses.SAVE, treasurer, Map.of("bankCode", "OPERATING", "bankName",
            "Lakeside National Bank", "glAccount", "1010", "routingNumber", "111000025",
            "companyAccountNumber", "000123456789", "achCompanyId", "1234567890", "achCompanyName", "NORTHWIND",
            "nextCheckNo", 10001));
        ok(ApSettingsProcesses.SET, controller, Map.of("payableAccount", "2000", "discountAccount", "5900",
            "useTaxAccount", "2210", "prepaymentAccount", "1310", "defaultBank", "OPERATING"));
        ok(AssetClassProcesses.SETTINGS_SET, controller, Map.of("gainLossAccount", "7400"));
        ok(FxSettingsProcesses.SET, controller, Map.of("realizedAccount", "7200", "unrealizedAccount", "7210"));
        importCsv("finance.open_payables", migrator, sampleText("open-payables.csv"), "commit", null, null, 200);
        importCsv("finance.fixed_assets", migrator, sampleText("fixed-assets.csv"), "commit", null, null, 200);
        importCsv("finance.bank_opening_items", as("migrator", "fin.migration", "fin.import"),
            "date,reference,description,amount\n2025-12-28,CHK-1045,Check 1045,-3200.00\n", "commit", null,
            Map.of("bankCode", "OPERATING", "statementBalance", "253200.00"), 200);
    }

    /** Receipts, invoices and the credit memo of FIN-SCN-03; RCPT-0003 waits unapplied. */
    protected void receivables() {
        receipt("C100", "2026-01-05", "32475.00", "INV-1001");
        String inv1004 = invoice("C100", "2026-01-06", List.of(invoiceLine("Components", "100", "400.00", "4000",
            null), invoiceLine("Engineering services", "1", "10000.00", "4100", "NT")));
        Map<String, Object> credit = invoiceInput("C100", "2026-01-10", "CREDIT_MEMO", List.of(
            invoiceLine("Returned components", "5", "400.00", null, null)));
        credit.put("originalInvoiceId", inv1004);
        String cm2001 = (String) ok(InvoiceProcesses.SAVE, arClerk, credit).get("invoiceId");
        assertThat(ok(InvoiceProcesses.POST, controller, Map.of("invoiceId", cm2001)))
            .containsEntry("invoiceNo", "CM-2001");
        ok(InvoiceProcesses.APPLY, arClerk, Map.of("creditMemoId", cm2001, "invoiceId", inv1004, "amount",
            "2165.00", "applicationDate", "2026-01-10"));
        invoice("C400", "2026-01-12", List.of(invoiceLine("Components", "1", "50000.00", "4000", null)));
        invoice("C200", "2026-01-14", List.of(invoiceLine("Engineering services", "1", "18000.00", "4100", null)));
        invoice("C300", "2026-01-15", List.of(invoiceLine("Components", "50", "500.00", "4000", null)));
        receipt("C200", "2026-01-16", "24025.00", "INV-1002");
        // RCPT-0003 is recorded before the remittance says what it pays: unapplied cash for now (FIN-CT-005).
        Map<String, Object> unapplied = new LinkedHashMap<>();
        unapplied.put("customerCode", "C300");
        unapplied.put("receiptDate", "2026-01-25");
        unapplied.put("amount", "20000.00");
        unapplied.put("method", "ACH");
        unapplied.put("bankAccount", "1010");
        Map<String, Object> recorded = ok(ReceiptProcesses.RECORD, arClerk, unapplied);
        assertThat(recorded).containsEntry("receiptNo", "RCPT-0003");
        rcpt3 = (String) recorded.get("receiptId");
        for (String number : List.of("INV-1004", "INV-1005", "INV-1006", "INV-1007")) {
            assertThat(find(InvoiceEntities.INVOICE_DATASET, "invoiceNo", number)).as(number).hasSize(1);
        }
    }

    /** FIN-SCN-04's and FIN-SCN-05's bills and payment runs, numbered as the sample. */
    protected void payables() {
        for (String[] bank : new String[][] {{"V100", "021000021", "100200300"}, {"V300", "091000019", "300400500"},
            {"V600", "111000025", "600700800"}, {"V800", "021000021", "800900100"}}) {
            decide(ok(VendorBankProcesses.CHANGE, apClerk, bankChange(bank[0], bank[1], bank[2]))
                .get("approvalRequestId"));
        }
        // V200's details wait until after PAY-RUN-01, which therefore holds DC-2025-12.
        Map<String, Object> v200 = ok(VendorBankProcesses.CHANGE, apClerk, bankChange("V200", "011000015",
            "200300400"));
        Map<String, String> bills = new LinkedHashMap<>();
        for (String[] s : new String[][] {{"V300", "MP-2026-01", "2026-01-02", "6200", "8500.00"},
            {"V100", "P-7902", "2026-01-09", "5000", "22000.00"}, {"V800", "JR-014", "2026-01-21", "6400", "1500.00"}}) {
            bills.put(s[1], bill(s));
        }
        Map<String, Object> run01 = ok(PaymentProcesses.PROPOSE, apClerk, Map.of("paymentDate", "2026-01-08",
            "method", "ACH", "dueThrough", "2026-01-20", "description", "ACH payment run 01"));
        assertThat(run01).containsEntry("runNo", "PAY-RUN-01");
        release((String) run01.get("runId"));
        decide(v200.get("approvalRequestId"));
        Map<String, Object> run02 = ok(PaymentProcesses.PROPOSE, apClerk, Map.of("paymentDate", "2026-01-22",
            "method", "ACH", "dueThrough", "2026-01-31", "vendorCodes", List.of("V200", "V300", "V800"),
            "description", "ACH payment run 02"));
        String run02Id = (String) run02.get("runId");
        ok(PaymentProcesses.ADD, apClerk, Map.of("runId", run02Id, "billId", bills.get("MP-2026-01")));
        ok(PaymentProcesses.ADD, apClerk, Map.of("runId", run02Id, "billId", bills.get("JR-014")));
        assertThat(run02).containsEntry("runNo", "PAY-RUN-02");
        release(run02Id);
        Map<String, Object> tax = ok(PaymentProcesses.PROPOSE, apClerk, Map.of("paymentDate", "2026-01-20",
            "method", "MANUAL"));
        ok(PaymentProcesses.ADD, apClerk, Map.of("runId", tax.get("runId"), "payee", "Texas Comptroller",
            "account", "2200", "amount", "3300.00", "description", "Texas sales tax return December 2025"));
        release((String) tax.get("runId"));
        // The bills the runs do not pay; TS-5520 makes FA-003.
        for (String[] s : new String[][] {{"V700", "TS-5520", "2026-01-15", "1520", "12000.00"},
            {"V200", "DC-2026-01", "2026-01-20", "6400", "7500.00"}, {"V400", "CS-0126", "2026-01-20", "6500",
                "1200.00"}, {"V600", "CPL-0126", "2026-01-28", "6300", "3600.00"}}) {
            bill(s);
        }
    }

    /** JE-0001 to JE-0003 of FIN-SCN-02. */
    protected void journals() {
        String je1 = (String) ok(JournalProcesses.SAVE, accountant, entry("2026-01-15",
            "Payout of 2025 bonus accrued at year end", List.of(line("2100", "15000.00", null, null),
                line("1010", null, "15000.00", null)))).get("journalId");
        ok(JournalProcesses.GRANT_CONTROL_EXCEPTION, controller, Map.of("journalId", je1,
            "reason", "Bonus paid from the operating account"));
        decide(ok(JournalProcesses.SUBMIT, accountant, Map.of("journalId", je1)).get("approvalRequestId"));
        String je2 = (String) ok(JournalProcesses.SAVE, accountant, entry("2026-01-31", "Accrue annual audit fee",
            List.of(line("6400", "25000.00", null, null), line("2100", null, "25000.00", null)))).get("journalId");
        decide(ok(JournalProcesses.SUBMIT, accountant, Map.of("journalId", je2)).get("approvalRequestId"));
        Object template = commit(JournalEntities.RECURRING_DATASET, Map.of("templateCode", "PREPAID-INS",
            "description", "Recurring: amortize prepaid insurance 1/12", "startDate", "2026-01-01",
            "endDate", "2026-12-31", "active", true)).get("id");
        commit(JournalEntities.RECURRING_LINE_DATASET, Map.of("templateId", template, "lineNo", 1,
            "accountCode", "6600", "debit", "1000.00"));
        commit(JournalEntities.RECURRING_LINE_DATASET, Map.of("templateId", template, "lineNo", 2,
            "accountCode", "1300", "credit", "1000.00"));
        ok(JournalAutomation.RECURRING_RUN, accountant, Map.of("date", "2026-01-31"));
        for (String number : List.of("JE-0001", "JE-0002", "JE-0003")) {
            assertThat(find(JournalEntities.JOURNAL_DATASET, "journalNo", number)).as(number).singleElement()
                .satisfies(j -> assertThat(j).containsEntry("status", "POSTED"));
        }
    }

    /** PAYROLL-2601 from the provider's file, approved (FIN-DI-004). */
    protected void payroll() throws IOException {
        for (String[] m : new String[][] {{"GROSS_WAGES", "6100", "DEBIT"}, {"EMPLOYER_TAX", "6150", "DEBIT"},
            {"NET_PAY", "1010", "CREDIT"}, {"EMPLOYEE_WITHHOLDING", "2150", "CREDIT"},
            {"EMPLOYER_TAX_LIABILITY", "2150", "CREDIT"}}) {
            post("/api/datasets/" + PayrollEntities.MAPPING_DATASET + "/commit", controller, Map.of("changes",
                List.of(Map.of("action", "INSERT", "attributes", Map.of("providerCode", m[0], "accountCode", m[1],
                    "side", m[2], "active", true))))).expectStatus().isOk();
        }
        String payroll;
        try (var in = FinScn06IT.class.getResourceAsStream("/payroll/provider-2026-01.csv")) {
            payroll = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        importCsv("finance.payroll", accountant, payroll, "commit", null, Map.of("run", "PAYROLL-2601",
            "payDate", "2026-01-30", "description", "Payroll summary from external provider"), 200);
        decide(find(JournalEntities.JOURNAL_DATASET, "journalNo", "PAYROLL-2601").getFirst()
            .get("approvalRequestId"));
        assertThat(find(JournalEntities.JOURNAL_DATASET, "journalNo", "PAYROLL-2601").getFirst())
            .containsEntry("status", "POSTED");
    }

    /**
     * SAV-INT-2601: the sample has no statement of the savings account, which is no bank account of the books here;
     * its interest is a journal entry the controller allows on the cash account.
     */
    protected void savingsInterest() {
        String id = (String) ok(JournalProcesses.SAVE, accountant, entry("2026-01-31", "Savings interest",
            List.of(line("1050", "125.00", null, null), line("7300", null, "125.00", null)))).get("journalId");
        ok(JournalProcesses.GRANT_CONTROL_EXCEPTION, controller, Map.of("journalId", id,
            "reason", "Interest credited by the bank on the savings account"));
        assertThat(ok(JournalProcesses.SUBMIT, accountant, Map.of("journalId", id))).containsEntry("status",
            "POSTED");
    }

    /** FIN-SCN-05: the statement, the proposed matches, the fee and the interest, signed off by the controller. */
    @SuppressWarnings("unchecked")
    protected void reconcile() {
        String fileId = upload(accountant, "fin.bank.statement",
            sampleText("bank-statement-2026-01.csv").getBytes(StandardCharsets.UTF_8), "statement.csv", "text/csv");
        post("/api/imports/finance.bank_statement/commit", accountant, Map.of("fileId", fileId,
            "params", Map.of("bankCode", "OPERATING"))).expectStatus().isOk();
        List<Map<String, Object>> proposals = (List<Map<String, Object>>) ok(MatchProcesses.PROPOSE, accountant,
            Map.of("bankCode", "OPERATING")).get("proposals");
        assertThat(ok(MatchProcesses.ACCEPT, accountant, Map.of("bankCode", "OPERATING", "proposals",
            proposals.stream().map(p -> Map.<String, Object>of("lineId", p.get("lineId"), "items",
                ((List<Map<String, Object>>) p.get("items")).stream()
                    .map(i -> Map.of("kind", i.get("kind"), "id", i.get("id"))).toList())).toList())))
            .containsEntry("matched", 8);
        ok(BankEntryProcesses.RULE_SAVE, controller, Map.of("ruleCode", "FEE", "keywords", "service fee",
            "direction", "PAYMENT", "account", "6800", "documentPrefix", "BANK-FEE", "description",
            "Account service fee"));
        ok(BankEntryProcesses.RULE_SAVE, controller, Map.of("ruleCode", "INT", "keywords", "interest",
            "direction", "PAYMENT", "account", "7100", "documentPrefix", "BANK-INT", "description",
            "Line of credit interest"));
        report(MatchProcesses.STATEMENT_ITEMS, accountant, Map.of("bankCode", "OPERATING")).stream()
            .filter(l -> List.of("BNK-0009", "BNK-0010").contains(l.get("bankReference")))
            .forEach(l -> ok(BankEntryProcesses.FROM_LINE, accountant, Map.of("lineId", l.get("lineId"))));
        Map<String, Object> prepared = ok(ReconciliationProcesses.PREPARE, accountant, Map.of("bankCode",
            "OPERATING", "statementDate", "2026-01-31"));
        assertThat(amount(prepared.get("difference"))).isEqualByComparingTo("0.00");
        decide(ok(ReconciliationProcesses.COMPLETE, accountant, Map.of("reconciliationId",
            prepared.get("reconciliationId"))).get("approvalRequestId"));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    protected void receipt(String customer, String date, String amount, String invoiceNo) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("customerCode", customer);
        input.put("receiptDate", date);
        input.put("amount", amount);
        input.put("method", "ACH");
        input.put("bankAccount", "1010");
        input.put("applications", List.of(Map.of("invoiceId", invoice(invoiceNo), "amount", amount)));
        ok(ReceiptProcesses.RECORD, arClerk, input);
    }

    protected String invoice(String customer, String date, List<Map<String, Object>> lines) {
        String id = (String) ok(InvoiceProcesses.SAVE, arClerk, invoiceInput(customer, date, null, lines))
            .get("invoiceId");
        ok(InvoiceProcesses.POST, arClerk, Map.of("invoiceId", id));
        return id;
    }

    protected String invoice(String number) {
        return (String) find(InvoiceEntities.INVOICE_DATASET, "invoiceNo", number).getFirst().get("invoiceId");
    }

    /** Saves and posts a bill {vendor, number, date, account, amount}; one above 10,000.00 is approved. */
    protected String bill(String[] s) {
        String id = (String) ok(BillProcesses.SAVE, apClerk, Map.of("vendorCode", s[0], "vendorInvoiceNo", s[1],
            "invoiceDate", s[2], "lines", List.of(Map.of("description", s[1], "amount", s[4], "account", s[3]))))
            .get("billId");
        Map<String, Object> posted = ok(BillProcesses.POST, apClerk, Map.of("billId", id));
        if ("PENDING".equals(posted.get("approval"))) {
            decide(posted.get("approvalRequestId"));
        }
        return (String) posted.get("billId");
    }

    protected void release(String runId) {
        decide(ok(PaymentProcesses.SUBMIT, apClerk, Map.of("runId", runId)).get("approvalRequestId"));
        ok(PaymentProcesses.RELEASE, treasurer, Map.of("runId", runId));
    }

    private static Map<String, Object> bankChange(String vendor, String routing, String account) {
        return Map.of("vendorCode", vendor, "bankName", "Some Bank", "routingNumber", routing,
            "bankAccountNumber", account, "reason", "Vendor set-up form");
    }

    protected Map<String, Object> commit(String dataset, Map<String, Object> attributes) {
        return post("/api/datasets/" + dataset + "/commit", accountant, Map.of("changes", List.of(
            Map.of("action", "INSERT", "attributes", attributes)))).expectStatus().isOk().expectBody(LIST)
            .returnResult().getResponseBody().getFirst();
    }

    protected void account(String code, String name, String type, String balance) {
        ok("FIN_ACCOUNT_CREATE", controller(), Map.of("accountCode", code, "accountName", name,
            "financialType", AccountTypes.fromChart(type), "normalBalance", AccountTypes.normalBalanceFromChart(
                balance), "statementLine", name));
    }

    private static Map<String, Object> assetClass(String code, String name, String costAccount, String method,
        int life) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("classCode", code);
        input.put("className", name);
        input.put("costAccount", costAccount);
        input.put("accumulatedAccount", "1590");
        input.put("expenseAccount", "6700");
        input.put("method", method);
        input.put("lifeMonths", life);
        input.put("convention", "FULL_MONTH");
        input.put("threshold", "2500.00");
        return input;
    }

    /**
     * January's postings as FIN-EXP-02 has them (FIN-SCN-02 to FIN-SCN-04 and FIN-SCN-06 steps 1 to 3): everything
     * but RCPT-0003's application and the reconciliation, which {@link #closeJanuary()} does.
     */
    protected void januaryPostings() throws java.io.IOException {
        people();
        books();
        receivables();
        payables();
        journals();
        ok(DepreciationProcesses.RUN, accountant, Map.of("periodKey", "2026-01"));
        ok(FxRevaluationProcesses.REVALUE, accountant, Map.of("periodKey", "2026-01"));
        payroll();
        String je4 = (String) ok(JournalProcesses.SAVE, accountant, entry("2026-01-31",
            "Estimated federal income tax provision", List.of(line("8000", "1362.90", null, null),
                line("2400", null, "1362.90", null)))).get("journalId");
        ok(JournalProcesses.SUBMIT, accountant, Map.of("journalId", je4));
        savingsInterest();
    }

    /** RCPT-0003 applied to INV-1003. */
    protected void applyReceipt3() {
        ok(ReceiptProcesses.APPLY, arClerk, Map.of("receiptId", rcpt3, "applicationDate", "2026-01-25",
            "applications", List.of(Map.of("invoiceId", invoice("INV-1003"), "amount", "20000.00"))));
    }

    /**
     * FIN-SCN-06 step 4 the short way: RCPT-0003 applied, the operating account reconciled, the checklist done and
     * January soft-closed and closed by the controller, two hours after the books (tokens signed in again).
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> closeJanuary() {
        applyReceipt3();
        reconcile();
        ok(CloseProcesses.START, accountant, Map.of("periodKey", "2026-01"));
        for (Map<String, Object> task : find(CloseEntities.TASK_DATASET, "periodKey", "2026-01")) {
            if ("MANUAL".equals(task.get("kind")) && "OPEN".equals(task.get("status"))) {
                ok(CloseProcesses.TASK_COMPLETE, "REVIEW".equals(task.get("taskCode")) ? controller : accountant,
                    Map.of("taskId", task.get("taskId")));
            }
        }
        ok(PeriodProcesses.SET_STATE, controller, Map.of("periodKey", "2026-01", "status", "SOFT_CLOSED"));
        clock.advance(java.time.Duration.ofHours(2));
        people();
        return ok(CloseProcesses.CLOSE, controller, Map.of("periodKey", "2026-01"));
    }
}
