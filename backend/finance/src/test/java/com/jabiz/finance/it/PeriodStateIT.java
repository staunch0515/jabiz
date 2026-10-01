package com.jabiz.finance.it;

import com.jabiz.finance.gl.GlEntities;
import com.jabiz.finance.gl.PeriodProcesses;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fiscal calendar and period states (FIN-PC-001, FIN-PC-003): a year and its periods, the adjustment period 13
 * when asked, no overlapping years, and the states of the general ledger and of each subledger, changed only by their
 * processes with the close permission. What a state allows is {@code PeriodPolicy}'s ({@code PeriodPolicyTest});
 * the posting processes that apply it come with phase F1b.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class PeriodStateIT extends FinanceItSupport {

    @Test
    @SuppressWarnings("unchecked")
    void aCalendarYearHasTwelvePeriodsAndTheAdjustmentPeriod() {
        Map<String, Object> year = ok(PeriodProcesses.FISCAL_YEAR_CREATE, controller(),
            Map.of("fiscalYear", 2026, "adjustmentPeriod", true));

        assertThat(year).containsEntry("startDate", "2026-01-01").containsEntry("endDate", "2026-12-31");
        assertThat((List<String>) year.get("periods")).hasSize(13).startsWith("2026-01").endsWith("2026-12", "2026-13");
        Map<String, Object> january = find(GlEntities.PERIOD_DATASET, "periodKey", "2026-01").getFirst();
        assertThat(january).containsEntry("startDate", "2026-01-01").containsEntry("endDate", "2026-01-31")
            .containsEntry("status", "OPEN").containsEntry("arStatus", "OPEN").containsEntry("faStatus", "OPEN");
        assertThat(find(GlEntities.PERIOD_DATASET, "periodKey", "2026-02").getFirst())
            .containsEntry("endDate", "2026-02-28");
        // Period 13 spans December's days.
        assertThat(find(GlEntities.PERIOD_DATASET, "periodKey", "2026-13").getFirst())
            .containsEntry("startDate", "2026-12-01").containsEntry("endDate", "2026-12-31");

        assertThat(refused(PeriodProcesses.FISCAL_YEAR_CREATE, controller(), Map.of("fiscalYear", 2026), 422))
            .isEqualTo(PeriodProcesses.FISCAL_YEAR_OVERLAP);
        // A year starting in July 2026 ends in 2027 and overlaps 2026.
        assertThat(refused(PeriodProcesses.FISCAL_YEAR_CREATE, controller(),
            Map.of("fiscalYear", 2027, "startDate", "2026-07-01"), 422)).isEqualTo(PeriodProcesses.FISCAL_YEAR_OVERLAP);
        assertThat(refused(PeriodProcesses.FISCAL_YEAR_CREATE, controller(),
            Map.of("fiscalYear", 2028, "startDate", "2027-01-15"), 422)).isEqualTo(PeriodProcesses.FISCAL_YEAR_INVALID);
        assertOnlyInserted("fi_fiscal_year_version", "fi_period_version");
    }

    /** Two overlapping years asked for at the same moment: one is created, never both. */
    @Test
    void overlappingYearsCannotBothBeCreatedEvenAtOnce() throws Exception {
        String controller = controller();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<Integer> first = pool.submit(() -> status(controller,
                Map.of("fiscalYear", 2051)));
            java.util.concurrent.Future<Integer> second = pool.submit(() -> status(controller,
                Map.of("fiscalYear", 2052, "startDate", "2051-07-01")));
            assertThat(List.of(first.get(), second.get())).filteredOn(s -> s == 200).hasSize(1);
        } finally {
            pool.shutdown();
        }
        assertThat(find(GlEntities.PERIOD_DATASET, "startDate", "2051-07-01")).hasSize(1);
    }

    private int status(String authorization, Map<String, Object> input) {
        return run(PeriodProcesses.FISCAL_YEAR_CREATE, authorization, input).returnResult(String.class)
            .getStatus().value();
    }

    @Test
    void aFiscalYearIsNamedAfterTheYearItEndsIn() {
        Map<String, Object> year = ok(PeriodProcesses.FISCAL_YEAR_CREATE, controller(),
            Map.of("fiscalYear", 2031, "startDate", "2030-07-01"));

        assertThat(year).containsEntry("endDate", "2031-06-30");
        assertThat(find(GlEntities.PERIOD_DATASET, "periodKey", "2031-01").getFirst())
            .containsEntry("startDate", "2030-07-01");
        assertThat(find(GlEntities.PERIOD_DATASET, "periodKey", "2031-13")).isEmpty();
        assertThat(refused(PeriodProcesses.FISCAL_YEAR_CREATE, controller(),
            Map.of("fiscalYear", 2031, "startDate", "2031-07-01"), 422)).isEqualTo(PeriodProcesses.FISCAL_YEAR_INVALID);
    }

    /** FIN-PC-003: states change only through their processes, and only with the close permission. */
    @Test
    void periodStatesChangeThroughTheirProcesses() {
        ok(PeriodProcesses.FISCAL_YEAR_CREATE, controller(), Map.of("fiscalYear", 2040));

        assertThat(ok(PeriodProcesses.SET_STATE, controller(), Map.of("periodKey", "2040-01", "status",
            "SOFT_CLOSED"))).containsEntry("status", "SOFT_CLOSED").containsEntry("changed", true);
        assertThat(ok(PeriodProcesses.SET_STATE, controller(), Map.of("periodKey", "2040-01", "status",
            "closed"))).containsEntry("status", "CLOSED");
        assertThat(ok(PeriodProcesses.SET_STATE, controller(), Map.of("periodKey", "2040-01", "status",
            "CLOSED"))).containsEntry("changed", false);
        // Receivables close for February before the general ledger does.
        assertThat(ok(PeriodProcesses.SET_SUBLEDGER_STATE, controller(), Map.of("periodKey", "2040-02",
            "subledger", "AR", "status", "CLOSED")))
            .containsEntry("status", "OPEN").containsEntry("arStatus", "CLOSED").containsEntry("apStatus", "OPEN");

        assertThat(refused(PeriodProcesses.SET_STATE, controller(), Map.of("periodKey", "2040-01", "status",
            "LOCKED"), 422)).isEqualTo(PeriodProcesses.INVALID_STATE);
        assertThat(refused(PeriodProcesses.SET_SUBLEDGER_STATE, controller(), Map.of("periodKey", "2040-01",
            "subledger", "AR", "status", "SOFT_CLOSED"), 422)).isEqualTo(PeriodProcesses.INVALID_STATE);
        assertThat(refused(PeriodProcesses.SET_SUBLEDGER_STATE, controller(), Map.of("periodKey", "2040-01",
            "subledger", "PAYROLL", "status", "CLOSED"), 422)).isEqualTo(PeriodProcesses.INVALID_STATE);
        assertThat(refused(PeriodProcesses.SET_STATE, controller(), Map.of("periodKey", "2039-01", "status",
            "OPEN"), 422)).isEqualTo(PeriodProcesses.PERIOD_NOT_FOUND);
        run(PeriodProcesses.SET_STATE, as("accountant", "fin.period.read", "fin.journal.prepare"),
            Map.of("periodKey", "2040-01", "status", "OPEN")).expectStatus().isForbidden();
        // The generic dataset API cannot change a state.
        Map<String, Object> january = find(GlEntities.PERIOD_DATASET, "periodKey", "2040-01").getFirst();
        assertThat(commitRefused(GlEntities.PERIOD_DATASET, as("admin", "*"), Map.of("action", "UPDATE",
            "id", january.get("periodId"), "version", 3, "attributes", Map.of("status", "OPEN"))))
            .isEqualTo("PROCESS_ONLY_DATASET");

        assertThat(find(GlEntities.PERIOD_DATASET, "periodKey", "2040-01").getFirst())
            .containsEntry("status", "CLOSED");
        assertOnlyInserted("fi_period_version");
    }
}
