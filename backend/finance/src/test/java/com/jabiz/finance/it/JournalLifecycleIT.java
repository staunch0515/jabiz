package com.jabiz.finance.it;

import com.jabiz.finance.gl.AccountProcesses;
import com.jabiz.finance.gl.JournalEntities;
import com.jabiz.finance.gl.JournalProcesses;
import com.jabiz.finance.gl.JournalValidator;
import com.jabiz.finance.gl.PeriodProcesses;
import com.jabiz.runtime.event.OutboxDeliverer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The life of a journal entry (FIN-GL-010 … 015, FIN-CT-001, 003): drafts, submission with every check at once, the
 * approval rule of manual entries above 10,000.00 with the preparer barred from approving, approvals bound to the
 * content, posting with its numbers, immutability once posted, and reversal. The approval rule is created as finance
 * setup proposes it and another person publishes it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class JournalLifecycleIT extends FinanceItSupport {

    @Autowired
    OutboxDeliverer deliverer;

    private static boolean booksOpen;

    @BeforeEach
    void books() {
        if (!booksOpen) {
            openBooks();
            booksOpen = true;
        }
    }

    private String accountant() {
        return as("accountant", "fin.journal.prepare", "fin.journal.read", "approval.decide",
            "fin.journal.approve");
    }

    private String controller(String id) {
        return as(id, "fin.journal.approve", "approval.decide", "fin.journal.control-exception", "fin.journal.read",
            "fin.journal.prepare", "fin.period.close");
    }

    static Map<String, Object> line(String account, String debit, String credit, String memo) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("accountCode", account);
        line.put("debit", debit);
        line.put("credit", credit);
        line.put("memo", memo);
        return line;
    }

    static Map<String, Object> entry(String date, String description, List<Map<String, Object>> lines) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("postingDate", date);
        entry.put("description", description);
        entry.put("lines", lines);
        return entry;
    }

    private String draft(String authorization, Map<String, Object> entry) {
        return (String) ok(JournalProcesses.SAVE, authorization, entry).get("journalId");
    }

    private Map<String, Object> submit(String authorization, String journalId) {
        return ok(JournalProcesses.SUBMIT, authorization, Map.of("journalId", journalId));
    }

    private Map<String, Object> journal(String journalId) {
        return read(JournalEntities.JOURNAL_DATASET, journalId);
    }

    private Map<String, Object> decide(String authorization, Object requestId, String decision, String reason) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("requestId", requestId);
        input.put("decision", decision);
        input.put("reason", reason);
        return ok("APPROVAL_DECIDE", authorization, input);
    }

    private void deliver() {
        deliverer.deliverPending().block();
    }

    /** FIN-GL-015 acceptance 2: no rule stops a small entry; the evaluation and its rule version are recorded. */
    @Test
    void aSmallEntryPostsAtOnceAndTheEvaluationIsRecorded() {
        String id = draft(accountant(), entry("2026-01-31", "Office supplies", List.of(
            line("6500", "1000.00", null, "licenses"), line("2100", null, "1000.00", null))));
        assertThat(journal(id)).containsEntry("status", "DRAFT").containsEntry("journalNo", null);

        Map<String, Object> submitted = submit(accountant(), id);
        assertThat(submitted).containsEntry("status", "POSTED").containsEntry("approval", "NOT_REQUIRED");
        assertThat((String) submitted.get("journalNo")).matches("JE-\\d{4}");
        assertThat((String) submitted.get("glNo")).matches("GJ-MAN-2026-\\d{6}");
        assertThat(journal(id)).containsEntry("status", "POSTED").containsEntry("periodKey", "2026-01")
            .containsEntry("transactionId", submitted.get("transactionId"));
        assertThat(find(JournalEntities.POSTING_DATASET, "glNo", submitted.get("glNo"))).singleElement()
            .satisfies(p -> assertThat(p).containsEntry("periodKey", "2026-01").containsEntry("source", "MAN")
                .containsEntry("documentNo", submitted.get("journalNo")).containsEntry("sourceId", id));
        // The ledger transaction names the entry as its source, booked at the start of the day in Chicago.
        assertThat(query("SELECT booking_time::text AS t, source_entity, source_id, reference FROM "
            + "ledger_transaction_version WHERE transaction_id = ?::uuid", submitted.get("transactionId")))
            .singleElement().satisfies(t -> assertThat(t).containsEntry("source_entity", "FinJournal")
                .containsEntry("source_id", id).containsEntry("reference", submitted.get("journalNo"))
                .containsEntry("t", "2026-01-31 06:00:00+00"));
        assertThat(query("SELECT outcome, matched_rule, rule_versions FROM sys_approval_evaluation "
            + "WHERE entity_id = ?", id)).singleElement().satisfies(e -> {
                assertThat(e).containsEntry("outcome", "NOT_REQUIRED").containsEntry("matched_rule", null);
                assertThat((String) e.get("rule_versions")).contains(":1");
            });
    }

    /** FIN-GL-015 acceptance 1 and 3, FIN-CT-001 acceptance 1. */
    @Test
    void aLargeEntryWaitsForAnotherApprover() {
        String id = draft(accountant(), entry("2026-01-31", "Accrue annual audit fee", List.of(
            line("6400", "25000.00", null, null), line("2100", null, "25000.00", null))));
        Map<String, Object> submitted = submit(accountant(), id);
        assertThat(submitted).containsEntry("status", "SUBMITTED").containsEntry("approval", "PENDING");
        Object request = submitted.get("approvalRequestId");

        // The preparer holds approval permissions too, and is still refused.
        assertThat(refused("APPROVAL_DECIDE", accountant(), Map.of("requestId", request, "decision", "APPROVE"), 422))
            .isEqualTo("APPROVAL_OWN_REQUEST");
        assertThat(journal(id)).containsEntry("status", "SUBMITTED");
        // A submitted entry is locked against a second submission.
        assertThat(refused(JournalProcesses.SUBMIT, accountant(), Map.of("journalId", id), 422))
            .isEqualTo(JournalProcesses.NOT_SUBMITTABLE);

        decide(controller("controller"), request, "APPROVE", null);
        assertThat(journal(id)).containsEntry("status", "SUBMITTED");
        deliver();
        assertThat(journal(id)).containsEntry("status", "POSTED");
        assertThat((String) journal(id).get("glNo")).startsWith("GJ-MAN-2026-");
        assertThat(query("SELECT outcome, matched_rule FROM sys_approval_evaluation WHERE entity_id = ?", id))
            .singleElement().satisfies(e -> assertThat((String) e.get("matched_rule")).endsWith(":1"));
    }

    /** FIN-GL-014, FIN-CT-003: a change after approval needs approval again. */
    @Test
    void aChangeAfterApprovalNeedsApprovalAgain() {
        Map<String, Object> entry = entry("2026-01-31", "Accrue annual audit fee", List.of(
            line("6400", "25000.00", null, null), line("2100", null, "25000.00", null)));
        String id = draft(accountant(), entry);
        Object first = submit(accountant(), id).get("approvalRequestId");
        decide(controller("controller"), first, "APPROVE", null);

        // The memo changes before the approval reaches the entry: it is a draft again.
        Map<String, Object> changed = new LinkedHashMap<>(entry);
        changed.put("journalId", id);
        changed.put("lines", List.of(line("6400", "25000.00", null, "audit fee 2025"),
            line("2100", null, "25000.00", null)));
        assertThat(ok(JournalProcesses.SAVE, accountant(), changed)).containsEntry("status", "DRAFT");
        deliver();
        assertThat(journal(id)).containsEntry("status", "DRAFT").containsEntry("glNo", null);

        Map<String, Object> again = submit(accountant(), id);
        assertThat(again).containsEntry("status", "SUBMITTED").containsEntry("journalNo", journal(id).get("journalNo"));
        assertThat(again.get("approvalRequestId")).isNotEqualTo(first);
        decide(controller("controller"), again.get("approvalRequestId"), "APPROVE", null);
        deliver();
        assertThat(journal(id)).containsEntry("status", "POSTED");

        // A pending request goes when the entry changes (its task with it).
        String other = draft(accountant(), entry);
        Object pending = submit(accountant(), other).get("approvalRequestId");
        Map<String, Object> edit = new LinkedHashMap<>(entry);
        edit.put("journalId", other);
        ok(JournalProcesses.SAVE, accountant(), edit);
        assertThat(query("SELECT status FROM sys_approval_request_version WHERE request_id = ?::uuid "
            + "ORDER BY version_no DESC LIMIT 1", pending)).singleElement()
            .satisfies(r -> assertThat(r).containsEntry("status", "WITHDRAWN"));
    }

    @Test
    void aRejectedEntryIsChangedAndSubmittedAgain() {
        String id = draft(accountant(), entry("2026-01-31", "Bonus accrual", List.of(
            line("6100", "12000.00", null, null), line("2100", null, "12000.00", null))));
        Object request = submit(accountant(), id).get("approvalRequestId");
        assertThat(refused("APPROVAL_DECIDE", controller("controller"), Map.of("requestId", request,
            "decision", "REJECT"), 422)).isEqualTo("REASON_REQUIRED");
        decide(controller("controller"), request, "REJECT", "Amount not supported");
        deliver();
        assertThat(journal(id)).containsEntry("status", "REJECTED");
        assertThat(submit(accountant(), id)).containsEntry("status", "SUBMITTED");
    }

    /** FIN-GL-011, 003, 004, 005, 006: every problem at once, with the difference. */
    @Test
    void submissionChecksEverythingAtOnce() {
        String id = draft(accountant(), entry("2026-01-31", "Everything wrong", List.of(
            line("1200", "100.00", null, null), line("9999", null, "50.00", null),
            line("6000", null, "49.99", null))));
        Map<String, Object> problem = run(JournalProcesses.SUBMIT, accountant(), Map.of("journalId", id))
            .expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody();
        assertThat(problem.toString()).contains(JournalValidator.CONTROL_ACCOUNT, JournalValidator.ACCOUNT_UNKNOWN,
            JournalValidator.UNBALANCED, "differ by 0.01");
        assertThat(journal(id)).containsEntry("status", "DRAFT").containsEntry("journalNo", null);

        // A draft line needs one positive amount in cents.
        assertThat(refused(JournalProcesses.SAVE, accountant(), entry("2026-01-31", "Bad line", List.of(
            line("6400", "1.001", null, null), line("2100", "1", "1", null))), 422))
            .isEqualTo(JournalValidator.LINE_AMOUNT);
        // No period, no posting.
        String outside = draft(accountant(), entry("2027-03-01", "Next year", List.of(
            line("6400", "1.00", null, null), line("2100", null, "1.00", null))));
        assertThat(refused(JournalProcesses.SUBMIT, accountant(), Map.of("journalId", outside), 422))
            .isEqualTo(JournalProcesses.NO_PERIOD);
    }

    /** FIN-GL-005 and design Q1: the controller grants a logged exception for this content only. */
    @Test
    void aControlAccountTakesAManualLineOnlyWithTheControllersException() {
        Map<String, Object> entry = entry("2026-01-15", "Payout of 2025 bonus", List.of(
            line("2100", "15000.00", null, null), line("1010", null, "15000.00", null)));
        String id = draft(accountant(), entry);
        assertThat(refused(JournalProcesses.SUBMIT, accountant(), Map.of("journalId", id), 422))
            .isEqualTo(JournalValidator.CONTROL_ACCOUNT);
        // The preparer does not grant it, and an accountant has no right to.
        run(JournalProcesses.GRANT_CONTROL_EXCEPTION, accountant(), Map.of("journalId", id, "reason", "x"))
            .expectStatus().isForbidden();

        ok(JournalProcesses.GRANT_CONTROL_EXCEPTION, controller("controller"),
            Map.of("journalId", id, "reason", "Bonus payout from the operating account"));
        assertThat(journal(id)).containsEntry("exceptionBy", "controller")
            .containsEntry("exceptionReason", "Bonus payout from the operating account");
        // Changing the lines voids it.
        Map<String, Object> changed = new LinkedHashMap<>(entry);
        changed.put("journalId", id);
        changed.put("lines", List.of(line("2100", "15000.00", null, null), line("1200", null, "15000.00", null)));
        ok(JournalProcesses.SAVE, accountant(), changed);
        assertThat(journal(id)).containsEntry("exceptionBy", null);
        assertThat(refused(JournalProcesses.SUBMIT, accountant(), Map.of("journalId", id), 422))
            .isEqualTo(JournalValidator.CONTROL_ACCOUNT);

        changed.put("lines", entry.get("lines"));
        ok(JournalProcesses.SAVE, accountant(), changed);
        ok(JournalProcesses.GRANT_CONTROL_EXCEPTION, controller("controller"),
            Map.of("journalId", id, "reason", "Bonus payout from the operating account"));
        assertThat(submit(accountant(), id)).containsEntry("status", "SUBMITTED");
        // A controller who prepared an entry does not grant its exception.
        String own = draft(controller("controller"), entry);
        assertThat(refused(JournalProcesses.GRANT_CONTROL_EXCEPTION, controller("controller"),
            Map.of("journalId", own, "reason", "mine"), 422)).isEqualTo(JournalProcesses.OWN_EXCEPTION);
    }

    /** FIN-GL-012 acceptance 1, FIN-GL-014: posted entries never change; drafts belong to their preparer. */
    @Test
    void postedEntriesNeverChangeAndDraftsBelongToTheirPreparer() {
        Map<String, Object> entry = entry("2026-01-31", "Small accrual", List.of(
            line("6300", "300.00", null, null), line("2100", null, "300.00", null)));
        String id = draft(accountant(), entry);
        assertThat(refused(JournalProcesses.SAVE, controller("controller"), withId(entry, id), 422))
            .isEqualTo(JournalProcesses.NOT_PREPARER);
        submit(accountant(), id);

        assertThat(refused(JournalProcesses.SAVE, accountant(), withId(entry, id), 422))
            .isEqualTo(JournalProcesses.IS_POSTED);
        assertThat(refused(JournalProcesses.DELETE, accountant(), Map.of("journalId", id), 422))
            .isEqualTo(JournalProcesses.IS_POSTED);
        // Not through the generic API either, not even for an administrator.
        Map<String, Object> stored = journal(id);
        assertThat(commitRefused(JournalEntities.JOURNAL_DATASET, as("admin", "*"), Map.of("action", "UPDATE",
            "id", id, "version", 3, "attributes", Map.of("description", "changed"))))
            .isEqualTo("PROCESS_ONLY_DATASET");
        assertThat(journal(id)).isEqualTo(stored);

        // A draft without a number goes; one that was numbered stays.
        String unnumbered = draft(accountant(), entry);
        ok(JournalProcesses.DELETE, accountant(), Map.of("journalId", unnumbered));
        assertThat(read(JournalEntities.JOURNAL_DATASET, unnumbered)).isEmpty();
        String numbered = draft(accountant(), entry("2026-01-31", "Big", List.of(
            line("6400", "20000.00", null, null), line("2100", null, "20000.00", null))));
        submit(accountant(), numbered);
        ok(JournalProcesses.SAVE, accountant(), withId(entry, numbered));
        assertThat(refused(JournalProcesses.DELETE, accountant(), Map.of("journalId", numbered), 422))
            .isEqualTo(JournalProcesses.NUMBERED);
    }

    /** FIN-GL-012 acceptance 2. */
    @Test
    void aReversalHasOppositeAmountsAndRefersToTheOriginal() {
        String id = draft(accountant(), entry("2026-01-20", "Utilities accrual", List.of(
            line("6300", "450.00", null, "January"), line("2100", null, "450.00", null))));
        Map<String, Object> original = submit(accountant(), id);

        Map<String, Object> reversal = ok(JournalProcesses.REVERSE, accountant(),
            Map.of("journalId", id, "postingDate", "2026-01-25"));
        assertThat(reversal).containsEntry("status", "POSTED");
        String reversalId = (String) reversal.get("journalId");
        assertThat(journal(reversalId)).containsEntry("reversesJournalId", id).containsEntry("source", "REVERSING")
            .containsEntry("description", "Reversal of " + original.get("journalNo"));
        List<Map<String, Object>> lines = find(JournalEntities.LINE_DATASET, "journalId", reversalId);
        assertThat(lines).anySatisfy(l -> assertThat(l).containsEntry("accountCode", "6300")
            .containsEntry("credit", 450.00).containsEntry("debit", null));
        // Both stay visible; a second reversal is refused.
        assertThat(journal(id)).containsEntry("status", "POSTED");
        assertThat(refused(JournalProcesses.REVERSE, accountant(), Map.of("journalId", id,
            "postingDate", "2026-01-26"), 422)).isEqualTo(JournalProcesses.ALREADY_REVERSED);
    }

    /** FIN-PC-003 acceptance 1 and 2. */
    @Test
    void closedAndSoftClosedPeriodsRefuseEntries() {
        ok(PeriodProcesses.FISCAL_YEAR_CREATE, controller(), Map.of("fiscalYear", 2025));
        closePeriod("2025-11");
        ok(PeriodProcesses.SET_STATE, controller(), Map.of("periodKey", "2025-12", "status", "SOFT_CLOSED"));

        String closed = draft(accountant(), entry("2025-11-20", "Late", List.of(
            line("6300", "10.00", null, null), line("2100", null, "10.00", null))));
        Map<String, Object> problem = run(JournalProcesses.SUBMIT, accountant(), Map.of("journalId", closed))
            .expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody();
        assertThat(problem.toString()).contains("FIN_PERIOD_CLOSED", "Period closed");

        Map<String, Object> adjusting = entry("2025-12-31", "Audit adjustment", List.of(
            line("6400", "10.00", null, null), line("2100", null, "10.00", null)));
        adjusting.put("adjusting", true);
        String byAccountant = draft(accountant(), adjusting);
        assertThat(refused(JournalProcesses.SUBMIT, accountant(), Map.of("journalId", byAccountant), 422))
            .isEqualTo("FIN_PERIOD_SOFT_CLOSED");
        String byController = draft(controller("controller-3"), adjusting);
        assertThat(submit(controller("controller-3"), byController)).containsEntry("status", "POSTED");
        String notAdjusting = draft(controller("controller-3"), entry("2025-12-31", "Ordinary", List.of(
            line("6400", "10.00", null, null), line("2100", null, "10.00", null))));
        assertThat(refused(JournalProcesses.SUBMIT, controller("controller-3"), Map.of("journalId", notAdjusting),
            422)).isEqualTo("FIN_PERIOD_SOFT_CLOSED");

        // Period 13 only for entries that ask for it.
        Map<String, Object> thirteen = entry("2026-12-31", "Audit adjustment 13", List.of(
            line("6400", "5.00", null, null), line("2100", null, "5.00", null)));
        thirteen.put("adjusting", true);
        thirteen.put("adjustmentPeriod", true);
        String adjustment = draft(accountant(), thirteen);
        submit(accountant(), adjustment);
        assertThat(journal(adjustment)).containsEntry("periodKey", "2026-13");
    }

    /** FIN-GL-013: numbers without gaps or duplicates, also when entries are submitted at once. */
    @Test
    void numbersHaveNoGapsEvenUnderConcurrency() throws Exception {
        // A refused submission takes no number.
        String refused = draft(accountant(), entry("2026-01-31", "Unbalanced", List.of(
            line("6400", "1.00", null, null), line("2100", null, "2.00", null))));
        run(JournalProcesses.SUBMIT, accountant(), Map.of("journalId", refused)).expectStatus().isEqualTo(422);

        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ids.add(draft(accountant(), entry("2026-01-30", "Concurrent " + i, List.of(
                line("6800", "1.00", null, null), line("2100", null, "1.00", null)))));
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            String token = accountant();
            for (String id : ids) {
                results.add(pool.submit(() -> run(JournalProcesses.SUBMIT, token, Map.of("journalId", id))
                    .returnResult(String.class).getStatus().value()));
            }
            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdown();
        }
        assertGapFree("journal_no", "JE-");
        assertGapFree("gl_no", "GJ-MAN-2026-");
        assertOnlyInserted("fi_journal_version", "fi_journal_line_version", "fi_posting_version");
    }

    /** A late decision of an earlier request does not decide the current one. */
    @Test
    void aStaleDecisionDoesNotDecideTheCurrentRequest() {
        Map<String, Object> entry = entry("2026-01-31", "Legal fees accrual", List.of(
            line("6400", "11000.00", null, null), line("2100", null, "11000.00", null)));
        String id = draft(accountant(), entry);
        Object first = submit(accountant(), id).get("approvalRequestId");
        decide(controller("controller"), first, "REJECT", "Not yet");
        // Before the rejection reaches the entry, the preparer saves and submits the same content again.
        ok(JournalProcesses.SAVE, accountant(), withId(entry, id));
        Object second = submit(accountant(), id).get("approvalRequestId");
        assertThat(second).isNotEqualTo(first);
        deliver();
        assertThat(journal(id)).containsEntry("status", "SUBMITTED");

        decide(controller("controller"), second, "APPROVE", null);
        deliver();
        assertThat(journal(id)).containsEntry("status", "POSTED");
    }

    /** An account closed between approval and posting: the entry waits approved and is posted once corrected. */
    @Test
    void anEntryTheLedgerWouldRefuseIsNotPostedOnApproval() {
        String code = "6" + unique();
        ok("FIN_ACCOUNT_CREATE", controller(), Map.of("accountCode", code, "accountName", "Temporary",
            "financialType", "EXPENSE", "normalBalance", "DEBIT", "statementLine", "Operating expenses"));
        Map<String, Object> entry = entry("2026-01-31", "Consulting accrual", List.of(
            line(code, "12000.00", null, null), line("2100", null, "12000.00", null)));
        String id = draft(accountant(), entry);
        Object request = submit(accountant(), id).get("approvalRequestId");
        ok(AccountProcesses.DEACTIVATE, controller(), Map.of("accountCode", code));

        decide(controller("controller"), request, "APPROVE", null);
        deliver();
        assertThat(journal(id)).containsEntry("status", "APPROVED").containsEntry("glNo", null);

        // The preparer moves it to an open account and it goes through approval again.
        Map<String, Object> corrected = withId(entry, id);
        corrected.put("lines", List.of(line("6400", "12000.00", null, null), line("2100", null, "12000.00", null)));
        ok(JournalProcesses.SAVE, accountant(), corrected);
        decide(controller("controller"), submit(accountant(), id).get("approvalRequestId"), "APPROVE", null);
        deliver();
        assertThat(journal(id)).containsEntry("status", "POSTED");
    }

    @Test
    void datesFlagsAndReversalLinesAreChecked() {
        Map<String, Object> early = entry("2026-01-31", "Reversed too early", List.of(
            line("6300", "5.00", null, null), line("2100", null, "5.00", null)));
        early.put("autoReverseDate", "2026-01-31");
        assertThat(refused(JournalProcesses.SAVE, accountant(), early, 422))
            .isEqualTo(JournalProcesses.AUTO_REVERSE_DATE);
        Map<String, Object> thirteen = entry("2026-12-31", "Not adjusting", List.of(
            line("6300", "5.00", null, null), line("2100", null, "5.00", null)));
        thirteen.put("adjustmentPeriod", true);
        assertThat(refused(JournalProcesses.SAVE, accountant(), thirteen, 422))
            .isEqualTo(JournalProcesses.ADJUSTMENT_PERIOD);

        // The reversal of a large entry waits for approval; its lines stay those of the original.
        String id = draft(accountant(), entry("2026-01-20", "Large accrual", List.of(
            line("6400", "30000.00", null, null), line("2100", null, "30000.00", null))));
        decide(controller("controller"), submit(accountant(), id).get("approvalRequestId"), "APPROVE", null);
        deliver();
        Map<String, Object> reversal = ok(JournalProcesses.REVERSE, accountant(),
            Map.of("journalId", id, "postingDate", "2026-01-25"));
        assertThat(reversal).containsEntry("status", "SUBMITTED");
        assertThat(journal(id)).containsEntry("reversedById", reversal.get("journalId"));
        Map<String, Object> changed = entry("2026-01-26", "Reversal, later", List.of(
            line("2100", "30000.00", null, null), line("6400", null, "30000.00", null)));
        changed.put("journalId", reversal.get("journalId"));
        assertThat(ok(JournalProcesses.SAVE, accountant(), changed)).containsEntry("status", "DRAFT");
        changed.put("lines", List.of(line("2100", "1.00", null, null), line("6400", null, "1.00", null)));
        assertThat(refused(JournalProcesses.SAVE, accountant(), changed, 422))
            .isEqualTo(JournalProcesses.REVERSAL_LINES);
    }

    /** The exception is granted to the content before it is submitted. */
    @Test
    void noExceptionForASubmittedEntry() {
        String id = draft(accountant(), entry("2026-01-31", "Big", List.of(
            line("6400", "40000.00", null, null), line("2100", null, "40000.00", null))));
        submit(accountant(), id);
        assertThat(refused(JournalProcesses.GRANT_CONTROL_EXCEPTION, controller("controller"),
            Map.of("journalId", id, "reason", "late"), 422)).isEqualTo(JournalProcesses.NOT_SUBMITTABLE);
    }

    private static void assertGapFree(String column, String prefix) {
        List<Map<String, Object>> rows = query("SELECT DISTINCT " + column + " AS no FROM fi_journal_version WHERE "
            + column + " LIKE ? AND " + column + " NOT LIKE '%-R'", prefix + "%");
        Set<Integer> numbers = new HashSet<>();
        for (Map<String, Object> row : rows) {
            String no = (String) row.get("no");
            numbers.add(Integer.parseInt(no.substring(no.lastIndexOf('-') + 1)));
        }
        assertThat(numbers).hasSize(rows.size());
        assertThat(numbers).containsExactlyInAnyOrderElementsOf(java.util.stream.IntStream.rangeClosed(1,
            numbers.size()).boxed().toList());
    }

    private static Map<String, Object> withId(Map<String, Object> entry, String id) {
        Map<String, Object> copy = new LinkedHashMap<>(entry);
        copy.put("journalId", id);
        return copy;
    }
}
