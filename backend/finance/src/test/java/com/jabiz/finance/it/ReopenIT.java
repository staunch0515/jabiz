package com.jabiz.finance.it;

import com.jabiz.finance.ar.InvoiceEntities;
import com.jabiz.finance.ar.InvoiceProcesses;
import com.jabiz.finance.calc.PeriodPolicy;
import com.jabiz.finance.close.ReopenProcesses;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.finance.setup.FinanceRoles;
import com.jabiz.finance.setup.SetupProcesses;
import com.jabiz.runtime.event.OutboxDeliverer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reopening's guards (FIN-PC-006) and prior-period invoices (FIN-PC-007): without the approval rule no period is
 * reopened on the requester's word; only the latest closed period is reopened; an invoice of a closed January is
 * booked in February, in the receivables from that day and in the prior-period report.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ReopenIT extends FinanceItSupport {

    @Autowired
    OutboxDeliverer deliverer;

    private Map<String, Object> period(String key) {
        return find(GlEntities.PERIOD_DATASET, "periodKey", key).getFirst();
    }

    @Test
    void reopeningsAndPriorPeriodInvoices() {
        Map<String, String> held = openReceivablesHolding(SetupProcesses.REOPEN_RULE);
        String controller = inRoles("controller", FinanceRoles.CONTROLLER);
        String accountant = inRoles("accountant", FinanceRoles.ACCOUNTANT);
        String clerk = inRoles("clerk", FinanceRoles.RECEIVABLES_CLERK);
        // The sample's opening payables and assets are not loaded here.
        closePeriod("2026-01", "SUBLEDGERS");

        // No rule yet: the request is refused, never approved by itself.
        assertThat(refused(ReopenProcesses.REQUEST, accountant, Map.of("periodKey", "2026-01", "reason",
            "Correction"), 422)).isEqualTo(ReopenProcesses.NO_RULE);
        ok("CONTROL_CHANGE_PUBLISH", as("controller-2", "control.publish"), Map.of("changeId",
            held.get(SetupProcesses.REOPEN_RULE)));

        // An invoice of January, booked in February: never before its date.
        Map<String, Object> invoice = invoiceInput("C200", "2026-01-20", null, List.of(invoiceLine(
            "Engineering services", "1", "1000.00", "4100", null)));
        invoice.put("postingDate", "2026-01-19");
        assertThat(refused(InvoiceProcesses.SAVE, clerk, invoice, 422)).isEqualTo(InvoiceProcesses.INVALID_VALUE);
        invoice.put("postingDate", "2026-02-03");
        String id = (String) ok(InvoiceProcesses.SAVE, clerk, invoice).get("invoiceId");
        String invoiceNo = (String) ok(InvoiceProcesses.POST, clerk, Map.of("invoiceId", id)).get("invoiceNo");
        assertThat(read(InvoiceEntities.INVOICE_DATASET, id)).containsEntry("invoiceDate", "2026-01-20")
            .containsEntry("postingDate", "2026-02-03");
        assertThat(report("finance.ar.aging", controller, Map.of("agingDate", "2026-01-31")))
            .noneMatch(r -> invoiceNo.equals(r.get("documentNo")));
        assertThat(report("finance.ar.aging", controller, Map.of("agingDate", "2026-02-03")))
            .anyMatch(r -> invoiceNo.equals(r.get("documentNo")));
        assertThat(report("finance.gl.prior_period_items", accountant, Map.of("from", "2026-02-01", "to",
            "2026-02-28"))).singleElement().satisfies(r -> assertThat(r).containsEntry("source", "INVOICE")
                .containsEntry("documentNo", invoiceNo).containsEntry("documentPeriod", "2026-01")
                .containsEntry("postingPeriod", "2026-02"));
        assertThat(amount(report("finance.gl.prior_period_items", accountant, Map.of("from", "2026-02-01", "to",
            "2026-02-28")).getFirst().get("amount"))).isEqualByComparingTo(new BigDecimal("1000.00"));
        // A receipt is not applied before the invoice is booked.
        Map<String, Object> receipt = new java.util.LinkedHashMap<>();
        receipt.put("customerCode", "C200");
        receipt.put("receiptDate", "2026-02-02");
        receipt.put("amount", "1000.00");
        receipt.put("method", "ACH");
        receipt.put("bankAccount", "1010");
        receipt.put("applications", List.of(Map.of("invoiceId", id, "amount", "1000.00")));
        assertThat(refused(com.jabiz.finance.ar.ReceiptProcesses.RECORD, clerk, receipt, 422))
            .isEqualTo(com.jabiz.finance.ar.ReceiptProcesses.NOT_OPEN);

        // A request for January waits while February closes: approved then, it lapses and January stays closed.
        Map<String, Object> waiting = ok(ReopenProcesses.REQUEST, accountant, Map.of("periodKey", "2026-01",
            "reason", "Correction"));
        closePeriod("2026-02", "SUBLEDGERS");
        ok("APPROVAL_DECIDE", controller, Map.of("requestId", waiting.get("approvalRequestId"), "decision",
            "APPROVE"));
        deliverer.deliverPending().block();
        assertThat(read(com.jabiz.finance.close.CloseEntities.REOPEN_DATASET, waiting.get("reopenId")))
            .containsEntry("status", "LAPSED");
        assertThat(period("2026-01")).containsEntry("status", "CLOSED");
        // Now only February, the latest closed period, is reopened.
        assertThat(refused(ReopenProcesses.REQUEST, accountant, Map.of("periodKey", "2026-01", "reason",
            "Correction"), 422)).isEqualTo(ReopenProcesses.LATER_CLOSED);
        // A request is withdrawn by its requester only, and a new one may follow.
        Map<String, Object> asked = ok(ReopenProcesses.REQUEST, accountant, Map.of("periodKey", "2026-02",
            "reason", "Correction"));
        assertThat(refused(ReopenProcesses.WITHDRAW, inRoles("accountant-2", FinanceRoles.ACCOUNTANT),
            Map.of("reopenId", asked.get("reopenId")), 422)).isEqualTo(ReopenProcesses.NOT_REQUESTER);
        assertThat(ok(ReopenProcesses.WITHDRAW, accountant, Map.of("reopenId", asked.get("reopenId"))))
            .containsEntry("status", "WITHDRAWN");
        // The decision comes from the platform's events only.
        run(ReopenProcesses.APPROVAL_RESULT, as("admin", "fin.period.close", "fin.period.reopen.request"),
            Map.of("subject", ReopenProcesses.SUBJECT, "entityId", asked.get("reopenId"), "status", "APPROVED",
                "requestId", asked.get("approvalRequestId"))).expectStatus().isForbidden();
        asked = ok(ReopenProcesses.REQUEST, accountant, Map.of("periodKey", "2026-02", "reason", "Correction"));
        ok("APPROVAL_DECIDE", controller, Map.of("requestId", asked.get("approvalRequestId"), "decision", "APPROVE"));
        deliverer.deliverPending().block();
        // Delivered again, the decision changes nothing more.
        deliverer.deliverPending().block();
        assertThat(period("2026-02")).containsEntry("status", "OPEN");
        assertThat(period("2026-01")).containsEntry("status", PeriodPolicy.Status.CLOSED.name());
        assertOnlyInserted("fi_period_reopen_version", "fi_invoice_version");
    }
}
