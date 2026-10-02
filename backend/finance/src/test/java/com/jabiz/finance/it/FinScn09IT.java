package com.jabiz.finance.it;

import com.jabiz.finance.ar.InvoiceEntities;
import com.jabiz.finance.ar.InvoiceProcesses;
import com.jabiz.finance.ar.ReceiptProcesses;
import com.jabiz.finance.bank.BankAccountProcesses;
import com.jabiz.finance.calc.PeriodPolicy;
import com.jabiz.finance.fx.FxEntities;
import com.jabiz.finance.fx.FxRevaluationProcesses;
import com.jabiz.finance.fx.FxSettingsProcesses;
import com.jabiz.finance.gl.AccountTypes;
import com.jabiz.finance.gl.JournalProcesses;
import com.jabiz.finance.setup.FinanceRoles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIN-SCN-09, a foreign currency receivable (FIN-FX-002…007; ROADMAP F7c): INV-1005 for EUR 50,000.00 on 12 January,
 * the January revaluation FXR-2601 with its unrealized gain of 350.00 reversed on 1 February, RCPT-0005 on 20 February
 * at 1.0800 realizing the loss of 250.00, and the 31 January rate corrected and the revaluation simulated again, the
 * original run still reproducible (FIN-EXP-12, the foreign exchange items of FIN-EXP-17). A euro bank account is
 * remeasured with it (FX-006).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FinScn09IT extends FinanceItSupport {

    private static boolean loaded;
    private static final Map<String, String> IDS = new LinkedHashMap<>();

    private String clerk;
    private String accountant;
    private String controller;
    private String treasurer;

    @BeforeEach
    void books() {
        clerk = inRoles("ar-clerk", FinanceRoles.RECEIVABLES_CLERK);
        accountant = inRoles("accountant", FinanceRoles.ACCOUNTANT);
        controller = inRoles("controller", FinanceRoles.CONTROLLER);
        treasurer = inRoles("treasurer", FinanceRoles.TREASURER);
        if (loaded) {
            return;
        }
        loaded = true;
        openReceivables();
        ok(FxSettingsProcesses.SET, controller, Map.of("realizedAccount", "7200", "unrealizedAccount", "7210"));
        // A euro account at the bank: its own cash account, brought over empty but for a check outstanding (paid
        // out of the old books), and funded on 12 January with EUR 9,000.00 bought at 1.0850 (9,765.00).
        ok("FIN_ACCOUNT_CREATE", controller(), Map.of("accountCode", "1060", "accountName", "Cash - Euro",
            "financialType", AccountTypes.fromChart("Asset"), "normalBalance", AccountTypes.normalBalanceFromChart("D"),
            "statementLine", "Cash and cash equivalents", "controlClass", "BANK"));
        ok(BankAccountProcesses.SAVE, treasurer, Map.of("bankCode", "EURO", "bankName", "Rheinbank",
            "glAccount", "1060", "currency", "EUR", "routingNumber", "021000021",
            "companyAccountNumber", "000777888999", "statementFormat", "CSV"));
        importCsv("finance.bank_opening_items", as("migrator", "fin.migration", "fin.import", "fin.bank.activity.read"),
            "date,reference,description,amount\n2025-12-30,CHK-E001,Check to Rheinwerk,-100.00\n", "commit", null,
            Map.of("bankCode", "EURO", "statementBalance", "100.00"), 200);
        String funding = (String) ok(JournalProcesses.SAVE, accountant, Map.of("postingDate", "2026-01-12",
            "description", "EUR 9,000.00 bought at 1.0850", "lines", List.of(
                Map.of("accountCode", "1060", "debit", "9765.00"),
                Map.of("accountCode", "1010", "credit", "9765.00")))).get("journalId");
        ok(JournalProcesses.GRANT_CONTROL_EXCEPTION, controller, Map.of("journalId", funding,
            "reason", "Euros bought for the German supplier"));
        assertThat(ok(JournalProcesses.SUBMIT, accountant, Map.of("journalId", funding)))
            .containsEntry("status", "POSTED");
        String statement = "date,bank_reference,description,amount\n"
            + "2026-01-01,OPENING,OPENING LEDGER BALANCE,100.00\n"
            + "2026-01-05,EUR-0001,CHECK E001,-100.00\n"
            + "2026-01-12,EUR-0002,EUR BOUGHT,9000.00\n"
            + "2026-01-31,CLOSING,CLOSING LEDGER BALANCE,9000.00\n";
        String fileId = upload(accountant, "fin.bank.statement", statement.getBytes(StandardCharsets.UTF_8),
            "euro.csv", "text/csv");
        post("/api/imports/finance.bank_statement/commit", accountant, Map.of("fileId", fileId,
            "params", Map.of("bankCode", "EURO"))).expectStatus().isOk();
    }

    @Test
    @Order(1)
    void step1Inv1005IsIssuedAt1Point0850() {
        String id = (String) ok(InvoiceProcesses.SAVE, clerk, invoiceInput("C400", "2026-01-12", null,
            List.of(invoiceLine("Components", "1", "50000.00", "4000", null)))).get("invoiceId");
        ok(InvoiceProcesses.POST, clerk, Map.of("invoiceId", id));
        IDS.put("INV-1005", id);
        IDS.put("number", (String) read(InvoiceEntities.INVOICE_DATASET, id).get("invoiceNo"));
        assertThat(read(InvoiceEntities.INVOICE_DATASET, id)).satisfies(i ->
            assertThat(amount(i.get("totalUsd"))).isEqualByComparingTo("54250.00"));
    }

    @Test
    @Order(2)
    @SuppressWarnings("unchecked")
    void step2TheJanuaryRevaluationPostsTheGainAndReversesItOnTheFirst() {
        Map<String, Object> run = ok(FxRevaluationProcesses.REVALUE, accountant, Map.of("periodKey", "2026-01"));
        assertThat(run).containsEntry("runNo", "FXR-2601").containsEntry("created", true)
            .containsEntry("revaluationDate", "2026-01-31").containsEntry("reversalDate", "2026-02-01");
        // INV-1005 at 1.0920: 54,600.00, a gain of 350.00 (FIN-EXP-12); the euro account 9,828.00, a gain of 63.00.
        List<Map<String, Object>> lines = find(FxEntities.LINE_DATASET, "runId", run.get("runId"));
        assertThat(lines).anySatisfy(l -> {
            assertThat(l).containsEntry("kind", "RECEIVABLE").containsEntry("currency", "EUR")
                .containsEntry("account", "1200");
            assertThat(amount(l.get("openAmount"))).isEqualByComparingTo("50000.00");
            assertThat(amount(l.get("carryingUsd"))).isEqualByComparingTo("54250.00");
            assertThat(amount(l.get("revaluedUsd"))).isEqualByComparingTo("54600.00");
            assertThat(amount(l.get("difference"))).isEqualByComparingTo("350.00");
        }).anySatisfy(l -> {
            assertThat(l).containsEntry("kind", "BANK").containsEntry("documentNo", "EURO")
                .containsEntry("account", "1060");
            assertThat(amount(l.get("revaluedUsd"))).isEqualByComparingTo("9828.00");
            assertThat(amount(l.get("difference"))).isEqualByComparingTo("63.00");
        });
        assertThat(postingLines("FXR-2601")).isEqualTo(amounts("1060", "63.00", "1200", "350.00",
            "7210", "-413.00"));
        assertThat(postingLines("FXR-2601-R")).isEqualTo(amounts("1060", "-63.00", "1200", "-350.00",
            "7210", "413.00"));
        assertThat(ledgerBalances("2026-01-31").get("7210")).isEqualByComparingTo("-413.00");
        assertThat(ledgerBalances("2026-02-01").get("7210")).isNull();

        // FIN-FX-007 acceptance 1: the aging of 31 January shows EUR 50,000.00 and USD 54,600.00, and equals 1200;
        // on 1 February the invoice carries its own 54,250.00 again.
        assertThat(aging("2026-01-31", IDS.get("number"))).satisfies(r -> {
            assertThat(r).containsEntry("currency", "EUR");
            assertThat(amount(r.get("openAmount"))).isEqualByComparingTo("50000.00");
            assertThat(amount(r.get("openAmountUsd"))).isEqualByComparingTo("54600.00");
        });
        agingEqualsTheLedger("2026-01-31");
        assertThat(amount(aging("2026-02-01", IDS.get("number")).get("openAmountUsd"))).isEqualByComparingTo("54250.00");
        agingEqualsTheLedger("2026-02-01");

        // Once a period: asked again, the run made; nothing more is posted.
        assertThat(ok(FxRevaluationProcesses.REVALUE, controller, Map.of("periodKey", "2026-01")))
            .containsEntry("runId", run.get("runId")).containsEntry("created", false);
        assertThat(postingLines("FXR-2601")).containsEntry("1200", new BigDecimal("350.00"));
        // A clerk does not run it; a closed period is refused.
        run(FxRevaluationProcesses.REVALUE, clerk, Map.of("periodKey", "2026-02")).expectStatus().isForbidden();
        // February's euro statement is not in yet.
        assertThat(refused(FxRevaluationProcesses.REVALUE, accountant, Map.of("periodKey", "2026-02"), 422))
            .isEqualTo(FxRevaluationProcesses.NO_STATEMENT);
        // These books hold only what the scenario needs: no revaluation of March, no payables or asset subledger
        // behind the opening balances, no March statement of the euro account.
        closePeriod("2026-03", "REVALUATION_RUN", "SUBLEDGERS", "BANK_RECONCILED");
        assertThat(refused(FxRevaluationProcesses.REVALUE, accountant, Map.of("periodKey", "2026-03"), 422))
            .isEqualTo(PeriodPolicy.PERIOD_CLOSED);
        // December reverses into a year not yet opened.
        assertThat(refused(FxRevaluationProcesses.REVALUE, accountant, Map.of("periodKey", "2026-12"), 422))
            .isEqualTo(FxRevaluationProcesses.NEXT_PERIOD);
    }

    @Test
    @Order(3)
    void step3Rcpt0005RealizesTheLossOf250() {
        Map<String, Object> receipt = new LinkedHashMap<>(Map.of("customerCode", "C400", "receiptDate", "2026-02-20",
            "amount", "50000.00", "method", "WIRE", "bankAccount", "1010",
            "applications", List.of(Map.of("invoiceId", IDS.get("INV-1005"), "amount", "50000.00"))));
        String receiptNo = (String) ok(ReceiptProcesses.RECORD, clerk, receipt).get("receiptNo");
        assertThat(postingLines(receiptNo)).isEqualTo(amounts("1010", "54000.00", "1200", "-54250.00",
            "7200", "250.00"));
        // FIN-EXP-17's foreign exchange items: 7200 250.00, 7210 0.00, C400 nothing open.
        Map<String, BigDecimal> balances = ledgerBalances("2026-02-28");
        assertThat(balances.get("7200")).isEqualByComparingTo("250.00");
        assertThat(balances.get("7210")).isNull();
        assertThat(report("finance.ar.aging", controller, Map.of("agingDate", "2026-02-28", "customerCode", "C400")))
            .isEmpty();
        agingEqualsTheLedger("2026-02-28");

        // By document: the unrealized gain, its reversal and the realized loss; the total is the accounts' credit.
        List<Map<String, Object>> rows = report("finance.fx.gains_losses", controller, Map.of("from", "2026-01-01",
            "to", "2026-02-28"));
        assertThat(rows).filteredOn(r -> IDS.get("number").equals(r.get("documentNo")))
            .extracting(r -> r.get("kind") + " " + r.get("day") + " " + amount(r.get("amount")).toPlainString())
            .containsExactlyInAnyOrder("UNREALIZED 2026-01-31 350.00", "REVERSAL 2026-02-01 -350.00",
                "REALIZED 2026-02-20 -250.00");
        BigDecimal total = rows.stream().map(r -> amount(r.get("amount"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isEqualByComparingTo("-250.00");
    }

    @Test
    @Order(4)
    @SuppressWarnings("unchecked")
    void step4TheCorrectedRateIsSimulatedAndTheOriginalRunReproduced() {
        // The 31 January rate corrected to 1.0950 later.
        clock.advance(Duration.ofMinutes(5));
        ok("FIN_EXCHANGE_RATE_SET", treasurer, Map.of("fromCurrency", "EUR", "toCurrency", "USD",
            "rateDate", "2026-01-31", "rateType", "SPOT", "rate", "1.0950"));
        Map<String, Object> now = ok(FxRevaluationProcesses.SIMULATE, accountant, Map.of("periodKey", "2026-01"));
        assertThat(now).containsEntry("runNo", "FXR-2601");
        assertThat(amount(now.get("originalTotal"))).isEqualByComparingTo("413.00");
        // INV-1005 at 1.0950: 54,750.00, 150.00 more; the euro account 9,855.00, 27.00 more.
        assertThat(amount(now.get("total"))).isEqualByComparingTo("590.00");
        assertThat(amount(now.get("change"))).isEqualByComparingTo("177.00");
        assertThat((List<Map<String, Object>>) now.get("lines")).anySatisfy(l -> {
            assertThat(l).containsEntry("documentNo", IDS.get("number"));
            assertThat(amount(l.get("difference"))).isEqualByComparingTo("500.00");
            assertThat(amount(l.get("originalDifference"))).isEqualByComparingTo("350.00");
            assertThat(amount(l.get("change"))).isEqualByComparingTo("150.00");
        });
        // As recorded when it was run, the computation gives the run's lines again (D7).
        Map<String, Object> recorded = ok(FxRevaluationProcesses.SIMULATE, accountant, Map.of("periodKey", "2026-01",
            "asRecorded", true));
        assertThat(amount(recorded.get("total"))).isEqualByComparingTo("413.00");
        assertThat(amount(recorded.get("change"))).isZero();
        assertThat((List<Map<String, Object>>) recorded.get("lines")).allSatisfy(l ->
            assertThat(amount(l.get("change"))).isZero());
        // The ledger keeps what was posted.
        assertThat(postingLines("FXR-2601")).containsEntry("1200", new BigDecimal("350.00"));
    }

    @Test
    @Order(5)
    void theRevaluationTablesAreOnlyInsertedInto() {
        assertOnlyInserted("fi_fx_revaluation_run_version", "fi_fx_revaluation_line_version");
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private Map<String, Object> aging(String day, String documentNo) {
        return report("finance.ar.aging", controller, Map.of("agingDate", day)).stream()
            .filter(r -> documentNo.equals(r.get("documentNo"))).findFirst().orElseThrow();
    }

    private void agingEqualsTheLedger(String day) {
        BigDecimal aging = report("finance.ar.aging", controller, Map.of("agingDate", day)).stream()
            .map(r -> amount(r.get("openAmountUsd"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(aging).as(day).isEqualByComparingTo(ledgerBalances(day).getOrDefault("1200", BigDecimal.ZERO));
    }

    private static Map<String, BigDecimal> amounts(String... pairs) {
        Map<String, BigDecimal> amounts = new TreeMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            amounts.put(pairs[i], new BigDecimal(pairs[i + 1]));
        }
        return amounts;
    }
}
