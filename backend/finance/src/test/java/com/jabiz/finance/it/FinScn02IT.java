package com.jabiz.finance.it;

import com.jabiz.finance.gl.JournalAttachments;
import com.jabiz.finance.gl.JournalAutomation;
import com.jabiz.finance.gl.JournalEntities;
import com.jabiz.finance.gl.JournalProcesses;
import com.jabiz.finance.gl.JournalValidator;
import com.jabiz.finance.setup.FinanceRoles;
import com.jabiz.runtime.event.OutboxDeliverer;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.task.MailMessage;
import com.jabiz.runtime.task.NotificationSender;
import com.jabiz.runtime.test.FileSamples;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.jabiz.finance.it.JournalLifecycleIT.entry;
import static com.jabiz.finance.it.JournalLifecycleIT.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance scenario FIN-SCN-02 (docs/finance-requirements/30-acceptance-scenarios.md), steps 1 to 5, on books of
 * their own: journal entries with maker–checker approval. The general ledger lines of JE-0001 … JE-0004 are compared
 * with FIN-EXP-02, read from the requirements in the test only. Entering JE-0002 in the grid is F1c's; here the
 * grid's process takes it. Submitted, JE-0002 is the controller's task in the application and in an e-mail linking
 * to it (FIN-DI-009).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {"jabiz.mail.enabled=true",
    "jabiz.mail.from=books@northwind.example", "spring.mail.host=localhost",
    "jabiz.mail.base-url=https://books.northwind.example"})
class FinScn02IT extends FinanceItSupport {

    /** Keeps what would go out by e-mail. */
    @TestConfiguration
    static class Mailbox {

        static final List<MailMessage> SENT = new CopyOnWriteArrayList<>();

        @Bean
        @Primary
        NotificationSender keptMail() {
            return new NotificationSender() {
                @Override
                public void send(String to, String subject, String body) {
                    SENT.add(new MailMessage(to, subject, body, List.of()));
                }

                @Override
                public void send(MailMessage message) {
                    SENT.add(message);
                }
            };
        }
    }

    private static final String CONTROLLER_MAIL = "controller@northwind.example";

