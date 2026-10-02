package com.jabiz.finance.it;

import com.jabiz.finance.ap.ApSettingsProcesses;
import com.jabiz.finance.ap.BillProcesses;
import com.jabiz.finance.ap.PaymentProcesses;
import com.jabiz.finance.ap.VendorBankProcesses;
import com.jabiz.finance.ar.InvoiceEntities;
import com.jabiz.finance.ar.ReceiptProcesses;
import com.jabiz.finance.bank.BankAccountProcesses;
import com.jabiz.finance.bank.BankEntryProcesses;
import com.jabiz.finance.bank.MatchProcesses;
import com.jabiz.finance.bank.ReconciliationEntities;
import com.jabiz.finance.bank.ReconciliationProcesses;
import com.jabiz.finance.bank.StatementEntities;
import com.jabiz.finance.bank.StatementProcesses;
import com.jabiz.finance.gl.AccountTypes;
import com.jabiz.finance.gl.JournalEntities;
import com.jabiz.finance.gl.JournalProcesses;
import com.jabiz.finance.payroll.PayrollEntities;
import com.jabiz.finance.setup.FinanceRoles;
import com.jabiz.runtime.event.OutboxDeliverer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIN-SCN-05, bank reconciliation, on the whole of January's books: the receipts, the payment runs and the sales tax
 * payment, JE-0001 and PAYROLL-2601 as FIN-EXP-02 has them. The January statement is imported once (again as CSV,
 * BAI2 or camt.053 it adds nothing); matching proposes the eight expected matches and the accountant accepts them; the
 * fee and the interest become their entries; a match is undone and redone; the accountant completes the
 * reconciliation and the controller signs it off. It equals FIN-EXP-10, and its reprint after February's postings is
 * the identical report.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class FinScn05IT extends FinanceItSupport {

    @Autowired
    OutboxDeliverer deliverer;

    private String controller;
    private String accountant;
    private String clerk;
    private String treasurer;

    private void decide(Object requestId) {
        ok("APPROVAL_DECIDE", controller, Map.of("requestId", requestId, "decision", "APPROVE"));
        deliverer.deliverPending().block();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theJanuaryReconciliationOfTheOperatingAccount() throws IOException {
        controller = inRoles("controller", FinanceRoles.CONTROLLER);
        accountant = inRoles("accountant", FinanceRoles.ACCOUNTANT);
        clerk = inRoles("ap-clerk", FinanceRoles.PAYABLES_CLERK);
        treasurer = inRoles("treasurer", FinanceRoles.TREASURER);
        januaryBooks();

        // Step 1: the statement, once.
        importCsv("finance.bank_opening_items", as("migrator", "fin.migration", "fin.import"),
            "date,reference,description,amount\n2025-12-28,CHK-1045,Check 1045,-3200.00\n", "commit", null,
            Map.of("bankCode", "OPERATING", "statementBalance", "253200.00"), 200);
        byte[] csv = sampleText("bank-statement-2026-01.csv").getBytes(StandardCharsets.UTF_8);
        statement("finance.bank_statement", csv, "statement.csv", "text/csv", 200);
        statement("finance.bank_statement", csv, "statement.csv", "text/csv", 409);
        assertThat(statement("finance.bank_statement_bai2", bank("statement-2026-01.bai2"), "statement.bai2",
            "text/plain", 422).toString()).contains(StatementProcesses.RECORDED);
        assertThat(statement("finance.bank_statement_camt053", bank("statement-2026-01.camt053.xml"),
            "statement.xml", "application/xml", 422).toString()).contains(StatementProcesses.RECORDED);
        Map<String, String> lines = new LinkedHashMap<>();
        find(StatementEntities.LINE_DATASET, "bankCode", "OPERATING")
            .forEach(l -> lines.put((String) l.get("bankReference"), (String) l.get("lineId")));
        assertThat(lines).hasSize(10);

        // Step 2: the eight expected matches, proposed and accepted.
        List<Map<String, Object>> proposals = (List<Map<String, Object>>) ok(MatchProcesses.PROPOSE, accountant,
            Map.of("bankCode", "OPERATING")).get("proposals");
        assertThat(proposals).extracting(p -> p.get("bankReference") + " " + ((List<Map<String, Object>>)
            p.get("items")).stream().map(i -> (String) i.get("documentNo")).sorted().toList())
            .containsExactlyInAnyOrder("BNK-0001 [CHK-1045]", "BNK-0002 [RCPT-0001]", "BNK-0003 " + runPayments(
                "PAY-RUN-01"), "BNK-0004 [JE-0001]", "BNK-0005 [RCPT-0002]", "BNK-0006 " + runPayments("STX"),
                "BNK-0007 " + runPayments("PAY-RUN-02"), "BNK-0008 [RCPT-0003]");
        assertThat(ok(MatchProcesses.ACCEPT, accountant, Map.of("bankCode", "OPERATING", "proposals",
            proposals.stream().map(p -> Map.<String, Object>of("lineId", p.get("lineId"), "items",
                ((List<Map<String, Object>>) p.get("items")).stream()
                    .map(i -> Map.of("kind", i.get("kind"), "id", i.get("id"))).toList())).toList())))
            .containsEntry("matched", 8);

        // Step 3: the fee and the interest become BANK-FEE-2601 and BANK-INT-2601.
        ok(BankEntryProcesses.RULE_SAVE, controller, Map.of("ruleCode", "FEE", "keywords", "service fee",
            "direction", "PAYMENT", "account", "6800", "documentPrefix", "BANK-FEE", "description",
            "Account service fee"));
        ok(BankEntryProcesses.RULE_SAVE, controller, Map.of("ruleCode", "INT", "keywords", "interest",
            "direction", "PAYMENT", "account", "7100", "documentPrefix", "BANK-INT", "description",
            "Line of credit interest"));
        assertThat(ok(BankEntryProcesses.FROM_LINE, accountant, Map.of("lineId", lines.get("BNK-0009"))))
            .containsEntry("entryNo", "BANK-FEE-2601");
        assertThat(ok(BankEntryProcesses.FROM_LINE, accountant, Map.of("lineId", lines.get("BNK-0010"))))
            .containsEntry("entryNo", "BANK-INT-2601");
        Map<String, Map<String, java.math.BigDecimal>> expected = expectedDocuments("BANK-FEE-2601", "bank");
        assertThat(postingLines("BANK-FEE-2601")).isEqualTo(expected.get("BANK-FEE-2601"));
        assertThat(postingLines("BANK-INT-2601")).isEqualTo(expectedDocuments("BANK-INT-2601", "bank").get("BANK-INT-2601"));

        // Step 4: a match undone and redone, both kept.
        List<Map<String, Object>> history = report("finance.bank.match_history", accountant,
            Map.of("bankCode", "OPERATING"));
        Map<String, Object> deposit = history.stream().filter(h -> "BNK-0005".equals(h.get("statementItems")))
            .findFirst().orElseThrow();
        ok(MatchProcesses.UNMATCH, accountant, Map.of("matchId", deposit.get("matchId"), "reason",
            "Checking the remittance"));
        Map<String, Object> receipt = report(MatchProcesses.BOOK_ITEMS, accountant, Map.of("bankCode", "OPERATING"))
            .stream().filter(i -> "RCPT-0002".equals(i.get("documentNo"))).findFirst().orElseThrow();
        ok(MatchProcesses.MATCH, accountant, Map.of("bankCode", "OPERATING", "lineIds", List.of(lines.get("BNK-0005")),
            "items", List.of(Map.of("kind", receipt.get("refKind"), "id", receipt.get("refId"))),
            "reason", "Remittance confirmed"));
        assertThat(report("finance.bank.match_history", accountant, Map.of("bankCode", "OPERATING")))
            .filteredOn(h -> "BNK-0005".equals(h.get("statementItems"))).extracting(h -> h.get("action"))
            .containsExactlyInAnyOrder("MATCH", "UNMATCH", "MATCH");

        // FIN-BK-010: the cash position on the 31st.
        List<Map<String, Object>> position = report("finance.bank.cash_position", treasurer,
            Map.of("asOf", "2026-01-31"));
        assertThat(position).filteredOn(r -> "ACCOUNT".equals(r.get("section"))).singleElement().satisfies(r -> {
            assertThat(r).containsEntry("bankCode", "OPERATING").containsEntry("glAccount", "1010");
            assertThat(amount(r.get("bookBalance"))).isEqualByComparingTo("211555.00");
            assertThat(amount(r.get("statementBalance"))).isEqualByComparingTo("256555.00");
        });
        assertThat(position).filteredOn(r -> "EXPECTED_PAYMENT".equals(r.get("section"))
            && "P-7902".equals(r.get("reference"))).singleElement().satisfies(r -> {
                assertThat(r).containsEntry("dueDate", "2026-02-08");
                assertThat(amount(r.get("amount"))).isEqualByComparingTo("22000.00");
            });

        // Step 5: completed by the accountant, signed off by the controller, issued.
        Map<String, Object> prepared = ok(ReconciliationProcesses.PREPARE, accountant, Map.of("bankCode",
            "OPERATING", "statementDate", "2026-01-31"));
        String recId = (String) prepared.get("reconciliationId");
        List<Map<String, Object>> rows = reconciliation();
        // FIN-EXP-10.
        assertThat(rows).extracting(r -> r.get("section") + " " + (r.get("reference") == null ? ""
            : r.get("reference") + " ") + (r.get("amount") == null ? r.get("description")
                : amount(r.get("amount")).toPlainString()))
            .containsExactly("STATEMENT_BALANCE 256555.00", "OUTSTANDING_PAYMENT PAYROLL-2601 -45000.00",
                "ADJUSTED_BANK_BALANCE 211555.00", "BOOK_BALANCE 211555.00", "DIFFERENCE 0.00");
        assertThat(rows).filteredOn(r -> "OUTSTANDING_PAYMENT".equals(r.get("section"))).singleElement()
            .satisfies(r -> assertThat(r).containsEntry("itemDate", "2026-01-30"));
        Map<String, Object> submitted = ok(ReconciliationProcesses.COMPLETE, accountant, Map.of("reconciliationId",
            recId));
        run("APPROVAL_DECIDE", accountant, Map.of("requestId", submitted.get("approvalRequestId"),
            "decision", "APPROVE")).expectStatus().isForbidden();
        decide(submitted.get("approvalRequestId"));
        assertThat(read(ReconciliationEntities.RECONCILIATION_DATASET, recId))
            .containsEntry("status", ReconciliationEntities.SIGNED_OFF).containsEntry("preparedBy", "accountant")
            .containsEntry("reviewedBy", "controller");
        String runId = (String) ok(ReconciliationProcesses.ISSUE_REPORT, accountant, Map.of("reconciliationId",
            recId)).get("reportRunId");
        String reader = as("auditor", "report.archive.read", "fin.bank.activity.read");
        byte[] issued = get("/api/reports/runs/" + runId + "/export?format=csv", reader).expectStatus().isOk()
            .expectBody(byte[].class).returnResult().getResponseBody();
        assertThat(new String(issued, StandardCharsets.UTF_8)).contains("PAYROLL-2601", "211555.00",
            "Prepared by accountant", "Reviewed by controller");

        // February moves on: a receipt and a bank fee of February.
        String rest = (String) find(InvoiceEntities.INVOICE_DATASET, "invoiceNo", "INV-1003").getFirst()
            .get("invoiceId");
        Map<String, Object> february = new LinkedHashMap<>();
        february.put("customerCode", "C300");
        february.put("receiptDate", "2026-02-05");
        february.put("amount", "1000.00");
        february.put("method", "ACH");
        february.put("bankAccount", "1010");
        february.put("applications", List.of(Map.of("invoiceId", rest, "amount", "1000.00")));
        ok(ReceiptProcesses.RECORD, inRoles("ar-clerk", FinanceRoles.RECEIVABLES_CLERK), february);
        // The expected result: the reprint is the identical report, and January worked out again is unchanged.
        assertThat(get("/api/reports/runs/" + runId + "/export?format=csv", reader).expectStatus().isOk()
            .expectBody(byte[].class).returnResult().getResponseBody()).isEqualTo(issued);
        assertThat(reconciliation()).isEqualTo(rows);
        assertOnlyInserted("fi_bank_match_version", "fi_bank_match_item_version", "fi_bank_entry_version",
            "fi_bank_reconciliation_version", "fi_statement_line_version");
    }

    private List<Map<String, Object>> reconciliation() {
        return report(ReconciliationProcesses.TEMPLATE, accountant, Map.of("bankCode", "OPERATING",
            "statementDate", "2026-01-31"));
    }

    /** The payment numbers of a run, as matching lists them for its one bank debit. */
    private String runPayments(String run) {
        return report(MatchProcesses.BOOK_ITEMS, accountant, Map.of("bankCode", "OPERATING")).stream()
            .filter(i -> run.equals(i.get("runNo")) || ("STX".equals(run) && "Texas Comptroller".equals(i.get("party"))))
            .map(i -> (String) i.get("documentNo")).sorted().toList().toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> statement(String importId, byte[] content, String name, String type, int status) {
        String fileId = upload(accountant, "fin.bank.statement", content, name, type);
        var exchange = post("/api/imports/" + importId + "/commit", accountant, Map.of("fileId", fileId,
            "params", Map.of("bankCode", "OPERATING"))).expectBody(MAP).returnResult();
        assertThat(exchange.getStatus().value()).as(importId + " answered " + exchange.getResponseBody())
            .isEqualTo(status);
        return exchange.getResponseBody();
    }

    private static byte[] bank(String name) throws IOException {
        return Files.readAllBytes(Path.of("src/test/resources/bank").resolve(name));
    }

    /** January as FIN-EXP-02 has it on the operating account, entered by its people. */
    private void januaryBooks() throws IOException {
        openReceivables();
        // Receivables: RCPT-0001…0003.
        String arClerk = inRoles("ar-clerk", FinanceRoles.RECEIVABLES_CLERK);
        for (String[] r : new String[][] {{"C100", "2026-01-05", "32475.00", "INV-1001"},
            {"C200", "2026-01-16", "24025.00", "INV-1002"}, {"C300", "2026-01-25", "20000.00", "INV-1003"}}) {
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("customerCode", r[0]);
            receipt.put("receiptDate", r[1]);
            receipt.put("amount", r[2]);
            receipt.put("method", "ACH");
            receipt.put("bankAccount", "1010");
            receipt.put("applications", List.of(Map.of("invoiceId", find(InvoiceEntities.INVOICE_DATASET,
                "invoiceNo", r[3]).getFirst().get("invoiceId"), "amount", r[2])));
            ok(ReceiptProcesses.RECORD, arClerk, receipt);
        }

        // Payables: the vendors, their banks, the open payables and the bills the runs pay.
        importCsv("finance.vendors", clerk, sampleText("vendors.csv"), "commit", null, null, 200);
        account("5900", "Purchase Discounts", "Expense", "C");
        account("2210", "Use Tax Payable", "Liability", "C");
        account("1310", "Vendor Prepayments", "Asset", "D");
        ok(BankAccountProcesses.SAVE, treasurer, Map.of("bankCode", "OPERATING", "bankName",
            "Lakeside National Bank", "glAccount", "1010", "routingNumber", "111000025",
            "companyAccountNumber", "000123456789", "achCompanyId", "1234567890", "achCompanyName", "NORTHWIND",
            "nextCheckNo", 10001));
        ok(ApSettingsProcesses.SET, controller, Map.of("payableAccount", "2000", "discountAccount", "5900",
            "useTaxAccount", "2210", "prepaymentAccount", "1310", "defaultBank", "OPERATING"));
        importCsv("finance.open_payables", as("migrator", "fin.migration", "fin.import", "fin.ap.read"),
            sampleText("open-payables.csv"), "commit", null, null, 200);
        for (String[] bank : new String[][] {{"V100", "021000021", "100200300"}, {"V300", "091000019", "300400500"},
            {"V600", "111000025", "600700800"}, {"V800", "021000021", "800900100"}}) {
            decide(ok(VendorBankProcesses.CHANGE, clerk, bankChange(bank[0], bank[1], bank[2]))
                .get("approvalRequestId"));
        }
        // V200's details wait until after PAY-RUN-01, which therefore holds DC-2025-12.
        Map<String, Object> v200 = ok(VendorBankProcesses.CHANGE, clerk, bankChange("V200", "011000015",
            "200300400"));
        Map<String, String> bills = new HashMap<>();
        for (String[] s : new String[][] {{"V300", "MP-2026-01", "2026-01-02", "6200", "8500.00"},
            {"V100", "P-7902", "2026-01-09", "5000", "22000.00"}, {"V800", "JR-014", "2026-01-21", "6400", "1500.00"}}) {
            Map<String, Object> posted = ok(BillProcesses.POST, clerk, Map.of("billId", saveBill(s[0], s[1], s[2],
                s[3], s[4])));
            bills.put(s[1], (String) posted.get("billId"));
            if ("PENDING".equals(posted.get("approval"))) {
                decide(posted.get("approvalRequestId"));
            }
        }
        // PAY-RUN-01 (8 January), PAY-RUN-02 (22 January) and STX-PAY-2512 (20 January), numbered as the sample.
        Map<String, Object> run01 = ok(PaymentProcesses.PROPOSE, clerk, Map.of("paymentDate", "2026-01-08",
            "method", "ACH", "dueThrough", "2026-01-20", "description", "ACH payment run 01"));
        assertThat(run01).containsEntry("runNo", "PAY-RUN-01");
        assertThat(amount(run01.get("total"))).isEqualByComparingTo("32300.00");
        release((String) run01.get("runId"));
        decide(v200.get("approvalRequestId"));
        Map<String, Object> run02 = ok(PaymentProcesses.PROPOSE, clerk, Map.of("paymentDate", "2026-01-22",
            "method", "ACH", "dueThrough", "2026-01-31", "vendorCodes", List.of("V200", "V300", "V800"),
            "description", "ACH payment run 02"));
        String run02Id = (String) run02.get("runId");
        ok(PaymentProcesses.ADD, clerk, Map.of("runId", run02Id, "billId", bills.get("MP-2026-01")));
        Map<String, Object> added = ok(PaymentProcesses.ADD, clerk, Map.of("runId", run02Id,
            "billId", bills.get("JR-014")));
        assertThat(run02).containsEntry("runNo", "PAY-RUN-02");
        assertThat(amount(added.get("total"))).isEqualByComparingTo("19000.00");
        release(run02Id);
        Map<String, Object> tax = ok(PaymentProcesses.PROPOSE, clerk, Map.of("paymentDate", "2026-01-20",
            "method", "MANUAL"));
        ok(PaymentProcesses.ADD, clerk, Map.of("runId", tax.get("runId"), "payee", "Texas Comptroller",
            "account", "2200", "amount", "3300.00", "description", "Texas sales tax return December 2025"));
        release((String) tax.get("runId"));

        // JE-0001: the bonus paid from the operating account, a control account the controller allows.
        String je1 = (String) ok(JournalProcesses.SAVE, accountant, JournalLifecycleIT.entry("2026-01-15",
            "Payout of 2025 bonus accrued at year end", List.of(JournalLifecycleIT.line("2100", "15000.00", null, null),
                JournalLifecycleIT.line("1010", null, "15000.00", null)))).get("journalId");
        ok(JournalProcesses.GRANT_CONTROL_EXCEPTION, controller, Map.of("journalId", je1,
            "reason", "Bonus paid from the operating account"));
        decide(ok(JournalProcesses.SUBMIT, accountant, Map.of("journalId", je1)).get("approvalRequestId"));
        assertThat(read(JournalEntities.JOURNAL_DATASET, je1)).containsEntry("journalNo", "JE-0001")
            .containsEntry("status", "POSTED");

        // PAYROLL-2601 from the provider's file.
        for (String[] m : new String[][] {{"GROSS_WAGES", "6100", "DEBIT"}, {"EMPLOYER_TAX", "6150", "DEBIT"},
            {"NET_PAY", "1010", "CREDIT"}, {"EMPLOYEE_WITHHOLDING", "2150", "CREDIT"},
            {"EMPLOYER_TAX_LIABILITY", "2150", "CREDIT"}}) {
            post("/api/datasets/" + PayrollEntities.MAPPING_DATASET + "/commit", controller, Map.of("changes",
                List.of(Map.of("action", "INSERT", "attributes", Map.of("providerCode", m[0], "accountCode", m[1],
                    "side", m[2], "active", true))))).expectStatus().isOk();
        }
        String payroll;
        try (var in = FinScn05IT.class.getResourceAsStream("/payroll/provider-2026-01.csv")) {
            payroll = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        importCsv("finance.payroll", accountant, payroll, "commit", null, Map.of("run", "PAYROLL-2601",
            "payDate", "2026-01-30", "description", "Payroll summary from external provider"), 200);
        decide(find(JournalEntities.JOURNAL_DATASET, "journalNo", "PAYROLL-2601").getFirst()
            .get("approvalRequestId"));
        assertThat(find(JournalEntities.JOURNAL_DATASET, "journalNo", "PAYROLL-2601").getFirst())
            .containsEntry("status", "POSTED");
    }

    private void release(String runId) {
        decide(ok(PaymentProcesses.SUBMIT, clerk, Map.of("runId", runId)).get("approvalRequestId"));
        ok(PaymentProcesses.RELEASE, treasurer, Map.of("runId", runId));
    }

    private static Map<String, Object> bankChange(String vendor, String routing, String account) {
        return Map.of("vendorCode", vendor, "bankName", "Some Bank", "routingNumber", routing,
            "bankAccountNumber", account, "reason", "Vendor set-up form");
    }

    private String saveBill(String vendor, String number, String date, String account, String amount) {
        return (String) ok(BillProcesses.SAVE, clerk, Map.of("vendorCode", vendor, "vendorInvoiceNo", number,
            "invoiceDate", date, "lines", List.of(Map.of("description", number, "amount", amount,
                "account", account)))).get("billId");
    }

    private void account(String code, String name, String type, String balance) {
        ok("FIN_ACCOUNT_CREATE", controller(), Map.of("accountCode", code, "accountName", name,
            "financialType", AccountTypes.fromChart(type), "normalBalance", AccountTypes.normalBalanceFromChart(
                balance), "statementLine", name));
    }
}
