package com.jabiz.finance.it;

import com.jabiz.finance.calc.CloseChecks;
import com.jabiz.finance.calc.PeriodPolicy;
import com.jabiz.finance.close.CloseEntities;
import com.jabiz.finance.close.CloseProcesses;
import com.jabiz.finance.gl.AccountTypes;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.finance.gl.JournalAutomation;
import com.jabiz.finance.gl.JournalEntities;
import com.jabiz.finance.gl.JournalProcesses;
import com.jabiz.finance.gl.PeriodProcesses;
import com.jabiz.finance.setup.FinanceRoles;
import com.jabiz.runtime.task.TaskEntities;
import com.jabiz.runtime.test.FileSamples;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.jabiz.finance.it.JournalLifecycleIT.entry;
import static com.jabiz.finance.it.JournalLifecycleIT.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The close checklist and the close (FIN-PC-004, PC-005, CT-005): the template {@code FIN_SETUP} makes and the
 * controller changes; a period's tasks, manual ones assigned as platform tasks to the holders of their permission;
 * each automatic check failing on what it looks for, with its result, time and evidence, then passing once the books
 * are put right; the close refused while anything required fails and naming each item; then the close, its artifact
 * and the issued trial balance, reproduced as known at the close; and a closed period that takes nothing more.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class CloseIT extends FinanceItSupport {

    private String controller;
    private String accountant;

    @Test
    @SuppressWarnings("unchecked")
    void theChecklistGuardsTheClose() {
        people();
        openBooks();

        // The sample template: eight checks and two manual tasks; the controller keeps it.
        assertThat(find(CloseEntities.TEMPLATE_DATASET, "active", true)).hasSize(10)
            .extracting(t -> (String) t.get("taskCode"))
            .containsAll(CloseChecks.ALL).contains("ACCRUALS", "REVIEW");
        assertThat(refused(CloseProcesses.TEMPLATE_SAVE, controller, item("CHECK_X", "AUTO", null, null), 422))
            .isEqualTo(CloseProcesses.TEMPLATE_INVALID);
        assertThat(refused(CloseProcesses.TEMPLATE_SAVE, controller, item("CHECK_Y", "AUTO", "BANK_RECONCILED",
            null), 422)).isEqualTo(CloseProcesses.TEMPLATE_INVALID);
        assertThat(refused(CloseProcesses.TEMPLATE_SAVE, controller, item("TASK_X", "MANUAL", null, null), 422))
            .isEqualTo(CloseProcesses.TEMPLATE_INVALID);
        run(CloseProcesses.TEMPLATE_SAVE, accountant, item("TAX_RETURN", "MANUAL", null, "fin.journal.prepare"))
            .expectStatus().isForbidden();

        // Not started, not closed.
        assertThat(refused(CloseProcesses.CLOSE, controller, Map.of("periodKey", "2026-01"), 422))
            .isEqualTo(CloseProcesses.NOT_STARTED);

        // The start makes the ten tasks; the manual ones become platform tasks of their permissions.
        Map<String, Object> started = ok(CloseProcesses.START, accountant, Map.of("periodKey", "2026-01"));
        assertThat(started).containsEntry("created", 10).containsEntry("ready", false);
        assertThat(find(TaskEntities.TASK_DATASET, "sourceKey", "fin.close:2026-01:ACCRUALS")).singleElement()
            .satisfies(t -> assertThat(t).containsEntry("assigneePermission", "fin.journal.prepare")
                .containsEntry("status", "OPEN").containsEntry("subjectEntity", CloseEntities.TASK)
                .containsEntry("taskType", CloseProcesses.TASK_TYPE));
        assertThat(find(TaskEntities.TASK_DATASET, "sourceKey", "fin.close:2026-01:REVIEW")).singleElement()
            .satisfies(t -> assertThat(t).containsEntry("assigneePermission", "fin.period.close"));
        Map<String, Map<String, Object>> tasks = tasks();
        assertThat(tasks.get("ACCRUALS")).containsEntry("dueDate", "2026-02-03").containsEntry("status", "OPEN");
        assertThat(tasks.get("BANK_RECONCILED")).containsEntry("dueDate", "2026-02-05");
        // An item added later joins at the next start; the others are not made twice.
        ok(CloseProcesses.TEMPLATE_SAVE, controller, item("TAX_RETURN", "MANUAL", null, "fin.journal.prepare"));
        assertThat(ok(CloseProcesses.START, accountant, Map.of("periodKey", "2026-01"))).containsEntry("created", 1);
        assertThat(tasks()).hasSize(11).containsKey("TAX_RETURN");

        // What the checks look for: a draft, a suspense balance, a recurring entry not made, an entry not reversed
        // and a manual line on the receivables account.
        String draft = (String) ok(JournalProcesses.SAVE, accountant, entry("2026-01-20", "Accrue utilities",
            List.of(line("6300", "100.00", null, null), line("2100", null, "100.00", null)))).get("journalId");
        ok("FIN_ACCOUNT_CREATE", controller(), Map.of("accountCode", "2900", "accountName", "Suspense",
            "financialType", AccountTypes.fromChart("Liability"), "normalBalance",
            AccountTypes.normalBalanceFromChart("C"), "statementLine", "Accrued liabilities", "clearing", true));
        post(entry("2026-01-22", "Unidentified bank charge", List.of(line("6800", "250.00", null, null),
            line("2900", null, "250.00", null))));
        Object template = commit(JournalEntities.RECURRING_DATASET, Map.of("templateCode", "PREPAID-INS",
            "description", "Recurring: amortize prepaid insurance 1/12", "startDate", "2026-01-01",
            "active", true)).get("id");
        commit(JournalEntities.RECURRING_LINE_DATASET, Map.of("templateId", template, "lineNo", 1,
            "accountCode", "6600", "debit", "1000.00"));
        commit(JournalEntities.RECURRING_LINE_DATASET, Map.of("templateId", template, "lineNo", 2,
            "accountCode", "1300", "credit", "1000.00"));
        Map<String, Object> accrual = new LinkedHashMap<>(entry("2026-01-10", "Accrue consulting",
            List.of(line("6400", "700.00", null, null), line("2100", null, "700.00", null))));
        accrual.put("autoReverseDate", "2026-01-25");
        String accrualNo = post(accrual);
        String manual = (String) ok(JournalProcesses.SAVE, accountant, entry("2026-01-28", "Customer fee",
            List.of(line("1200", "500.00", null, null), line("4000", null, "500.00", null)))).get("journalId");
        ok(JournalProcesses.GRANT_CONTROL_EXCEPTION, controller, Map.of("journalId", manual,
            "reason", "Fee billed outside receivables"));
        assertThat(ok(JournalProcesses.SUBMIT, accountant, Map.of("journalId", manual)))
            .containsEntry("status", "POSTED");

        // The checks record each result, its time and its evidence (FIN-PC-004 acceptance 2).
        Map<String, Object> checked = ok(CloseProcesses.CHECK, accountant, Map.of("periodKey", "2026-01"));
        assertThat(checked).containsEntry("ready", false);
        tasks = tasks();
        assertThat(tasks.get(CloseChecks.ENTRIES_POSTED)).containsEntry("status", "FAILED")
            .containsEntry("evidence", "finance.close.exceptions?periodKey=2026-01&checkCode=ENTRIES_POSTED")
            .containsEntry("checkedAt", "2026-01-31T09:00:00Z");
        assertThat((String) tasks.get(CloseChecks.ENTRIES_POSTED).get("result"))
            .contains("1 exception", "Draft", "Accrue utilities");
        assertThat((String) tasks.get(CloseChecks.CLEARING_ZERO).get("result")).contains("2900", "Suspense");
        assertThat(tasks.get(CloseChecks.CLEARING_ZERO)).containsEntry("status", "FAILED");
        assertThat((String) tasks.get(CloseChecks.RECURRING_RUN).get("result")).contains("PREPAID-INS");
        assertThat((String) tasks.get(CloseChecks.AUTO_REVERSALS).get("result")).contains("Accrue consulting");
        assertThat(tasks.get(CloseChecks.SUBLEDGERS)).containsEntry("status", "FAILED");
        assertThat((String) tasks.get(CloseChecks.SUBLEDGERS).get("result"))
            .contains("Receivables aging 0.00 against the control accounts 500.00, a difference of -500.00");
        assertThat((String) tasks.get(CloseChecks.SUBLEDGERS).get("evidence"))
            .contains("finance.ar.aging?agingDate=2026-01-31");
        for (String passing : List.of(CloseChecks.BANK_RECONCILED, CloseChecks.DEPRECIATION_RUN,
            CloseChecks.REVALUATION_RUN)) {
            assertThat(tasks.get(passing)).as(passing).containsEntry("status", "PASSED")
                .containsEntry("checkedAt", "2026-01-31T09:00:00Z");
        }
        // The evidence is the exceptions report.
        assertThat(report(CloseChecks.EXCEPTIONS_TEMPLATE, accountant, Map.of("periodKey", "2026-01")))
            .extracting(r -> r.get("checkCode") + " " + r.get("reference"))
            .containsExactlyInAnyOrder("ENTRIES_POSTED Draft", "CLEARING_ZERO 2900", "RECURRING_RUN PREPAID-INS",
                "AUTO_REVERSALS " + accrualNo);

        // The close is refused, naming every required item that fails or is not done; TAX_RETURN is not required.
        assertThat(failures()).containsExactlyInAnyOrder(CloseChecks.ENTRIES_POSTED, CloseChecks.SUBLEDGERS,
            CloseChecks.RECURRING_RUN, CloseChecks.AUTO_REVERSALS, CloseChecks.CLEARING_ZERO, "ACCRUALS", "REVIEW");

        // Put right: the draft deleted, the suspense cleared, the recurring entry made, the accrual reversed, the
        // receivables line reversed.
        ok(JournalProcesses.DELETE, accountant, Map.of("journalId", draft));
        post(entry("2026-01-30", "Bank charge identified", List.of(line("2900", "250.00", null, null),
            line("6800", null, "250.00", null))));
        ok(JournalAutomation.RECURRING_RUN, accountant, Map.of("date", "2026-01-31"));
        ok(JournalAutomation.AUTO_REVERSE_RUN, accountant, Map.of("date", "2026-01-31"));
        String undo = (String) ok(JournalProcesses.SAVE, accountant, entry("2026-01-30", "Customer fee billed "
            + "through receivables instead", List.of(line("4000", "500.00", null, null), line("1200", null, "500.00",
                null)))).get("journalId");
        ok(JournalProcesses.GRANT_CONTROL_EXCEPTION, controller, Map.of("journalId", undo,
            "reason", "Taking back the fee posted outside receivables"));
        assertThat(ok(JournalProcesses.SUBMIT, accountant, Map.of("journalId", undo)))
            .containsEntry("status", "POSTED");

        // The manual tasks: only a holder of the task's permission does it, once, with its evidence.
        String evidence = upload(accountant, CloseEntities.EVIDENCE_FILES, FileSamples.pdf(), "accruals.pdf",
            "application/pdf");
        assertThat(refused(CloseProcesses.TASK_COMPLETE, accountant, Map.of("taskId",
            tasks.get("REVIEW").get("taskId")), 422)).isEqualTo(CloseProcesses.NOT_OWNER);
        run(CloseProcesses.TASK_COMPLETE, inRoles("clerk", FinanceRoles.RECEIVABLES_CLERK), Map.of("taskId",
            tasks.get("ACCRUALS").get("taskId"))).expectStatus().isForbidden();
        assertThat(refused(CloseProcesses.TASK_COMPLETE, accountant, Map.of("taskId",
            tasks.get(CloseChecks.SUBLEDGERS).get("taskId")), 422)).isEqualTo(CloseProcesses.TASK_NOT_MANUAL);
        assertThat(ok(CloseProcesses.TASK_COMPLETE, accountant, Map.of("taskId", tasks.get("ACCRUALS").get("taskId"),
            "note", "Accruals agreed to the schedule", "evidenceFileId", evidence)))
            .containsEntry("status", "DONE").containsEntry("completedBy", "accountant");
        assertThat(refused(CloseProcesses.TASK_COMPLETE, accountant, Map.of("taskId",
            tasks.get("ACCRUALS").get("taskId")), 422)).isEqualTo(CloseProcesses.TASK_DONE);
        assertThat(find(TaskEntities.TASK_DATASET, "sourceKey", "fin.close:2026-01:ACCRUALS")).singleElement()
            .satisfies(t -> assertThat(t).containsEntry("status", "DONE"));
        assertThat(read(CloseEntities.TASK_DATASET, tasks.get("ACCRUALS").get("taskId")))
            .containsEntry("note", "Accruals agreed to the schedule").containsEntry("evidenceFileId", evidence);

        // Only the review is left; the period is soft-closed first, never closed by its state.
        assertThat(failures()).containsExactly("REVIEW");
        assertThat(refused(PeriodProcesses.SET_STATE, controller, Map.of("periodKey", "2026-01", "status",
            "CLOSED"), 422)).isEqualTo(PeriodProcesses.CLOSE_REQUIRED);
        ok(PeriodProcesses.SET_STATE, controller, Map.of("periodKey", "2026-01", "status", "SOFT_CLOSED"));
        ok(CloseProcesses.TASK_COMPLETE, controller, Map.of("taskId", tasks.get("REVIEW").get("taskId")));

        // The close: every ledger of the period closed, the trial balance issued, the artifact kept.
        clock.advance(Duration.ofHours(1));
        people();
        run(CloseProcesses.CLOSE, accountant, Map.of("periodKey", "2026-01")).expectStatus().isForbidden();
        Map<String, Object> closed = ok(CloseProcesses.CLOSE, controller, Map.of("periodKey", "2026-01"));
        assertThat(closed).containsEntry("status", "CLOSED").containsEntry("seq", 1);
        assertThat((List<Map<String, Object>>) closed.get("checklist")).hasSize(11)
            .allSatisfy(t -> assertThat(t.get("status")).isIn("PASSED", "DONE", "OPEN"));
        assertThat(find(GlEntities.PERIOD_DATASET, "periodKey", "2026-01").getFirst())
            .containsEntry("status", "CLOSED").containsEntry("arStatus", "CLOSED").containsEntry("apStatus", "CLOSED")
            .containsEntry("bankStatus", "CLOSED").containsEntry("faStatus", "CLOSED");
        Map<String, Object> artifact = read(CloseEntities.ARTIFACT_DATASET, closed.get("artifactId"));
        assertThat(artifact).containsEntry("periodKey", "2026-01").containsEntry("closedBy", "controller")
            .containsEntry("closedAt", "2026-01-31T10:00:00Z").containsEntry("knownAt", "2026-01-31T10:00:00Z")
            .containsEntry("supersedesId", null).containsEntry("reportRunId", closed.get("reportRunId"))
            .containsEntry("trialBalanceHash", closed.get("trialBalanceHash"));
        // What is left of January: the recurring entry; the rest was put right within the month.
        assertThat(amount(artifact.get("totalDebit"))).isEqualByComparingTo("1000.00");
        assertThat(amount(artifact.get("totalCredit"))).isEqualByComparingTo("1000.00");
        List<Map<String, Object>> lines = find(CloseEntities.ARTIFACT_LINE_DATASET, "artifactId",
            closed.get("artifactId"));
        Map<String, Long> sections = lines.stream().collect(Collectors.groupingBy(l -> (String) l.get("section"),
            Collectors.counting()));
        assertThat(sections).containsEntry("SUBLEDGER", 4L).containsEntry("CHECKLIST", 11L);
        assertThat(lines).filteredOn(l -> "CHECKLIST".equals(l.get("section")) && "ACCRUALS".equals(l.get("code")))
            .singleElement().satisfies(l -> assertThat((String) l.get("result")).startsWith("Done by accountant"));
        assertThat(lines).filteredOn(l -> "TRIAL_BALANCE".equals(l.get("section")))
            .extracting(l -> l.get("code") + " " + amount(l.get("debit")) + " " + amount(l.get("credit")))
            .containsExactly("1300 0.00 1000.00", "6600 1000.00 0.00");

        // The artifact's rows give its content hash again.
        List<List<Object>> hashed = lines.stream()
            .sorted(java.util.Comparator.comparing(l -> amount(l.get("seq"))))
            .map(l -> java.util.Arrays.<Object>asList(l.get("section"), amount(l.get("seq")), l.get("code"),
                l.get("name"), amount(l.get("debit")), amount(l.get("credit")), amount(l.get("amount")),
                amount(l.get("ledgerAmount")), l.get("status"), l.get("result")))
            .toList();
        assertThat(CloseChecks.contentHash(java.util.Arrays.asList(artifact.get("periodKey"),
            amount(artifact.get("seq")), artifact.get("periodEnd"), artifact.get("closedBy"), artifact.get("closedAt"),
            artifact.get("knownAt"), amount(artifact.get("totalDebit")), amount(artifact.get("totalCredit")),
            artifact.get("trialBalanceHash"), artifact.get("reportRunId")), hashed))
            .isEqualTo(artifact.get("contentHash"));

        // FIN-PC-005 acceptance 2: run again as known at the close, the trial balance is the artifact's, also after
        // February's postings.
        clock.advance(Duration.ofDays(5));
        people();
        post(entry("2026-02-05", "February consulting", List.of(line("6400", "300.00", null, null),
            line("2100", null, "300.00", null))));
        List<Map<String, Object>> asClosed = report(CloseProcesses.TRIAL_BALANCE, controller,
            Map.of("through", "2026-01-31", "adjustments", false, "knownAt", artifact.get("knownAt")));
        assertThat(CloseProcesses.trialBalanceHash(asClosed)).isEqualTo(artifact.get("trialBalanceHash"));
        String issued = new String(get("/api/reports/runs/" + closed.get("reportRunId") + "/export?format=csv",
            as("auditor", "report.archive.read", "ledger.read")).expectStatus().isOk().expectBody(byte[].class)
            .returnResult().getResponseBody(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(issued).contains("6600", "1000.00");

        // A closed period takes nothing more and changes only through a reopening.
        String late = (String) ok(JournalProcesses.SAVE, accountant, entry("2026-01-20", "Late January invoice",
            List.of(line("6300", "80.00", null, null), line("2100", null, "80.00", null)))).get("journalId");
        assertThat(refused(JournalProcesses.SUBMIT, accountant, Map.of("journalId", late), 422))
            .isEqualTo(PeriodPolicy.PERIOD_CLOSED);
        assertThat(refused(PeriodProcesses.SET_STATE, controller, Map.of("periodKey", "2026-01", "status", "OPEN"),
            422)).isEqualTo(PeriodProcesses.REOPEN_REQUIRED);
        assertThat(refused(PeriodProcesses.SET_SUBLEDGER_STATE, controller, Map.of("periodKey", "2026-01",
            "subledger", "AR", "status", "OPEN"), 422)).isEqualTo(PeriodProcesses.REOPEN_REQUIRED);
        for (String process : List.of(CloseProcesses.START, CloseProcesses.CHECK, CloseProcesses.CLOSE)) {
            assertThat(refused(process, controller, Map.of("periodKey", "2026-01"), 422)).as(process)
                .isEqualTo(PeriodPolicy.PERIOD_CLOSED);
        }
        assertThat(refused(CloseProcesses.TASK_COMPLETE, controller, Map.of("taskId",
            "00000000-0000-0000-0000-000000000000"), 422)).isEqualTo(CloseProcesses.TASK_NOT_FOUND);
        run(CloseProcesses.TASK_COMPLETE, controller, Map.of("taskId", "not-a-task")).expectStatus().isBadRequest();
        assertThat(refused(CloseProcesses.START, controller, Map.of("periodKey", "2026-13"), 422))
            .isEqualTo(CloseProcesses.NOT_REGULAR);
        // The adjustment period closes with the year; February has not started.
        assertThat(refused(CloseProcesses.CLOSE, controller, Map.of("periodKey", "2026-13"), 422))
            .isEqualTo(CloseProcesses.NOT_REGULAR);
        assertThat(refused(CloseProcesses.CLOSE, controller, Map.of("periodKey", "2026-02"), 422))
            .isEqualTo(CloseProcesses.NOT_STARTED);

        // Left out of December's balances, period 13 carries into the next year's.
        ok(PeriodProcesses.FISCAL_YEAR_CREATE, controller(), Map.of("fiscalYear", 2027));
        Map<String, Object> audit = new LinkedHashMap<>(entry("2026-12-31", "Audit adjustment",
            List.of(line("6400", "40.00", null, null), line("2100", null, "40.00", null))));
        audit.put("adjustmentPeriod", true);
        audit.put("adjusting", true);
        post(audit);
        Function<String, java.math.BigDecimal> accrued = through -> report(CloseProcesses.TRIAL_BALANCE, controller,
            Map.of("through", through, "adjustments", false)).stream().filter(r -> "2100".equals(r.get("accountCode")))
            .map(r -> amount(r.get("balance"))).findFirst().orElseThrow();
        assertThat(accrued.apply("2027-01-31").subtract(accrued.apply("2026-12-31")))
            .isEqualByComparingTo("-40.00");

        assertOnlyInserted("fi_close_template_version", "fi_close_task_version", "fi_close_artifact_version",
            "fi_close_artifact_line_version", "fi_period_version");
    }

    /** Signed in now: tokens expire as the clock moves on. */
    private void people() {
        controller = inRoles("controller", FinanceRoles.CONTROLLER);
        accountant = inRoles("accountant", FinanceRoles.ACCOUNTANT);
    }

    /** The task codes the close names as failing or not done. */
    @SuppressWarnings("unchecked")
    private List<String> failures() {
        var exchange = run(CloseProcesses.CLOSE, controller, Map.of("periodKey", "2026-01")).expectBody(MAP)
            .returnResult();
        assertThat(exchange.getStatus().value()).as(String.valueOf(exchange.getResponseBody())).isEqualTo(422);
        return ((List<Map<String, Object>>) exchange.getResponseBody().get("violations")).stream()
            .peek(v -> assertThat(v).containsEntry("ruleCode", CloseProcesses.CHECKS_FAILED))
            // The message names the task first: "BANK_RECONCILED (Bank accounts reconciled …) failed: …".
            .map(v -> ((String) v.get("message")).split(" ")[0]).toList();
    }

    private Map<String, Map<String, Object>> tasks() {
        return find(CloseEntities.TASK_DATASET, "periodKey", "2026-01").stream()
            .collect(Collectors.toMap(t -> (String) t.get("taskCode"), Function.identity()));
    }

    /** Posts an entry small enough to need no approval; its number. */
    private String post(Map<String, Object> entry) {
        String id = (String) ok(JournalProcesses.SAVE, accountant, entry).get("journalId");
        Map<String, Object> posted = ok(JournalProcesses.SUBMIT, accountant, Map.of("journalId", id));
        assertThat(posted).containsEntry("status", "POSTED");
        return (String) posted.get("journalNo");
    }

    private Map<String, Object> commit(String dataset, Map<String, Object> attributes) {
        return post("/api/datasets/" + dataset + "/commit", as("accountant", "fin.recurring.maintain",
            "fin.journal.read"), Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", attributes))))
            .expectStatus().isOk().expectBody(LIST).returnResult().getResponseBody().getFirst();
    }

    private static Map<String, Object> item(String code, String kind, String check, String owner) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("taskCode", code);
        item.put("name", "Item " + code);
        item.put("kind", kind);
        item.put("checkCode", check);
        item.put("ownerPermission", owner);
        item.put("dueDays", 10);
        item.put("required", false);
        item.put("sortOrder", 110);
        return item;
    }
}
