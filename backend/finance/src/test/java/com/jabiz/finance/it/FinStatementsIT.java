package com.jabiz.finance.it;

import com.jabiz.finance.report.StatementEntities;
import com.jabiz.finance.report.StatementProcesses;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The financial statements of January (FIN-RP-002, RP-003, RP-005, RP-011; ROADMAP F9b) from the sample layouts
 * {@code FIN_SETUP} made, after January is closed (read from its period balances): the balance sheet is FIN-EXP-05,
 * the income statement FIN-EXP-04 and the statement of equity FIN-EXP-07. A layout version leaving an account with a
 * balance off every line shows it as unmapped and is refused for issue; a statement issued with the earlier version
 * still verifies identical, and so does one issued again with a version that takes the account.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class FinStatementsIT extends JanuaryBooks {

    @Test
    void januarysStatements() throws IOException {
        januaryPostings();
        closeJanuary();
        assertThat(find(StatementEntities.LAYOUT_DATASET, "layoutCode", "BS")).singleElement()
            .satisfies(l -> assertThat(l).containsEntry("version", 1).containsEntry("statement", "BALANCE_SHEET"));

        // FIN-EXP-05 (FIN-RP-002 acceptance 1): total assets equal total liabilities and equity, 617,415.00.
        Map<String, Map<String, Object>> balanceSheet = byLine(report(StatementProcesses.BALANCE_SHEET, controller,
            Map.of("asOf", "2026-01-31")));
        assertThat(amounts(balanceSheet, "amount")).containsExactly(
            "ASSETS", "CASH 311680.00", "RECEIVABLES 154735.00", "PREPAID 11000.00", "CURRENT_ASSETS 477415.00",
            "PPE_COST 202000.00", "PPE_DEPRECIATION -62000.00", "PPE_NET 140000.00", "TOTAL_ASSETS 617415.00",
            "LIABILITIES", "PAYABLES 46300.00", "ACCRUED 25000.00", "PAYROLL 19590.00", "SALES_TAX 3135.00",
            "INCOME_TAX_PAYABLE 9362.90", "CREDIT_LINE 50000.00", "TOTAL_LIABILITIES 153387.90", "EQUITY",
            "COMMON_STOCK 100000.00", "APIC 200000.00", "RETAINED 164027.10", "TOTAL_EQUITY 464027.10",
            "TOTAL_LIABILITIES_EQUITY 617415.00");
        assertThat(balanceSheet.get("RECEIVABLES").get("label"))
            .isEqualTo("Accounts receivable, net of allowance of 4,000.00");
        // The year before: the opening balances (FIN-EXP-06's cash at the beginning).
        assertThat(amount(balanceSheet.get("CASH").get("priorYear"))).isEqualByComparingTo("350000.00");
        assertThat(amount(balanceSheet.get("CASH").get("priorMonth"))).isEqualByComparingTo("350000.00");
        assertThat(amount(balanceSheet.get("RETAINED").get("priorYear"))).isEqualByComparingTo("158900.00");

        // FIN-EXP-04 (FIN-RP-003 acceptance 1): net income 5,127.10, the month, the quarter and the year alike.
        Map<String, Map<String, Object>> income = byLine(report(StatementProcesses.INCOME_STATEMENT, controller,
            Map.of("through", "2026-01-31")));
        assertThat(amounts(income, "month")).containsExactly(
            "PRODUCT_SALES 119250.00", "SERVICE_REVENUE 28000.00", "RETURNS -2000.00", "NET_REVENUE 145250.00",
            "COGS -22000.00", "GROSS_PROFIT 123250.00", "OPERATING_EXPENSES.6100 -60000.00",
            "OPERATING_EXPENSES.6150 -4590.00", "OPERATING_EXPENSES.6200 -8500.00", "OPERATING_EXPENSES.6300 -3600.00",
            "OPERATING_EXPENSES.6400 -34000.00", "OPERATING_EXPENSES.6500 -1200.00",
            "OPERATING_EXPENSES.6600 -1000.00", "OPERATING_EXPENSES.6700 -4000.00", "OPERATING_EXPENSES.6800 -45.00",
            "TOTAL_OPERATING_EXPENSES -116935.00", "OPERATING_INCOME 6315.00", "INTEREST_EXPENSE -300.00",
            "INTEREST_INCOME 125.00", "UNREALIZED_FX 350.00", "PRETAX_INCOME 6490.00", "INCOME_TAX -1362.90",
            "NET_INCOME 5127.10");
        assertThat(income.get("OPERATING_EXPENSES.6400").get("label")).isEqualTo("Professional Fees");
        for (String column : List.of("quarter", "yearToDate")) {
            assertThat(amounts(income, column)).as(column).isEqualTo(amounts(income, "month"));
        }
        // 2026 is the books' first year: last year's columns are empty, not zero.
        assertThat(income.get("NET_INCOME")).containsEntry("priorMonth", null).containsEntry("priorYearToDate", null);

        // FIN-EXP-07 (FIN-RP-005 acceptance 1).
        Map<String, Map<String, Object>> equity = byLine(report(StatementProcesses.EQUITY, controller,
            Map.of("through", "2026-01-31")));
        assertThat(equity.values().stream().map(r -> r.get("lineCode") + " " + amount(r.get("opening")) + " "
            + amount(r.get("netIncome")) + " " + amount(r.get("otherChanges")) + " " + amount(r.get("closing"))))
            .containsExactly("COMMON_STOCK 100000.00 0.00 0.00 100000.00", "APIC 200000.00 0.00 0.00 200000.00",
                "RETAINED 158900.00 5127.10 0.00 164027.10", "TOTAL 458900.00 5127.10 0.00 464027.10");

        // Issued with the layout's version, checked against the data later: identical.
        Map<String, Object> issued = ok(StatementProcesses.ISSUE, controller, Map.of("templateId",
            StatementProcesses.BALANCE_SHEET, "params", Map.of("asOf", "2026-01-31")));
        assertThat(issued).containsEntry("layoutCode", "BS").containsEntry("layoutVersion", 1);
        String firstIssue = clock.instant().toString();
        run(StatementProcesses.ISSUE, accountant, Map.of("templateId", StatementProcesses.BALANCE_SHEET, "params",
            Map.of("asOf", "2026-01-31"))).expectStatus().isForbidden();
        assertThat(refused(StatementProcesses.ISSUE, controller, Map.of("templateId", "finance.gl.trial_balance",
            "params", Map.of("through", "2026-01-31")), 422)).isEqualTo(StatementProcesses.NOT_A_STATEMENT);
        // Never an empty statement: a layout of another statement, or as known before the layout existed, is refused.
        assertThat(refused(StatementProcesses.ISSUE, controller, Map.of("templateId", StatementProcesses.BALANCE_SHEET,
            "params", Map.of("asOf", "2026-01-31", "layout", "IS")), 422)).isEqualTo(StatementProcesses.NO_LAYOUT);
        assertThat(refused(StatementProcesses.ISSUE, controller, Map.of("templateId", StatementProcesses.BALANCE_SHEET,
            "params", Map.of("asOf", "2026-01-31", "knownAt", "2020-01-01T00:00:00Z")), 422))
            .isEqualTo(StatementProcesses.NO_LAYOUT);
        assertThat(refused(StatementProcesses.ISSUE, controller, Map.of("templateId", StatementProcesses.BALANCE_SHEET,
            "params", Map.of("asOf", "2026-01-31", "layoutVersion", "x")), 422)).isEqualTo(StatementProcesses.NO_LAYOUT);
        // In the opening period (no month of a fiscal year), the month and the year are empty, never the books'
        // lifetime; on a day in no period, every column is empty.
        Map<String, Map<String, Object>> opening = byLine(report(StatementProcesses.INCOME_STATEMENT, controller,
            Map.of("through", "2025-12-31")));
        assertThat(opening.get("NET_INCOME")).containsEntry("month", null).containsEntry("yearToDate", null);
        Map<String, Map<String, Object>> before = byLine(report(StatementProcesses.BALANCE_SHEET, controller,
            Map.of("asOf", "2020-06-30")));
        assertThat(before.get("TOTAL_ASSETS")).containsEntry("amount", null).containsEntry("priorMonth", null)
            .containsEntry("priorYear", null);

        // A layout that is not valid is refused; only the controller publishes.
        List<Map<String, Object>> broken = rows(StatementProcesses.SAMPLE_BALANCE_SHEET);
        broken.set(0, Map.of("lineCode", "ASSETS", "label", "Assets", "kind", "HEADING", "accounts", "1000-1999"));
        assertThat(refused(StatementProcesses.PUBLISH, controller, layout("BS", broken), 422))
            .isEqualTo(StatementProcesses.INVALID);
        run(StatementProcesses.PUBLISH, accountant, layout("BS", rows(StatementProcesses.SAMPLE_BALANCE_SHEET)))
            .expectStatus().isForbidden();

        clock.advance(java.time.Duration.ofMinutes(1));
        // FIN-RP-002 acceptance 2: version 2 leaves payroll liabilities (2150) off every line: reported unmapped, and
        // the statement cannot be issued with it.
        List<Map<String, Object>> withoutPayroll = rows(StatementProcesses.SAMPLE_BALANCE_SHEET).stream()
            .filter(r -> !"PAYROLL".equals(r.get("lineCode"))).collect(Collectors.toCollection(ArrayList::new));
        assertThat(ok(StatementProcesses.PUBLISH, controller, layout("BS", withoutPayroll)))
            .containsEntry("version", 2);
        Map<String, Map<String, Object>> unmapped = byLine(report(StatementProcesses.BALANCE_SHEET, controller,
            Map.of("asOf", "2026-01-31")));
        assertThat(unmapped).containsKey("UNMAPPED.2150").doesNotContainKey("PAYROLL");
        assertThat(unmapped.get("UNMAPPED.2150")).containsEntry("kind", StatementProcesses.UNMAPPED_KIND);
        assertThat(amount(unmapped.get("UNMAPPED.2150").get("amount"))).isEqualByComparingTo("-19590.00");
        var refusal = run(StatementProcesses.ISSUE, controller, Map.of("templateId", StatementProcesses.BALANCE_SHEET,
            "params", Map.of("asOf", "2026-01-31"))).expectStatus().isEqualTo(422).expectBody(MAP).returnResult()
            .getResponseBody();
        assertThat(String.valueOf(refusal.get("violations"))).contains(StatementProcesses.UNMAPPED, "2150");
        // Version 1 still issues, and the first issue verifies identical (FIN-RP-011 acceptance 1).
        assertThat(ok(StatementProcesses.ISSUE, controller, Map.of("templateId", StatementProcesses.BALANCE_SHEET,
            "params", Map.of("asOf", "2026-01-31", "layoutVersion", 1)))).containsEntry("layoutVersion", 1);
        assertThat(verify(issued.get("runId"))).isEqualTo("identical");
        // As known at the first issue, the version then latest: version 1.
        assertThat(ok(StatementProcesses.ISSUE, controller, Map.of("templateId", StatementProcesses.BALANCE_SHEET,
            "params", Map.of("asOf", "2026-01-31", "knownAt", firstIssue)))).containsEntry("layoutVersion", 1);

        // Version 3 takes payroll liabilities into accrued liabilities: it issues, with 44,590.00 on that line.
        List<Map<String, Object>> merged = new ArrayList<>(withoutPayroll);
        merged.replaceAll(r -> "ACCRUED".equals(r.get("lineCode")) ? with(r, "accounts", "2100-2199") : r);
        assertThat(ok(StatementProcesses.PUBLISH, controller, layout("BS", merged))).containsEntry("version", 3);
        Map<String, Object> third = ok(StatementProcesses.ISSUE, controller, Map.of("templateId",
            StatementProcesses.BALANCE_SHEET, "params", Map.of("asOf", "2026-01-31")));
        assertThat(third).containsEntry("layoutVersion", 3);
        assertThat(amount(byLine(report(StatementProcesses.BALANCE_SHEET, controller, Map.of("asOf", "2026-01-31",
            "layoutVersion", 3))).get("ACCRUED").get("amount"))).isEqualByComparingTo("44590.00");
        assertThat(verify(third.get("runId"))).isEqualTo("identical");
        assertThat(verify(issued.get("runId"))).isEqualTo("identical");
        // A time without seconds, as a form sends it, reads as the template reads it.
        clock.advance(java.time.Duration.ofMinutes(1));
        String minute = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmXXX")
            .withZone(java.time.ZoneOffset.UTC).format(clock.instant());
        assertThat(ok(StatementProcesses.ISSUE, controller, Map.of("templateId", StatementProcesses.BALANCE_SHEET,
            "params", Map.of("asOf", "2026-01-31", "knownAt", minute)))).containsEntry("layoutVersion", 3);

        assertOnlyInserted("fi_statement_layout_version", "fi_statement_layout_row_version");
    }

    private String verify(Object runId) {
        return (String) post("/api/reports/runs/" + runId + "/verify", as("auditor", "report.archive.read",
            "ledger.read"), Map.of()).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody()
            .get("verdict");
    }

    /** The rows in their order, by line code. */
    private static Map<String, Map<String, Object>> byLine(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> byLine = new LinkedHashMap<>();
        rows.forEach(r -> byLine.put((String) r.get("lineCode"), r));
        return byLine;
    }

    /** Each row's code and amount in the column; a heading's code alone. */
    private static List<String> amounts(Map<String, Map<String, Object>> rows, String column) {
        return rows.values().stream().map(r -> r.get(column) == null ? (String) r.get("lineCode")
            : r.get("lineCode") + " " + amount(r.get(column)).toPlainString()).toList();
    }

    private static List<Map<String, Object>> rows(List<StatementProcesses.RowInput> sample) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (StatementProcesses.RowInput r : sample) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("lineCode", r.lineCode());
            row.put("label", r.label());
            row.put("kind", r.kind());
            if (r.accounts() != null) {
                row.put("accounts", r.accounts());
            }
            row.put("sign", r.sign());
            row.put("detail", r.detail());
            row.put("omitZero", r.omitZero());
            if (r.noteAccounts() != null) {
                row.put("noteAccounts", r.noteAccounts());
            }
            rows.add(row);
        }
        return rows;
    }

    private static Map<String, Object> with(Map<String, Object> row, String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        copy.put(key, value);
        return copy;
    }

    private static Map<String, Object> layout(String code, List<Map<String, Object>> rows) {
        return Map.of("layoutCode", code, "statement", "BALANCE_SHEET", "title", "Balance sheet", "rows", rows);
    }
}
