package com.jabiz.finance.it;

import com.jabiz.finance.ap.BillEntities;
import com.jabiz.finance.ap.BillProcesses;
import com.jabiz.finance.ap.PaymentProcesses;
import com.jabiz.finance.ar.InvoiceEntities;
import com.jabiz.finance.ar.ReceiptEntities;
import com.jabiz.finance.ar.WriteOffProcesses;
import com.jabiz.finance.bank.BankAccountProcesses;
import com.jabiz.finance.bank.MatchProcesses;
import com.jabiz.finance.bank.TransferEntities;
import com.jabiz.finance.bank.StatementProcesses;
import com.jabiz.finance.bank.TransferProcesses;
import com.jabiz.finance.calc.PeriodPolicy;
import com.jabiz.finance.close.CloseEntities;
import com.jabiz.finance.close.CloseProcesses;
import com.jabiz.finance.close.ReopenProcesses;
import com.jabiz.finance.fa.AssetEntities;
import com.jabiz.finance.fa.AssetEventProcesses;
import com.jabiz.finance.fa.DepreciationEntities;
import com.jabiz.finance.fa.DepreciationProcesses;
import com.jabiz.finance.gl.AccountProcesses;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.finance.gl.JournalEntities;
import com.jabiz.finance.gl.JournalProcesses;
import com.jabiz.finance.gl.JournalValidator;
import com.jabiz.runtime.ledger.LedgerEntities;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.jabiz.finance.it.JournalLifecycleIT.entry;
import static com.jabiz.finance.it.JournalLifecycleIT.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIN-SCN-15, adjustments and exceptions on a copy of January's books after FIN-SCN-06 (not part of FIN-EXP-*):
 * <ol>
 *   <li>the controller renames 6800, deactivates 7300 and cannot delete 6400, which has postings; a journal line to
 *       7300 is then refused (FIN-GL-004);</li>
 *   <li>a 500.00 test invoice is written off against the allowance once approved (FIN-AR-012);</li>
 *   <li>a vendor credit of 500.00 is applied to CS-0126 (1,200.00), leaving 700.00 payable (FIN-AP-008); the 700.00
 *       are paid by check, which is voided on 2026-02-10, reopening the bill (FIN-AP-014);</li>
 *   <li>50,000.00 move from savings to operating (FIN-BK-002);</li>
 *   <li>FA-001's life is extended by 12 months from 2026-02, February then taking 1,489.36 (FIN-FA-006); FA-002 is
 *       sold on 2026-01-31 for 60,000.00 at a gain of 1,666.67 (FIN-FA-007). January being closed, the sale is
 *       refused until January is reopened through its governed reopening (FIN-PC-006).</li>
 * </ol>
 * The original books are unchanged: one schema holds both, so "the original" is January as known at its close, whose
 * trial balance is still FIN-EXP-03 and the close artifact's; the steps show only in what is known after.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class FinScn15IT extends JanuaryBooks {

    @Test
    @SuppressWarnings("unchecked")
    void adjustmentsAndExceptionsOnACopyOfTheJanuaryBooks() throws IOException {
        januaryPostings();
        Map<String, Object> closed = closeJanuary();
        Map<String, Object> artifact = read(CloseEntities.ARTIFACT_DATASET, closed.get("artifactId"));
        String atClose = (String) artifact.get("knownAt");
        assertThat(balances("2026-01-31", null)).isEqualTo(finExp03());

        // ---- Step 1, 2 February: the chart (FIN-GL-004) ----------------------------------------------------------
        clock.advance(Duration.ofDays(2));
        people();
        // Acceptance 1: an account with postings is not deleted; deactivation is offered instead.
        Map<String, Object> refusal = run(AccountProcesses.DELETE, controller, Map.of("accountCode", "6400"))
            .expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody();
        assertThat(((List<Map<String, Object>>) refusal.get("violations")).getFirst())
            .containsEntry("ruleCode", AccountProcesses.HAS_POSTINGS)
            .satisfies(v -> assertThat((String) v.get("message")).contains("deactivate"));
        assertThat(find(GlEntities.ACCOUNT_DATASET, "accountCode", "6400")).hasSize(1);
        // Acceptance 2: 6800 renamed; its history lists the old and new names, the controller and the time.
        String renamedAt = clock.instant().toString().substring(0, 10);
        assertThat(ok(AccountProcesses.UPDATE, controller, Map.of("accountCode", "6800",
            "accountName", "Bank Service Charges"))).containsEntry("changed", true);
        Object ledgerAccount = find(GlEntities.ACCOUNT_DATASET, "accountCode", "6800").getFirst()
            .get("ledgerAccountId");
        List<Map<String, Object>> history = get("/api/datasets/" + LedgerEntities.ACCOUNT_DATASET + "/entities/"
            + ledgerAccount + "/history", as("auditor", "*")).expectStatus().isOk().expectBody(LIST).returnResult()
            .getResponseBody();
        assertThat(history).hasSizeGreaterThanOrEqualTo(2);
        assertThat(history.toString()).contains("Bank Fees", "Bank Service Charges", "controller", renamedAt);
        // Acceptance 3: 7300 deactivated takes no new line, but stays in the reports.
        assertThat(ok(AccountProcesses.DEACTIVATE, controller, Map.of("accountCode", "7300")))
            .containsEntry("active", false).containsEntry("changed", true);
        // A draft may name it; posting it is refused.
        String toInactive = (String) ok(JournalProcesses.SAVE, accountant, entry("2026-02-02",
            "Interest accrued", List.of(line("2100", "10.00", null, null), line("7300", null, "10.00", null))))
            .get("journalId");
        assertThat(refused(JournalProcesses.SUBMIT, accountant, Map.of("journalId", toInactive), 422))
            .isEqualTo(JournalValidator.ACCOUNT_INACTIVE);
        assertThat(balances("2026-02-02", null)).containsEntry("7300", new BigDecimal("-125.00"));

        // ---- Step 2, 3 February: a 500.00 test invoice written off against the allowance (FIN-AR-012) ------------
        clock.advance(Duration.ofDays(1));
        people();
        String test = invoice("C200", "2026-02-03", List.of(invoiceLine("Test engineering", "1", "500.00", "4100",
            null)));
        String testNo = (String) read(InvoiceEntities.INVOICE_DATASET, test).get("invoiceNo");
        Map<String, Object> writeOff = ok(WriteOffProcesses.REQUEST, arClerk, Map.of("invoiceId", test,
            "writeOffDate", "2026-02-03", "amount", "500.00", "reason", "Test invoice uncollectible"));
        assertThat(writeOff).containsEntry("approval", "PENDING");
        // Nothing posts before the approval.
        assertThat(postingLines(testNo)).containsEntry("1200", new BigDecimal("500.00"));
        assertThat(read(InvoiceEntities.INVOICE_DATASET, test)).containsEntry("status", InvoiceEntities.POSTED);
        decide(writeOff.get("approvalRequestId"));
        assertThat(find(ReceiptEntities.WRITE_OFF_DATASET, "invoiceId", test)).singleElement()
            .satisfies(w -> assertThat(w).containsEntry("status", ReceiptEntities.POSTED));
        Map<String, Object> writtenOff = read(InvoiceEntities.INVOICE_DATASET, test);
        assertThat(writtenOff).containsEntry("status", InvoiceEntities.WRITTEN_OFF);
        assertThat(amount(writtenOff.get("openAmount"))).isEqualByComparingTo("0.00");
        // The allowance debited and receivables credited: the invoice nets to the allowance and its revenue.
        assertThat(postingLines(testNo)).isEqualTo(new TreeMap<>(Map.of("1210", new BigDecimal("500.00"),
            "4100", new BigDecimal("-500.00"))));

        // ---- Step 3, 4 February: a vendor credit of 500.00 applied to CS-0126 of 1,200.00 (FIN-AP-008) -----------
        clock.advance(Duration.ofDays(1));
        people();
        String cs0126 = (String) find(BillEntities.BILL_DATASET, "vendorInvoiceNo", "CS-0126").getFirst()
            .get("billId");
        assertThat(amount(read(BillEntities.BILL_DATASET, cs0126).get("openAmount")))
            .isEqualByComparingTo("1200.00");
        Map<String, Object> credit = new LinkedHashMap<>();
        credit.put("vendorCode", "V400");
        credit.put("vendorInvoiceNo", "CS-CR-0126");
        credit.put("invoiceDate", "2026-02-04");
        credit.put("kind", "CREDIT");
        credit.put("originalBillId", cs0126);
        credit.put("lines", List.of(Map.of("description", "Service credit, January outage", "amount", "500.00",
            "account", "6500")));
        String creditId = (String) ok(BillProcesses.SAVE, apClerk, credit).get("billId");
        Map<String, Object> postedCredit = ok(BillProcesses.POST, apClerk, Map.of("billId", creditId));
        assertThat(postingLines((String) postedCredit.get("billNo"))).isEqualTo(new TreeMap<>(Map.of(
            "2000", new BigDecimal("500.00"), "6500", new BigDecimal("-500.00"))));
        Map<String, Object> applied = ok(BillProcesses.APPLY, apClerk, Map.of("creditId", creditId,
            "billId", cs0126, "amount", "500.00", "applicationDate", "2026-02-04"));
        assertThat(amount(applied.get("billOpen"))).isEqualByComparingTo("700.00");
        assertThat(amount(applied.get("creditOpen"))).isEqualByComparingTo("0.00");
        assertThat(amount(read(BillEntities.BILL_DATASET, cs0126).get("openAmount"))).isEqualByComparingTo("700.00");

        // 5 February: the 700.00 left paid by check 10001.
        clock.advance(Duration.ofDays(1));
        people();
        Map<String, Object> proposed = ok(PaymentProcesses.PROPOSE, apClerk, Map.of("paymentDate", "2026-02-05",
            "method", "CHECK", "dueThrough", "2026-02-05", "vendorCodes", List.of("V400")));
        String runId = (String) proposed.get("runId");
        ok(PaymentProcesses.ADD, apClerk, Map.of("runId", runId, "billId", cs0126));
        decide(ok(PaymentProcesses.SUBMIT, apClerk, Map.of("runId", runId)).get("approvalRequestId"));
        List<Map<String, Object>> payments = (List<Map<String, Object>>) ok(PaymentProcesses.RELEASE, treasurer,
            Map.of("runId", runId)).get("payments");
        assertThat(payments).extracting(p -> p.get("vendorCode") + " " + p.get("checkNo") + " "
            + amount(p.get("amount")).toPlainString()).containsExactly("V400 10001 700.00");
        Map<String, Object> check = payments.getFirst();
        String checkNo = (String) check.get("paymentNo");
        assertThat(postingLines(checkNo)).isEqualTo(Map.of("2000", new BigDecimal("700.00"),
            "1010", new BigDecimal("-700.00")));
        assertThat(amount(read(BillEntities.BILL_DATASET, cs0126).get("openAmount"))).isZero();

        // 10 February: the check is stopped and voided (FIN-AP-014): CS-0126 is open again and the bank account
        // debited on the void date; the payment stays visible, void.
        clock.advance(Duration.ofDays(5));
        people();
        Map<String, Object> voided = ok(PaymentProcesses.VOID, controller, Map.of("paymentId",
            check.get("paymentId"), "voidDate", "2026-02-10", "reason", "Check stopped"));
        assertThat(voided).containsEntry("status", "VOID")
            .containsEntry("billsReopened", List.of(read(BillEntities.BILL_DATASET, cs0126).get("billNo")));
        assertThat(amount(read(BillEntities.BILL_DATASET, cs0126).get("openAmount"))).isEqualByComparingTo("700.00");
        assertThat(find(JournalEntities.POSTING_DATASET, "documentNo", checkNo))
            .extracting(p -> p.get("postingDate")).containsExactlyInAnyOrder("2026-02-05", "2026-02-10");
        assertThat(postingLines(checkNo)).isEmpty();
        assertThat(balances("2026-02-10", null).get("1010").subtract(balances("2026-02-09", null).get("1010")))
            .isEqualByComparingTo("700.00");
        assertThat(refused(PaymentProcesses.VOID, controller, Map.of("paymentId", check.get("paymentId"),
            "voidDate", "2026-02-10", "reason", "Again"), 422)).isEqualTo(PaymentProcesses.NOT_POSTED);

        // ---- Step 4, 11 February: 50,000.00 from savings to operating (FIN-BK-002) -------------------------------
        clock.advance(Duration.ofDays(1));
        people();
        // The savings account becomes a bank account of the books (FIN-SCN-01's books keep it as a cash account).
        ok(BankAccountProcesses.SAVE, treasurer, Map.of("bankCode", "SAVINGS", "bankName", "Lakeside National Bank",
            "glAccount", "1050", "routingNumber", "111000025", "companyAccountNumber", "000987654321"));
        // Its cutover: the bank held the opening balance of 1050, 100,000.00, with nothing outstanding.
        // (The import takes no empty file; its process takes the cutover with no items.)
        assertThat(ok(StatementProcesses.OPENING_ITEMS, as("migrator", "fin.migration"),
            Map.of("bankCode", "SAVINGS", "statementBalance", "100000.00", "items", List.of())))
            .containsEntry("cutoverDate", "2025-12-31").containsEntry("items", 0);
        Map<String, BigDecimal> before = balances("2026-02-11", null);
        Map<String, Object> transfer = ok(TransferProcesses.POST, treasurer, Map.of("fromBank", "SAVINGS",
            "toBank", "OPERATING", "amount", "50000.00", "sentDate", "2026-02-11", "receivedDate", "2026-02-11",
            "description", "Funding the operating account"));
        String transferNo = (String) transfer.get("transferNo");
        assertThat(transfer).containsEntry("status", TransferEntities.COMPLETED);
        assertThat((List<?>) transfer.get("glNos")).hasSize(1);
        assertThat(postingLines(transferNo)).isEqualTo(Map.of("1010", new BigDecimal("50000.00"),
            "1050", new BigDecimal("-50000.00")));
        Map<String, BigDecimal> after = balances("2026-02-11", null);
        assertThat(after.get("1010").subtract(before.get("1010"))).isEqualByComparingTo("50000.00");
        assertThat(after.get("1050").subtract(before.get("1050"))).isEqualByComparingTo("-50000.00");
        // Both reconciliations can match it: it is an open book item of each account.
        for (String bank : List.of("OPERATING", "SAVINGS")) {
            assertThat(report(MatchProcesses.BOOK_ITEMS, accountant, Map.of("bankCode", bank)))
                .as(bank).extracting(i -> i.get("documentNo")).contains(transferNo);
        }

        // ---- Step 5, 12 February: FA-001's life extended from 2026-02 (FIN-FA-006) -------------------------------
        clock.advance(Duration.ofDays(1));
        people();
        Map<String, Object> fa001 = asset("FA-001");
        assertThat(amount(fa001.get("lifeMonths"))).isEqualByComparingTo("60");
        Map<String, Object> change = ok(AssetEventProcesses.CHANGE, controller, Map.of("assetId",
            fa001.get("assetId"), "lifeMonths", 72, "reason", "Overhauled: twelve more months"));
        assertThat(change).containsEntry("fromPeriod", "2026-02");
        // 70,000.00 left over the 47 months left.
        assertThat(amount(change.get("nextAmount"))).isEqualByComparingTo("1489.36");

        // FA-002 sold on 2026-01-31 for 60,000.00 (FIN-FA-007): January is closed, so the sale waits for the
        // governed reopening of January.
        Map<String, Object> sale = Map.of("assetId", asset("FA-002").get("assetId"), "disposalDate", "2026-01-31",
            "kind", "SALE", "proceeds", "60000.00", "proceedsAccount", "1010", "reason", "Sold to the dealer");
        assertThat(refused(AssetEventProcesses.DISPOSE, controller, sale, 422)).isEqualTo(PeriodPolicy.PERIOD_CLOSED);
        Map<String, Object> reopen = ok(ReopenProcesses.REQUEST, accountant, Map.of("periodKey", "2026-01",
            "reason", "Sale of FA-002 on 31 January"));
        decide(reopen.get("approvalRequestId"));
        assertThat(find(GlEntities.PERIOD_DATASET, "periodKey", "2026-01").getFirst())
            .containsEntry("status", "OPEN").containsEntry("faStatus", "OPEN");
        Map<String, Object> sold = ok(AssetEventProcesses.DISPOSE, controller, sale);
        assertThat(sold).containsEntry("documentNo", "DSP-FA-002");
        // After January's depreciation: net book value 58,333.33, a gain of 1,666.67.
        assertThat(amount(sold.get("monthDepreciation"))).isEqualByComparingTo("0.00");
        assertThat(amount(sold.get("accumulated"))).isEqualByComparingTo("11666.67");
        assertThat(amount(sold.get("gainLoss"))).isEqualByComparingTo("1666.67");
        assertThat(postingLines("DSP-FA-002")).isEqualTo(Map.of("1590", new BigDecimal("11666.67"),
            "1010", new BigDecimal("60000.00"), "1510", new BigDecimal("-70000.00"),
            "7400", new BigDecimal("-1666.67")));
        assertThat(find(JournalEntities.POSTING_DATASET, "documentNo", "DSP-FA-002")).singleElement()
            .satisfies(p -> assertThat(p).containsEntry("postingDate", "2026-01-31")
                .containsEntry("periodKey", "2026-01"));
        assertThat(asset("FA-002")).containsEntry("status", AssetEntities.DISPOSED);

        // February's run: FA-001 by its new life, FA-003 as planned, FA-002 gone; January as it was.
        Map<String, Object> february = ok(DepreciationProcesses.RUN, accountant, Map.of("periodKey", "2026-02"));
        assertThat(february).containsEntry("runNo", "DEP-2602").containsEntry("posted", true);
        Map<String, BigDecimal> lines = new TreeMap<>();
        find(DepreciationEntities.LINE_DATASET, "runId", february.get("runId"))
            .forEach(l -> lines.put((String) l.get("assetNo"), amount(l.get("amount"))));
        assertThat(lines).isEqualTo(Map.of("FA-001", new BigDecimal("1489.36"), "FA-003", new BigDecimal("333.33")));
        assertThat(postingLines("DEP-2602")).isEqualTo(Map.of("6700", new BigDecimal("1822.69"),
            "1590", new BigDecimal("-1822.69")));
        assertThat(postingLines("DEP-2601")).isEqualTo(expectedDocuments("DEP-2601", "depreciation")
            .get("DEP-2601"));
        assertThat(report("finance.fa.depreciation_schedule", controller, Map.of("assetNo", "FA-001")))
            .extracting(r -> r.get("periodKey") + " " + amount(r.get("amount")).toPlainString())
            .containsExactly("2026-01 2000.00", "2026-02 1489.36");

        // ---- Expected: the original books are unchanged ---------------------------------------------------------
        // January as known at its close is FIN-EXP-03, the close artifact's trial balance, after all the steps.
        assertThat(balances("2026-01-31", atClose)).isEqualTo(finExp03());
        List<Map<String, Object>> asClosed = report(CloseProcesses.TRIAL_BALANCE, controller,
            Map.of("through", "2026-01-31", "adjustments", false, "knownAt", atClose));
        assertThat(CloseProcesses.trialBalanceHash(asClosed)).isEqualTo(artifact.get("trialBalanceHash"));
        // Known now, January differs only by the sale dated in it; everything else the steps did is February's.
        List<Map<String, Object>> compared = report("finance.gl.trial_balance_compare", controller,
            Map.of("through", "2026-01-31", "earlier", atClose, "later", clock.instant().toString(),
                "changedOnly", true));
        assertThat(compared).extracting(r -> r.get("accountCode") + " " + amount(r.get("difference")))
            .containsExactlyInAnyOrder("1010 60000.00", "1510 -70000.00", "1590 11666.67", "7400 -1666.67");
        // The close artifact itself stands as it was.
        assertThat(read(CloseEntities.ARTIFACT_DATASET, closed.get("artifactId")))
            .containsEntry("trialBalanceHash", artifact.get("trialBalanceHash"))
            .containsEntry("knownAt", atClose);
        assertOnlyInserted("fi_account_version", "fi_bill_version", "fi_asset_version", "fi_asset_change_version",
            "fi_asset_disposal_version", "fi_depreciation_run_version", "fi_period_reopen_version");
    }

    private Map<String, Object> asset(String number) {
        return find(AssetEntities.ASSET_DATASET, "assetNo", number).getFirst();
    }

    /**
     * The trial balance on the day, as known now or at {@code knownAt}: account to balance, debits positive, summary
     * accounts and accounts at zero left out.
     */
    private Map<String, BigDecimal> balances(String day, String knownAt) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("through", day);
        if (knownAt != null) {
            params.put("knownAt", knownAt);
        }
        Map<String, BigDecimal> balances = new TreeMap<>();
        for (Map<String, Object> row : report("finance.gl.trial_balance", as("reader", "ledger.read"), params)) {
            BigDecimal balance = amount(row.get("debit")).subtract(amount(row.get("credit")));
            if (balance.signum() != 0 && !Boolean.TRUE.equals(row.get("summary"))) {
                balances.put((String) row.get("accountCode"), balance);
            }
        }
        return balances;
    }

    /** FIN-EXP-03 as {@code 21-expected-results.md} has it: account to balance, debits positive. */
    private static Map<String, BigDecimal> finExp03() throws IOException {
        Map<String, BigDecimal> expected = new TreeMap<>();
        for (String[] row : expectedRows("FIN-EXP-03")) {
            if (row[0].matches("\\d{4}")) {
                expected.put(row[0], money(row[2]).subtract(money(row[3])).setScale(2));
            }
        }
        return expected;
    }
}
