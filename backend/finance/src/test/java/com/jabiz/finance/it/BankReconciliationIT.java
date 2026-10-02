package com.jabiz.finance.it;

import com.jabiz.finance.ar.InvoiceEntities;
import com.jabiz.finance.ar.ReceiptProcesses;
import com.jabiz.finance.bank.BankAccountProcesses;
import com.jabiz.finance.bank.BankEntryProcesses;
import com.jabiz.finance.bank.MatchProcesses;
import com.jabiz.finance.bank.ReconciliationEntities;
import com.jabiz.finance.bank.ReconciliationProcesses;
import com.jabiz.finance.bank.StatementEntities;
import com.jabiz.finance.bank.TransferProcesses;
import com.jabiz.finance.setup.FinanceRoles;
import com.jabiz.finance.setup.SetupProcesses;
import com.jabiz.runtime.event.OutboxDeliverer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reconciling the operating account (ROADMAP F5c; FIN-BK-007…010) on books of its own: three January receipts and a
 * statement that cleared two of them, the check outstanding at the cutover and a 10.00 fee. Prepared until the
 * difference is zero, completed by its preparer only once every earlier month is signed off and a rule names the
 * reviewer, signed off by another controller and issued; its archive reprints identically after February moves on,
 * and its matches stay as they were. The stale-check report and the cash position read the same books.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BankReconciliationIT extends FinanceItSupport {

    private static final String JANUARY = """
        date,bank_reference,description,amount
        2026-01-01,OPENING,OPENING LEDGER BALANCE,253200.00
        2026-01-03,BNK-0001,CHECK 1045,-3200.00
        2026-01-05,BNK-0002,DEPOSIT ACME ROBOTICS INC,32475.00
        2026-01-16,BNK-0005,DEPOSIT CASCADE MACHINING,24025.00
        2026-01-31,BNK-0009,ACCOUNT SERVICE FEE,-10.00
        2026-01-31,CLOSING,CLOSING LEDGER BALANCE,306490.00
        """;
    private static final String FEBRUARY = """
        date,bank_reference,description,amount
        2026-02-01,OPENING,OPENING LEDGER BALANCE,306490.00
        2026-02-02,BNK-0101,TRANSFER TO SAVINGS,-1000.00
        2026-02-28,CLOSING,CLOSING LEDGER BALANCE,305490.00
        """;

    private static boolean loaded;
    private static Map<String, String> held;
    private static String januaryId;
    private static String februaryId;
    private static String januaryRun;
    private static byte[] januaryReport;
    private static List<Map<String, Object>> januaryRows;

    @Autowired
    OutboxDeliverer deliverer;

    private String controller;
    private String reviewer;
    private String accountant;
    private String treasurer;

    @BeforeEach
    void books() {
        controller = inRoles("controller", FinanceRoles.CONTROLLER);
        reviewer = inRoles("controller-2", FinanceRoles.CONTROLLER);
        accountant = inRoles("accountant", FinanceRoles.ACCOUNTANT);
        treasurer = inRoles("treasurer", FinanceRoles.TREASURER);
        if (loaded) {
            return;
        }
        loaded = true;
        held = openReceivablesHolding(SetupProcesses.BANK_REC_RULE);
        ok(BankAccountProcesses.SAVE, treasurer, Map.of("bankCode", "OPERATING", "bankName",
            "Lakeside National Bank", "glAccount", "1010", "routingNumber", "111000025",
            "companyAccountNumber", "000123456789"));
        ok(BankAccountProcesses.SAVE, treasurer, Map.of("bankCode", "SAVINGS", "bankName", "Lakeside National Bank",
            "glAccount", "1050", "routingNumber", "111000025", "companyAccountNumber", "000987654321"));
        importCsv("finance.bank_opening_items", as("migrator", "fin.migration", "fin.import"),
            "date,reference,description,amount\n2025-12-28,CHK-1045,Check 1045,-3200.00\n", "commit", null,
            Map.of("bankCode", "OPERATING", "statementBalance", "253200.00"), 200);
        statement(JANUARY);
        String clerk = inRoles("ar-clerk", FinanceRoles.RECEIVABLES_CLERK);
        for (String[] r : new String[][] {{"C100", "2026-01-05", "32475.00", "INV-1001"},
            {"C200", "2026-01-16", "24025.00", "INV-1002"}, {"C300", "2026-01-25", "20000.00", "INV-1003"}}) {
            String invoiceId = (String) find(InvoiceEntities.INVOICE_DATASET, "invoiceNo", r[3]).getFirst()
                .get("invoiceId");
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("customerCode", r[0]);
            receipt.put("receiptDate", r[1]);
            receipt.put("amount", r[2]);
            receipt.put("method", "ACH");
            receipt.put("bankAccount", "1010");
            receipt.put("applications", List.of(Map.of("invoiceId", invoiceId, "amount", r[2])));
            ok(ReceiptProcesses.RECORD, clerk, receipt);
        }
    }

    private void statement(String csv) {
        String fileId = upload(accountant, "fin.bank.statement", csv.getBytes(StandardCharsets.UTF_8),
            "statement.csv", "text/csv");
        post("/api/imports/finance.bank_statement/commit", accountant, Map.of("fileId", fileId, "params",
            Map.of("bankCode", "OPERATING"))).expectStatus().isOk();
    }

    private Map<String, Object> prepare(String authorization, String day) {
        return ok(ReconciliationProcesses.PREPARE, authorization, Map.of("bankCode", "OPERATING",
            "statementDate", day));
    }

    private Map<String, Object> rec(String id) {
        return read(ReconciliationEntities.RECONCILIATION_DATASET, id);
    }

    private List<Map<String, Object>> rows(String day) {
        return report(ReconciliationProcesses.TEMPLATE, accountant, Map.of("bankCode", "OPERATING",
            "statementDate", day));
    }

    private void decide(String authorization, Object requestId) {
        ok("APPROVAL_DECIDE", authorization, Map.of("requestId", requestId, "decision", "APPROVE"));
        deliverer.deliverPending().block();
    }

    private byte[] archived(String runId) {
        return get("/api/reports/runs/" + runId + "/export?format=csv",
            as("auditor", "report.archive.read", "fin.bank.activity.read")).expectStatus().isOk()
            .expectBody(byte[].class).returnResult().getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private void acceptProposals() {
        List<Map<String, Object>> proposals = (List<Map<String, Object>>) ok(MatchProcesses.PROPOSE, accountant,
            Map.of("bankCode", "OPERATING")).get("proposals");
        ok(MatchProcesses.ACCEPT, accountant, Map.of("bankCode", "OPERATING", "proposals", proposals.stream()
            .map(p -> Map.<String, Object>of("lineId", p.get("lineId"), "items",
                ((List<Map<String, Object>>) p.get("items")).stream()
                    .map(i -> Map.of("kind", i.get("kind"), "id", i.get("id"))).toList())).toList()));
    }

    @Test
    @Order(1)
    void theCheckOutstandingFor95DaysIsStale() {
        // FIN-BK-009 acceptance 1: CHK-1045 of 2025-12-28 is 95 days old on 2026-04-02.
        assertThat(report("finance.bank.stale_checks", accountant, Map.of("asOf", "2026-04-02")))
            .singleElement().satisfies(check -> {
                assertThat(check).containsEntry("bankCode", "OPERATING").containsEntry("checkNo", "CHK-1045")
                    .containsEntry("refKind", "OPENING");
                assertThat(amount(check.get("daysOutstanding"))).isEqualByComparingTo("95");
                assertThat(amount(check.get("amount"))).isEqualByComparingTo("3200.00");
            });
        assertThat(report("finance.bank.stale_checks", accountant, Map.of("asOf", "2026-04-02", "days", 96)))
            .isEmpty();
        assertThat(report("finance.bank.stale_checks", accountant, Map.of("asOf", "2026-03-27"))).isEmpty();
    }

    @Test
    @Order(2)
    void aDifferenceOf10IsNotCompleted() {
        // Before matching, every line is "not in the books" and every item outstanding; the identity still holds.
        Map<String, Object> first = prepare(accountant, "2026-01-31");
        januaryId = (String) first.get("reconciliationId");
        assertThat(amount(first.get("difference"))).isEqualByComparingTo(amount(first.get("notInBooks")));
        assertThat(refused(ReconciliationProcesses.PREPARE, accountant, Map.of("bankCode", "OPERATING",
            "statementDate", "2026-01-30"), 422)).isEqualTo(ReconciliationProcesses.NO_STATEMENT);

        acceptProposals();
        Map<String, Object> again = prepare(accountant, "2026-01-31");
        assertThat(again.get("reconciliationId")).isEqualTo(januaryId);
        assertThat(amount(again.get("statementBalance"))).isEqualByComparingTo("306490.00");
        assertThat(amount(again.get("depositsInTransit"))).isEqualByComparingTo("20000.00");
        assertThat(amount(again.get("outstandingPayments"))).isEqualByComparingTo("0.00");
        assertThat(amount(again.get("bookBalance"))).isEqualByComparingTo("326500.00");
        assertThat(amount(again.get("notInBooks"))).isEqualByComparingTo("-10.00");
        assertThat(amount(again.get("difference"))).isEqualByComparingTo("-10.00");
        assertThat(refused(ReconciliationProcesses.COMPLETE, accountant, Map.of("reconciliationId", januaryId), 422))
            .isEqualTo(ReconciliationProcesses.DIFFERENCE);
        assertThat(rec(januaryId)).containsEntry("status", ReconciliationEntities.PREPARED);
        // Matched by the 3rd, CHK-1045 is no longer stale in April.
        assertThat(report("finance.bank.stale_checks", accountant, Map.of("asOf", "2026-04-02"))).isEmpty();
    }

    @Test
    @Order(3)
    void theFeeEntryBringsItToZeroAndOnlyItsPreparerCompletesItUnderARule() {
        ok(BankEntryProcesses.RULE_SAVE, controller, Map.of("ruleCode", "FEE", "keywords", "service fee",
            "direction", "PAYMENT", "account", "6800", "documentPrefix", "BANK-FEE", "description",
            "Account service fee"));
        ok(BankEntryProcesses.FROM_LINE, accountant, Map.of("lineId", find(StatementEntities.LINE_DATASET,
            "bankCode", "OPERATING").stream().filter(l -> "BNK-0009".equals(l.get("bankReference"))).findFirst()
            .orElseThrow().get("lineId")));
        // Prepared again by the controller, who now must complete it.
        Map<String, Object> zero = prepare(controller, "2026-01-31");
        assertThat(amount(zero.get("adjustedBalance"))).isEqualByComparingTo("326490.00");
        assertThat(amount(zero.get("bookBalance"))).isEqualByComparingTo("326490.00");
        assertThat(amount(zero.get("difference"))).isEqualByComparingTo("0.00");
        assertThat(refused(ReconciliationProcesses.COMPLETE, accountant, Map.of("reconciliationId", januaryId), 422))
            .isEqualTo(ReconciliationProcesses.NOT_PREPARER);
        // No rule names a reviewer yet: nothing is signed off by its preparer alone (FIN-BK-008).
        assertThat(refused(ReconciliationProcesses.COMPLETE, controller, Map.of("reconciliationId", januaryId), 422))
            .isEqualTo(ReconciliationProcesses.NO_RULE);
        ok("CONTROL_CHANGE_PUBLISH", as("controller-3", "control.publish"), Map.of("changeId",
            held.get(SetupProcesses.BANK_REC_RULE)));
        Map<String, Object> submitted = ok(ReconciliationProcesses.COMPLETE, controller, Map.of("reconciliationId",
            januaryId));
        assertThat(submitted).containsEntry("status", ReconciliationEntities.SUBMITTED);
        assertThat(submitted.get("approvalRequestId")).isNotNull();
        // Submitted, it is not prepared again until decided.
        assertThat(refused(ReconciliationProcesses.PREPARE, controller, Map.of("bankCode", "OPERATING",
            "statementDate", "2026-01-31"), 422)).isEqualTo(ReconciliationProcesses.NOT_PREPARED);
        // FIN-BK-008: the preparer does not sign off their own.
        assertThat(refused("APPROVAL_DECIDE", controller, Map.of("requestId", submitted.get("approvalRequestId"),
            "decision", "APPROVE"), 422)).isEqualTo("APPROVAL_OWN_REQUEST");
        assertThat(rec(januaryId)).containsEntry("status", ReconciliationEntities.SUBMITTED);
    }

    @Test
    @Order(4)
    void februaryWaitsForJanuaryToBeSignedOff() {
        ok(TransferProcesses.POST, treasurer, Map.of("fromBank", "OPERATING", "toBank", "SAVINGS",
            "amount", "1000.00", "sentDate", "2026-02-02", "receivedDate", "2026-02-02"));
        statement(FEBRUARY);
        Map<String, Object> transfer = report(MatchProcesses.BOOK_ITEMS, accountant, Map.of("bankCode", "OPERATING"))
            .stream().filter(i -> String.valueOf(i.get("documentNo")).startsWith("TRF-")).findFirst().orElseThrow();
        ok(MatchProcesses.MATCH, accountant, Map.of("bankCode", "OPERATING", "lineIds", List.of(
            find(StatementEntities.LINE_DATASET, "bankCode", "OPERATING").stream()
                .filter(l -> "BNK-0101".equals(l.get("bankReference"))).findFirst().orElseThrow().get("lineId")),
            "items", List.of(Map.of("kind", transfer.get("refKind"), "id", transfer.get("refId")))));
        Map<String, Object> february = prepare(controller, "2026-02-28");
        februaryId = (String) february.get("reconciliationId");
        assertThat(amount(february.get("difference"))).isEqualByComparingTo("0.00");
        assertThat(refused(ReconciliationProcesses.COMPLETE, controller, Map.of("reconciliationId", februaryId),
            422)).isEqualTo(ReconciliationProcesses.EARLIER_OPEN);
        // January, worked out now, is untouched by February.
        assertThat(amount(rows("2026-01-31").stream().filter(r -> "BOOK_BALANCE".equals(r.get("section")))
            .findFirst().orElseThrow().get("amount"))).isEqualByComparingTo("326490.00");
    }

    @Test
    @Order(5)
    void anotherControllerSignsItOffAndTheReportIsIssued() {
        decide(reviewer, rec(januaryId).get("approvalRequestId"));
        Map<String, Object> signed = rec(januaryId);
        assertThat(signed).containsEntry("status", ReconciliationEntities.SIGNED_OFF)
            .containsEntry("preparedBy", "controller").containsEntry("reviewedBy", "controller-2");
        assertThat(signed.get("signedOffTime")).isNotNull();
        assertThat(signed.get("reportRunId")).isNull();
        // Issued once, by whoever reconciles: as the books were at the sign-off.
        januaryRun = (String) ok(ReconciliationProcesses.ISSUE_REPORT, accountant, Map.of("reconciliationId",
            januaryId)).get("reportRunId");
        assertThat(januaryRun).isNotNull();
        assertThat(rec(januaryId)).containsEntry("reportRunId", januaryRun);
        assertThat(rec(januaryId).get("reportHash")).isNotNull();
        assertThat(refused(ReconciliationProcesses.ISSUE_REPORT, accountant, Map.of("reconciliationId", januaryId),
            422)).isEqualTo(ReconciliationProcesses.ISSUED_ALREADY);
        assertThat(refused(ReconciliationProcesses.ISSUE_REPORT, accountant, Map.of("reconciliationId", februaryId),
            422)).isEqualTo(ReconciliationProcesses.NOT_SIGNED_OFF);
        januaryReport = archived(januaryRun);
        januaryRows = rows("2026-01-31");
        String text = new String(januaryReport, StandardCharsets.UTF_8);
        assertThat(text).contains("Balance per bank statement", "306490.00", "DEPOSIT_IN_TRANSIT,2026-01-25,RCPT-0003", "Prepared by controller",
            "Reviewed by controller-2", "Difference");
        // Signed off, it is not prepared again; its matches stay.
        assertThat(refused(ReconciliationProcesses.PREPARE, controller, Map.of("bankCode", "OPERATING",
            "statementDate", "2026-01-31"), 422)).isEqualTo(ReconciliationProcesses.NOT_PREPARED);
        Map<String, Object> deposit = report("finance.bank.match_history", accountant, Map.of("bankCode",
            "OPERATING")).stream().filter(h -> "BNK-0002".equals(h.get("statementItems"))).findFirst().orElseThrow();
        assertThat(refused(MatchProcesses.UNMATCH, accountant, Map.of("matchId", deposit.get("matchId"),
            "reason", "After the sign-off"), 422)).isEqualTo(MatchProcesses.RECONCILED);
    }

    @Test
    @Order(6)
    void februaryChangedUnderItsApprovalIsPreparedAgainAndJanuaryReprintsIdentically() {
        Map<String, Object> first = ok(ReconciliationProcesses.COMPLETE, controller, Map.of("reconciliationId",
            februaryId));
        // Withdrawn by who completed it only; then completed again, with a new request.
        assertThat(refused(ReconciliationProcesses.WITHDRAW, accountant, Map.of("reconciliationId", februaryId), 422))
            .isEqualTo(ReconciliationProcesses.NOT_PREPARER);
        assertThat(ok(ReconciliationProcesses.WITHDRAW, controller, Map.of("reconciliationId", februaryId)))
            .containsEntry("status", ReconciliationEntities.PREPARED);
        assertThat(refused(ReconciliationProcesses.WITHDRAW, controller, Map.of("reconciliationId", februaryId), 422))
            .isEqualTo(ReconciliationProcesses.NOT_SUBMITTED);
        Map<String, Object> submitted = ok(ReconciliationProcesses.COMPLETE, controller, Map.of("reconciliationId",
            februaryId));
        assertThat(submitted.get("approvalRequestId")).isNotEqualTo(first.get("approvalRequestId"));
        // A result that the platform's request does not bear out changes nothing, whoever sends it.
        ok(ReconciliationProcesses.APPROVAL_RESULT, as("forger", "*"), Map.of("subject",
            ReconciliationProcesses.SUBJECT, "entityId", februaryId, "status", "APPROVED", "requestId",
            submitted.get("approvalRequestId")));
        assertThat(rec(februaryId)).containsEntry("status", ReconciliationEntities.SUBMITTED);
        // A February payment after the completion: what the reviewer would approve is no longer what it shows.
        ok(TransferProcesses.POST, treasurer, Map.of("fromBank", "OPERATING", "toBank", "SAVINGS",
            "amount", "500.00", "sentDate", "2026-02-10", "receivedDate", "2026-02-10"));
        decide(reviewer, submitted.get("approvalRequestId"));
        Map<String, Object> february = rec(februaryId);
        assertThat(february).containsEntry("status", ReconciliationEntities.PREPARED);
        assertThat(february.get("reportRunId")).isNull();
        Map<String, Object> again = prepare(controller, "2026-02-28");
        assertThat(amount(again.get("outstandingPayments"))).isEqualByComparingTo("-500.00");
        assertThat(amount(again.get("difference"))).isEqualByComparingTo("0.00");
        // February's match counts from February on: undoing it is still possible.
        Map<String, Object> sweep = report("finance.bank.match_history", accountant, Map.of("bankCode",
            "OPERATING")).stream().filter(h -> "BNK-0101".equals(h.get("statementItems"))).findFirst().orElseThrow();
        ok(MatchProcesses.UNMATCH, accountant, Map.of("matchId", sweep.get("matchId"), "reason", "Checking"));

        // FIN-SCN-05 expected: the reprint after February postings is the identical report, as is January itself.
        assertThat(archived(januaryRun)).isEqualTo(januaryReport);
        assertThat(rows("2026-01-31")).isEqualTo(januaryRows);

        // March was never reconciled: April waits for it, not only for reconciliations begun.
        statement("""
            date,bank_reference,description,amount
            2026-03-01,OPENING,OPENING LEDGER BALANCE,305490.00
            2026-03-05,BNK-0201,DEPOSIT,100.00
            2026-03-31,CLOSING,CLOSING LEDGER BALANCE,305590.00
            """);
        statement("""
            date,bank_reference,description,amount
            2026-04-01,OPENING,OPENING LEDGER BALANCE,305590.00
            2026-04-03,BNK-0301,DEPOSIT,100.00
            2026-04-30,CLOSING,CLOSING LEDGER BALANCE,305690.00
            """);
        String april = (String) prepare(controller, "2026-04-30").get("reconciliationId");
        assertThat(refused(ReconciliationProcesses.COMPLETE, controller, Map.of("reconciliationId", april), 422))
            .isEqualTo(ReconciliationProcesses.EARLIER_OPEN);
    }

    @Test
    @Order(7)
    void theCashPositionShowsBooksStatementsAndWhatIsDue() {
        List<Map<String, Object>> position = report("finance.bank.cash_position", accountant, Map.of("asOf",
            "2026-01-31"));
        assertThat(position).filteredOn(r -> "ACCOUNT".equals(r.get("section")) && "OPERATING".equals(
            r.get("bankCode"))).singleElement().satisfies(r -> {
                assertThat(r).containsEntry("glAccount", "1010").containsEntry("statementDate", "2026-01-31");
                assertThat(amount(r.get("bookBalance"))).isEqualByComparingTo("326490.00");
                assertThat(amount(r.get("statementBalance"))).isEqualByComparingTo("306490.00");
            });
        // The receipts settled INV-1001 and INV-1002 in full: they are no longer expected.
        assertThat(position).filteredOn(r -> "EXPECTED_RECEIPT".equals(r.get("section")))
            .extracting(r -> r.get("documentNo")).doesNotContain("INV-1001", "INV-1002");
    }

    @Test
    @Order(8)
    void reconciliationsKeepTheirVersions() {
        assertOnlyInserted("fi_bank_reconciliation_version");
    }
}
