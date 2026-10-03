package com.jabiz.finance.it;

import com.jabiz.finance.gl.AccountProcesses;
import com.jabiz.finance.report.CashFlowProcesses;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The statement of cash flows of January (FIN-RP-004; ROADMAP F9c) and the note schedules (FIN-RP-012), January
 * closed. Without cash flow classes the statement explains nothing and is refused for issue; classified, it is
 * FIN-EXP-06: depreciation and the unrealized gain found from their runs, the server bought on account (TS-5520) left
 * out of payables and investing and shown as a non-cash activity, interest and income taxes paid from the report
 * settings; issued, it verifies identical. The receivables, accrued liabilities and debt schedules roll each account
 * from the opening balances to January's end, their totals the balance sheet's lines.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class FinCashFlowIT extends JanuaryBooks {

    /** The sample company's cash flow classes: its chart file has none. */
    private static final Map<String, String> CLASSES = classes();

    @Test
    void januarysCashFlows() throws IOException {
        januaryPostings();
        closeJanuary();
        Map<String, Object> january = Map.of("from", "2026-01-01", "through", "2026-01-31");

        // No class: every balance sheet account with a change is unclassified, the change in cash unexplained.
        Map<String, Map<String, Object>> unclassified = byLine(report(CashFlowProcesses.CASH_FLOW, controller,
            january));
        assertThat(unclassified).containsKeys("UNCLASSIFIED.1200", "UNCLASSIFIED.2000");
        assertThat(amount(unclassified.get("UNEXPLAINED").get("amount")).signum()).isNotZero();
        assertThat(unclassified.get("INTEREST_PAID")).containsEntry("amount", null);
        assertThat(refused(CashFlowProcesses.ISSUE, controller, Map.of("params", january), 422))
            .isEqualTo(CashFlowProcesses.UNEXPLAINED);

        // The controller classifies the accounts and names the settings' accounts.
        CLASSES.forEach((account, cashFlowClass) -> ok(AccountProcesses.UPDATE, controller,
            Map.of("accountCode", account, "cashFlowClass", cashFlowClass)));
        assertThat(refused(CashFlowProcesses.SETTINGS_SET, controller, Map.of("interestAccounts", "7100-"), 422))
            .isEqualTo(CashFlowProcesses.SETTINGS_INVALID);
        run(CashFlowProcesses.SETTINGS_SET, accountant, Map.of("interestAccounts", "7100")).expectStatus()
            .isForbidden();
        ok(CashFlowProcesses.SETTINGS_SET, controller, Map.of("interestAccounts", "7100",
            "incomeTaxAccounts", "8000", "incomeTaxPayableAccounts", "2400", "receivablesAccounts", "1200-1210",
            "accruedAccounts", "2100-2150", "debtAccounts", "2300"));

        // FIN-EXP-06 (FIN-RP-004 acceptance 1): net decrease in cash 38,320.00, the server a non-cash activity.
        Map<String, Map<String, Object>> cashFlows = byLine(report(CashFlowProcesses.CASH_FLOW, controller,
            january));
        assertThat(amounts(cashFlows)).containsExactly(
            "OPERATING", "NET_INCOME 5127.10", "NONCASH_ITEM.DEP 4000.00", "NONCASH_ITEM.FXR -350.00",
            "OPERATING.1200 -71885.00", "OPERATING.1300 1000.00", "OPERATING.2000 -7000.00",
            "OPERATING.2100 29590.00", "OPERATING.2200 -165.00", "OPERATING.2400 1362.90", "NET_OPERATING -38320.00",
            "INVESTING", "INVESTING.1500 0.00", "NET_INVESTING 0.00",
            "FINANCING", "FINANCING.2300 0.00", "FINANCING.3000 0.00", "FINANCING.3100 0.00",
            "FINANCING.3200 0.00", "NET_FINANCING 0.00",
            "FX_EFFECT 0.00", "NET_CHANGE -38320.00", "CASH_BEGINNING 350000.00", "CASH_ENDING 311680.00", "UNEXPLAINED 0.00",
            "SUPPLEMENTAL", "NONCASH.1520 12000.00", "INTEREST_PAID 300.00", "INCOME_TAXES_PAID 0.00");
        assertThat(cashFlows.get("OPERATING.1200").get("label")).isEqualTo("Accounts receivable, net");
        assertThat(cashFlows.get("NONCASH.1520").get("label")).isEqualTo("Non-cash: Property and equipment, net");

        // Issued, it verifies identical; only those who issue reports issue it.
        Map<String, Object> issued = ok(CashFlowProcesses.ISSUE, controller, Map.of("params", january));
        assertThat(verify(issued.get("runId"))).isEqualTo("identical");
        run(CashFlowProcesses.ISSUE, accountant, Map.of("params", january)).expectStatus().isForbidden();

        // February: the lender forgives 1,000.00 of the line of credit. A financing account moves without cash and
        // nothing operating takes it: no non-cash activity, the line of credit's decrease against the income, and the
        // change in cash still explained (as is January's reversed revaluation).
        clock.advance(java.time.Duration.ofDays(10));
        people();
        String forgiven = (String) ok(com.jabiz.finance.gl.JournalProcesses.SAVE, accountant,
            JournalLifecycleIT.entry("2026-02-10", "Line of credit forgiven", List.of(
                JournalLifecycleIT.line("2300", "1000.00", null, null),
                JournalLifecycleIT.line("7300", null, "1000.00", null)))).get("journalId");
        assertThat(ok(com.jabiz.finance.gl.JournalProcesses.SUBMIT, accountant, Map.of("journalId", forgiven)))
            .containsEntry("status", "POSTED");
        Map<String, Map<String, Object>> february = byLine(report(CashFlowProcesses.CASH_FLOW, controller,
            Map.of("through", "2026-02-28")));
        assertThat(amount(february.get("FINANCING.2300").get("amount"))).isEqualByComparingTo("-1000.00");
        assertThat(amount(february.get("UNEXPLAINED").get("amount"))).isEqualByComparingTo("0.00");
        assertThat(amount(february.get("FX_EFFECT").get("amount"))).isEqualByComparingTo("0.00");
        assertThat(amount(february.get("CASH_BEGINNING").get("amount"))).isEqualByComparingTo("311680.00");
        assertThat(february.keySet()).noneMatch(line -> line.startsWith("NONCASH."));

        // The note schedules (FIN-RP-012): each account from the opening balances to January's end; the totals are
        // the balance sheet's receivables (net of the allowance), accrued and payroll liabilities and line of credit.
        Map<String, Map<String, Object>> receivables = byLine(report(CashFlowProcesses.NOTE, controller,
            Map.of("note", "RECEIVABLES", "from", "2026-01-01", "through", "2026-01-31")));
        assertThat(amount(receivables.get("TOTAL.OPENING").get("amount"))).isEqualByComparingTo("82500.00");
        assertThat(amount(receivables.get("TOTAL.CLOSING").get("amount"))).isEqualByComparingTo("154735.00");
        assertThat(amount(receivables.get("1210.CLOSING").get("amount"))).isEqualByComparingTo("4000.00");
        assertThat(amount(receivables.get("1200.FX").get("amount"))).isEqualByComparingTo("350.00");
        rollsForward(receivables);
        Map<String, Map<String, Object>> accrued = byLine(report(CashFlowProcesses.NOTE, controller,
            Map.of("note", "ACCRUED", "through", "2026-01-31")));
        assertThat(amount(accrued.get("TOTAL.OPENING").get("amount"))).isEqualByComparingTo("15000.00");
        assertThat(amount(accrued.get("TOTAL.CLOSING").get("amount"))).isEqualByComparingTo("44590.00");
        rollsForward(accrued);
        Map<String, Map<String, Object>> debt = byLine(report(CashFlowProcesses.NOTE, controller,
            Map.of("note", "DEBT", "through", "2026-01-31")));
        assertThat(debt.keySet()).containsExactly("2300.OPENING", "2300.CLOSING", "TOTAL.OPENING", "TOTAL.CLOSING");
        assertThat(amount(debt.get("TOTAL.CLOSING").get("amount"))).isEqualByComparingTo("50000.00");
        // Through February the forgiveness shows as a manual journal.
        Map<String, Map<String, Object>> debtFebruary = byLine(report(CashFlowProcesses.NOTE, controller,
            Map.of("note", "DEBT", "from", "2026-01-01", "through", "2026-02-28")));
        assertThat(amount(debtFebruary.get("2300.MAN").get("amount"))).isEqualByComparingTo("-1000.00");
        assertThat(amount(debtFebruary.get("TOTAL.CLOSING").get("amount"))).isEqualByComparingTo("49000.00");

        assertOnlyInserted("fi_report_settings_version");
    }

    /** Each account's and the total's start and movements add up to its end. */
    private static void rollsForward(Map<String, Map<String, Object>> rows) {
        Map<Object, BigDecimal> sums = new LinkedHashMap<>();
        rows.values().forEach(r -> {
            Object account = r.get("accountCode") == null ? "TOTAL" : r.get("accountCode");
            if ("CLOSING".equals(r.get("movement"))) {
                assertThat(sums.get(account)).as(account + " rolls forward")
                    .isEqualByComparingTo(amount(r.get("amount")));
            } else {
                sums.merge(account, amount(r.get("amount")), BigDecimal::add);
            }
        });
    }

    private String verify(Object runId) {
        return (String) post("/api/reports/runs/" + runId + "/verify", as("auditor", "report.archive.read",
            "ledger.read"), Map.of()).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody()
            .get("verdict");
    }

    private static Map<String, Map<String, Object>> byLine(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> byLine = new LinkedHashMap<>();
        rows.forEach(r -> byLine.put((String) r.get("lineCode"), r));
        return byLine;
    }

    private static List<String> amounts(Map<String, Map<String, Object>> rows) {
        return rows.values().stream().map(r -> r.get("amount") == null ? (String) r.get("lineCode")
            : r.get("lineCode") + " " + amount(r.get("amount")).toPlainString()).toList();
    }

    private static Map<String, String> classes() {
        Map<String, String> classes = new LinkedHashMap<>();
        for (String code : List.of("1010", "1050")) {
            classes.put(code, "CASH");
        }
        for (String code : List.of("1200", "1210", "1300", "2000", "2100", "2150", "2200", "2400")) {
            classes.put(code, "OPERATING");
        }
        for (String code : List.of("1500", "1510", "1520", "1590")) {
            classes.put(code, "INVESTING");
        }
        for (String code : List.of("2300", "3000", "3100", "3200")) {
            classes.put(code, "FINANCING");
        }
        return classes;
    }
}
