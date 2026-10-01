package com.jabiz.finance.it;

import com.jabiz.finance.ar.ArSettingsProcesses;
import com.jabiz.finance.ar.CustomerProcesses;
import com.jabiz.finance.ar.InvoiceEntities;
import com.jabiz.finance.ar.InvoiceProcesses;
import com.jabiz.finance.calc.SalesTax;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.finance.gl.JournalEntities;
import com.jabiz.finance.setup.FinanceRoles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invoices and credit memos on the sample company's books (ROADMAP F3b): the legacy open items brought over against
 * the opening receivables, the January sales posted as FIN-EXP-02 has them (INV-1004 … INV-1007, CM-2001 applied),
 * each general ledger line opening its document, the tax explained, and the rules around them: posted documents do not
 * change, voids reverse, credit limits warn, certificates and tax codes are checked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InvoiceIT extends FinanceItSupport {

    /** The schema lives as long as the class: the books are opened once. */
    private static boolean loaded;
    private static final Map<String, String> IDS = new HashMap<>();

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
        ok(ArSettingsProcesses.SET, controller, Map.of("receivableAccount", "1200", "allowanceAccount", "1210",
            "returnsAccount", "4900", "salesTaxAccount", "2200"));
    }

    @Test
    @Order(1)
    void theLegacyOpenItemsMustAddUpToTheOpeningReceivables() {
        Map<String, Object> wrong = importCsv("finance.open_receivables", controller, """
            document,customer,date,due,amount_usd
            INV-1001,C100,2025-12-05,2026-01-04,32475.00
            """, "commit", null, null, 422);
        assertThat(issues(wrong)).contains("1:" + InvoiceProcesses.OPENING_TOTAL);
        // A legacy number the new invoices will give out would collide with them.
        assertThat(issues(importCsv("finance.open_receivables", controller, sampleText("open-receivables.csv")
            .replace("INV-1003,", "INV-1004,"), "commit", null, null, 422))).contains("1:"
            + InvoiceProcesses.OPENING_NUMBER);

        // The legacy system had Acme Robotics twice: its open item comes in under the customer it was merged into.
        ok("FIN_MIGRATION_DECIDE", controller, Map.of("kind", "CUSTOMER", "legacyValue", "C100-OLD",
            "decidedValue", "C100", "reason", "Acme Robotics twice in the legacy system"));
        Map<String, Object> report = importCsv("finance.open_receivables", controller,
            sampleText("open-receivables.csv").replace("INV-1001,C100,", "INV-1001,C100-OLD,"), "commit", null, null,
            200);
        assertThat(report).containsEntry("committed", true);
        assertThat(document("INV-1003")).containsEntry("customerCode", "C300").containsEntry("source", "OPENING")
            .containsEntry("status", "POSTED").containsEntry("dueDate", "2026-01-14");
        assertThat(amount(document("INV-1003").get("openAmount"))).isEqualByComparingTo("30000.00");
        assertThat(document("INV-1001")).containsEntry("customerCode", "C100");
        // Nothing posted again: the opening entry has the receivables already.
        assertThat(postingLines("INV-1001")).isEmpty();
        List<Map<String, Object>> reconciliation = report("finance.migration.reconciliation", controller, Map.of());
        assertThat(reconciliation.stream().filter(r -> "OPEN_ITEMS".equals(r.get("section")))).singleElement()
            .satisfies(row -> {
                assertThat(amount(row.get("sourceAmount"))).isEqualByComparingTo("86500.00");
                assertThat(amount(row.get("difference"))).isEqualByComparingTo("0.00");
            });
    }

    @Test
    @Order(2)
    void theJanuarySalesPostAsTheExpectedResults() throws Exception {
        Map<String, Map<String, BigDecimal>> invoices = expectedDocuments("INV-\\d{4}", "invoice");
        Map<String, Map<String, BigDecimal>> credits = expectedDocuments("CM-\\d{4}", "credit memo");

        // INV-1004: components taxed in Austin, engineering services not (FIN-AR-003, FIN-TX-002, FIN-TX-003).
        String inv1004 = draft("C100", "2026-01-06", null, List.of(
            line("Components", "100", "400.00", "4000", null),
            line("Engineering services", "1", "10000.00", "4100", "NT")));
        Map<String, Object> posted = ok(InvoiceProcesses.POST, clerk, Map.of("invoiceId", inv1004));
        assertThat(posted).containsEntry("invoiceNo", "INV-1004").containsEntry("status", "POSTED")
            .containsEntry("dueDate", "2026-02-05");
        assertThat(amount(posted.get("taxTotal"))).isEqualByComparingTo("3300.00");
        assertThat(amount(posted.get("total"))).isEqualByComparingTo("53300.00");
        assertThat(postingLines("INV-1004")).isEqualTo(invoices.get("INV-1004"));
        // Every general ledger line opens the invoice (FIN-GL-021).
        assertThat(query("SELECT DISTINCT t.source_entity, t.source_id FROM fi_posting_version p "
            + "JOIN ledger_transaction_version t ON t.transaction_id = p.transaction_id WHERE p.document_no = ?",
            "INV-1004")).singleElement().satisfies(row -> {
                assertThat(row.get("source_entity")).isEqualTo(InvoiceEntities.INVOICE);
                assertThat(row.get("source_id")).isEqualTo(inv1004);
            });
        assertThat(find(JournalEntities.POSTING_DATASET, "documentNo", "INV-1004")).singleElement()
            .satisfies(p -> assertThat(p).containsEntry("source", "AR").containsEntry("sourceEntity", "FinInvoice"));
        // Why 3,300.00: the base, each jurisdiction's rate and the day it took effect (FIN-UI-007).
        List<String> explanation = new ArrayList<>();
        for (Map<String, Object> row : find(InvoiceEntities.TAX_DATASET, "invoiceId", inv1004)) {
            explanation.add(row.get("jurisdiction") == null
                ? "line " + row.get("lineNo") + " " + row.get("taxCode") + " " + row.get("taxKind") + " "
                    + amount(row.get("tax")).toPlainString()
                : row.get("jurisdiction") + " " + amount(row.get("base")).toPlainString() + " "
                    + amount(row.get("ratePercent")).stripTrailingZeros().toPlainString() + "% from "
                    + row.get("rateFrom") + " = " + amount(row.get("tax")).toPlainString());
        }
        assertThat(explanation).containsExactlyInAnyOrder("TX 40000.00 6.25% from 2025-01-01 = 2500.00",
            "TX-AUSTIN-LOCAL 40000.00 2% from 2025-01-01 = 800.00", "line 1 TX-AUSTIN TAXABLE 3300.00",
            "line 2 NT NON_TAXABLE 0.00");
        // A posted invoice is not changed: corrections are credit memos (FIN-AR-004).
        Map<String, Object> change = new HashMap<>(invoiceInput("C100", "2026-01-06", null, List.of(
            line("Components", "100", "500.00", "4000", null))));
        change.put("invoiceId", inv1004);
        assertThat(refused(InvoiceProcesses.SAVE, clerk, change, 422)).isEqualTo(InvoiceProcesses.NOT_DRAFT);

        // CM-2001: 2,000.00 of returned components, tax at INV-1004's rates (FIN-AR-006, FIN-TX-005), applied.
        Map<String, Object> credit = invoiceInput("C100", "2026-01-10", "CREDIT_MEMO", List.of(
            line("Returned components", "5", "400.00", null, null)));
        credit.put("originalInvoiceId", inv1004);
        String cm2001 = (String) ok(InvoiceProcesses.SAVE, clerk, credit).get("invoiceId");
        // Writing receivables down is the controller's, and not the preparer's (FIN-CT-001).
        assertThat(refused(InvoiceProcesses.POST, clerk, Map.of("invoiceId", cm2001), 422))
            .isEqualTo(InvoiceProcesses.CREDIT_RESTRICTED);
        Map<String, Object> creditPosted = ok(InvoiceProcesses.POST, controller, Map.of("invoiceId", cm2001));
        assertThat(creditPosted).containsEntry("invoiceNo", "CM-2001");
        assertThat(amount(creditPosted.get("taxTotal"))).isEqualByComparingTo("165.00");
        assertThat(postingLines("CM-2001")).isEqualTo(credits.get("CM-2001"));
        assertThat(refused(InvoiceProcesses.APPLY, clerk, Map.of("creditMemoId", cm2001, "invoiceId", inv1004,
            "amount", "2165.01", "applicationDate", "2026-01-10"), 422)).isEqualTo(InvoiceProcesses.APPLY_REFUSED);
        Map<String, Object> applied = ok(InvoiceProcesses.APPLY, clerk, Map.of("creditMemoId", cm2001,
            "invoiceId", inv1004, "amount", "2165.00", "applicationDate", "2026-01-10"));
        assertThat(amount(applied.get("invoiceOpen"))).isEqualByComparingTo("51135.00");
        assertThat(amount(applied.get("creditOpen"))).isEqualByComparingTo("0.00");
        assertThat(amount(document("INV-1004").get("openAmountUsd"))).isEqualByComparingTo("51135.00");
        // No more is credited than is left of the invoice: 38,000.00 of components and 3,135.00 of tax.
        Map<String, Object> tooMuch = invoiceInput("C100", "2026-01-11", "CREDIT_MEMO", List.of(
            line("Returned components", "96", "400.00", null, null)));
        tooMuch.put("originalInvoiceId", inv1004);
        String excess = (String) ok(InvoiceProcesses.SAVE, clerk, tooMuch).get("invoiceId");
        assertThat(refused(InvoiceProcesses.POST, controller, Map.of("invoiceId", excess), 422))
            .isEqualTo(InvoiceProcesses.EXCEEDS);
        ok(InvoiceProcesses.DELETE, clerk, Map.of("invoiceId", excess));

        // INV-1005: EUR 50,000.00 at 1.0850 to a German customer, no US sales tax.
        String inv1005 = draft("C400", "2026-01-12", null, List.of(line("Components", "1", "50000.00", "4000", null)));
        Map<String, Object> euro = ok(InvoiceProcesses.POST, clerk, Map.of("invoiceId", inv1005));
        assertThat(euro).containsEntry("invoiceNo", "INV-1005");
        assertThat(amount(euro.get("total"))).isEqualByComparingTo("50000.00");
        assertThat(amount(euro.get("totalUsd"))).isEqualByComparingTo("54250.00");
        assertThat(document("INV-1005")).containsEntry("currency", "EUR");
        assertThat(postingLines("INV-1005")).isEqualTo(invoices.get("INV-1005"));

        // INV-1006: services shipped to Oregon, no sales tax (FIN-TX-002 acceptance 2).
        String inv1006 = draft("C200", "2026-01-14", null, List.of(
            line("Engineering services", "1", "18000.00", "4100", null)));
        assertThat(amount(ok(InvoiceProcesses.POST, clerk, Map.of("invoiceId", inv1006)).get("taxTotal")))
            .isEqualByComparingTo("0.00");
        assertThat(postingLines("INV-1006")).isEqualTo(invoices.get("INV-1006"));

        // INV-1007: components for resale, exempt with certificate RC-3301, which the invoice names (FIN-TX-004).
        String inv1007 = draft("C300", "2026-01-15", null, List.of(line("Components", "50", "500.00", "4000", null)));
        assertThat(ok(InvoiceProcesses.POST, clerk, Map.of("invoiceId", inv1007))).containsEntry("invoiceNo",
            "INV-1007");
        assertThat(postingLines("INV-1007")).isEqualTo(invoices.get("INV-1007"));
        assertThat(find(InvoiceEntities.TAX_DATASET, "invoiceId", inv1007)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("taxKind", "EXEMPT").containsEntry("reason", "RESALE")
                .containsEntry("certificateNo", "RC-3301"));
        IDS.put("INV-1004", inv1004);
    }

    @Test
    @Order(3)
    void aPostedDocumentIsVoidedByReversingItsEntryUnlessSomethingWasAppliedToIt() {
        String id = draft("C200", "2026-01-20", null, List.of(line("Engineering services", "1", "1000.00", "4100",
            null)));
        String number = (String) ok(InvoiceProcesses.POST, clerk, Map.of("invoiceId", id)).get("invoiceNo");
        assertThat(postingLines(number)).containsEntry("1200", new BigDecimal("1000.00"));
        assertThat(refused(InvoiceProcesses.VOID, controller, Map.of("invoiceId", id, "voidDate", "2026-01-19",
            "reason", "early"), 422)).isEqualTo(InvoiceProcesses.INVALID_VALUE);
        // Voiding is the controller's, and never the preparer's.
        assertThat(refused(InvoiceProcesses.VOID, clerk, Map.of("invoiceId", id, "voidDate", "2026-01-21",
            "reason", "mine"), 403)).isEqualTo("PERMISSION_DENIED");
        Map<String, Object> voided = ok(InvoiceProcesses.VOID, controller, Map.of("invoiceId", id, "voidDate",
            "2026-01-21", "reason", "Billed in error"));
        assertThat(voided).containsEntry("status", "VOID");
        // The reversal nets the document out; its number is kept, the next invoice gets the next one.
        assertThat(postingLines(number)).isEmpty();
        assertThat(find(JournalEntities.POSTING_DATASET, "documentNo", number)).hasSize(2);
        assertThat(refused(InvoiceProcesses.VOID, controller, Map.of("invoiceId", id, "voidDate", "2026-01-22",
            "reason", "again"), 422)).isEqualTo(InvoiceProcesses.NOT_POSTED);
        // INV-1004 had a credit applied: it is not voided.
        assertThat(refused(InvoiceProcesses.VOID, controller, Map.of("invoiceId", IDS.get("INV-1004"), "voidDate",
            "2026-01-21", "reason", "no"), 422)).isEqualTo(InvoiceProcesses.APPLIED);
        // The controller's own credit memo is posted by someone else.
        Map<String, Object> own = invoiceInput("C200", "2026-01-21", "CREDIT_MEMO", List.of(
            line("Goodwill", "1", "10.00", null, null)));
        String ownId = (String) ok(InvoiceProcesses.SAVE, controller, own).get("invoiceId");
        assertThat(refused(InvoiceProcesses.POST, controller, Map.of("invoiceId", ownId), 422))
            .isEqualTo(InvoiceProcesses.OWN_DOCUMENT);
    }

    @Test
    @Order(4)
    void theRulesAroundPostingHold() {
        // A credit limit exceeded warns (FIN-AR-013): C200 owes 24,025.00 + 18,000.00.
        ok(CustomerProcesses.SAVE, controller, Map.of("customerCode", "C200", "creditLimit", 50000));
        String id = draft("C200", "2026-01-22", null, List.of(line("Engineering services", "1", "10000.00", "4100",
            null)));
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) ok(InvoiceProcesses.POST, clerk, Map.of("invoiceId", id))
            .get("warnings");
        assertThat(warnings).singleElement().satisfies(w -> assertThat(w).startsWith(InvoiceProcesses.CREDIT_LIMIT));

        // A resale customer without a valid certificate: the posting is refused (FIN-TX-004 acceptance 2).
        ok(CustomerProcesses.SAVE, controller, Map.of("customerCode", "C700", "legalName", "Paperless Resale",
            "currency", "USD", "termsDays", 30, "taxCode", "TX-RESALE"));
        String resale = draft("C700", "2026-01-22", null, List.of(line("Components", "1", "100.00", "4000", null)));
        assertThat(refused(InvoiceProcesses.POST, clerk, Map.of("invoiceId", resale), 422))
            .isEqualTo(SalesTax.CERTIFICATE_MISSING);
        ok(InvoiceProcesses.DELETE, clerk, Map.of("invoiceId", resale));

        // A clerk cannot take tax off an invoice: another ship-to code, or a no-tax code on goods.
        assertThat(refused(InvoiceProcesses.SAVE, clerk, invoiceInput("C100", "2026-01-22", null, List.of(
            line("Components", "1", "100.00", "4000", null)), "EXPORT"), 422))
            .isEqualTo(InvoiceProcesses.TAX_RESTRICTED);
        assertThat(refused(InvoiceProcesses.SAVE, clerk, invoiceInput("C100", "2026-01-22", null, List.of(
            line("Components", "1", "100.00", "4000", "TX-RESALE")), null), 422))
            .isEqualTo(InvoiceProcesses.TAX_RESTRICTED);
        // Lines post to revenue accounts only: not the bank, not an expense.
        for (String account : List.of("1010", "6100", "2000")) {
            assertThat(refused(InvoiceProcesses.SAVE, clerk, invoiceInput("C100", "2026-01-22", "CREDIT_MEMO",
                List.of(line("Refund", "1", "100.00", account, null))), 422)).as(account)
                .isEqualTo(InvoiceProcesses.ACCOUNT);
        }
        // The open items were brought over once.
        assertThat(issues(importCsv("finance.open_receivables", controller, """
            document,customer,date,due,amount_usd
            LEGACY-9,C100,2025-12-05,2026-01-04,86500.00
            """, "commit", null, null, 422))).contains("1:" + InvoiceProcesses.OPENING_DONE);

        // Receivables closed for January: nothing posts there, though the general ledger is open.
        ok("FIN_PERIOD_SET_SUBLEDGER_STATE", controller, Map.of("periodKey", "2026-01", "subledger", "AR",
            "status", "CLOSED"));
        String late = draft("C100", "2026-01-23", null, List.of(line("Components", "1", "100.00", "4000", null)));
        assertThat(refused(InvoiceProcesses.POST, clerk, Map.of("invoiceId", late), 422))
            .isEqualTo("FIN_SUBLEDGER_CLOSED");
        ok("FIN_PERIOD_SET_SUBLEDGER_STATE", controller, Map.of("periodKey", "2026-01", "subledger", "AR",
            "status", "OPEN"));
        Map<String, Object> latePosted = ok(InvoiceProcesses.POST, clerk, Map.of("invoiceId", late));
        assertThat(latePosted).containsEntry("status", "POSTED");

        // A draft saved again keeps its lines' numbers; the next invoice gets the next number, without gaps.
        String twice = draft("C100", "2026-01-23", null, List.of(line("Components", "1", "100.00", "4000", null),
            line("Components", "2", "100.00", "4000", null)));
        Map<String, Object> again = new HashMap<>(invoiceInput("C100", "2026-01-23", null, List.of(
            line("Components", "3", "100.00", "4000", null), line("Components", "4", "100.00", "4000", null))));
        again.put("invoiceId", twice);
        assertThat(amount(ok(InvoiceProcesses.SAVE, clerk, again).get("subtotal"))).isEqualByComparingTo("700.00");
        String previous = (String) latePosted.get("invoiceNo");
        String next = (String) ok(InvoiceProcesses.POST, clerk, Map.of("invoiceId", twice)).get("invoiceNo");
        assertThat(Integer.parseInt(next.substring(4))).isEqualTo(Integer.parseInt(previous.substring(4)) + 1);

        // A credit is applied within one customer, and not into a closed period.
        Map<String, Object> other = invoiceInput("C200", "2026-01-23", "CREDIT_MEMO", List.of(
            line("Allowance", "1", "50.00", null, null)));
        String otherCredit = (String) ok(InvoiceProcesses.SAVE, clerk, other).get("invoiceId");
        ok(InvoiceProcesses.POST, controller, Map.of("invoiceId", otherCredit));
        assertThat(refused(InvoiceProcesses.APPLY, clerk, Map.of("creditMemoId", otherCredit, "invoiceId", twice,
            "amount", "10.00", "applicationDate", "2026-01-23"), 422)).isEqualTo(InvoiceProcesses.APPLY_REFUSED);
        ok("FIN_PERIOD_SET_SUBLEDGER_STATE", controller, Map.of("periodKey", "2026-01", "subledger", "AR",
            "status", "CLOSED"));
        assertThat(refused(InvoiceProcesses.APPLY, clerk, Map.of("creditMemoId", otherCredit, "invoiceId", id,
            "amount", "10.00", "applicationDate", "2026-01-23"), 422)).isEqualTo("FIN_SUBLEDGER_CLOSED");
        ok("FIN_PERIOD_SET_SUBLEDGER_STATE", controller, Map.of("periodKey", "2026-01", "subledger", "AR",
            "status", "OPEN"));
        ok(InvoiceProcesses.APPLY, clerk, Map.of("creditMemoId", otherCredit, "invoiceId", id, "amount", "10.00",
            "applicationDate", "2026-01-23"));
        // An applied credit memo is not voided either; nor is an opening item.
        assertThat(refused(InvoiceProcesses.VOID, controller, Map.of("invoiceId", otherCredit, "voidDate",
            "2026-01-24", "reason", "no"), 422)).isEqualTo(InvoiceProcesses.APPLIED);
        assertThat(refused(InvoiceProcesses.VOID, controller, Map.of("invoiceId", document("INV-1002").get("invoiceId"),
            "voidDate", "2026-01-24", "reason", "no"), 422)).isEqualTo(InvoiceProcesses.NOT_POSTED);

        assertOnlyInserted("fi_invoice_version", "fi_invoice_line_version", "fi_invoice_tax_version",
            "fi_application_version", "fi_posting_version");
    }

    private String draft(String customer, String date, String kind, List<Map<String, Object>> lines) {
        return (String) ok(InvoiceProcesses.SAVE, clerk, invoiceInput(customer, date, kind, lines)).get("invoiceId");
    }

    private static Map<String, Object> invoiceInput(String customer, String date, String kind,
        List<Map<String, Object>> lines) {
        return invoiceInput(customer, date, kind, lines, null);
    }

    private static Map<String, Object> invoiceInput(String customer, String date, String kind,
        List<Map<String, Object>> lines, String taxCode) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("customerCode", customer);
        input.put("invoiceDate", date);
        if (kind != null) {
            input.put("kind", kind);
        }
        if (taxCode != null) {
            input.put("taxCode", taxCode);
        }
        input.put("lines", lines);
        return input;
    }

    private static Map<String, Object> line(String description, String quantity, String price, String account,
        String taxCode) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("description", description);
        line.put("quantity", quantity);
        line.put("unitPrice", price);
        if (account != null) {
            line.put("revenueAccount", account);
        }
        if (taxCode != null) {
            line.put("taxCode", taxCode);
        }
        return line;
    }

    private Map<String, Object> document(String number) {
        return find(InvoiceEntities.INVOICE_DATASET, "invoiceNo", number).getFirst();
    }
}
