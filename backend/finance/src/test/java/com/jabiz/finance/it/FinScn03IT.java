package com.jabiz.finance.it;

import com.jabiz.finance.ar.CustomerProcesses;
import com.jabiz.finance.ar.InvoiceDocuments;
import com.jabiz.finance.ar.InvoiceEntities;
import com.jabiz.finance.ar.InvoiceProcesses;
import com.jabiz.finance.ar.ReceiptProcesses;
import com.jabiz.finance.setup.FinanceRoles;
import com.jabiz.runtime.task.MailMessage;
import com.jabiz.runtime.task.NotificationSender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance scenario FIN-SCN-03, invoice to cash with sales tax (docs/finance-requirements/30-acceptance-scenarios.md),
 * steps 1 to 6 through the API as the receivables clerk, the accountant and, where a second person is required, the
 * controller. The general ledger lines are FIN-EXP-02's, the aging FIN-EXP-08's (INV-1005 at its invoice-date dollars
 * until the revaluation of F7) and the sales tax report FIN-EXP-13's, read from the requirements in the test only.
 * The invoice PDF is e-mailed through a sender that keeps the messages, and a later reprint is the copy that was sent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {"jabiz.mail.enabled=true",
    "jabiz.mail.from=billing@northwind.example", "spring.mail.host=localhost"})
class FinScn03IT extends FinanceItSupport {

    /** Keeps what would go out by e-mail. */
    @TestConfiguration
    static class Mailbox {

        static final List<MailMessage> SENT = new CopyOnWriteArrayList<>();

        @Bean
        @Primary
        NotificationSender keptMail() {
            return new NotificationSender() {
                @Override
                public void send(String to, String subject, String body) {
                    SENT.add(new MailMessage(to, subject, body, List.of()));
                }

                @Override
                public void send(MailMessage message) {
                    SENT.add(message);
                }
            };
        }
    }

    private static final String ACME_PAYABLES = "ap@acme-robotics.example";

    private String clerk;
    private String controller;

    private String post(String customer, String date, List<Map<String, Object>> lines) {
        String id = (String) ok(InvoiceProcesses.SAVE, clerk, invoiceInput(customer, date, null, lines))
            .get("invoiceId");
        ok(InvoiceProcesses.POST, clerk, Map.of("invoiceId", id));
        return id;
    }

