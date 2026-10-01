package com.jabiz.finance.it;

import com.jabiz.finance.gl.JournalAutomation;
import com.jabiz.finance.gl.JournalEntities;
import com.jabiz.finance.gl.JournalProcesses;
import com.jabiz.finance.gl.PeriodProcesses;
import com.jabiz.job.JobDefinition;
import com.jabiz.runtime.job.JobRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.jabiz.finance.it.JournalLifecycleIT.entry;
import static com.jabiz.finance.it.JournalLifecycleIT.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Entries the books make by themselves (FIN-GL-017, FIN-GL-018): recurring entries once per template and period,
 * submitted under the approval rules, and automatic reversals numbered after their original with "-R".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class JournalAutomationIT extends FinanceItSupport {

    @Autowired
    JobRunner jobs;

    @Autowired
    @Qualifier("recurringJob")
    JobDefinition<JournalAutomation.RunInput> recurringJob;

    @Autowired
    @Qualifier("autoReverseJob")
    JobDefinition<JournalAutomation.RunInput> autoReverseJob;

    private static boolean booksOpen;

    @BeforeEach
    void books() {
        if (!booksOpen) {
            openBooks();
            booksOpen = true;
        }
    }

    private String accountant() {
        return as("accountant", "fin.journal.prepare", "fin.journal.read", "fin.recurring.maintain");
    }

    private Map<String, Object> commit(String dataset, Map<String, Object> attributes) {
        return post("/api/datasets/" + dataset + "/commit", accountant(), Map.of("changes", List.of(
            Map.of("action", "INSERT", "attributes", attributes)))).expectStatus().isOk().expectBody(LIST)
            .returnResult().getResponseBody().getFirst();
    }

    private void template(String code, String start, String debit, String credit, String amount) {
        Object id = commit(JournalEntities.RECURRING_DATASET, Map.of("templateCode", code,
            "description", "Recurring: " + code, "startDate", start, "active", true)).get("id");
        commit(JournalEntities.RECURRING_LINE_DATASET, Map.of("templateId", id, "lineNo", 1, "accountCode", debit,
            "debit", amount));
        commit(JournalEntities.RECURRING_LINE_DATASET, Map.of("templateId", id, "lineNo", 2, "accountCode", credit,
            "credit", amount));
    }

    /** FIN-GL-017 acceptance 1: the January run makes the entry once; a second run makes nothing. */
    @Test
    @SuppressWarnings("unchecked")
    void recurringEntriesAreMadeOncePerPeriod() {
        template("PREPAID-INS", "2026-01-01", "6600", "1300", "1000.00");
        template("RENT-ACCRUAL", "2026-01-01", "6200", "2100", "12000.00");
        template("LATER", "2026-03-01", "6800", "2100", "5.00");

        // The job of 31 January 06:00 in Chicago.
        assertThat(jobs.run(recurringJob, Instant.parse("2026-01-31T12:00:00Z")))
            .isEqualTo(JobRunner.Outcome.SUCCEEDED);
        List<Map<String, Object>> made = january();
        assertThat(made).hasSize(2);
        Map<String, Object> insurance = made.stream()
            .filter(j -> "Recurring: PREPAID-INS".equals(j.get("description"))).findFirst().orElseThrow();
        // Small enough to post at once; the rent waits for an approver like a manual entry would.
        assertThat(insurance).containsEntry("status", "POSTED").containsEntry("postingDate", "2026-01-31")
            .containsEntry("recurringKey", "PREPAID-INS/2026-01").containsEntry("preparer", "system");
        assertThat(made).anySatisfy(j -> assertThat(j).containsEntry("recurringKey", "RENT-ACCRUAL/2026-01")
            .containsEntry("status", "SUBMITTED"));

        Map<String, Object> again = ok(JournalAutomation.RECURRING_RUN, accountant(), Map.of("date", "2026-01-15"));
        assertThat((List<?>) again.get("entries")).isEmpty();
        assertThat(again).containsEntry("periodKey", "2026-01");
        assertThat(january()).hasSize(2);
        assertThat(refused(JournalAutomation.RECURRING_RUN, accountant(), Map.of("date", "2030-01-15"), 422))
            .isEqualTo(JournalAutomation.NO_PERIOD);
    }

    /** A template that cannot post is left out with its reason; the others are made. */
    @Test
    @SuppressWarnings("unchecked")
    void aTemplateThatCannotPostIsReportedAndTheOthersAreMade() {
        String good = "GOOD-" + unique();
        template(good, "2026-04-01", "6800", "2100", "10.00");
        String bad = "BAD-" + unique();
        template(bad, "2026-04-01", "6800", "1200", "10.00");
        Map<String, Object> run = ok(JournalAutomation.RECURRING_RUN, accountant(), Map.of("date", "2026-04-15"));
        List<Map<String, Object>> made = find(JournalEntities.JOURNAL_DATASET, "source", "RECURRING");
        assertThat(made).filteredOn(j -> (good + "/2026-04").equals(j.get("recurringKey"))).singleElement()
            .satisfies(j -> assertThat(j).containsEntry("status", "POSTED"));
        assertThat(made).noneSatisfy(j -> assertThat(j.get("recurringKey")).isEqualTo(bad + "/2026-04"));
        assertThat((List<Map<String, Object>>) run.get("skipped")).singleElement()
            .satisfies(s -> assertThat(s).containsEntry("templateCode", bad)
                .hasEntrySatisfying("reason", r -> assertThat((String) r).contains("1200")));
    }

    /** Reversals post without approval, so never ahead of their day; reversed entries are not looked at again. */
    @Test
    @SuppressWarnings("unchecked")
    void reversalsAreNotPostedEarlyNorTwice() {
        assertThat(refused(JournalAutomation.AUTO_REVERSE_RUN, accountant(), Map.of("date", "2026-02-01"), 422))
            .isEqualTo(JournalAutomation.FUTURE_DATE);

        Map<String, Object> accrual = entry("2026-01-30", "Accrue freight", List.of(
            line("6300", "20.00", null, null), line("2100", null, "20.00", null)));
        accrual.put("autoReverseDate", "2026-01-31");
        String id = (String) ok(JournalProcesses.SAVE, accountant(), accrual).get("journalId");
        ok(JournalProcesses.SUBMIT, accountant(), Map.of("journalId", id));
        // Reversed by hand first: the automatic reversal leaves it alone.
        ok(JournalProcesses.REVERSE, accountant(), Map.of("journalId", id, "postingDate", "2026-01-30"));
        Map<String, Object> run = ok(JournalAutomation.AUTO_REVERSE_RUN, accountant(), Map.of("date", "2026-01-31"));
        assertThat((List<Map<String, Object>>) run.get("reversals"))
            .noneSatisfy(r -> assertThat(r).containsEntry("journalId", id));
    }

    /** FIN-GL-018 acceptance 1: on February 1 the reversal posts with opposite amounts and refers to the original. */
    @Test
    void anEntryMarkedToReverseIsReversedOnItsDay() {
        Map<String, Object> accrual = entry("2026-01-31", "Accrue unbilled consulting", List.of(
            line("6400", "350.00", null, null), line("2100", null, "350.00", null)));
        accrual.put("autoReverseDate", "2026-02-01");
        String id = (String) ok(JournalProcesses.SAVE, accountant(), accrual).get("journalId");
        Map<String, Object> original = ok(JournalProcesses.SUBMIT, accountant(), Map.of("journalId", id));
        assertThat(original).containsEntry("status", "POSTED");

        // Not yet on 31 January.
        ok(JournalAutomation.AUTO_REVERSE_RUN, accountant(), Map.of("date", "2026-01-31"));
        assertThat(reversalsOf(id)).isEmpty();
        clock.set(Instant.parse("2026-02-02T12:00:00Z"));

        assertThat(jobs.run(autoReverseJob, Instant.parse("2026-02-01T06:30:00Z")))
            .isEqualTo(JobRunner.Outcome.SUCCEEDED);
        List<Map<String, Object>> reversals = reversalsOf(id);
        assertThat(reversals).singleElement().satisfies(r -> assertThat(r)
            .containsEntry("journalNo", original.get("journalNo") + "-R").containsEntry("status", "POSTED")
            .containsEntry("reversesJournalId", id).containsEntry("postingDate", "2026-02-01")
            .containsEntry("periodKey", "2026-02"));
        Object reversalId = reversals.getFirst().get("journalId");
        assertThat(find(JournalEntities.LINE_DATASET, "journalId", reversalId))
            .anySatisfy(l -> assertThat(l).containsEntry("accountCode", "6400").containsEntry("credit", 350.00))
            .anySatisfy(l -> assertThat(l).containsEntry("accountCode", "2100").containsEntry("debit", 350.00));

        // Again: nothing more.
        ok(JournalAutomation.AUTO_REVERSE_RUN, accountant(), Map.of("date", "2026-02-02"));
        assertThat(reversalsOf(id)).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aReversalWaitsWhileItsPeriodIsClosed() {
        Map<String, Object> accrual = entry("2026-02-27", "Accrue utilities", List.of(
            line("6300", "90.00", null, null), line("2100", null, "90.00", null)));
        accrual.put("autoReverseDate", "2026-03-01");
        String id = (String) ok(JournalProcesses.SAVE, accountant(), accrual).get("journalId");
        ok(JournalProcesses.SUBMIT, accountant(), Map.of("journalId", id));
        ok(PeriodProcesses.SET_STATE, controller(), Map.of("periodKey", "2026-03", "status", "CLOSED"));
        clock.set(Instant.parse("2026-03-02T12:00:00Z"));

        Map<String, Object> run = ok(JournalAutomation.AUTO_REVERSE_RUN, accountant(), Map.of("date", "2026-03-01"));
        assertThat((List<Map<String, Object>>) run.get("reversals")).singleElement()
            .satisfies(r -> assertThat(r).containsEntry("posted", false).containsEntry("reversalId", null));
        ok(PeriodProcesses.SET_STATE, controller(), Map.of("periodKey", "2026-03", "status", "OPEN"));
        Map<String, Object> later = ok(JournalAutomation.AUTO_REVERSE_RUN, accountant(), Map.of("date", "2026-03-02"));
        assertThat((List<Map<String, Object>>) later.get("reversals")).singleElement()
            .satisfies(r -> assertThat(r).containsEntry("posted", true));
        assertOnlyInserted("fi_journal_version", "fi_journal_line_version", "fi_recurring_template_version",
            "fi_recurring_line_version");
    }

    /** The recurring entries of January; other tests make entries of other months. */
    private List<Map<String, Object>> january() {
        return find(JournalEntities.JOURNAL_DATASET, "source", "RECURRING").stream()
            .filter(j -> String.valueOf(j.get("recurringKey")).endsWith("/2026-01")).toList();
    }

    private List<Map<String, Object>> reversalsOf(String journalId) {
        return find(JournalEntities.JOURNAL_DATASET, "source", "AUTO_REVERSING").stream()
            .filter(j -> journalId.equals(j.get("reversesJournalId"))).toList();
    }
}
