package com.jabiz.finance.it;

import com.jabiz.finance.ar.ArSettingsProcesses;
import com.jabiz.finance.ar.CustomerProcesses;
import com.jabiz.finance.ar.InvoiceEntities;
import com.jabiz.finance.ar.InvoiceProcesses;
import com.jabiz.finance.ar.ReceiptEntities;
import com.jabiz.finance.ar.ReceiptProcesses;
import com.jabiz.finance.ar.RecurringInvoiceProcesses;
import com.jabiz.finance.ar.WriteOffProcesses;
import com.jabiz.finance.gl.AccountTypes;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.finance.gl.JournalProcesses;
import com.jabiz.finance.setup.FinanceRoles;
import com.jabiz.runtime.event.OutboxDeliverer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.jabiz.finance.it.JournalLifecycleIT.entry;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Receipts, applications and the receivables reports on the sample company's books (ROADMAP F3c): January's receipts
 * post as FIN-EXP-02 has them, the aging, the open-item statement, the sales tax report and the allowance suggestion
 * on 31 January are FIN-EXP-08 (INV-1005 unrevalued until F7), FIN-AR-009, FIN-EXP-13 and FIN-AR-011, and stay so
 * after later receipts; a receipt applied to the wrong customer is put right with its history; discounts, unapplied
 * cash, voids, write-offs with their approval, the credit limit's approval and recurring invoices.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReceivablesIT extends FinanceItSupport {

    /** The schema lives as long as the class: the books are opened and January's sales posted once. */
    private static boolean loaded;
    private static final Map<String, String> IDS = new HashMap<>();

    @Autowired
    OutboxDeliverer deliverer;

    private String clerk;
    private String controller;

    @BeforeEach
    void books() {
        clerk = inRoles("clerk", FinanceRoles.RECEIVABLES_CLERK);
        controller = inRoles("controller", FinanceRoles.CONTROLLER);
        if (loaded) {
            return;
        }
        loaded = true;
        openBooks();
        post("/api/datasets/" + GlEntities.CURRENCY_DATASET + "/commit", controller(), Map.of("changes", List.of(
            Map.of("action", "INSERT", "attributes", Map.of("currencyCode", "EUR", "currencyName", "Euro",
                "minorUnits", 2, "active", true))))).expectStatus().isOk();
        importCsv("finance.fx_rates", controller, sampleText("fx-rates.csv"), "commit",
            Map.of("columns", Map.of("rateDate", "date", "rate", "eur_usd"),
                "constants", Map.of("fromCurrency", "EUR", "toCurrency", "USD")), null, 200);
        importCsv("finance.opening_balances", controller, sampleText("opening-balances.csv"), "commit", null, null,
            200);
        importCsv("finance.tax_codes", controller, sampleText("tax-codes.csv"), "commit", null,
            Map.of("ratesFrom", "2025-01-01"), 200);
        importCsv("finance.customers", controller, sampleText("customers.csv"), "commit", null, null, 200);
        // The sample chart has no unapplied cash or sales discount account (design Q4): the controller adds them.
        ok("FIN_ACCOUNT_CREATE", controller(), Map.of("accountCode", "1250", "accountName", "Unapplied Cash",
            "financialType", AccountTypes.fromChart("Liability"), "normalBalance",
            AccountTypes.normalBalanceFromChart("C"), "statementLine", "Accrued liabilities", "clearing", true));
        ok("FIN_ACCOUNT_CREATE", controller(), Map.of("accountCode", "4950", "accountName", "Sales Discounts",
            "financialType", AccountTypes.fromChart("Revenue"), "normalBalance",
            AccountTypes.normalBalanceFromChart("D"), "statementLine", "Revenue"));
        settings("1250");
        importCsv("finance.open_receivables", controller, sampleText("open-receivables.csv"), "commit", null, null,
            200);
        januarySales();
    }

    private void settings(String unapplied) {
        Map<String, Object> input = new HashMap<>(Map.of("receivableAccount", "1200", "allowanceAccount", "1210",
            "returnsAccount", "4900", "salesTaxAccount", "2200", "discountAccount", "4950",
            "lossRateCurrent", "1", "lossRate1", "5"));
        input.put("unappliedCashAccount", unapplied);
        ok(ArSettingsProcesses.SET, controller, input);
    }

    /** INV-1004 with CM-2001 applied, INV-1005, INV-1006 and INV-1007, as InvoiceIT checks them. */
    private void januarySales() {
        String inv1004 = post("C100", "2026-01-06", List.of(invoiceLine("Components", "100", "400.00", "4000", null),
            invoiceLine("Engineering services", "1", "10000.00", "4100", "NT")));
        Map<String, Object> credit = invoiceInput("C100", "2026-01-10", "CREDIT_MEMO", List.of(
            invoiceLine("Returned components", "5", "400.00", null, null)));
        credit.put("originalInvoiceId", inv1004);
        String cm2001 = (String) ok(InvoiceProcesses.SAVE, clerk, credit).get("invoiceId");
        ok(InvoiceProcesses.POST, controller, Map.of("invoiceId", cm2001));
        ok(InvoiceProcesses.APPLY, clerk, Map.of("creditMemoId", cm2001, "invoiceId", inv1004, "amount", "2165.00",
            "applicationDate", "2026-01-10"));
        post("C400", "2026-01-12", List.of(invoiceLine("Components", "1", "50000.00", "4000", null)));
        post("C200", "2026-01-14", List.of(invoiceLine("Engineering services", "1", "18000.00", "4100", null)));
        post("C300", "2026-01-15", List.of(invoiceLine("Components", "50", "500.00", "4000", null)));
        for (String number : List.of("INV-1001", "INV-1002", "INV-1003", "INV-1004", "INV-1005", "INV-1006",
            "INV-1007", "CM-2001")) {
            IDS.put(number, (String) document(number).get("invoiceId"));
        }
    }

    private String post(String customer, String date, List<Map<String, Object>> lines) {
        String id = (String) ok(InvoiceProcesses.SAVE, clerk, invoiceInput(customer, date, null, lines))
            .get("invoiceId");
        ok(InvoiceProcesses.POST, clerk, Map.of("invoiceId", id));
        return id;
    }

    private Map<String, Object> receipt(String customer, String date, String amount, String reference,
        List<Map<String, Object>> applications) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("customerCode", customer);
        input.put("receiptDate", date);
        input.put("amount", amount);
        input.put("method", "ACH");
        input.put("reference", reference);
        input.put("bankAccount", "1010");
        input.put("applications", applications);
        return input;
    }

    private static Map<String, Object> pay(String invoiceId, String amount) {
        return Map.of("invoiceId", invoiceId, "amount", amount);
    }

    private static Map<String, Object> pay(String invoiceId, String amount, String discount) {
        return Map.of("invoiceId", invoiceId, "amount", amount, "discount", discount);
    }

    private Map<String, Object> document(String number) {
        return find(InvoiceEntities.INVOICE_DATASET, "invoiceNo", number).getFirst();
    }

    private void deliver() {
        deliverer.deliverPending().block();
    }

    /** The aging rows by document: open amount in US dollars and bucket. */
    private static Map<String, String> aging(List<Map<String, Object>> rows) {
        Map<String, String> byDocument = new TreeMap<>();
        for (Map<String, Object> row : rows) {
            byDocument.put((String) row.get("documentNo"), row.get("customerCode") + " " + row.get("bucket") + " "
                + amount(row.get("openAmountUsd")).toPlainString());
        }
        return byDocument;
    }

    private static BigDecimal total(List<Map<String, Object>> rows, String field) {
        return rows.stream().map(r -> amount(r.get(field))).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal balance(String account, String through) {
        return report("finance.gl.trial_balance", controller, Map.of("through", through)).stream()
            .filter(r -> account.equals(r.get("accountCode"))).map(r -> amount(r.get("balance"))).findFirst()
            .orElseThrow();
    }

    @Test
    @Order(1)
    void januaryReceiptsAndTheReportsOnTheThirtyFirst() throws Exception {
        Map<String, Map<String, BigDecimal>> expected = expectedDocuments("RCPT-\\d{4}", "receipt");
        // RCPT-0001 … RCPT-0003 in the order of the sample (FIN-AR-007).
        Map<String, Object> first = ok(ReceiptProcesses.RECORD, clerk, receipt("C100", "2026-01-05", "32475.00",
            "Remittance INV-1001", List.of(pay(IDS.get("INV-1001"), "32475.00"))));
        assertThat(first).containsEntry("receiptNo", "RCPT-0001");
        assertThat(amount(first.get("unappliedAmount"))).isEqualByComparingTo("0.00");
        assertThat(postingLines("RCPT-0001")).isEqualTo(expected.get("RCPT-0001"));
        ok(ReceiptProcesses.RECORD, clerk, receipt("C200", "2026-01-16", "24025.00", null,
            List.of(pay(IDS.get("INV-1002"), "24025.00"))));
        assertThat(postingLines("RCPT-0002")).isEqualTo(expected.get("RCPT-0002"));
        // RCPT-0003: 20,000.00 from C300 leaves 10,000.00 of INV-1003 open (FIN-AR-007 acceptance 1).
        Map<String, Object> third = ok(ReceiptProcesses.RECORD, clerk, receipt("C300", "2026-01-25", "20000.00",
            null, List.of(pay(IDS.get("INV-1003"), "20000.00"))));
        assertThat(third).containsEntry("receiptNo", "RCPT-0003");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> applied = (List<Map<String, Object>>) third.get("applications");
        assertThat(amount(applied.getFirst().get("invoiceOpen"))).isEqualByComparingTo("10000.00");
        assertThat(postingLines("RCPT-0003")).isEqualTo(expected.get("RCPT-0003"));
        assertThat(amount(document("INV-1003").get("openAmount"))).isEqualByComparingTo("10000.00");

        // STX-PAY-2512, December's return: a manual entry until F4 brings tax payments; 1010 by exception.
        Map<String, Object> payment = entry("2026-01-20", "Texas sales tax return December 2025", List.of(
            JournalLifecycleIT.line("2200", "3300.00", null, null), JournalLifecycleIT.line("1010", null, "3300.00",
                null)));
        String accountant = inRoles("accountant", FinanceRoles.ACCOUNTANT);
        String paymentId = (String) ok(JournalProcesses.SAVE, accountant, payment).get("journalId");
        ok(JournalProcesses.GRANT_CONTROL_EXCEPTION, controller, Map.of("journalId", paymentId,
            "reason", "Sales tax payment from the operating account until F4"));
        assertThat(ok(JournalProcesses.SUBMIT, accountant, Map.of("journalId", paymentId)))
            .containsEntry("status", "POSTED");

        // The aging on 31 January by due date: FIN-EXP-08 with INV-1005 at its invoice-date dollars until F7.
        List<Map<String, Object>> aging = report("finance.ar.aging", clerk, Map.of("agingDate", "2026-01-31"));
        assertThat(aging(aging)).containsExactlyEntriesOf(new TreeMap<>(Map.of(
            "INV-1003", "C300 1-30 10000.00",
            "INV-1004", "C100 Current 51135.00",
            "INV-1005", "C400 Current 54250.00",
            "INV-1006", "C200 Current 18000.00",
            "INV-1007", "C300 Current 25000.00")));
        assertThat(total(aging, "openAmountUsd")).isEqualByComparingTo("158385.00")
            .isEqualByComparingTo(balance("1200", "2026-01-31"));
        // By invoice date the buckets count from the invoice: INV-1003 is 47 days old.
        assertThat(aging(report("finance.ar.aging", clerk, Map.of("agingDate", "2026-01-31", "basis", "INVOICE"))))
            .containsEntry("INV-1003", "C300 31-60 10000.00").containsEntry("INV-1004", "C100 0-30 51135.00");
        // The open-item statement of C300 (FIN-AR-009 acceptance 1).
        assertThat(aging(report("finance.ar.aging", clerk, Map.of("agingDate", "2026-01-31",
            "customerCode", "C300")))).containsExactlyEntriesOf(new TreeMap<>(Map.of(
                "INV-1003", "C300 1-30 10000.00", "INV-1007", "C300 Current 25000.00")));
        // C100's statement for January: what it owed, what came and went, what it owes.
        List<Map<String, Object>> statement = report("finance.ar.statement", clerk, Map.of("customerCode", "C100",
            "from", "2026-01-01", "to", "2026-01-31"));
        assertThat(statement).extracting(r -> r.get("entry") + " " + r.get("documentNo") + " "
            + amount(r.get("balance")).toPlainString()).containsExactly(
                "OPENING null 32475.00", "RECEIPT RCPT-0001 0.00", "INVOICE INV-1004 53300.00",
                "CREDIT_MEMO CM-2001 51135.00", "CLOSING null 51135.00");

        // Sales tax for January: FIN-EXP-13, and 2200 at 3,135.00 (FIN-TX-006, FIN-TX-008).
        Map<String, String> tax = new TreeMap<>();
        for (Map<String, Object> row : report("finance.tax.sales_tax", clerk, Map.of("from", "2026-01-01",
            "to", "2026-01-31"))) {
            tax.put((String) row.get("taxCode"), amount(row.get("taxableSales")).toPlainString() + " "
                + amount(row.get("exemptSales")).toPlainString() + " " + amount(row.get("taxCollected")).toPlainString()
                + " " + row.get("documents") + (row.get("certificates") == null ? "" : " " + row.get("certificates")));
        }
        assertThat(tax).containsExactlyEntriesOf(new TreeMap<>(Map.of(
            "TX-AUSTIN", "38000.00 0.00 3135.00 CM-2001, INV-1004",
            "TX-RESALE", "0.00 25000.00 0.00 INV-1007 RC-3301",
            "NT", "0.00 10000.00 0.00 INV-1004",
            "OR-NONE", "0.00 18000.00 0.00 INV-1006",
            "EXPORT", "0.00 54250.00 0.00 INV-1005")));
        assertThat(balance("2200", "2026-01-31")).isEqualByComparingTo("-3135.00");
        // The return data: Texas state and local parts, credits apart, the tax due adds up to the same.
        List<Map<String, Object>> returns = report("finance.tax.sales_tax_return", clerk, Map.of("from",
            "2026-01-01", "to", "2026-01-31", "state", "TX"));
        Map<String, String> byJurisdiction = new TreeMap<>();
        for (Map<String, Object> row : returns) {
            byJurisdiction.put(row.get("jurisdiction") + "/" + row.get("category"),
                amount(row.get("sales")).toPlainString() + " " + amount(row.get("credits")).toPlainString() + " "
                    + amount(row.get("taxCollected")).toPlainString() + " "
                    + amount(row.get("taxCredited")).toPlainString() + " " + amount(row.get("taxDue")).toPlainString());
        }
        assertThat(byJurisdiction).containsExactlyEntriesOf(new TreeMap<>(Map.of(
            "TX/TAXABLE", "40000.00 2000.00 2500.00 125.00 2375.00",
            "TX-AUSTIN-LOCAL/TAXABLE", "40000.00 2000.00 800.00 40.00 760.00",
            "null/RESALE", "25000.00 0.00 0.00 0.00 0.00",
            "null/NON_TAXABLE_SERVICE", "10000.00 0.00 0.00 0.00 0.00")));
        assertThat(total(returns, "taxDue")).isEqualByComparingTo("3135.00");

        // The allowance suggestion (FIN-AR-011): 1% of 148,385.00 current and 5% of 10,000.00 against 4,000.00.
        List<Map<String, Object>> allowance = report("finance.ar.allowance_suggestion", controller,
            Map.of("onDate", "2026-01-31"));
        Map<String, Object> totals = allowance.stream().filter(r -> "Total".equals(r.get("bucket"))).findFirst()
            .orElseThrow();
        assertThat(amount(totals.get("suggested"))).isEqualByComparingTo("1983.85");
        assertThat(amount(totals.get("existing"))).isEqualByComparingTo("4000.00");
        assertThat(amount(totals.get("adjustment"))).isEqualByComparingTo("-2016.15");

        // A later receipt leaves the aging on 31 January as it was (FIN-AR-010 acceptance 2).
        ok(ReceiptProcesses.RECORD, clerk, receipt("C100", "2026-02-05", "51135.00", null,
            List.of(pay(IDS.get("INV-1004"), "51135.00"))));
        assertThat(aging(report("finance.ar.aging", clerk, Map.of("agingDate", "2026-01-31"))))
            .isEqualTo(aging(aging));
        assertThat(aging(report("finance.ar.aging", clerk, Map.of("agingDate", "2026-02-05"))))
            .doesNotContainKey("INV-1004");
    }

    @Test
    @Order(2)
    void aReceiptAppliedToTheWrongCustomerIsPutRightWithItsHistory() {
        String acme = post("C100", "2026-01-26", List.of(invoiceLine("Spare parts", "1", "1000.00", "4100", "NT")));
        String cascade = post("C200", "2026-01-26", List.of(invoiceLine("Engineering", "1", "2000.00", "4100",
            null)));
        // Cascade's check, recorded for Acme and applied to Acme's invoice.
        Map<String, Object> wrong = ok(ReceiptProcesses.RECORD, clerk, receipt("C100", "2026-01-27", "1000.00",
            "Check 5521", List.of(pay(acme, "1000.00"))));
        String receiptId = (String) wrong.get("receiptId");
        @SuppressWarnings("unchecked")
        String applicationId = (String) ((List<Map<String, Object>>) wrong.get("applications")).getFirst()
            .get("applicationId");
        String before = clock.instant().toString();
        Map<String, String> agingBefore = aging(report("finance.ar.aging", clerk, Map.of("agingDate", "2026-01-27")));
        clock.advance(Duration.ofMinutes(5));

        // Not to another customer's invoice; not moved while applied; not more than is open.
        assertThat(refused(ReceiptProcesses.APPLY, clerk, Map.of("receiptId", receiptId, "applicationDate",
            "2026-01-28", "applications", List.of(pay(cascade, "1.00"))), 422)).isEqualTo(ReceiptProcesses.NOT_OPEN);
        assertThat(refused(ReceiptProcesses.REASSIGN, controller, Map.of("receiptId", receiptId, "customerCode",
            "C200", "reason", "x"), 422)).isEqualTo(ReceiptProcesses.APPLIED);
        // Moving a payment between customers is not the recording clerk's (lapping, FIN-CT-001).
        assertThat(refused(ReceiptProcesses.REVERSE, clerk, Map.of("applicationId", applicationId,
            "reverseDate", "2026-01-28", "reason", "mine"), 403)).isEqualTo("PERMISSION_DENIED");
        assertThat(refused(ReceiptProcesses.REVERSE, inRoles("clerk", FinanceRoles.RECEIVABLES_CLERK,
            FinanceRoles.CONTROLLER), Map.of("applicationId", applicationId, "reverseDate", "2026-01-28",
            "reason", "mine"), 422)).isEqualTo(ReceiptProcesses.NOT_REVERSIBLE);
        // Taken back on the 28th, moved to Cascade and applied to its invoice.
        Map<String, Object> reversed = ok(ReceiptProcesses.REVERSE, controller, Map.of("applicationId", applicationId,
            "reverseDate", "2026-01-28", "reason", "Cascade's check, not Acme's"));
        assertThat(amount(reversed.get("invoiceOpen"))).isEqualByComparingTo("1000.00");
        assertThat(amount(reversed.get("sourceOpen"))).isEqualByComparingTo("1000.00");
        assertThat(refused(ReceiptProcesses.REVERSE, controller, Map.of("applicationId", applicationId,
            "reverseDate", "2026-01-28", "reason", "again"), 422)).isEqualTo(ReceiptProcesses.NOT_REVERSIBLE);
        ok(ReceiptProcesses.REASSIGN, controller, Map.of("receiptId", receiptId, "customerCode", "C200",
            "reason", "Cascade's check"));
        assertThat(refused(ReceiptProcesses.APPLY, clerk, Map.of("receiptId", receiptId, "applicationDate",
            "2026-01-28", "applications", List.of(pay(cascade, "1000.01"))), 422))
            .isEqualTo(ReceiptProcesses.OVER_APPLIED);
        Map<String, Object> right = ok(ReceiptProcesses.APPLY, clerk, Map.of("receiptId", receiptId,
            "applicationDate", "2026-01-28", "applications", List.of(pay(cascade, "1000.00"))));
        assertThat(amount(right.get("unappliedAmount"))).isEqualByComparingTo("0.00");
        assertThat(amount(read(InvoiceEntities.INVOICE_DATASET, cascade).get("openAmount")))
            .isEqualByComparingTo("1000.00");
        assertThat(amount(read(InvoiceEntities.INVOICE_DATASET, acme).get("openAmount")))
            .isEqualByComparingTo("1000.00");

        // Both actions stay in the history (FIN-AR-008 acceptance 1) …
        assertThat(find(InvoiceEntities.APPLICATION_DATASET, "sourceId", receiptId)).extracting(
            a -> a.get("applicationDate") + " " + amount(a.get("amount")).toPlainString() + " "
                + (a.get("reversesApplicationId") != null))
            .containsExactlyInAnyOrder("2026-01-27 1000.00 false", "2026-01-28 -1000.00 true",
                "2026-01-28 1000.00 false");
        // … and the aging on the 27th, before the correction, reads as it did then.
        assertThat(aging(report("finance.ar.aging", clerk, Map.of("agingDate", "2026-01-27"))))
            .isEqualTo(agingBefore);
        assertThat(aging(reportKnownAt("finance.ar.aging", clerk, Map.of("agingDate", "2026-01-28"), before)))
            .doesNotContainKey(read(InvoiceEntities.INVOICE_DATASET, acme).get("invoiceNo").toString());
        assertThat(aging(report("finance.ar.aging", clerk, Map.of("agingDate", "2026-01-28"))))
            .containsKey(read(InvoiceEntities.INVOICE_DATASET, acme).get("invoiceNo").toString());
        // Receivables and unapplied cash in the ledger agree with the subledger: nothing left unapplied.
        assertThat(postingLines((String) wrong.get("receiptNo"))).containsEntry("1010", new BigDecimal("1000.00"))
            .containsEntry("1200", new BigDecimal("-1000.00")).doesNotContainKey("1250");
    }

    @Test
    @Order(3)
    void discountsUnappliedCashAndVoids() {
        ok(CustomerProcesses.TERMS_SAVE, controller, Map.of("termsCode", "2-10-NET30",
            "description", "2/10 net 30", "netDays", 30, "discountPercent", "2", "discountDays", 10));
        ok(CustomerProcesses.SAVE, controller, Map.of("customerCode", "C500", "legalName", "Prompt Payers LLC",
            "currency", "USD", "termsCode", "2-10-NET30", "taxCode", "OR-NONE"));
        String invoice = post("C500", "2026-01-20", List.of(invoiceLine("Engineering", "1", "1000.00", "4100",
            null)));
        // On the 29th the discount is offered: 2% of 1,000.00, and 980.00 matches the invoice less it.
        assertThat(report("finance.ar.receipt_suggestions", clerk, Map.of("customerCode", "C500",
            "onDate", "2026-01-29", "amount", "980.00"))).singleElement().satisfies(s -> {
                assertThat(s).containsEntry("matched", "AMOUNT_LESS_DISCOUNT").containsEntry("discountUntil",
                    "2026-01-30");
                assertThat(amount(s.get("discountOffered"))).isEqualByComparingTo("20.00");
            });
        // Not after the discount date, nor more than the terms give.
        assertThat(refused(ReceiptProcesses.RECORD, clerk, receipt("C500", "2026-01-31", "980.00", null,
            List.of(pay(invoice, "980.00", "20.00"))), 422)).isEqualTo(ReceiptProcesses.DISCOUNT);
        assertThat(refused(ReceiptProcesses.RECORD, clerk, receipt("C500", "2026-01-29", "979.00", null,
            List.of(pay(invoice, "979.00", "21.00"))), 422)).isEqualTo(ReceiptProcesses.DISCOUNT);
        // Taken: the discount goes to the sales discount account and clears the invoice (FIN-AR-002 acceptance 2).
        Map<String, Object> discounted = ok(ReceiptProcesses.RECORD, clerk, receipt("C500", "2026-01-29", "980.00",
            null, List.of(pay(invoice, "980.00", "20.00"))));
        assertThat(postingLines((String) discounted.get("receiptNo"))).isEqualTo(new TreeMap<>(Map.of(
            "1010", new BigDecimal("980.00"), "4950", new BigDecimal("20.00"), "1200", new BigDecimal("-1000.00"))));
        assertThat(amount(read(InvoiceEntities.INVOICE_DATASET, invoice).get("openAmount")))
            .isEqualByComparingTo("0.00");

        // Part applied, the rest waits as unapplied cash and is applied later.
        String second = post("C500", "2026-01-21", List.of(invoiceLine("Engineering", "1", "500.00", "4100", null)));
        Map<String, Object> partly = ok(ReceiptProcesses.RECORD, clerk, receipt("C500", "2026-01-22", "500.00",
            null, List.of(pay(second, "300.00"))));
        assertThat(amount(partly.get("unappliedAmount"))).isEqualByComparingTo("200.00");
        assertThat(postingLines((String) partly.get("receiptNo"))).containsEntry("1250", new BigDecimal("-200.00"));
        ok(ReceiptProcesses.APPLY, clerk, Map.of("receiptId", partly.get("receiptId"), "applicationDate",
            "2026-01-23", "applications", List.of(pay(second, "200.00"))));
        assertThat(postingLines((String) partly.get("receiptNo"))).doesNotContainKey("1250")
            .containsEntry("1200", new BigDecimal("-500.00"));

        // Money received before the invoice pays it later; the discount counts from when the money came in.
        Map<String, Object> prepaid = ok(ReceiptProcesses.RECORD, clerk, receipt("C500", "2026-01-25", "98.00", null,
            List.of()));
        String later = post("C500", "2026-01-26", List.of(invoiceLine("Engineering", "1", "100.00", "4100", null)));
        assertThat(amount(ok(ReceiptProcesses.APPLY, clerk, Map.of("receiptId", prepaid.get("receiptId"),
            "applicationDate", "2026-02-10", "applications", List.of(pay(later, "98.00", "2.00"))))
            .get("unappliedAmount"))).isEqualByComparingTo("0.00");
        assertThat(amount(read(InvoiceEntities.INVOICE_DATASET, later).get("openAmount")))
            .isEqualByComparingTo("0.00");

        // Without an unapplied cash account a receipt is applied in full.
        settings(null);
        String third = post("C500", "2026-01-23", List.of(invoiceLine("Engineering", "1", "100.00", "4100", null)));
        assertThat(refused(ReceiptProcesses.RECORD, clerk, receipt("C500", "2026-01-24", "150.00", null,
            List.of(pay(third, "100.00"))), 422)).isEqualTo(ReceiptProcesses.UNAPPLIED);
        settings("1250");

        // A receipt recorded in error is voided by someone else once nothing of it is applied; cash goes back.
        Map<String, Object> error = ok(ReceiptProcesses.RECORD, clerk, receipt("C500", "2026-01-24", "75.00", null,
            List.of()));
        assertThat(refused(ReceiptProcesses.VOID, clerk, Map.of("receiptId", error.get("receiptId"), "voidDate",
            "2026-01-25", "reason", "Recorded twice"), 403)).isEqualTo("PERMISSION_DENIED");
        Map<String, Object> voided = ok(ReceiptProcesses.VOID, controller, Map.of("receiptId", error.get("receiptId"),
            "voidDate", "2026-01-25", "reason", "Recorded twice"));
        assertThat(voided).containsEntry("status", ReceiptEntities.VOID);
        assertThat(postingLines((String) error.get("receiptNo"))).isEmpty();
        assertThat(refused(ReceiptProcesses.VOID, controller, Map.of("receiptId", discounted.get("receiptId"),
            "voidDate", "2026-01-30", "reason", "no"), 422)).isEqualTo(ReceiptProcesses.APPLIED);
        // A credit memo's open credit is paid back from the bank (FIN-AR-006): no more than is open of it.
        Map<String, Object> credit = invoiceInput("C500", "2026-01-26", "CREDIT_MEMO", List.of(
            invoiceLine("Goodwill", "1", "50.00", null, null)));
        String creditId = (String) ok(InvoiceProcesses.SAVE, clerk, credit).get("invoiceId");
        String creditNo = (String) ok(InvoiceProcesses.POST, controller, Map.of("invoiceId", creditId))
            .get("invoiceNo");
        Map<String, Object> refund = new LinkedHashMap<>(Map.of("creditMemoId", creditId, "refundDate", "2026-01-27",
            "amount", "50.01", "method", "CHECK", "bankAccount", "1010"));
        assertThat(refused(ReceiptProcesses.REFUND, controller, refund, 422))
            .isEqualTo(ReceiptProcesses.NOT_REFUNDABLE);
        assertThat(refused(ReceiptProcesses.REFUND, clerk, refund, 403)).isEqualTo("PERMISSION_DENIED");
        refund.put("amount", "50.00");
        assertThat(amount(ok(ReceiptProcesses.REFUND, controller, refund).get("creditOpen")))
            .isEqualByComparingTo("0.00");
        assertThat(postingLines(creditNo)).isEqualTo(new TreeMap<>(Map.of("4900", new BigDecimal("50.00"),
            "1010", new BigDecimal("-50.00"))));
        // Receipts are the bank's: an expense account is no bank account.
        Map<String, Object> wrongBank = receipt("C500", "2026-01-24", "10.00", null, List.of());
        wrongBank.put("bankAccount", "6100");
        assertThat(refused(ReceiptProcesses.RECORD, clerk, wrongBank, 422)).isEqualTo(ReceiptProcesses.BANK_ACCOUNT);
    }

    @Test
    @Order(4)
    void writeOffsNeedApprovalAndRecoveriesReopenTheInvoice() {
        String bad = post("C200", "2026-01-27", List.of(invoiceLine("Engineering", "1", "500.00", "4100", null)));
        Map<String, Object> request = ok(WriteOffProcesses.REQUEST, clerk, Map.of("invoiceId", bad,
            "writeOffDate", "2026-01-30", "amount", "500.00", "reason", "Customer insolvent"));
        assertThat(request).containsEntry("status", ReceiptEntities.PENDING).containsEntry("approval", "PENDING");
        assertThat(refused(WriteOffProcesses.REQUEST, clerk, Map.of("invoiceId", bad, "writeOffDate", "2026-01-30",
            "amount", "1.00", "reason", "again"), 422)).isEqualTo(WriteOffProcesses.PENDING);
        // Nothing is posted until approved, and not by the requester (FIN-AR-012, FIN-CT-001).
        assertThat(postingLines((String) read(InvoiceEntities.INVOICE_DATASET, bad).get("invoiceNo")))
            .containsEntry("1200", new BigDecimal("500.00"));
        Map<String, Object> approve = Map.of("requestId", request.get("approvalRequestId"), "decision", "APPROVE");
        assertThat(refused("APPROVAL_DECIDE", inRoles("clerk", FinanceRoles.RECEIVABLES_CLERK, FinanceRoles.APPROVER),
            approve, 422)).isEqualTo("APPROVAL_OWN_REQUEST");
        ok("APPROVAL_DECIDE", inRoles("approver", FinanceRoles.APPROVER), approve);
        deliver();
        Map<String, Object> posted = find(ReceiptEntities.WRITE_OFF_DATASET, "invoiceId", bad).getFirst();
        assertThat(posted).containsEntry("status", ReceiptEntities.POSTED);
        Map<String, Object> invoice = read(InvoiceEntities.INVOICE_DATASET, bad);
        assertThat(invoice).containsEntry("status", InvoiceEntities.WRITTEN_OFF);
        assertThat(amount(invoice.get("openAmount"))).isEqualByComparingTo("0.00");
        // The allowance is debited and receivables credited: the invoice nets to nothing in the ledger.
        assertThat(postingLines((String) invoice.get("invoiceNo"))).isEqualTo(new TreeMap<>(Map.of(
            "1210", new BigDecimal("500.00"), "4100", new BigDecimal("-500.00"))));

        // The customer pays 200.00 after all: recovered onto the invoice by someone else, then received as usual.
        assertThat(refused(WriteOffProcesses.RECOVER, clerk, Map.of("writeOffId", posted.get("writeOffId"),
            "recoveryDate", "2026-01-31", "amount", "200.00", "reason", "mine"), 422))
            .isEqualTo(WriteOffProcesses.NOT_RECOVERABLE);
        Map<String, Object> recovered = ok(WriteOffProcesses.RECOVER, controller, Map.of("writeOffId",
            posted.get("writeOffId"), "recoveryDate", "2026-01-31", "amount", "200.00", "reason", "Paid by trustee"));
        assertThat(amount(recovered.get("invoiceOpen"))).isEqualByComparingTo("200.00");
        assertThat(read(InvoiceEntities.INVOICE_DATASET, bad)).containsEntry("status", InvoiceEntities.POSTED);
        ok(ReceiptProcesses.RECORD, clerk, receipt("C200", "2026-01-31", "200.00", null, List.of(pay(bad, "200.00"))));
        assertThat(refused(WriteOffProcesses.RECOVER, controller, Map.of("writeOffId", posted.get("writeOffId"),
            "recoveryDate", "2026-01-31", "amount", "300.01", "reason", "too much"), 422))
            .isEqualTo(WriteOffProcesses.NOT_RECOVERABLE);

        // A rejected request leaves the invoice as it was.
        String other = post("C200", "2026-01-27", List.of(invoiceLine("Engineering", "1", "300.00", "4100", null)));
        Map<String, Object> second = ok(WriteOffProcesses.REQUEST, clerk, Map.of("invoiceId", other,
            "writeOffDate", "2026-01-30", "amount", "300.00", "reason", "Disputed"));
        ok("APPROVAL_DECIDE", inRoles("approver", FinanceRoles.APPROVER), Map.of("requestId",
            second.get("approvalRequestId"), "decision", "REJECT", "reason", "Collect it"));
        deliver();
        assertThat(find(ReceiptEntities.WRITE_OFF_DATASET, "invoiceId", other).getFirst())
            .containsEntry("status", ReceiptEntities.REJECTED);
        assertThat(amount(read(InvoiceEntities.INVOICE_DATASET, other).get("openAmount")))
            .isEqualByComparingTo("300.00");
    }

    @Test
    @Order(5)
    void anInvoiceOverTheCreditLimitWaitsForApprovalWhenTheControllerSaysSo() {
        // The controller asks for approval over the credit limit; another publishes it (FIN-AR-013, FIN-CT-002).
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("ruleCode", "FIN-AR-CREDIT-LIMIT");
        rule.put("subject", InvoiceProcesses.SUBJECT);
        rule.put("priority", 100);
        rule.put("enabled", true);
        rule.put("condition", Map.of("all", List.of(Map.of("fact", "overCreditLimit", "op", "eq", "value", true))));
        rule.put("levels", List.of(Map.of("permission", "fin.invoice.approve")));
        rule.put("description", "Invoices over the credit limit need approval");
        Map<String, Object> proposed = ok("CONTROL_CHANGE_PROPOSE", controller, Map.of("targetEntity",
            com.jabiz.runtime.approval.ApprovalEntities.RULE, "values", rule, "reason", "Credit control"));
        ok("CONTROL_CHANGE_PUBLISH", as("controller-2", "control.publish"), Map.of("changeId",
            proposed.get("changeId")));

        ok(CustomerProcesses.SAVE, controller, Map.of("customerCode", "C600", "legalName", "Limited Inc.",
            "currency", "USD", "termsDays", 30, "taxCode", "OR-NONE", "creditLimit", 100000));
        Map<String, Object> within = ok(InvoiceProcesses.POST, clerk, Map.of("invoiceId", ok(InvoiceProcesses.SAVE,
            clerk, invoiceInput("C600", "2026-01-28", null, List.of(invoiceLine("Engineering", "1", "95000.00",
                "4100", null)))).get("invoiceId")));
        assertThat(within).containsEntry("status", "POSTED");
        String over = (String) ok(InvoiceProcesses.SAVE, clerk, invoiceInput("C600", "2026-01-29", null, List.of(
            invoiceLine("Engineering", "1", "10000.00", "4100", null)))).get("invoiceId");
        Map<String, Object> held = ok(InvoiceProcesses.POST, clerk, Map.of("invoiceId", over));
        assertThat(held).containsEntry("status", "DRAFT").containsEntry("approval", "PENDING");
        assertThat(held.get("invoiceNo")).isNull();
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) held.get("warnings");
        assertThat(warnings).singleElement().satisfies(w -> assertThat(w).startsWith(InvoiceProcesses.CREDIT_LIMIT));
        Object requestId = read(InvoiceEntities.INVOICE_DATASET, over).get("approvalRequestId");
        ok("APPROVAL_DECIDE", inRoles("approver", FinanceRoles.APPROVER), Map.of("requestId", requestId,
            "decision", "APPROVE"));
        deliver();
        assertThat(read(InvoiceEntities.INVOICE_DATASET, over)).containsEntry("approval", "APPROVED");
        // Posted again, it finds the approval of the same content and takes the next number.
        Map<String, Object> posted = ok(InvoiceProcesses.POST, clerk, Map.of("invoiceId", over));
        assertThat(posted).containsEntry("status", "POSTED").containsEntry("approval", "APPROVED");
        assertThat(Integer.parseInt(((String) posted.get("invoiceNo")).substring(4)))
            .isEqualTo(Integer.parseInt(((String) within.get("invoiceNo")).substring(4)) + 1);
    }

    @Test
    @Order(6)
    void theFebruaryRunMakesOneInvoicePerTemplate() {
        Object template = commit(ReceiptEntities.RECURRING_DATASET, Map.of("templateCode", "SUPPORT-C200",
            "customerCode", "C200", "description", "Monthly support", "invoiceDay", 1, "startDate", "2026-01-01",
            "active", true)).get("id");
        commit(ReceiptEntities.RECURRING_LINE_DATASET, Map.of("templateId", template, "lineNo", 1,
            "description", "Support, monthly", "quantity", 1, "unitPrice", "1500.00", "revenueAccount", "4100"));
        Map<String, Object> first = ok(RecurringInvoiceProcesses.RUN, clerk, Map.of("date", "2026-02-15"));
        assertThat(first).containsEntry("periodKey", "2026-02");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> made = (List<Map<String, Object>>) first.get("invoices");
        assertThat(made).singleElement().satisfies(m -> assertThat(m).containsEntry("invoiceDate", "2026-02-01")
            .containsEntry("customerCode", "C200"));
        // Run twice, one invoice (FIN-AR-014 acceptance 1).
        assertThat(ok(RecurringInvoiceProcesses.RUN, clerk, Map.of("date", "2026-02-20")).get("invoices"))
            .isEqualTo(List.of());
        assertThat(find(InvoiceEntities.INVOICE_DATASET, "recurringKey", "SUPPORT-C200/2026-02")).singleElement()
            .satisfies(i -> assertThat(i).containsEntry("source", "RECURRING").containsEntry("status", "DRAFT"));
        assertThat(ok(InvoiceProcesses.POST, clerk, Map.of("invoiceId", made.getFirst().get("invoiceId"))))
            .containsEntry("status", "POSTED");

        // A template cannot take tax off: the posting checks the lines' codes as saving a draft does.
        Object exempt = commit(ReceiptEntities.RECURRING_DATASET, Map.of("templateCode", "PARTS-C100",
            "customerCode", "C100", "description", "Monthly parts", "invoiceDay", 5, "startDate", "2026-03-01",
            "active", true)).get("id");
        commit(ReceiptEntities.RECURRING_LINE_DATASET, Map.of("templateId", exempt, "lineNo", 1,
            "description", "Parts", "quantity", 1, "unitPrice", "100.00", "revenueAccount", "4000",
            "taxCode", "TX-RESALE"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> march = (List<Map<String, Object>>) ok(RecurringInvoiceProcesses.RUN, clerk,
            Map.of("date", "2026-03-15")).get("invoices");
        Map<String, Object> parts = march.stream().filter(m -> "PARTS-C100".equals(m.get("templateCode")))
            .findFirst().orElseThrow();
        assertThat(refused(InvoiceProcesses.POST, clerk, Map.of("invoiceId", parts.get("invoiceId")), 422))
            .isEqualTo(InvoiceProcesses.TAX_RESTRICTED);

        // On any day the subledger's open items add up to the receivables account.
        for (String day : List.of("2026-01-05", "2026-01-15", "2026-01-28", "2026-01-31", "2026-02-28")) {
            assertThat(total(report("finance.ar.aging", clerk, Map.of("agingDate", day)), "openAmountUsd")).as(day)
                .isEqualByComparingTo(balance("1200", day));
        }
        assertOnlyInserted("fi_receipt_version", "fi_write_off_version", "fi_application_version",
            "fi_invoice_version", "fi_recurring_invoice_version", "fi_recurring_invoice_line_version",
            "fi_posting_version");
    }

    private Map<String, Object> commit(String dataset, Map<String, Object> attributes) {
        return post("/api/datasets/" + dataset + "/commit", clerk, Map.of("changes", List.of(
            Map.of("action", "INSERT", "attributes", attributes)))).expectStatus().isOk().expectBody(LIST)
            .returnResult().getResponseBody().getFirst();
    }
}
