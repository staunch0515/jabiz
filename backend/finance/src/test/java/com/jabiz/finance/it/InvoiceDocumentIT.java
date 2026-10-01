package com.jabiz.finance.it;

import com.jabiz.finance.ar.CustomerProcesses;
import com.jabiz.finance.ar.InvoiceDocuments;
import com.jabiz.finance.ar.InvoiceEntities;
import com.jabiz.finance.ar.InvoiceProcesses;
import com.jabiz.finance.company.CompanyEntities;
import com.jabiz.finance.setup.FinanceRoles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The documents of invoices and credit memos (ROADMAP F3d, FIN-AR-005): a posted invoice is issued with the company,
 * the customer and its addresses, the lines, the tax by jurisdiction, the totals, terms, due date and remittance
 * instructions; the PDF is kept as issued and every reprint is that copy; a reprint after the customer moved shows
 * the address of the invoice's date (FIN-AR-001 acceptance 2); drafts and legacy open items are not issued; the
 * company's profile is data kept by the controller.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InvoiceDocumentIT extends FinanceItSupport {

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
        openReceivables();
        customer("C100", "1200 Robotics Way", "Austin", "78758", "Dana Fox", "ap@acme-robotics.example", null);
        customer("C300", "88 Commerce Street", "Dallas", "75202", null, "payables@lonestar.example", null);
        String inv1004 = post("C100", "2026-01-06", List.of(invoiceLine("Components", "100", "400.00", "4000", null),
            invoiceLine("Engineering services", "1", "10000.00", "4100", "NT")));
        Map<String, Object> credit = invoiceInput("C100", "2026-01-10", "CREDIT_MEMO", List.of(
            invoiceLine("Returned components", "5", "400.00", null, null)));
        credit.put("originalInvoiceId", inv1004);
        String cm2001 = (String) ok(InvoiceProcesses.SAVE, clerk, credit).get("invoiceId");
        ok(InvoiceProcesses.POST, controller, Map.of("invoiceId", cm2001));
        post("C300", "2026-01-15", List.of(invoiceLine("Components", "50", "500.00", "4000", null)));
        for (String number : List.of("INV-1001", "INV-1004", "INV-1005", "CM-2001")) {
            IDS.put(number, (String) find(InvoiceEntities.INVOICE_DATASET, "invoiceNo", number).getFirst()
                .get("invoiceId"));
        }
    }

    private void customer(String code, String street, String city, String zip, String contact, String email,
        String from) {
        Map<String, Object> address = Map.of("street", street, "city", city, "state", "TX", "postalCode", zip,
            "country", "United States");
        Map<String, Object> input = new HashMap<>(Map.of("customerCode", code, "billing", address,
            "shipping", address, "contactEmail", email));
        if (contact != null) {
            input.put("contactName", contact);
        }
        if (from != null) {
            input.put("effectiveDate", from);
        }
        ok(CustomerProcesses.SAVE, clerk, input);
    }

    private String post(String customer, String date, List<Map<String, Object>> lines) {
        String id = (String) ok(InvoiceProcesses.SAVE, clerk, invoiceInput(customer, date, null, lines))
            .get("invoiceId");
        ok(InvoiceProcesses.POST, clerk, Map.of("invoiceId", id));
        return id;
    }

    private Map<String, Object> issue(String invoiceId) {
        return ok(InvoiceDocuments.ISSUE, clerk, Map.of("invoiceId", invoiceId));
    }

    @Test
    @Order(1)
    void aPostedInvoiceIsIssuedWithWhatItsCustomerNeedsAndReprintedAsIssued() {
        // Without the company's profile there is nothing to say who sends it.
        assertThat(refused(InvoiceDocuments.ISSUE, clerk, Map.of("invoiceId", IDS.get("INV-1004")), 422))
            .isEqualTo(InvoiceDocuments.NO_COMPANY);
        assertThat(refused("FIN_COMPANY_PROFILE_SET", clerk, Map.of("legalName", "Someone else"), 403))
            .isEqualTo("PERMISSION_DENIED");
        companyProfile();

        Map<String, Object> issued = issue(IDS.get("INV-1004"));
        assertThat(issued).containsEntry("documentNo", "INV-1004");
        String runId = (String) issued.get("runId");
        byte[] pdf = documentPdf(runId, clerk);
        assertThat(sha256(pdf)).isEqualTo(issued.get("pdfHash"));
        String text = pdfText(pdf);
        // Company, customer and both addresses, the invoice's facts, the lines, the tax by jurisdiction, totals.
        assertThat(text).contains("Invoice", "INV-1004", "Northwind Components, Inc.", "500 Congress Avenue",
            "Austin, TX 78701", "billing@northwind.example", "Bill to", "Ship to", "Acme Robotics, Inc.", "Dana Fox",
            "1200 Robotics Way", "Austin, TX 78758", "Jan 6, 2026", "Net 30 days", "Feb 5, 2026", "C100",
            "Components", "Engineering services", "1 Components 100 400.00 40,000.00 TX-AUSTIN", "10,000.00", "NT", "TX state",
            "6.25%", "2%", "Not taxable: non taxable service",
            "2,500.00", "800.00", "50,000.00", "3,300.00", "53,300.00", "Remittance instructions",
            "Lakeside National Bank", "Please quote the invoice number.");
        assertThat(text).doesNotContain("100.0000", "#", "PREVIEW");

        // A reprint is the kept copy, byte for byte, whoever reads it; it still matches the books.
        String accountant = inRoles("accountant", FinanceRoles.ACCOUNTANT);
        assertThat(documentPdf(runId, accountant)).isEqualTo(pdf);
        assertThat(post("/api/documents/runs/" + runId + "/verify", accountant, Map.of()).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody()).containsEntry("verdict", "identical")
            .containsEntry("copyIntact", true);
        // Reading is not issuing.
        assertThat(refused(InvoiceDocuments.ISSUE, accountant, Map.of("invoiceId", IDS.get("INV-1004")), 403))
            .isEqualTo("PERMISSION_DENIED");
        // Issued again, the data the same: the same document again, kept as a new copy.
        Map<String, Object> again = issue(IDS.get("INV-1004"));
        assertThat(again.get("runId")).isNotEqualTo(runId);
        assertThat(again.get("pdfHash")).isEqualTo(issued.get("pdfHash"));
        assertThat(get("/api/documents/runs?subject=" + IDS.get("INV-1004"), clerk).expectStatus().isOk()
            .expectBody(LIST).returnResult().getResponseBody()).hasSize(2);

        // The credit memo names the invoice it credits and asks for no payment.
        Map<String, Object> credit = issue(IDS.get("CM-2001"));
        String creditText = pdfText(documentPdf((String) credit.get("runId"), clerk));
        assertThat(creditText).contains("Credit Memo", "CM-2001", "Credits invoice", "INV-1004",
            "Returned components", "2,000.00", "165.00", "2,165.00", "not to be paid")
            .doesNotContain("Remittance instructions");
    }

    @Test
    @Order(2)
    void draftsAndLegacyItemsAreNotIssued() {
        String draft = (String) ok(InvoiceProcesses.SAVE, clerk, invoiceInput("C100", "2026-01-20", null,
            List.of(invoiceLine("Spare parts", "1", "10.00", "4100", "NT")))).get("invoiceId");
        assertThat(refused(InvoiceDocuments.ISSUE, clerk, Map.of("invoiceId", draft), 422))
            .isEqualTo(InvoiceDocuments.NOT_POSTED);
        // INV-1001 came over from the legacy system, which issued its document.
        assertThat(refused(InvoiceDocuments.ISSUE, clerk, Map.of("invoiceId", IDS.get("INV-1001")), 422))
            .isEqualTo(InvoiceDocuments.NOT_ISSUABLE);
        // Without mail the document is not sent, and nothing is kept of the attempt.
        assertThat(refused(InvoiceDocuments.SEND, clerk, Map.of("invoiceId", IDS.get("INV-1004")), 422))
            .isEqualTo("MAIL_DISABLED");
    }

    @Test
    @Order(3)
    void aReprintAfterTheCustomerMovedShowsTheAddressOfTheInvoiceDate() {
        // On 31 January Lone Star tells us its address from 1 February (FIN-AR-001 acceptance 2).
        customer("C300", "1 Elm Plaza", "Fort Worth", "76102", null, "payables@lonestar.example", "2026-02-01");
        // INV-1005 here: Lone Star's components of 15 January.
        String january15 = IDS.get("INV-1005");
        Map<String, Object> january = issue(january15);
        assertThat(pdfText(documentPdf((String) january.get("runId"), clerk))).contains("88 Commerce Street",
            "Dallas, TX 75202", "Jan 15, 2026", "Exempt: resale, certificate RC-3301").doesNotContain("Elm Plaza");

        // In February the address has changed; the January invoice still shows the old one, a new invoice the new.
        clock.set(Instant.parse("2026-02-10T15:00:00Z"));
        clerk = inRoles("clerk", FinanceRoles.RECEIVABLES_CLERK);
        Map<String, Object> reissued = issue(january15);
        assertThat(reissued.get("pdfHash")).isNotNull();
        assertThat(pdfText(documentPdf((String) reissued.get("runId"), clerk))).contains("88 Commerce Street")
            .doesNotContain("Elm Plaza");
        assertThat(documentPdf((String) january.get("runId"), clerk)).satisfies(bytes ->
            assertThat(sha256(bytes)).isEqualTo(january.get("pdfHash")));
        String february = post("C300", "2026-02-03", List.of(invoiceLine("Components", "2", "500.00", "4000",
            null)));
        assertThat(pdfText(documentPdf((String) issue(february).get("runId"), clerk))).contains("1 Elm Plaza",
            "Fort Worth, TX 76102").doesNotContain("Commerce Street");
    }

    @Test
    @Order(4)
    void theProfileIsWrittenOnlyByItsProcessAndOnlyInserted() {
        String commit = commitRefused(CompanyEntities.PROFILE_DATASET, controller, Map.of("action", "INSERT",
            "attributes", Map.of("profileKey", "COMPANY", "legalName", "Northwind")));
        assertThat(commit).isNotBlank();
        Map<String, Object> unchanged = ok("FIN_COMPANY_PROFILE_SET", controller, Map.of(
            "legalName", "Northwind Components, Inc.", "street", "500 Congress Avenue", "city", "Austin",
            "state", "TX", "postalCode", "78701", "country", "United States", "phone", "+1 512 555 0100",
            "email", "billing@northwind.example", "remittance", "ACH or wire to Lakeside National Bank, "
                + "account 000123456789, routing 111000025.\nPlease quote the invoice number."));
        assertThat(unchanged).containsEntry("changed", false);
        assertThat(ok("FIN_COMPANY_PROFILE_SET", controller, Map.of("legalName", "Northwind Components, Inc.",
            "street", "600 Congress Avenue"))).containsEntry("changed", true);
        assertOnlyInserted("fi_company_profile_version", "fi_invoice_version");
    }
}
