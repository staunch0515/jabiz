package com.jabiz.finance.it;

import com.jabiz.finance.ar.InvoiceEntities;
import com.jabiz.finance.ar.InvoiceProcesses;
import com.jabiz.finance.ar.ReceiptProcesses;
import com.jabiz.finance.fx.FxRates;
import com.jabiz.finance.fx.FxSettingsProcesses;
import com.jabiz.finance.gl.JournalEntities;
import com.jabiz.finance.gl.JournalImportProcesses;
import com.jabiz.finance.gl.JournalProcesses;
import com.jabiz.finance.gl.JournalValidator;
import com.jabiz.finance.setup.FinanceRoles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.jabiz.runtime.event.OutboxDeliverer;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Receivables in a foreign currency (ROADMAP F7a; FIN-FX-003, 004): C400's euro invoices converted at the spot rate
 * of their day or of the days just before; RCPT-0005's realized loss of 250.00 against INV-1005 as FIN-EXP-12 has
 * it; partial receipts at other rates, one at a rate given, clearing the invoice's dollars exactly; unapplied cash
 * applied later and taken back; a credit memo at another rate; and the aging's dollars equal to the receivables
 * account throughout.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FxReceivablesIT extends FinanceItSupport {

    @Autowired
    OutboxDeliverer deliverer;

    private static boolean loaded;
    private static final Map<String, String> IDS = new LinkedHashMap<>();

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
    }

    private String post(String date, String amount) {
        String id = (String) ok(InvoiceProcesses.SAVE, clerk, invoiceInput("C400", date, null,
            List.of(invoiceLine("Components", "1", amount, "4000", null)))).get("invoiceId");
        ok(InvoiceProcesses.POST, clerk, Map.of("invoiceId", id));
        return id;
    }

    private Map<String, Object> invoice(String id) {
        return read(InvoiceEntities.INVOICE_DATASET, id);
    }

    private static Map<String, Object> receipt(String date, String amount, String rate,
        List<Map<String, Object>> applications) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("customerCode", "C400");
        input.put("receiptDate", date);
        input.put("amount", amount);
        input.put("method", "WIRE");
        input.put("bankAccount", "1010");
        input.put("applications", applications);
        if (rate != null) {
            input.put("exchangeRate", rate);
        }
        return input;
    }

    private static Map<String, Object> pay(String invoiceId, String amount) {
        return Map.of("invoiceId", invoiceId, "amount", amount);
    }

    private static Map<String, BigDecimal> amounts(Object... pairs) {
        Map<String, BigDecimal> amounts = new TreeMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            amounts.put((String) pairs[i], new BigDecimal((String) pairs[i + 1]));
        }
        return amounts;
    }

    /** The receivables' dollars on the aging equal the receivables account on the day. */
    private void agingEqualsTheLedger(String day) {
        BigDecimal aging = report("finance.ar.aging", controller, Map.of("agingDate", day)).stream()
            .map(r -> amount(r.get("openAmountUsd"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(aging).as(day).isEqualByComparingTo(ledgerBalances(day).getOrDefault("1200", BigDecimal.ZERO));
    }

    @Test
    @Order(1)
    void theSettingsNameIncomeStatementAccounts() {
        assertThat(refused(FxSettingsProcesses.SET, controller, Map.of("realizedAccount", "1200"), 422))
            .isEqualTo(FxSettingsProcesses.WRONG_ACCOUNT);
        assertThat(refused(FxSettingsProcesses.SET, controller, Map.of("revaluationRateType", "MONTHLY"), 422))
            .isEqualTo(FxSettingsProcesses.WRONG_RATE_TYPE);
        run(FxSettingsProcesses.SET, clerk, Map.of("realizedAccount", "7200")).expectStatus().isForbidden();
        // Before the settings name an account, a settlement with a difference is refused.
        String early = post("2026-01-12", "1000.00");
        IDS.put("early", early);
        assertThat(refused(ReceiptProcesses.RECORD, clerk, receipt("2026-01-31", "1000.00", null,
            List.of(pay(early, "1000.00"))), 422)).isEqualTo(FxSettingsProcesses.NO_SETTINGS);
        assertThat(ok(FxSettingsProcesses.SET, controller, Map.of("realizedAccount", "7200",
            "unrealizedAccount", "7210"))).containsEntry("changed", true);
    }

    @Test
    @Order(2)
    void anInvoiceTakesTheRateOfItsDayOrOfTheDaysJustBefore() {
        // INV-1005 for EUR 50,000.00 on 2026-01-12: 54,250.00 at 1.0850 (FIN-FX-003 acceptance 1).
        String inv1005 = post("2026-01-12", "50000.00");
        IDS.put("INV-1005", inv1005);
        assertThat(invoice(inv1005)).containsEntry("currency", "EUR").satisfies(i -> {
            assertThat(new BigDecimal(String.valueOf(i.get("exchangeRate")))).isEqualByComparingTo("1.0850");
            assertThat(amount(i.get("totalUsd"))).isEqualByComparingTo("54250.00");
        });
        // Two days later there is no rate of the day: the 12th's, within five days.
        String later = post("2026-01-14", "10000.00");
        IDS.put("later", later);
        assertThat(amount(invoice(later).get("totalUsd"))).isEqualByComparingTo("10850.00");
        // Eight days later the 12th's is too old.
        String stale = (String) ok(InvoiceProcesses.SAVE, clerk, invoiceInput("C400", "2026-01-20", null,
            List.of(invoiceLine("Components", "1", "100.00", "4000", null)))).get("invoiceId");
        assertThat(refused(InvoiceProcesses.POST, clerk, Map.of("invoiceId", stale), 422))
            .isEqualTo(InvoiceProcesses.NO_RATE);
        agingEqualsTheLedger("2026-01-31");
    }

    @Test
    @Order(3)
    void rcpt0005RealizesTheLossOfFinExp12() {
        String inv1005 = IDS.get("INV-1005");
        // The early invoice settles too: 1,000.00 at 1.0920 against 1.0850, a gain of 7.00.
        Map<String, Object> early = ok(ReceiptProcesses.RECORD, clerk, receipt("2026-01-31", "1000.00", null,
            List.of(pay(IDS.get("early"), "1000.00"))));
        assertThat(postingLines((String) early.get("receiptNo"))).isEqualTo(amounts("1010", "1092.00",
            "1200", "-1085.00", "7200", "-7.00"));
        // EUR 50,000.00 at 1.0800 on 2026-02-20: 54,000.00 in the bank, INV-1005's 54,250.00 off the receivables,
        // a realized loss of 250.00 (FIN-FX-004 acceptance 1).
        Map<String, Object> rcpt = ok(ReceiptProcesses.RECORD, clerk, receipt("2026-02-20", "50000.00", null,
            List.of(pay(inv1005, "50000.00"))));
        assertThat(postingLines((String) rcpt.get("receiptNo"))).isEqualTo(amounts("1010", "54000.00",
            "1200", "-54250.00", "7200", "250.00"));
        assertThat(invoice(inv1005)).satisfies(i -> {
            assertThat(amount(i.get("openAmount"))).isZero();
            assertThat(amount(i.get("openAmountUsd"))).isZero();
        });
        assertThat(find(InvoiceEntities.APPLICATION_DATASET, "invoiceId", inv1005)).singleElement()
            .satisfies(a -> {
                assertThat(amount(a.get("amountUsd"))).isEqualByComparingTo("54250.00");
                assertThat(amount(a.get("sourceAmountUsd"))).isEqualByComparingTo("54000.00");
                assertThat(amount(a.get("fxGainLoss"))).isEqualByComparingTo("-250.00");
            });
        // A receipt in dollars does not pay a euro invoice; a discount is not taken in euros.
        String later = IDS.get("later");
        Map<String, Object> dollars = receipt("2026-02-20", "100.00", null, List.of(pay(later, "100.00")));
        dollars.put("customerCode", "C100");
        assertThat(refused(ReceiptProcesses.RECORD, clerk, dollars, 422)).isEqualTo(ReceiptProcesses.NOT_OPEN);
        assertThat(refused(ReceiptProcesses.RECORD, clerk, receipt("2026-02-20", "100.00", null,
            List.of(Map.of("invoiceId", later, "amount", "100.00", "discount", "1.00"))), 422))
            .isEqualTo(ReceiptProcesses.DISCOUNT);
        agingEqualsTheLedger("2026-02-20");
    }

    @Test
    @Order(4)
    void partialReceiptsClearTheInvoicesDollarsExactly() {
        String later = IDS.get("later");
        // EUR 4,000.00 of 10,000.00 at 1.0920: 4,340.00 off at the invoice's 1.0850, 4,368.00 in, a gain of 28.00.
        Map<String, Object> first = ok(ReceiptProcesses.RECORD, clerk, receipt("2026-01-31", "4000.00", null,
            List.of(pay(later, "4000.00"))));
        assertThat(postingLines((String) first.get("receiptNo"))).isEqualTo(amounts("1010", "4368.00",
            "1200", "-4340.00", "7200", "-28.00"));
        assertThat(amount(invoice(later).get("openAmountUsd"))).isEqualByComparingTo("6510.00");
        // The rest at a rate given, 1.1000: the invoice's last 6,510.00 off, 6,600.00 in, a gain of 90.00.
        Map<String, Object> rest = ok(ReceiptProcesses.RECORD, clerk, receipt("2026-02-05", "6000.00", "1.1000",
            List.of(pay(later, "6000.00"))));
        assertThat(postingLines((String) rest.get("receiptNo"))).isEqualTo(amounts("1010", "6600.00",
            "1200", "-6510.00", "7200", "-90.00"));
        assertThat(invoice(later)).satisfies(i -> assertThat(amount(i.get("openAmountUsd"))).isZero());
        agingEqualsTheLedger("2026-02-05");
    }

    @Test
    @Order(5)
    void unappliedCashIsAppliedLaterAtItsOwnRateAndTakenBack() {
        String inv = post("2026-01-31", "1000.00");
        // EUR 1,000.00 on 2026-02-20 at 1.0800 waits as unapplied cash of 1,080.00.
        Map<String, Object> rcpt = ok(ReceiptProcesses.RECORD, clerk, receipt("2026-02-20", "1000.00", null,
            List.of()));
        String number = (String) rcpt.get("receiptNo");
        assertThat(postingLines(number)).isEqualTo(amounts("1010", "1080.00", "1250", "-1080.00"));
        // Applied the next day: the invoice's 1,092.00 off, the cash's 1,080.00, a loss of 12.00.
        Map<String, Object> applied = ok(ReceiptProcesses.APPLY, clerk, Map.of("receiptId", rcpt.get("receiptId"),
            "applicationDate", "2026-02-21", "applications", List.of(pay(inv, "1000.00"))));
        assertThat(postingLines(number)).isEqualTo(amounts("1010", "1080.00", "1200", "-1092.00", "7200", "12.00"));
        @SuppressWarnings("unchecked")
        String applicationId = (String) ((List<Map<String, Object>>) applied.get("applications")).getFirst()
            .get("applicationId");
        // Taken back: the loss with it, the invoice open again with its dollars, the cash unapplied at its own.
        ok(ReceiptProcesses.REVERSE, controller, Map.of("applicationId", applicationId, "reverseDate", "2026-02-22",
            "reason", "Wrong invoice"));
        assertThat(postingLines(number)).isEqualTo(amounts("1010", "1080.00", "1250", "-1080.00"));
        assertThat(amount(invoice(inv).get("openAmountUsd"))).isEqualByComparingTo("1092.00");
        IDS.put("open", inv);
        agingEqualsTheLedger("2026-02-22");
    }

    @Test
    @Order(6)
    void aCreditMemoAtAnotherRateRealizesTheDifference() {
        String inv = IDS.get("open");
        Map<String, Object> credit = invoiceInput("C400", "2026-02-20", "CREDIT_MEMO", List.of(
            invoiceLine("Returned components", "1", "1000.00", null, null)));
        credit.put("originalInvoiceId", inv);
        String cm = (String) ok(InvoiceProcesses.SAVE, clerk, credit).get("invoiceId");
        ok(InvoiceProcesses.POST, controller, Map.of("invoiceId", cm));
        String cmNo = (String) invoice(cm).get("invoiceNo");
        // The credit carries 1,080.00 at 1.0800, the invoice 1,092.00 at 1.0920: a loss of 12.00.
        Map<String, Object> applied = ok(InvoiceProcesses.APPLY, clerk, Map.of("creditMemoId", cm, "invoiceId", inv,
            "amount", "1000.00", "applicationDate", "2026-02-25"));
        assertThat(postingLines(cmNo)).isEqualTo(amounts("1200", "-1092.00", "4900", "1080.00", "7200", "12.00"));
        assertThat(invoice(inv)).satisfies(i -> assertThat(amount(i.get("openAmountUsd"))).isZero());
        assertThat(invoice(cm)).satisfies(i -> assertThat(amount(i.get("openAmountUsd"))).isZero());
        agingEqualsTheLedger("2026-02-25");
        // Taken back, both are open again at their own dollars and the loss goes.
        ok(ReceiptProcesses.REVERSE, controller, Map.of("applicationId", applied.get("applicationId"),
            "reverseDate", "2026-02-26", "reason", "Credit for another order"));
        assertThat(postingLines(cmNo)).isEqualTo(amounts("1200", "-1080.00", "4900", "1080.00"));
        assertThat(amount(invoice(inv).get("openAmountUsd"))).isEqualByComparingTo("1092.00");
        assertThat(amount(invoice(cm).get("openAmountUsd"))).isEqualByComparingTo("1080.00");
        agingEqualsTheLedger("2026-02-28");
        // Gains 7.00, 28.00 and 90.00 and the loss of 250.00: 125.00 debit.
        assertThat(ledgerBalances("2026-02-28").get("7200")).isEqualByComparingTo("125.00");
    }

    private static Map<String, Object> journalLine(String account, String debit, String credit, String currency,
        String rate) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("accountCode", account);
        line.put("debit", debit);
        line.put("credit", credit);
        line.put("currency", currency);
        line.put("exchangeRate", rate);
        return line;
    }

    private static Map<String, Object> journal(String date, List<Map<String, Object>> lines) {
        return Map.of("postingDate", date, "description", "Paris office rent", "lines", lines);
    }

    /** F7 plan decision D5: a journal line in euros keeps its euros and the rate, and the ledger entry does too. */
    @Test
    @Order(7)
    void aJournalLineInEurosIsConvertedAtTheRateOfItsDay() {
        String accountant = inRoles("accountant", FinanceRoles.ACCOUNTANT);
        // On the 14th the 12th's rate, 1.0850: EUR 1,000.00 is 1,085.00.
        String id = (String) ok(JournalProcesses.SAVE, accountant, journal("2026-01-14", List.of(
            journalLine("6400", "1000.00", null, "eur", null),
            journalLine("2100", null, "1000.00", "EUR", null)))).get("journalId");
        assertThat(find(JournalEntities.LINE_DATASET, "journalId", id)).anySatisfy(l -> {
            assertThat(l).containsEntry("accountCode", "6400").containsEntry("currency", "EUR");
            assertThat(amount(l.get("debit"))).isEqualByComparingTo("1085.00");
            assertThat(amount(l.get("foreignAmount"))).isEqualByComparingTo("1000.00");
            assertThat(new BigDecimal(String.valueOf(l.get("exchangeRate")))).isEqualByComparingTo("1.0850");
        });
        Map<String, Object> posted = ok(JournalProcesses.SUBMIT, accountant, Map.of("journalId", id));
        assertThat(posted).containsEntry("status", "POSTED");
        assertThat(query("SELECT e.currency, e.transaction_amount::text AS foreign, e.exchange_rate::text AS rate "
            + "FROM ledger_entry_version e WHERE e.transaction_id = ?::uuid AND e.currency IS NOT NULL",
            posted.get("transactionId"))).hasSize(2).allSatisfy(e -> {
                assertThat(e).containsEntry("currency", "EUR");
                assertThat(new BigDecimal((String) e.get("foreign"))).isEqualByComparingTo("1000.00");
                assertThat(new BigDecimal((String) e.get("rate"))).isEqualByComparingTo("1.0850");
            });

        // Its reversal keeps the euros on the other side.
        Map<String, Object> reversal = ok(JournalProcesses.REVERSE, accountant,
            Map.of("journalId", id, "postingDate", "2026-01-25"));
        assertThat(find(JournalEntities.LINE_DATASET, "journalId", reversal.get("journalId"))).anySatisfy(l -> {
            assertThat(l).containsEntry("accountCode", "6400").containsEntry("currency", "EUR")
                .containsEntry("debit", null);
            assertThat(amount(l.get("credit"))).isEqualByComparingTo("1085.00");
            assertThat(amount(l.get("foreignAmount"))).isEqualByComparingTo("1000.00");
        });

        // A rate given is taken as given.
        String given = (String) ok(JournalProcesses.SAVE, accountant, journal("2026-01-14", List.of(
            journalLine("6400", "1000.00", null, "EUR", "1.1"),
            journalLine("2100", null, "1100.00", null, null)))).get("journalId");
        assertThat(find(JournalEntities.LINE_DATASET, "journalId", given)).anySatisfy(l ->
            assertThat(amount(l.get("debit"))).isEqualByComparingTo("1100.00"));
        // Its euros have no other side: the ledger would refuse it, and so does submission.
        assertThat(refused(JournalProcesses.SUBMIT, accountant, Map.of("journalId", given), 422))
            .isEqualTo(JournalValidator.UNBALANCED_IN_CURRENCY);

        // Eight days after the last rate there is none.
        assertThat(refused(JournalProcesses.SAVE, accountant, journal("2026-01-20", List.of(
            journalLine("6400", "100.00", null, "EUR", null),
            journalLine("2100", null, "108.50", null, null))), 422)).isEqualTo(FxRates.NO_RATE);

        // A control account takes no foreign line, not even with the controller's exception.
        String control = (String) ok(JournalProcesses.SAVE, accountant, journal("2026-01-14", List.of(
            journalLine("1200", "1000.00", null, "EUR", null),
            journalLine("2100", null, "1000.00", "EUR", null)))).get("journalId");
        ok(JournalProcesses.GRANT_CONTROL_EXCEPTION, controller, Map.of("journalId", control,
            "reason", "Customer settlement in euros"));
        assertThat(refused(JournalProcesses.SUBMIT, accountant, Map.of("journalId", control), 422))
            .isEqualTo(JournalValidator.CONTROL_ACCOUNT);
    }

    /** A foreign receipt voided leaves the bank and unapplied cash at nothing, in dollars. */
    @Test
    @Order(8)
    void aVoidedForeignReceiptTakesBackItsDollars() {
        BigDecimal bank = ledgerBalances("2026-02-28").getOrDefault("1010", BigDecimal.ZERO);
        Map<String, Object> recorded = ok(ReceiptProcesses.RECORD, clerk, receipt("2026-02-27", "1000.00", "1.1",
            List.of()));
        String id = (String) recorded.get("receiptId");
        assertThat(ledgerBalances("2026-02-28").get("1010").subtract(bank)).isEqualByComparingTo("1100.00");
        ok(ReceiptProcesses.VOID, controller, Map.of("receiptId", id, "voidDate", "2026-02-28",
            "reason", "Bounced"));
        assertThat(ledgerBalances("2026-02-28").get("1010")).isEqualByComparingTo(bank);
        assertThat(read(com.jabiz.finance.ar.ReceiptEntities.RECEIPT_DATASET, id)).satisfies(r ->
            assertThat(amount(r.get("unappliedAmountUsd"))).isZero());
        agingEqualsTheLedger("2026-02-28");
    }

    /** Review fixes: currencies, their decimals, imports and the rates of a reversal's lines. */
    @Test
    @Order(9)
    void journalCurrenciesAreCheckedEverywhere() {
        String accountant = inRoles("accountant", FinanceRoles.ACCOUNTANT);
        assertThat(refused(JournalProcesses.SAVE, accountant, journal("2026-01-14", List.of(
            journalLine("6400", "1000.00", null, "XYZ", "1.1"),
            journalLine("2100", null, "1000.00", "XYZ", "1.1"))), 422)).isEqualTo(JournalProcesses.CURRENCY);
        assertThat(refused(JournalProcesses.SAVE, accountant, journal("2026-01-14", List.of(
            journalLine("6400", "1000.005", null, "EUR", null),
            journalLine("2100", null, "1000.005", "EUR", null))), 422)).isEqualTo(JournalValidator.LINE_AMOUNT);
        // An import takes dollars only: a line in euros is refused, not taken as dollars.
        assertThat(refused(JournalImportProcesses.IMPORT_ENTRY, accountant, Map.of("externalRef", "FX-IMPORT-1",
            "postingDate", "2026-01-14", "description", "Imported", "lines", List.of(
                journalLine("6400", "1000.00", null, "EUR", null),
                journalLine("2100", null, "1000.00", "EUR", null))), 422))
            .isEqualTo(JournalProcesses.FOREIGN_NOT_HERE);

        // A reversal waiting for approval moves to a day without a rate and keeps its lines' rates.
        String id = (String) ok(JournalProcesses.SAVE, accountant, journal("2026-01-14", List.of(
            journalLine("6400", "20000.00", null, "EUR", null),
            journalLine("2100", null, "20000.00", "EUR", null)))).get("journalId");
        String approver = inRoles("approver", FinanceRoles.CONTROLLER);
        Map<String, Object> submitted = ok(JournalProcesses.SUBMIT, accountant, Map.of("journalId", id));
        ok("APPROVAL_DECIDE", approver, Map.of("requestId", submitted.get("approvalRequestId"),
            "decision", "APPROVE"));
        deliverer.deliverPending().block();
        Map<String, Object> reversal = ok(JournalProcesses.REVERSE, accountant,
            Map.of("journalId", id, "postingDate", "2026-01-15"));
        assertThat(reversal).containsEntry("status", "SUBMITTED");
        Map<String, Object> moved = ok(JournalProcesses.SAVE, accountant, Map.of("journalId", reversal.get("journalId"),
            "postingDate", "2026-01-20", "description", "Reversal moved", "lines", List.of(
                journalLine("6400", null, "20000.00", "EUR", null),
                journalLine("2100", "20000.00", null, "EUR", null))));
        assertThat(moved).containsEntry("journalId", reversal.get("journalId"));
        assertThat(find(JournalEntities.LINE_DATASET, "journalId", reversal.get("journalId"))).allSatisfy(l ->
            assertThat(amount(l.get("debit") != null ? l.get("debit") : l.get("credit")))
                .isEqualByComparingTo("21700.00"));
    }
}