    private Map<String, Object> receipt(String customer, String date, String amount, String invoiceId) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("customerCode", customer);
        input.put("receiptDate", date);
        input.put("amount", amount);
        input.put("method", "ACH");
        input.put("bankAccount", "1010");
        input.put("applications", List.of(Map.of("invoiceId", invoiceId, "amount", amount)));
        return ok(ReceiptProcesses.RECORD, clerk, input);
    }

    private String id(String number) {
        return (String) find(InvoiceEntities.INVOICE_DATASET, "invoiceNo", number).getFirst().get("invoiceId");
    }

    private static List<MailMessage> awaitMail(String to) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            List<MailMessage> mail = Mailbox.SENT.stream().filter(m -> m.to().equals(to)).toList();
            if (!mail.isEmpty()) {
                return mail;
            }
            Thread.sleep(100);
        }
        return List.of();
    }

    @Test
    void invoiceToCashWithSalesTax() throws Exception {
        clerk = inRoles("clerk", FinanceRoles.RECEIVABLES_CLERK);
        controller = inRoles("controller", FinanceRoles.CONTROLLER);
        String accountant = inRoles("accountant", FinanceRoles.ACCOUNTANT);
        openReceivables();
        companyProfile();
        // The sample's customer file has no e-mail addresses: Acme's payables contact is master data kept here.
        ok(CustomerProcesses.SAVE, clerk, Map.of("customerCode", "C100", "contactName", "Dana Fox",
            "contactEmail", ACME_PAYABLES));
        Map<String, Map<String, BigDecimal>> receipts = expectedDocuments("RCPT-\\d{4}", "receipt");
        Map<String, Map<String, BigDecimal>> invoices = expectedDocuments("INV-\\d{4}", "invoice");
        Map<String, Map<String, BigDecimal>> credits = expectedDocuments("CM-\\d{4}", "credit memo");

        // 1. RCPT-0001 is recorded and applied to INV-1001.
        assertThat(receipt("C100", "2026-01-05", "32475.00", id("INV-1001"))).containsEntry("receiptNo", "RCPT-0001");
        assertThat(postingLines("RCPT-0001")).isEqualTo(receipts.get("RCPT-0001"));

        // 2. INV-1004: the components taxed in Austin, the service line not; its PDF is e-mailed to Acme.
        String inv1004 = post("C100", "2026-01-06", List.of(invoiceLine("Components", "100", "400.00", "4000", null),
            invoiceLine("Engineering services", "1", "10000.00", "4100", "NT")));
        Map<String, Object> posted = read(InvoiceEntities.INVOICE_DATASET, inv1004);
        assertThat(posted).containsEntry("invoiceNo", "INV-1004");
        assertThat(amount(posted.get("taxTotal"))).isEqualByComparingTo("3300.00");
        assertThat(postingLines("INV-1004")).isEqualTo(invoices.get("INV-1004"));
        Map<String, Object> sent = ok(InvoiceDocuments.SEND, clerk, Map.of("invoiceId", inv1004));
        assertThat(sent).containsEntry("documentNo", "INV-1004").containsEntry("addresses", List.of(ACME_PAYABLES));
        List<MailMessage> mail = awaitMail(ACME_PAYABLES);
        assertThat(mail).singleElement().satisfies(m -> {
            assertThat(m.subject()).isEqualTo("Invoice INV-1004");
            assertThat(m.attachments()).singleElement().satisfies(a -> {
                assertThat(a.fileName()).isEqualTo("INV-1004.pdf");
                assertThat(a.contentType()).isEqualTo("application/pdf");
            });
        });
        byte[] emailed = mail.getFirst().attachments().getFirst().content();
        assertThat(pdfText(emailed)).contains("INV-1004", "Acme Robotics, Inc.", "3,300.00", "53,300.00",
            "Feb 5, 2026", "Lakeside National Bank");
        // Reprinted later by the accountant: the copy that was sent, byte for byte (FIN-AR-005 acceptance 1).
        assertThat(documentPdf((String) sent.get("runId"), accountant)).isEqualTo(emailed);

        // 3. CM-2001 for the returned components, posted by someone other than its preparer, applied to INV-1004.
        Map<String, Object> credit = invoiceInput("C100", "2026-01-10", "CREDIT_MEMO", List.of(
            invoiceLine("Returned components", "5", "400.00", null, null)));
        credit.put("originalInvoiceId", inv1004);
        String cm2001 = (String) ok(InvoiceProcesses.SAVE, clerk, credit).get("invoiceId");
        assertThat(ok(InvoiceProcesses.POST, controller, Map.of("invoiceId", cm2001)))
            .containsEntry("invoiceNo", "CM-2001");
        assertThat(amount(read(InvoiceEntities.INVOICE_DATASET, cm2001).get("taxTotal")))
            .isEqualByComparingTo("165.00");
        assertThat(postingLines("CM-2001")).isEqualTo(credits.get("CM-2001"));
        ok(InvoiceProcesses.APPLY, clerk, Map.of("creditMemoId", cm2001, "invoiceId", inv1004, "amount", "2165.00",
            "applicationDate", "2026-01-10"));

        // 4. INV-1005 (euros), INV-1006 (Oregon) and INV-1007 (resale with certificate RC-3301).
        post("C400", "2026-01-12", List.of(invoiceLine("Components", "1", "50000.00", "4000", null)));
        post("C200", "2026-01-14", List.of(invoiceLine("Engineering services", "1", "18000.00", "4100", null)));
        String inv1007 = post("C300", "2026-01-15", List.of(invoiceLine("Components", "50", "500.00", "4000",
            null)));
        for (String number : List.of("INV-1005", "INV-1006", "INV-1007")) {
            assertThat(postingLines(number)).as(number).isEqualTo(invoices.get(number));
        }

        // 5. RCPT-0002 pays INV-1002; RCPT-0003 is applied to the wrong invoice, taken back and applied in part to
        // INV-1003, its history kept (FIN-AR-008).
        receipt("C200", "2026-01-16", "24025.00", id("INV-1002"));
        Map<String, Object> rcpt3 = receipt("C300", "2026-01-25", "20000.00", inv1007);
        assertThat(rcpt3).containsEntry("receiptNo", "RCPT-0003");
        @SuppressWarnings("unchecked")
        String wrong = (String) ((List<Map<String, Object>>) rcpt3.get("applications")).getFirst()
            .get("applicationId");
        // Taking an application back moves money between invoices: not the recording clerk's (FIN-CT-001).
        assertThat(refused(ReceiptProcesses.REVERSE, clerk, Map.of("applicationId", wrong, "reverseDate",
            "2026-01-25", "reason", "Wrong invoice"), 403)).isEqualTo("PERMISSION_DENIED");
        ok(ReceiptProcesses.REVERSE, controller, Map.of("applicationId", wrong, "reverseDate", "2026-01-25",
            "reason", "Remittance names INV-1003, not INV-1007"));
        ok(ReceiptProcesses.APPLY, clerk, Map.of("receiptId", rcpt3.get("receiptId"), "applicationDate", "2026-01-25",
            "applications", List.of(Map.of("invoiceId", id("INV-1003"), "amount", "20000.00"))));
        assertThat(find(InvoiceEntities.APPLICATION_DATASET, "sourceId", rcpt3.get("receiptId"))).extracting(
            a -> a.get("invoiceId").equals(inv1007) + " " + amount(a.get("amount")).toPlainString() + " "
                + (a.get("reversesApplicationId") != null))
            .containsExactlyInAnyOrder("true 20000.00 false", "true -20000.00 true", "false 20000.00 false");
        assertThat(amount(read(InvoiceEntities.INVOICE_DATASET, inv1007).get("openAmount")))
            .isEqualByComparingTo("25000.00");
        assertThat(amount(read(InvoiceEntities.INVOICE_DATASET, id("INV-1003")).get("openAmount")))
            .isEqualByComparingTo("10000.00");
        for (String number : List.of("RCPT-0002", "RCPT-0003")) {
            assertThat(postingLines(number)).as(number).isEqualTo(receipts.get(number));
        }

        // 6. C300's statement for January and the aging on the 31st (FIN-EXP-08), the sales tax report (FIN-EXP-13).
        assertThat(report("finance.ar.statement", clerk, Map.of("customerCode", "C300", "from", "2026-01-01",
            "to", "2026-01-31"))).extracting(r -> r.get("entry") + " " + r.get("documentNo") + " "
                + amount(r.get("balance")).toPlainString()).containsExactly("OPENING null 30000.00",
                    "INVOICE INV-1007 55000.00", "RECEIPT RCPT-0003 35000.00", "CLOSING null 35000.00");
        Map<String, String> expectedAging = expectedAging();
        // INV-1005 is revalued at month end only with F7: until then at its invoice-date dollars, 50,000.00 x 1.0850.
        expectedAging.put("INV-1005", "C400 Current 54250.00");
        Map<String, String> aging = new TreeMap<>();
        BigDecimal agingTotal = BigDecimal.ZERO;
        for (Map<String, Object> row : report("finance.ar.aging", accountant, Map.of("agingDate", "2026-01-31"))) {
            aging.put((String) row.get("documentNo"), row.get("customerCode") + " " + row.get("bucket") + " "
                + amount(row.get("openAmountUsd")).toPlainString());
            agingTotal = agingTotal.add(amount(row.get("openAmountUsd")));
        }
        assertThat(aging).isEqualTo(expectedAging);
        assertThat(agingTotal).isEqualByComparingTo(report("finance.gl.trial_balance", controller,
            Map.of("through", "2026-01-31")).stream().filter(r -> "1200".equals(r.get("accountCode")))
            .map(r -> amount(r.get("balance"))).findFirst().orElseThrow());
        Map<String, String> salesTax = new TreeMap<>();
        for (Map<String, Object> row : report("finance.tax.sales_tax", accountant, Map.of("from", "2026-01-01",
            "to", "2026-01-31"))) {
            salesTax.put((String) row.get("taxCode"), amount(row.get("taxableSales")).toPlainString() + " "
                + amount(row.get("exemptSales")).toPlainString() + " "
                + amount(row.get("taxCollected")).toPlainString());
        }
        assertThat(salesTax).isEqualTo(expectedSalesTax());

        // The invoice register for January adds up to the documents posted in it (FIN-UI-004), credit memos less.
        List<Map<String, Object>> register = report("finance.ar.invoice_register", clerk, Map.of("from",
            "2026-01-01", "to", "2026-01-31"));
        assertThat(register).extracting(r -> r.get("invoiceNo")).containsExactlyInAnyOrder("INV-1004", "CM-2001",
            "INV-1005", "INV-1006", "INV-1007");
        assertThat(register.stream().map(r -> amount(r.get("totalUsd"))).reduce(BigDecimal.ZERO, BigDecimal::add))
            .isEqualByComparingTo("148385.00");
        assertThat(report("finance.ar.receipt_register", clerk, Map.of("from", "2026-01-01", "to", "2026-01-31")))
            .extracting(r -> r.get("receiptNo") + " " + amount(r.get("amount")).toPlainString() + " "
                + amount(r.get("unappliedAmount")).toPlainString())
            .containsExactlyInAnyOrder("RCPT-0001 32475.00 0.00", "RCPT-0002 24025.00 0.00",
                "RCPT-0003 20000.00 0.00");
    }

    /** FIN-EXP-08's rows: document to "customer bucket open amount". */
    private static Map<String, String> expectedAging() throws IOException {
        Pattern row = Pattern.compile("^\\| (C\\d{3}) \\| ([A-Z]+-\\d{4}) \\| [0-9-]+ \\| [0-9-]+ \\| ([^|]+) \\| "
            + "([0-9,.]+) \\|$");
        Map<String, String> rows = new TreeMap<>();
        for (String line : section("FIN-EXP-08")) {
            Matcher m = row.matcher(line);
            if (m.find()) {
                rows.put(m.group(2), m.group(1) + " " + m.group(3).trim().replace('–', '-') + " "
                    + m.group(4).replace(",", ""));
            }
        }
        assertThat(rows).isNotEmpty();
        return rows;
    }

    /** FIN-EXP-13's rows: tax code to "taxable exempt collected". */
    private static Map<String, String> expectedSalesTax() throws IOException {
        Pattern row = Pattern.compile("^\\| ([A-Z][A-Z-]*)[^|]* \\| ([0-9,.]+) \\| ([0-9,.]+) \\| ([0-9,.]+) \\|");
        Map<String, String> rows = new TreeMap<>();
        for (String line : section("FIN-EXP-13")) {
            Matcher m = row.matcher(line);
            if (m.find()) {
                rows.put(m.group(1), m.group(2).replace(",", "") + " " + m.group(3).replace(",", "") + " "
                    + m.group(4).replace(",", ""));
            }
        }
        assertThat(rows).isNotEmpty();
        return rows;
    }

    private static List<String> section(String id) throws IOException {
        List<String> lines = Files.readAllLines(SAMPLE_COMPANY.resolveSibling("21-expected-results.md"));
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("## ") && lines.get(i).contains("(" + id + ")")) {
                start = i + 1;
            } else if (start >= 0 && lines.get(i).startsWith("## ")) {
                return lines.subList(start, i);
            }
        }
        return start < 0 ? List.of() : lines.subList(start, lines.size());
    }
}
