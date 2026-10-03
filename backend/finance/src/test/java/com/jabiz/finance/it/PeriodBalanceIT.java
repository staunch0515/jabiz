package com.jabiz.finance.it;

import com.jabiz.context.DataPeriod;
import com.jabiz.finance.close.CloseEntities;
import com.jabiz.finance.close.CloseProcesses;
import com.jabiz.finance.close.ReopenProcesses;
import com.jabiz.finance.gl.JournalProcesses;
import com.jabiz.finance.gl.PeriodBalances;
import com.jabiz.finance.setup.FinanceRoles;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jabiz.finance.it.JournalLifecycleIT.entry;
import static com.jabiz.finance.it.JournalLifecycleIT.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The period balances (perf.md Q1; ROADMAP F9a decision D1) and the report trial balance read from them (FIN-RP-001):
 * January's close keeps its balances counted to the close; the trial balance at January's end is FIN-EXP-03 with its
 * opening, debits, credits and closing balance, and the same as summing the entries, also over January and February
 * together; reopened, posted to and closed again, January reads as it was known at each time, before and after the
 * second snapshot; a department's trial balance counts only its own, from the snapshot too; a reader limited to a data
 * period reads only what lies within it; and a snapshot is taken only of a closed period, by someone not so limited.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class PeriodBalanceIT extends JanuaryBooks {

    @Test
    void theReportsReadThePeriodBalancesAsTheEntriesSumUp() throws IOException {
        januaryPostings();
        Map<String, Object> closed = closeJanuary();
        Map<String, Object> artifact = read(CloseEntities.ARTIFACT_DATASET, closed.get("artifactId"));
        String firstClose = (String) artifact.get("knownAt");

        // The close kept January's balances, counted to the close, as the entries add up.
        List<Map<String, Object>> snapshot = find(PeriodBalances.DATASET, "periodKey", "2026-01");
        assertThat(snapshot).isNotEmpty().allSatisfy(b -> assertThat(b).containsEntry("countedTo", firstClose)
            .containsEntry("fiscalYear", 2026).containsEntry("periodNo", 1));
        assertThat(total(snapshot, "debit")).isEqualByComparingTo(total(snapshot, "credit"));
        assertThat(sums(snapshot)).isEqualTo(sums(report(PeriodBalances.MOVEMENTS_TEMPLATE, controller,
            Map.of("periodKey", "2026-01", "knownAt", firstClose))));

        // FIN-RP-001 acceptance 1: at 2026-01-31 the trial balance is FIN-EXP-03, totals 826,012.90; January's opening
        // is the cutover's, its debits and credits January's entries.
        Map<String, Map<String, Object>> january = sameAsEntries(controller, "2026-01-01", "2026-01-31", Map.of());
        List<Map<String, Object>> accounts = january.values().stream()
            .filter(r -> !Boolean.TRUE.equals(r.get("summary"))).toList();
        assertThat(total(accounts, "closingDebit")).isEqualByComparingTo("826012.90");
        assertThat(total(accounts, "closingCredit")).isEqualByComparingTo("826012.90");
        assertThat(amount(january.get("1010").get("opening"))).isEqualByComparingTo("250000.00");
        assertThat(amount(january.get("1010").get("closingDebit"))).isEqualByComparingTo("211555.00");
        assertThat(amount(january.get("6400").get("debit"))).isEqualByComparingTo("34000.00");
        assertThat(amount(january.get("6400").get("opening"))).isEqualByComparingTo("0.00");
        // The range defaults to the period of its last day.
        sameAsEntries(controller, null, "2026-01-31", Map.of());

        // February: its entries read one by one after January's snapshot; mid-month too.
        clock.advance(Duration.ofDays(5));
        people();
        post(entry("2026-02-03", "February consulting", List.of(line("6400", "300.00", null, null),
            line("2100", null, "300.00", null))));
        sameAsEntries(controller, "2026-01-01", "2026-02-28", Map.of());
        sameAsEntries(controller, "2026-02-01", "2026-02-15", Map.of());
        sameAsEntries(controller, "2026-01-15", "2026-02-03", Map.of());
        assertThat(amount(sameAsEntries(controller, "2026-02-01", "2026-02-28", Map.of()).get("6400").get("debit")))
            .isEqualByComparingTo("300.00");

        // A department: only its lines.
        Map<String, Object> sales = new LinkedHashMap<>(line("6400", "40.00", null, null));
        sales.put("department", "SALES");
        post(entry("2026-02-04", "Sales team consulting", List.of(sales, line("2100", null, "40.00", null))));
        Map<String, Object> bySales = Map.of("from", "2026-02-01", "through", "2026-02-28", "department", "SALES");
        assertThat(report("finance.report.trial_balance", controller, bySales)).filteredOn(
            r -> amount(r.get("closing")).signum() != 0 && !Boolean.TRUE.equals(r.get("summary")))
            .extracting(r -> r.get("accountCode") + " " + amount(r.get("closing"))).containsExactly("6400 40.00");

        // Reopened, January takes an entry and closes again: as known at the first close it is as it was, as known
        // now it has the entry, and before the second close it reads the first snapshot and the entry after it.
        Map<String, Object> asked = ok(ReopenProcesses.REQUEST, accountant, Map.of("periodKey", "2026-01",
            "reason", "Late accrual"));
        decide(asked.get("approvalRequestId"));
        clock.advance(Duration.ofMinutes(10));
        people();
        Map<String, Object> salesAccrual = new LinkedHashMap<>(line("6400", "100.00", null, null));
        salesAccrual.put("department", "SALES");
        post(entry("2026-01-31", "Late accrual", List.of(salesAccrual, line("2100", null, "100.00", null))));
        String afterEntry = clock.instant().toString();
        sameAsEntries(controller, "2026-01-01", "2026-01-31", Map.of("knownAt", afterEntry));
        clock.advance(Duration.ofHours(1));
        people();
        ok(CloseProcesses.CLOSE, controller, Map.of("periodKey", "2026-01"));
        assertThat(find(PeriodBalances.DATASET, "periodKey", "2026-01").stream().map(b -> b.get("countedTo"))
            .distinct()).hasSize(2);
        Map<String, Map<String, Object>> asFirst = sameAsEntries(controller, "2026-01-01", "2026-01-31",
            Map.of("knownAt", firstClose));
        assertThat(amount(asFirst.get("6400").get("closing"))).isEqualByComparingTo("34000.00");
        Map<String, Map<String, Object>> between = sameAsEntries(controller, "2026-01-01", "2026-01-31",
            Map.of("knownAt", afterEntry));
        assertThat(amount(between.get("6400").get("closing"))).isEqualByComparingTo("34100.00");
        assertThat(amount(sameAsEntries(controller, "2026-01-01", "2026-01-31", Map.of()).get("6400").get("closing")))
            .isEqualByComparingTo("34100.00");
        // The second snapshot keeps the department: read from it, January's SALES trial balance has the accrual.
        assertThat(find(PeriodBalances.DATASET, "accountCode", "6400")).anySatisfy(b -> assertThat(b)
            .containsEntry("periodKey", "2026-01").containsEntry("department", "SALES"));
        assertThat(report("finance.report.trial_balance", controller, Map.of("from", "2026-01-01",
            "through", "2026-02-28", "department", "SALES"))).filteredOn(
            r -> amount(r.get("closing")).signum() != 0 && !Boolean.TRUE.equals(r.get("summary")))
            .extracting(r -> r.get("accountCode") + " " + amount(r.get("debit")) + " " + amount(r.get("closing")))
            .containsExactly("6400 140.00 140.00");

        // A reader limited to February sees no January: neither its snapshots nor its entries; a snapshot taken by
        // someone so limited would count only part of the books and is refused.
        DataPeriod february = new DataPeriod(Instant.parse("2026-02-01T06:00:00Z"),
            Instant.parse("2026-03-01T06:00:00Z"));
        String[] controls = FinanceRoles.all().stream().filter(r -> FinanceRoles.CONTROLLER.equals(r.code()))
            .flatMap(r -> r.permissions().stream()).toArray(String[]::new);
        String limited = TestTokens.withinPeriod(tokens, february, "limited", controls);
        Map<String, Map<String, Object>> seen = sameAsEntries(limited, "2026-01-01", "2026-02-28", Map.of());
        assertThat(amount(seen.get("6400").get("closing"))).isEqualByComparingTo("340.00");
        assertThat(amount(seen.get("6400").get("opening"))).isEqualByComparingTo("0.00");
        assertThat(refused(PeriodBalances.SNAPSHOT, limited, Map.of("periodKey", "2026-01"), 422))
            .isEqualTo(PeriodBalances.DATA_PERIOD);

        // A snapshot is taken only of a closed period; taken again, the figures stay.
        assertThat(refused(PeriodBalances.SNAPSHOT, controller, Map.of("periodKey", "2026-02"), 422))
            .isEqualTo(PeriodBalances.NOT_CLOSED);
        run(PeriodBalances.SNAPSHOT, accountant, Map.of("periodKey", "2026-01")).expectStatus().isForbidden();
        clock.advance(Duration.ofMinutes(5));
        people();
        assertThat(ok(PeriodBalances.SNAPSHOT, controller, Map.of("periodKey", "2026-01")))
            .containsEntry("periodKey", "2026-01");
        sameAsEntries(controller, "2026-01-01", "2026-02-28", Map.of());
        sameAsEntries(controller, "2026-01-01", "2026-01-31", Map.of("knownAt", firstClose));

        assertOnlyInserted("fi_period_balance_version");
    }

    /** Posts an entry small enough to need no approval. */
    private void post(Map<String, Object> entry) {
        String id = (String) ok(JournalProcesses.SAVE, accountant, entry).get("journalId");
        assertThat(ok(JournalProcesses.SUBMIT, accountant, Map.of("journalId", id))).containsEntry("status", "POSTED");
    }

    private static BigDecimal total(List<Map<String, Object>> rows, String field) {
        return rows.stream().map(r -> amount(r.get(field))).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Debit and credit by account, department and location. */
    private static Map<String, String> sums(List<Map<String, Object>> rows) {
        Map<String, String> sums = new java.util.TreeMap<>();
        rows.forEach(r -> sums.put(r.get("accountCode") + "|" + r.get("department") + "|" + r.get("location"),
            amount(r.get("debit")) + "|" + amount(r.get("credit"))));
        return sums;
    }
}
