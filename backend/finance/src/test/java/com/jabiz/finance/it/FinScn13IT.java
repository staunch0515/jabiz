package com.jabiz.finance.it;

import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.ap.BillEntities;
import com.jabiz.finance.ap.BillProcesses;
import com.jabiz.finance.ap.PaymentProcesses;
import com.jabiz.finance.ar.InvoiceEntities;
import com.jabiz.finance.ar.InvoiceProcesses;
import com.jabiz.runtime.approval.ApprovalEntities;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIN-SCN-13, interfaces (FIN-DI-005, FIN-DI-006; ROADMAP F11a), on January's books, closed. The finance API is the
 * platform's: processes, datasets and SQL templates over HTTP with a Bearer token, each checked against the
 * permissions its metadata declares (docs/finance/api.md).
 * <ol>
 *   <li>an integration client creates the test bill T-9001 of 12,000.00 twice with the same idempotency key: one bill;
 *       posted, it waits for approval before it can be paid as one entered on the page does, and posting it again
 *       with the key changes nothing; the same goes for an invoice (FIN-DI-006 acceptance 1);</li>
 *   <li>a client without the permission to post bills is refused;</li>
 *   <li>the client reads January's trial balance through the API: FIN-EXP-03.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class FinScn13IT extends JanuaryBooks {

    /** Runs a process with an {@code Idempotency-Key}; returns the status and the body. */
    private Map<String, Object> withKey(String process, String authorization, String key, Object input,
        int status) {
        var exchange = client.post().uri("/api/processes/" + process + "/latest")
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, authorization)
            .header("Idempotency-Key", key).bodyValue(input).exchange().expectBody(MAP).returnResult();
        assertThat(exchange.getStatus().value()).as(process + " answered " + exchange.getResponseBody())
            .isEqualTo(status);
        return exchange.getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> output(Map<String, Object> body) {
        return (Map<String, Object>) body.get("output");
    }

    @Test
    @SuppressWarnings("unchecked")
    void interfaces() throws Exception {
        januaryPostings();
        closeJanuary();
        // The ERP's client prepares bills and invoices and reads the books; the BI tool only reads.
        String erp = as("erp-client", FinancePermissions.BILL_PREPARE, FinancePermissions.INVOICE_PREPARE,
            "ledger.read");
        String bi = as("bi-client", "ledger.read");

        // Step 1: T-9001 sent twice with one key, then posted twice with another.
        Map<String, Object> bill = Map.of("vendorCode", "V400", "vendorInvoiceNo", "T-9001", "invoiceDate",
            "2026-02-02", "lines", List.of(Map.of("description", "Interface test", "amount", "12000.00",
                "account", "6500")));
        Map<String, Object> first = withKey(BillProcesses.SAVE, erp, "erp-bill-T-9001", bill, 200);
        Map<String, Object> second = withKey(BillProcesses.SAVE, erp, "erp-bill-T-9001", bill, 200);
        assertThat(second).isEqualTo(first);
        String billId = (String) output(first).get("billId");
        assertThat(find(BillEntities.BILL_DATASET, "vendorInvoiceNo", "T-9001")).singleElement()
            .satisfies(b -> assertThat(b).containsEntry("billId", billId).containsEntry("status", BillEntities.DRAFT));

        // Step 2: a client that may not post is refused, and nothing changes.
        assertThat(refused(BillProcesses.POST, bi, Map.of("billId", billId), 403)).isEqualTo("PERMISSION_DENIED");
        assertThat(read(BillEntities.BILL_DATASET, billId)).containsEntry("status", BillEntities.DRAFT);

        // Posted by the ERP: above 10,000.00 it is booked and waits for approval before it can be paid, as one
        // entered on the page (FIN-AP-006; FIN-DI-005 acceptance 1).
        Map<String, Object> posted = output(withKey(BillProcesses.POST, erp, "erp-post-T-9001",
            Map.of("billId", billId), 200));
        assertThat(posted).containsEntry("approval", "PENDING");
        assertThat(output(withKey(BillProcesses.POST, erp, "erp-post-T-9001", Map.of("billId", billId), 200)))
            .isEqualTo(posted);
        assertThat(find(BillEntities.BILL_DATASET, "vendorInvoiceNo", "T-9001")).singleElement()
            .satisfies(b -> assertThat(b).containsEntry("approval", "PENDING").containsEntry("status",
                BillEntities.POSTED));
        assertThat(find(ApprovalEntities.REQUEST_DATASET, "entityId", billId)).singleElement()
            .satisfies(r -> assertThat(r).containsEntry("status", "PENDING"));
        // Unapproved, a payment run holds it out.
        Map<String, Object> run = ok(PaymentProcesses.PROPOSE, apClerk, Map.of("paymentDate", "2026-03-04",
            "method", "CHECK", "dueThrough", "2026-03-31", "vendorCodes", List.of("V400")));
        assertThat((List<Map<String, Object>>) run.get("held")).filteredOn(h -> billId.equals(h.get("billId")))
            .singleElement().satisfies(h -> assertThat(h).containsEntry("reason", PaymentProcesses.HOLD_NOT_APPROVED));
        ok(PaymentProcesses.CANCEL, apClerk, Map.of("runId", run.get("runId"), "reason", "Interface test"));
        // Approving is not given to the client.
        assertThat(refused("APPROVAL_DECIDE", erp, Map.of("requestId", posted.get("approvalRequestId"), "decision",
            "APPROVE"), 403)).isEqualTo("PERMISSION_DENIED");
        // A key is the request it was first used for: another process with it is a conflict.
        withKey(BillProcesses.DELETE, erp, "erp-bill-T-9001", Map.of("billId", billId), 409);

        // FIN-DI-006 acceptance 1: the same invoice request twice with one key, one invoice.
        Map<String, Object> invoice = invoiceInput("C300", "2026-02-02", null, List.of(invoiceLine("Interface test",
            "1", "500.00", "4100", "NT")));
        Object invoiceId = output(withKey(InvoiceProcesses.SAVE, erp, "erp-invoice-1", invoice, 200))
            .get("invoiceId");
        assertThat(output(withKey(InvoiceProcesses.SAVE, erp, "erp-invoice-1", invoice, 200)))
            .containsEntry("invoiceId", invoiceId);
        assertThat(find(InvoiceEntities.INVOICE_DATASET, "customerCode", "C300"))
            .filteredOn(i -> "2026-02-02".equals(i.get("invoiceDate"))).singleElement()
            .satisfies(i -> assertThat(i).containsEntry("invoiceId", invoiceId));

        // Step 3: January's trial balance, read by the BI client: FIN-EXP-03.
        Map<String, String> trialBalance = new TreeMap<>();
        for (Map<String, Object> row : report("finance.report.trial_balance", bi, Map.of("through",
            "2026-01-31"))) {
            BigDecimal debit = amount(row.get("closingDebit"));
            BigDecimal credit = amount(row.get("closingCredit"));
            if (!Boolean.TRUE.equals(row.get("summary")) && (debit.signum() != 0 || credit.signum() != 0)) {
                trialBalance.put((String) row.get("accountCode"), debit.toPlainString() + " "
                    + credit.toPlainString());
            }
        }
        Map<String, String> expected = new TreeMap<>();
        for (String[] row : expectedRows("FIN-EXP-03")) {
            if (row[0].matches("\\d{4}")) {
                expected.put(row[0], (row[2].isBlank() ? "0.00" : money(row[2]).toPlainString()) + " "
                    + (row[3].isBlank() ? "0.00" : money(row[3]).toPlainString()));
            }
        }
        assertThat(trialBalance).isEqualTo(expected);
    }
}
