package com.jabiz.finance.it;

import com.jabiz.finance.close.CloseEntities;
import com.jabiz.finance.close.ReopenProcesses;
import com.jabiz.finance.gl.AccountTypes;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.finance.gl.JournalEntities;
import com.jabiz.finance.gl.JournalProcesses;
import com.jabiz.finance.gl.PeriodProcesses;
import com.jabiz.finance.gl.YearCloseProcesses;
import com.jabiz.finance.setup.FinanceRoles;
import com.jabiz.runtime.event.OutboxDeliverer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.jabiz.finance.it.JournalLifecycleIT.entry;
import static com.jabiz.finance.it.JournalLifecycleIT.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIN-SCN-11, the year-end close (FIN-PC-008), on a generated year that follows the sample's rules: the sample chart,
 * a sale, the rent and the utilities every month, each month closed through its checklist. The controller closes
 * the fiscal year 2026: the closing entry CLS-2026 in period 13 carries the net income to retained earnings 3200,
 * income and expense start 2027 at zero and the balance sheet at the year's end is unchanged but for retained
 * earnings taking the income. An audit adjustment is posted to the reopened period 13 and the year closed again:
 * CLS-2026 is reversed, CLS-2026-2 carries the income with the adjustment, the first year-end artifact is superseded.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class FinScn11IT extends FinanceItSupport {

    @Autowired
    OutboxDeliverer deliverer;

    private String controller;
    private String accountant;

    private void people() {
        controller = inRoles("controller", FinanceRoles.CONTROLLER);
        accountant = inRoles("accountant", FinanceRoles.ACCOUNTANT);
    }

    @Test
    void theYearEndClose() {
        clock.set(Instant.parse("2027-01-15T15:00:00Z"));
        people();
        openBooks();
        ok("FIN_ACCOUNT_CREATE", controller(), Map.of("accountCode", "1400", "accountName", "Other Receivables",
            "financialType", AccountTypes.fromChart("Asset"), "normalBalance", AccountTypes.normalBalanceFromChart("D"),
            "statementLine", "Other receivables"));
        ok(PeriodProcesses.FISCAL_YEAR_CREATE, controller(), Map.of("fiscalYear", 2027, "adjustmentPeriod", true));

        // Step 1: a generated year; each month's sale, rent and utilities, each month closed.
        BigDecimal expected = BigDecimal.ZERO.setScale(2);
        for (int month = 1; month <= 12; month++) {
            YearMonth ym = YearMonth.of(2026, month);
            String day = ym.atDay(15).toString();
            BigDecimal sale = new BigDecimal("8000.00").add(BigDecimal.valueOf(month * 50L));
            BigDecimal power = new BigDecimal("500.00").add(BigDecimal.valueOf(month * 10L));
            post(entry(day, "Engineering services " + ym, List.of(line("1400", sale.toPlainString(), null, null),
                line("4100", null, sale.toPlainString(), null))));
            post(entry(ym.atEndOfMonth().toString(), "Rent " + ym, List.of(line("6200", "3000.00", null, null),
                line("2100", null, "3000.00", null))));
            post(entry(ym.atEndOfMonth().toString(), "Utilities " + ym, List.of(line("6300", power.toPlainString(),
                null, null), line("2100", null, power.toPlainString(), null))));
            expected = expected.add(sale).subtract(new BigDecimal("3000.00")).subtract(power);
            if (month == 12) {
                // The year waits for December.
                ok(YearCloseProcesses.SETTINGS_SET, controller, Map.of("retainedEarningsAccount", "3200"));
                assertThat(refused(YearCloseProcesses.YEAR_CLOSE, controller, Map.of("fiscalYear", 2026), 422))
                    .isEqualTo(YearCloseProcesses.PERIODS_OPEN);
            }
            closePeriod(ym.toString());
        }
        people();
        assertThat(expected).isEqualByComparingTo("57120.00");

        // The settings: retained earnings is an equity account that is no control account.
        assertThat(refused(YearCloseProcesses.SETTINGS_SET, controller, Map.of("retainedEarningsAccount", "4100"),
            422)).isEqualTo(YearCloseProcesses.SETTINGS_ACCOUNT);
        run(YearCloseProcesses.YEAR_CLOSE, accountant, Map.of("fiscalYear", 2026)).expectStatus().isForbidden();
        assertThat(refused(YearCloseProcesses.YEAR_CLOSE, controller, Map.of("fiscalYear", 2027), 422))
            .isEqualTo(YearCloseProcesses.PERIODS_OPEN);

        Map<String, BigDecimal> december = balances("2026-12-31", false);
        Map<String, BigDecimal> before = balances("2026-12-31", true);
        Map<String, Object> first = ok(YearCloseProcesses.YEAR_CLOSE, controller, Map.of("fiscalYear", 2026));
        assertThat(first).containsEntry("journalNo", "CLS-2026").containsEntry("reversalNo", null)
            .containsEntry("seq", 1).containsEntry("retainedEarningsAccount", "3200");
        assertThat(amount(first.get("netIncome"))).isEqualByComparingTo(expected);
        assertThat(postingLines("CLS-2026")).containsEntry("4100", new BigDecimal("99900.00"))
            .containsEntry("6200", new BigDecimal("-36000.00")).containsEntry("6300", new BigDecimal("-6780.00"))
            .containsEntry("3200", expected.negate());
        assertThat(find(JournalEntities.POSTING_DATASET, "documentNo", "CLS-2026")).singleElement()
            .satisfies(p -> assertThat(p).containsEntry("source", "CLS").containsEntry("periodKey", "2026-13"));
        assertThat(find(GlEntities.PERIOD_DATASET, "periodKey", "2026-13").getFirst())
            .containsEntry("status", "CLOSED");

        // Expected: income and expense at zero after the close; retained earnings up by the net income; the rest of
        // the balance sheet and December's own figures unchanged.
        Map<String, BigDecimal> after = balances("2026-12-31", true);
        for (String account : List.of("4100", "6200", "6300")) {
            assertThat(after.get(account)).as(account).isEqualByComparingTo("0.00");
        }
        assertThat(after.get("3200")).isEqualByComparingTo(before.get("3200").subtract(expected));
        for (String account : List.of("1400", "2100")) {
            assertThat(after.get(account)).as(account).isEqualByComparingTo(before.get(account));
        }
        assertThat(balances("2026-12-31", false)).isEqualTo(december);
        // Before closing entries, the year's income is still there (FIN-RP-001).
        assertThat(report("finance.gl.trial_balance", controller, Map.of("through", "2026-12-31",
            "closingEntries", false)).stream().filter(r -> "4100".equals(r.get("accountCode")))
            .map(r -> amount(r.get("balance"))).findFirst().orElseThrow()).isEqualByComparingTo("-99900.00");
        // 2027 starts with income and expense at zero and the balance sheet carried forward.
        Map<String, BigDecimal> newYear = balances("2027-01-01", false);
        for (String account : List.of("4100", "6200", "6300")) {
            assertThat(newYear.get(account)).as(account).isEqualByComparingTo("0.00");
        }
        assertThat(newYear.get("3200")).isEqualByComparingTo(expected.negate());
        assertThat(newYear.get("1400")).isEqualByComparingTo(after.get("1400"));
        // In 2027, before closing entries means 2027's: 2026's closing still counts.
        assertThat(report("finance.gl.trial_balance", controller, Map.of("through", "2027-01-01",
            "closingEntries", false)).stream().filter(r -> "4100".equals(r.get("accountCode")))
            .map(r -> amount(r.get("balance"))).findFirst().orElseThrow()).isEqualByComparingTo("0.00");
        // Closed: not again until period 13 is reopened; its entry is reversed only by closing again; its months
        // stay closed.
        assertThat(refused(YearCloseProcesses.YEAR_CLOSE, controller, Map.of("fiscalYear", 2026), 422))
            .isEqualTo(YearCloseProcesses.CLOSED_ALREADY);
        assertThat(refused(JournalProcesses.REVERSE, accountant, Map.of("journalId", find(
            JournalEntities.JOURNAL_DATASET, "journalNo", "CLS-2026").getFirst().get("journalId"), "postingDate",
            "2026-12-31"), 422)).isEqualTo(JournalProcesses.CLOSING_NOT_REVERSED);
        assertThat(refused(ReopenProcesses.REQUEST, accountant, Map.of("periodKey", "2026-12", "reason",
            "Late invoice"), 422)).isEqualTo(ReopenProcesses.YEAR_CLOSED);

        // Step 2: period 13 reopened for an audit adjustment; the year closed again.
        clock.advance(java.time.Duration.ofDays(20));
        people();
        Map<String, Object> asked = ok(ReopenProcesses.REQUEST, accountant, Map.of("periodKey", "2026-13",
            "reason", "Audit adjustment of the accrued fees"));
        ok("APPROVAL_DECIDE", controller, Map.of("requestId", asked.get("approvalRequestId"), "decision", "APPROVE"));
        deliverer.deliverPending().block();
        assertThat(find(GlEntities.PERIOD_DATASET, "periodKey", "2026-13").getFirst())
            .containsEntry("status", "OPEN");
        Map<String, Object> audit = new LinkedHashMap<>(entry("2026-12-31", "Audit adjustment: fees accrued",
            List.of(line("6400", "1200.00", null, null), line("2100", null, "1200.00", null))));
        audit.put("adjusting", true);
        audit.put("adjustmentPeriod", true);
        post(audit);
        Map<String, Object> second = ok(YearCloseProcesses.YEAR_CLOSE, controller, Map.of("fiscalYear", 2026));
        BigDecimal adjusted = expected.subtract(new BigDecimal("1200.00"));
        assertThat(second).containsEntry("journalNo", "CLS-2026-2").containsEntry("reversalNo", "CLS-2026-R")
            .containsEntry("seq", 2);
        assertThat(amount(second.get("netIncome"))).isEqualByComparingTo(adjusted);
        assertThat(postingLines("CLS-2026-R")).containsEntry("3200", expected)
            .containsEntry("4100", new BigDecimal("-99900.00"));
        assertThat(postingLines("CLS-2026-2")).containsEntry("6400", new BigDecimal("-1200.00"))
            .containsEntry("3200", adjusted.negate());
        assertThat(find(JournalEntities.JOURNAL_DATASET, "journalNo", "CLS-2026").getFirst())
            .containsEntry("reversedById", find(JournalEntities.JOURNAL_DATASET, "journalNo", "CLS-2026-R")
                .getFirst().get("journalId"));

        // Expected: retained earnings up by the income with the adjustment, income and expense at zero again, the
        // first year-end artifact superseded.
        Map<String, BigDecimal> again = balances("2027-01-01", false);
        for (String account : List.of("4100", "6200", "6300", "6400")) {
            assertThat(again.get(account)).as(account).isEqualByComparingTo("0.00");
        }
        assertThat(again.get("3200")).isEqualByComparingTo(adjusted.negate());
        List<Map<String, Object>> artifacts = report("finance.close.artifacts", controller,
            Map.of("periodKey", "2026-13"));
        assertThat(artifacts).hasSize(2);
        assertThat(artifacts).filteredOn(a -> first.get("artifactId").equals(a.get("artifactId"))).singleElement()
            .satisfies(a -> assertThat(a).containsEntry("supersededBy", second.get("artifactId")));
        assertThat(find(CloseEntities.ARTIFACT_LINE_DATASET, "artifactId", second.get("artifactId")))
            .filteredOn(l -> CloseEntities.YEAR_END.equals(l.get("section"))).singleElement()
            .satisfies(l -> assertThat((String) l.get("result")).contains("CLS-2026-2", "CLS-2026-R", "3200"));
        // As known at the first close, the year is as its first artifact holds it.
        Map<String, Object> firstArtifact = read(CloseEntities.ARTIFACT_DATASET, first.get("artifactId"));
        assertThat(com.jabiz.finance.close.CloseProcesses.trialBalanceHash(report("finance.gl.trial_balance",
            controller, Map.of("through", "2026-12-31", "adjustments", true, "knownAt", firstArtifact.get("knownAt")))))
            .isEqualTo(firstArtifact.get("trialBalanceHash"));
        // Closed a third time without changes: the second entry reversed, the third the same.
        clock.advance(java.time.Duration.ofDays(1));
        people();
        Map<String, Object> third = ok(ReopenProcesses.REQUEST, accountant, Map.of("periodKey", "2026-13",
            "reason", "Review of the adjustment"));
        ok("APPROVAL_DECIDE", controller, Map.of("requestId", third.get("approvalRequestId"), "decision", "APPROVE"));
        deliverer.deliverPending().block();
        Map<String, Object> closedAgain = ok(YearCloseProcesses.YEAR_CLOSE, controller, Map.of("fiscalYear", 2026));
        assertThat(closedAgain).containsEntry("journalNo", "CLS-2026-3").containsEntry("reversalNo", "CLS-2026-2-R")
            .containsEntry("seq", 3);
        assertThat(amount(closedAgain.get("netIncome"))).isEqualByComparingTo(adjusted);
        assertThat(balances("2027-01-01", false).get("3200")).isEqualByComparingTo(adjusted.negate());
        assertThat(find(CloseEntities.YEAR_CLOSE_DATASET, "fiscalYear", 2026)).hasSize(3);
        // Every month and each of period 13's closes kept the period's balances; read from them, the year's trial
        // balance is the entries' sum, with and without period 13 and the closing entries, and as known at the first
        // year close; 2027 opens with the balances carried forward (ROADMAP F9 decision D1).
        assertThat(find(com.jabiz.finance.gl.PeriodBalances.DATASET, "periodKey", "2026-13").stream()
            .map(b -> b.get("countedTo")).distinct()).hasSize(3);
        assertThat(find(com.jabiz.finance.gl.PeriodBalances.DATASET, "periodKey", "2026-06")).isNotEmpty();
        sameAsEntries(controller, "2026-01-01", "2026-12-31", Map.of());
        sameAsEntries(controller, "2026-07-01", "2026-12-31", Map.of());
        sameAsEntries(controller, "2026-01-01", "2026-12-31", Map.of("adjustments", false));
        sameAsEntries(controller, "2026-01-01", "2026-12-31", Map.of("closingEntries", false));
        sameAsEntries(controller, "2026-01-01", "2026-12-31", Map.of("knownAt", firstArtifact.get("knownAt")));
        sameAsEntries(controller, "2027-01-01", "2027-01-31", Map.of());
        assertOnlyInserted("fi_period_balance_version", "fi_year_close_version", "fi_close_artifact_version", "fi_close_settings_version");
    }

    /** Every account's balance (debit positive) on a day, period 13 of its year counted or not. */
    private Map<String, BigDecimal> balances(String day, boolean adjustments) {
        Map<String, BigDecimal> balances = new TreeMap<>();
        report("finance.gl.trial_balance", controller, Map.of("through", day, "adjustments", adjustments))
            .stream().filter(r -> !Boolean.TRUE.equals(r.get("summary")))
            .forEach(r -> balances.put((String) r.get("accountCode"), amount(r.get("balance"))));
        return balances;
    }

    private void post(Map<String, Object> entry) {
        String id = (String) ok(JournalProcesses.SAVE, accountant, entry).get("journalId");
        assertThat(ok(JournalProcesses.SUBMIT, accountant, Map.of("journalId", id))).containsEntry("status",
            "POSTED");
    }
}