    /** The mail to {@code to}, once the notifications sent after their commit have gone out. */
    private static List<MailMessage> awaitMail(String to) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            List<MailMessage> mail = Mailbox.SENT.stream().filter(m -> m.to().equals(to)).toList();
            if (!mail.isEmpty()) {
                return mail;
            }
            Thread.sleep(100);
        }
        return List.of();
    }

    @Autowired
    OutboxDeliverer deliverer;

    private String accountant() {
        return inRoles("accountant", FinanceRoles.ACCOUNTANT);
    }

    private String controllerUser() {
        return inRoles("controller", FinanceRoles.CONTROLLER);
    }

    private void deliver() {
        deliverer.deliverPending().block();
    }

    private Map<String, Object> decide(String authorization, Object requestId) {
        return ok("APPROVAL_DECIDE", authorization, Map.of("requestId", requestId, "decision", "APPROVE"));
    }

    private Map<String, Object> journal(Object id) {
        return read(JournalEntities.JOURNAL_DATASET, id);
    }

    @Test
    @SuppressWarnings("unchecked")
    void journalEntriesWithMakerCheckerApproval() throws Exception {
        Mailbox.SENT.clear();
        openBooks();

        // Step 1: JE-0001 with its support; 1010 is a control account, so the controller allows the manual line.
        Map<String, Object> bonus = entry("2026-01-15", "Payout of 2025 bonus accrued at year end", List.of(
            line("2100", "15000.00", null, null), line("1010", null, "15000.00", null)));
        String je1 = (String) ok(JournalProcesses.SAVE, accountant(), bonus).get("journalId");
        String memo = upload(accountant(), JournalEntities.SUPPORT_FILES, FileSamples.pdf(), "bonus-memo.pdf",
            "application/pdf");
        ok(JournalAttachments.ATTACH, accountant(), Map.of("journalId", je1, "fileId", memo,
            "description", "Board approval of the 2025 bonus"));
        ok(JournalProcesses.GRANT_CONTROL_EXCEPTION, controllerUser(),
            Map.of("journalId", je1, "reason", "Bonus paid from the operating account"));
        Map<String, Object> submitted1 = ok(JournalProcesses.SUBMIT, accountant(), Map.of("journalId", je1));
        assertThat(submitted1).containsEntry("journalNo", "JE-0001").containsEntry("approval", "PENDING");
        decide(controllerUser(), submitted1.get("approvalRequestId"));
        deliver();
        assertThat(journal(je1)).containsEntry("status", "POSTED");

        // Step 2: JE-0002; the preparer may not approve; a change after approval needs approval again.
        String admin = as("admin", "*");
        String anna = (String) ok("SEC_USER_CREATE", admin, Map.of("userName", "ctl-anna", "displayName", "Anna",
            "password", "password-123", "email", CONTROLLER_MAIL)).get("userId");
        post("/api/datasets/" + SecurityEntities.USER_ROLE_DATASET + "/commit", admin, Map.of("changes", List.of(
            Map.of("action", "INSERT", "attributes", Map.of("userId", anna, "roleId", find(
                SecurityEntities.ROLE_DATASET, "roleCode", FinanceRoles.CONTROLLER).getFirst().get("roleId"))))))
            .expectStatus().isOk();
        Map<String, Object> audit = entry("2026-01-31", "Accrue annual audit fee", List.of(
            line("6400", "25000.00", null, null), line("2100", null, "25000.00", null)));
        String je2 = (String) ok(JournalProcesses.SAVE, accountant(), audit).get("journalId");
        Map<String, Object> submitted2 = ok(JournalProcesses.SUBMIT, accountant(), Map.of("journalId", je2));
        assertThat(submitted2).containsEntry("journalNo", "JE-0002").containsEntry("approval", "PENDING");
        // FIN-DI-009 acceptance 1: the controller's task in the application, and an e-mail linking to it.
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) get("/api/tasks/mine", controllerUser())
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody().get("tasks");
        assertThat(tasks).filteredOn(t -> submitted2.get("approvalRequestId").equals(t.get("subjectId")))
            .singleElement().satisfies(t -> assertThat(t).containsEntry("link", "/tasks"));
        deliver();
        assertThat(awaitMail(CONTROLLER_MAIL)).singleElement().satisfies(m -> assertThat(m.body())
            .contains("https://books.northwind.example/tasks"));
        Map<String, Object> approve = Map.of("requestId", submitted2.get("approvalRequestId"), "decision", "APPROVE");
        assertThat(refused("APPROVAL_DECIDE", accountant(), approve, 403)).isEqualTo("PERMISSION_DENIED");
        // Also when the accountant holds the approver role besides: nobody approves their own entry.
        assertThat(refused("APPROVAL_DECIDE", inRoles("accountant", FinanceRoles.ACCOUNTANT, FinanceRoles.APPROVER),
            approve, 422)).isEqualTo("APPROVAL_OWN_REQUEST");
        decide(controllerUser(), submitted2.get("approvalRequestId"));
        Map<String, Object> changed = new LinkedHashMap<>(audit);
        changed.put("journalId", je2);
        changed.put("lines", List.of(line("6400", "25000.00", null, "Audit of FY2025"),
            line("2100", null, "25000.00", null)));
        ok(JournalProcesses.SAVE, accountant(), changed);
        deliver();
        assertThat(journal(je2)).containsEntry("status", "DRAFT").containsEntry("approvalRequestId", null);
        Map<String, Object> again = ok(JournalProcesses.SUBMIT, accountant(), Map.of("journalId", je2));
        assertThat(again).containsEntry("journalNo", "JE-0002");
        decide(controllerUser(), again.get("approvalRequestId"));
        deliver();
        assertThat(journal(je2)).containsEntry("status", "POSTED");

        // Step 3: JE-0003 from the recurring template; running it again makes nothing.
        Object template = commit(JournalEntities.RECURRING_DATASET, Map.of("templateCode", "PREPAID-INS",
            "description", "Recurring: amortize prepaid insurance 1/12", "startDate", "2026-01-01",
            "endDate", "2026-12-31", "active", true)).get("id");
        commit(JournalEntities.RECURRING_LINE_DATASET, Map.of("templateId", template, "lineNo", 1,
            "accountCode", "6600", "debit", "1000.00"));
        commit(JournalEntities.RECURRING_LINE_DATASET, Map.of("templateId", template, "lineNo", 2,
            "accountCode", "1300", "credit", "1000.00"));
        ok(JournalAutomation.RECURRING_RUN, accountant(), Map.of("date", "2026-01-31"));
        Map<String, Object> rerun = ok(JournalAutomation.RECURRING_RUN, accountant(), Map.of("date", "2026-01-31"));
        assertThat((List<?>) rerun.get("entries")).isEmpty();
        List<Map<String, Object>> recurring = find(JournalEntities.JOURNAL_DATASET, "source", "RECURRING");
        assertThat(recurring).singleElement().satisfies(j -> assertThat(j).containsEntry("journalNo", "JE-0003")
            .containsEntry("status", "POSTED"));

        // Step 4: JE-0004 below the limit posts without approval; the evaluation names the rule version.
        String je4 = (String) ok(JournalProcesses.SAVE, accountant(), entry("2026-01-31",
            "Estimated federal income tax provision", List.of(line("8000", "1362.90", null, null),
                line("2400", null, "1362.90", null)))).get("journalId");
        assertThat(ok(JournalProcesses.SUBMIT, accountant(), Map.of("journalId", je4)))
            .containsEntry("journalNo", "JE-0004").containsEntry("status", "POSTED")
            .containsEntry("approval", "NOT_REQUIRED");
        String rule = query("SELECT DISTINCT rule_id::text AS id FROM sys_approval_rule_version "
            + "WHERE rule_code = 'FIN-MANUAL-10K'").getFirst().get("id") + ":1";
        assertThat(query("SELECT outcome, rule_versions FROM sys_approval_evaluation WHERE entity_id = ?", je4))
            .singleElement().satisfies(e -> assertThat((String) e.get("rule_versions")).contains(rule));
        // The approvals of JE-0001 and JE-0002 are in the trail with the rule that asked for them.
        assertThat(query("SELECT entity_id, matched_rule FROM sys_approval_evaluation WHERE outcome = 'PENDING' "
            + "AND entity_id IN (?, ?)", je1, je2)).hasSize(3)
            .allSatisfy(e -> assertThat(e).containsEntry("matched_rule", rule));

        // Step 5: a posted amount does not change, and account 1200 takes no manual line.
        Map<String, Object> raise = new LinkedHashMap<>(bonus);
        raise.put("journalId", je1);
        raise.put("lines", List.of(line("2100", "16000.00", null, null), line("1010", null, "16000.00", null)));
        assertThat(refused(JournalProcesses.SAVE, accountant(), raise, 422)).isEqualTo(JournalProcesses.IS_POSTED);
        Map<String, Object> firstLine = find(JournalEntities.LINE_DATASET, "journalId", je1).getFirst();
        assertThat(commitRefused(JournalEntities.LINE_DATASET, as("admin", "*"), Map.of("action", "UPDATE",
            "id", firstLine.get("lineId"), "version", 1, "attributes", Map.of("debit", "16000.00"))))
            .isEqualTo("PROCESS_ONLY_DATASET");
        String direct = (String) ok(JournalProcesses.SAVE, accountant(), entry("2026-01-31", "Direct to AR",
            List.of(line("1200", "100.00", null, null), line("4000", null, "100.00", null)))).get("journalId");
        assertThat(refused(JournalProcesses.SUBMIT, accountant(), Map.of("journalId", direct), 422))
            .isEqualTo(JournalValidator.CONTROL_ACCOUNT);

        // Expected: the general ledger lines of the four entries are those of FIN-EXP-02; numbers without gaps.
        Map<String, Map<String, BigDecimal>> expected = expectedDocuments("JE-\\d{4}");
        assertThat(expected).containsOnlyKeys("JE-0001", "JE-0002", "JE-0003", "JE-0004");
        for (var journalNo : expected.keySet()) {
            assertThat(ledgerLines(journalNo)).as(journalNo).isEqualTo(expected.get(journalNo));
        }
        assertThat(report("finance.gl.journal_register", accountant(),
            Map.of("from", "2026-01-01", "to", "2026-01-31", "status", "POSTED")))
            .extracting(r -> r.get("journalNo"))
            .containsExactlyInAnyOrder("JE-0001", "JE-0002", "JE-0003", "JE-0004");
        assertOnlyInserted("fi_journal_version", "fi_journal_line_version", "fi_posting_version",
            "fi_journal_attachment_version");
    }

    private Map<String, Object> commit(String dataset, Map<String, Object> attributes) {
        return post("/api/datasets/" + dataset + "/commit", accountant(), Map.of("changes", List.of(
            Map.of("action", "INSERT", "attributes", attributes)))).expectStatus().isOk().expectBody(LIST)
            .returnResult().getResponseBody().getFirst();
    }
}
