package com.jabiz.finance.it;

import com.jabiz.finance.ap.BillEntities;
import com.jabiz.finance.ap.BillProcesses;
import com.jabiz.finance.calc.PeriodPolicy;
import com.jabiz.finance.close.CloseEntities;
import com.jabiz.finance.close.CloseProcesses;
import com.jabiz.finance.close.ReopenProcesses;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.finance.gl.JournalProcesses;
import com.jabiz.finance.gl.PeriodProcesses;
import com.jabiz.finance.setup.FinanceRoles;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jabiz.finance.it.JournalLifecycleIT.entry;
import static com.jabiz.finance.it.JournalLifecycleIT.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIN-SCN-07, a closed period and its governed reopening, after FIN-SCN-06 closed January: a bill dated in January
 * is refused (period closed); BILL-OS-0120 with document date 2026-01-20 is booked on 2026-02-10 and the prior-period
 * report lists it. The accountant asks for January to open again; the controller rejects and January stays closed. A
 * second request is approved; a test entry is posted and reversed; January closes again, its new artifact superseding
 * the first. As known at the first close, January's trial balance is the first artifact's (FIN-PC-006 acceptance 3).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class FinScn07IT extends JanuaryBooks {

    @Test
    @SuppressWarnings("unchecked")
    void aClosedPeriodAndItsGovernedReopening() throws IOException {
        januaryPostings();
        Map<String, Object> first = closeJanuary();
        Map<String, Object> firstArtifact = read(CloseEntities.ARTIFACT_DATASET, first.get("artifactId"));

        // 10 February.
        clock.advance(Duration.ofDays(10));
        people();

        // Step 1: a bill dated in January is refused, the period being closed.
        String bill = (String) ok(BillProcesses.SAVE, apClerk, osBill(null)).get("billId");
        assertThat(refused(BillProcesses.POST, apClerk, Map.of("billId", bill), 422))
            .isEqualTo(PeriodPolicy.PERIOD_CLOSED);
        // A bill is not booked before its date.
        Map<String, Object> early = osBill("2026-01-19");
        early.put("billId", bill);
        assertThat(refused(BillProcesses.SAVE, apClerk, early, 422)).isEqualTo(BillProcesses.INVALID_VALUE);

        // Step 2: BILL-OS-0120, document date 20 January, booked on 10 February.
        Map<String, Object> february = osBill("2026-02-10");
        february.put("billId", bill);
        ok(BillProcesses.SAVE, apClerk, february);
        Map<String, Object> posted = ok(BillProcesses.POST, apClerk, Map.of("billId", bill));
        String billNo = (String) posted.get("billNo");
        assertThat(read(BillEntities.BILL_DATASET, bill)).containsEntry("invoiceDate", "2026-01-20")
            .containsEntry("postingDate", "2026-02-10");
        assertThat(postingLines(billNo)).isEqualTo(Map.of("6300", new BigDecimal("480.00"),
            "2000", new BigDecimal("-480.00")));
        assertThat(find(com.jabiz.finance.gl.JournalEntities.POSTING_DATASET, "documentNo", billNo)).singleElement()
            .satisfies(p -> assertThat(p).containsEntry("postingDate", "2026-02-10")
                .containsEntry("periodKey", "2026-02"));
        // The prior-period report lists it with its January date (FIN-PC-007 acceptance 1).
        List<Map<String, Object>> prior = report("finance.gl.prior_period_items", accountant,
            Map.of("from", "2026-02-01", "to", "2026-02-28"));
        assertThat(prior).singleElement().satisfies(r -> assertThat(r).containsEntry("source", "BILL")
            .containsEntry("documentNo", billNo).containsEntry("party", "V600")
            .containsEntry("documentDate", "2026-01-20").containsEntry("documentPeriod", "2026-01")
            .containsEntry("periodStatus", "CLOSED").containsEntry("postingDate", "2026-02-10")
            .containsEntry("postingPeriod", "2026-02"));
        // In the payables from the day it is booked: January's aging is as closed, February's has it.
        BigDecimal january = payables("2026-01-31");
        assertThat(january).isEqualByComparingTo("46300.00");
        assertThat(payables("2026-02-10")).isEqualByComparingTo(january.add(new BigDecimal("480.00")));

        // Step 3: a reopening is asked for with a reason; the controller rejects it and January stays closed.
        run(ReopenProcesses.REQUEST, apClerk, Map.of("periodKey", "2026-01", "reason", "x")).expectStatus()
            .isForbidden();
        assertThat(refused(ReopenProcesses.REQUEST, accountant, Map.of("periodKey", "2026-02", "reason",
            "Test correction"), 422)).isEqualTo(ReopenProcesses.NOT_CLOSED);
        Map<String, Object> asked = ok(ReopenProcesses.REQUEST, accountant, Map.of("periodKey", "2026-01",
            "reason", "Test correction"));
        assertThat(asked).containsEntry("status", "PENDING").containsEntry("periodStatus", "CLOSED");
        assertThat(refused(ReopenProcesses.REQUEST, accountant, Map.of("periodKey", "2026-01", "reason",
            "Again"), 422)).isEqualTo(ReopenProcesses.PENDING_ALREADY);
        // Nobody approves their own request.
        assertThat(refused("APPROVAL_DECIDE", inRoles("accountant", FinanceRoles.ACCOUNTANT,
            FinanceRoles.CONTROLLER), Map.of("requestId", asked.get("approvalRequestId"), "decision", "APPROVE"),
            422)).isEqualTo("APPROVAL_OWN_REQUEST");
        ok("APPROVAL_DECIDE", controller, Map.of("requestId", asked.get("approvalRequestId"), "decision", "REJECT",
            "reason", "Not needed"));
        deliverer.deliverPending().block();
        assertThat(read(CloseEntities.REOPEN_DATASET, asked.get("reopenId"))).containsEntry("status", "REJECTED")
            .containsEntry("decidedBy", "controller").containsEntry("requestedBy", "accountant")
            .containsEntry("artifactId", first.get("artifactId"));
        assertThat(january()).containsEntry("status", "CLOSED");

        // A second request is approved: January and its subledgers are open again.
        Map<String, Object> again = ok(ReopenProcesses.REQUEST, accountant, Map.of("periodKey", "2026-01",
            "reason", "Test correction of the accrual"));
        decide(again.get("approvalRequestId"));
        assertThat(read(CloseEntities.REOPEN_DATASET, again.get("reopenId"))).containsEntry("status", "APPROVED")
            .containsEntry("decidedBy", "controller");
        assertThat(january()).containsEntry("status", "OPEN").containsEntry("arStatus", "OPEN")
            .containsEntry("apStatus", "OPEN").containsEntry("bankStatus", "OPEN").containsEntry("faStatus", "OPEN");

        // A test entry posted and reversed; January closes again.
        String test = (String) ok(JournalProcesses.SAVE, accountant, entry("2026-01-31", "Test correction",
            List.of(line("6400", "100.00", null, null), line("2100", null, "100.00", null)))).get("journalId");
        ok(JournalProcesses.SUBMIT, accountant, Map.of("journalId", test));
        ok(JournalProcesses.REVERSE, accountant, Map.of("journalId", test, "postingDate", "2026-01-31"));
        clock.advance(Duration.ofHours(1));
        people();
        Map<String, Object> second = ok(CloseProcesses.CLOSE, controller, Map.of("periodKey", "2026-01"));
        assertThat(second).containsEntry("seq", 2);

        // Expected: two artifacts, the first superseded by the second; as known at the first close, January's trial
        // balance is the first's, and the second holds the same figures (the test entry reversed).
        assertThat(read(CloseEntities.ARTIFACT_DATASET, second.get("artifactId")))
            .containsEntry("supersedesId", first.get("artifactId"));
        List<Map<String, Object>> artifacts = report("finance.close.artifacts", controller,
            Map.of("periodKey", "2026-01"));
        assertThat(artifacts).hasSize(2);
        assertThat(artifacts).filteredOn(a -> first.get("artifactId").equals(a.get("artifactId"))).singleElement()
            .satisfies(a -> assertThat(a).containsEntry("supersededBy", second.get("artifactId")));
        List<Map<String, Object>> asFirstClosed = report(CloseProcesses.TRIAL_BALANCE, controller,
            Map.of("through", "2026-01-31", "adjustments", false, "knownAt", firstArtifact.get("knownAt")));
        assertThat(CloseProcesses.trialBalanceHash(asFirstClosed)).isEqualTo(firstArtifact.get("trialBalanceHash"));
        assertThat(second).containsEntry("trialBalanceHash", firstArtifact.get("trialBalanceHash"));
        // The reopening's trail: both requests, both decisions.
        assertThat(find(CloseEntities.REOPEN_DATASET, "periodKey", "2026-01")).extracting(r -> r.get("status"))
            .containsExactlyInAnyOrder("REJECTED", "APPROVED");
        // Closed again, it changes only through another reopening.
        assertThat(refused(PeriodProcesses.SET_STATE, controller, Map.of("periodKey", "2026-01", "status", "OPEN"),
            422)).isEqualTo(PeriodProcesses.REOPEN_REQUIRED);
        assertOnlyInserted("fi_period_reopen_version", "fi_close_artifact_version", "fi_bill_version");
    }

    private Map<String, Object> january() {
        return find(GlEntities.PERIOD_DATASET, "periodKey", "2026-01").getFirst();
    }

    private BigDecimal payables(String day) {
        return report("finance.ap.aging", accountant, Map.of("agingDate", day)).stream()
            .map(r -> amount(r.get("openAmountUsd"))).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** BILL-OS-0120: V600's January office-power adjustment, 480.00 to 6300. */
    private static Map<String, Object> osBill(String postingDate) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("vendorCode", "V600");
        input.put("vendorInvoiceNo", "OS-0120");
        input.put("invoiceDate", "2026-01-20");
        input.put("description", "January office-power adjustment");
        input.put("lines", List.of(Map.of("description", "Office power, January adjustment", "amount", "480.00",
            "account", "6300")));
        if (postingDate != null) {
            input.put("postingDate", postingDate);
        }
        return input;
    }
}
