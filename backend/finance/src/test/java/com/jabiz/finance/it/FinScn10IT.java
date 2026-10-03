package com.jabiz.finance.it;

import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.gl.JournalEntities;
import com.jabiz.finance.gl.JournalProcesses;
import com.jabiz.finance.setup.SetupProcesses;
import com.jabiz.runtime.approval.ApprovalEntities;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jabiz.finance.it.JournalLifecycleIT.entry;
import static com.jabiz.finance.it.JournalLifecycleIT.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIN-SCN-10, a rule change with impact preview (FIN-CT-002, FIN-CT-004, FIN-SC-005; ROADMAP F10a): the controller
 * prepares lowering the journal approval threshold from 10,000.00 to 5,000.00 effective 2026-03-01 and previews it
 * on January's entries, none of which would be treated differently; another controller publishes it. January's
 * entries keep the 10,000.00 version in their evaluations; a 6,000.00 entry submitted in February still posts at once,
 * one submitted in March waits for an approver under the new version, even back-dated to February; the change, who
 * proposed it and who published it are on record.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class FinScn10IT extends JanuaryBooks {

    /** 2026-03-01 at the start of the day in Chicago, the books' zone. */
    private static final Instant MARCH = Instant.parse("2026-03-01T06:00:00Z");

    @Test
    void aRuleChangeWithItsPreview() {
        people();
        books();
        journals();
        Map<String, Object> rule = find(ApprovalEntities.RULE_DATASET, "ruleCode", SetupProcesses.APPROVAL_RULE)
            .getFirst();
        String ruleId = (String) rule.get("ruleId");

        // Step 1: the change prepared, and previewed on the entries so far (January's): none would change.
        Map<String, Object> lowered = loweredRule();
        @SuppressWarnings("unchecked")
        Map<String, Object> preview = post("/api/approvals/preview", as("controller", "approval.read"),
            Map.of("subject", JournalProcesses.SUBJECT, "ruleId", ruleId, "draft", Map.of("ruleCode",
                SetupProcesses.APPROVAL_RULE, "condition", lowered.get("condition"), "levels", lowered.get("levels"),
                "priority", 100, "enabled", true))).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
        assertThat(((Number) preview.get("evaluated")).intValue()).isEqualTo(3);
        assertThat(preview).containsEntry("truncated", false);
        assertThat((List<?>) preview.get("changed")).isEmpty();
        String changeId = (String) ok("CONTROL_CHANGE_PROPOSE", as("controller", "control.propose"),
            Map.of("targetEntity", ApprovalEntities.RULE, "targetId", ruleId, "values", lowered,
                "effectiveTime", MARCH.toString(), "reason", "Lower the journal approval threshold to 5,000.00"))
            .get("changeId");

        // Step 2: another controller publishes it; the proposer may not.
        assertThat(refused("CONTROL_CHANGE_PUBLISH", as("controller", "control.publish"),
            Map.of("changeId", changeId), 422)).isEqualTo("CONTROL_SAME_PERSON");
        ok("CONTROL_CHANGE_PUBLISH", as("controller-2", "control.publish"), Map.of("changeId", changeId));
        assertThat(read(ApprovalEntities.CONTROL_CHANGE_DATASET, changeId)).containsEntry("proposedBy", "controller")
            .containsEntry("publishedBy", "controller-2");

        // Expected: January's entries keep the 10,000.00 version (1) in their evaluations.
        for (String number : List.of("JE-0001", "JE-0002", "JE-0003")) {
            Object journalId = find(JournalEntities.JOURNAL_DATASET, "journalNo", number).getFirst().get("journalId");
            assertThat(query("SELECT rule_versions FROM sys_approval_evaluation WHERE entity_id = ?", journalId))
                .as(number).isNotEmpty().allSatisfy(e -> assertThat((String) e.get("rule_versions"))
                    .contains(ruleId + ":1").doesNotContain(ruleId + ":2"));
        }
        // The new version applies from 2026-03-01 by when an entry is submitted (its business time): a 6,000.00
        // entry submitted in February posts at once; one submitted in March waits, back-dated to February too.
        clock.set(Instant.parse("2026-02-20T15:00:00Z"));
        people();
        assertThat(submit("2026-02-16")).containsEntry("status", "POSTED").containsEntry("approval", "NOT_REQUIRED");
        clock.set(Instant.parse("2026-03-10T15:00:00Z"));
        people();
        for (String date : List.of("2026-02-27", "2026-03-05")) {
            Map<String, Object> submitted = submit(date);
            assertThat(submitted).as(date).containsEntry("approval", "PENDING");
            assertThat(query("SELECT rule_versions FROM sys_approval_evaluation WHERE entity_id = ?",
                submitted.get("journalId"))).as(date).isNotEmpty()
                .allSatisfy(e -> assertThat((String) e.get("rule_versions")).contains(ruleId + ":2"));
        }
        // The rule's change is in the audit trail with who published it.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trail = (List<Map<String, Object>>) get("/api/audit/records?entityType="
            + ApprovalEntities.RULE + "&entityId=" + ruleId, as("auditor", "audit.read")).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody().get("items");
        assertThat(trail).anySatisfy(r -> assertThat(r).containsEntry("actorId", "controller-2"));
    }

    /** FIN_SETUP's rule with the threshold at 5,000.00. */
    private static Map<String, Object> loweredRule() {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("ruleCode", SetupProcesses.APPROVAL_RULE);
        rule.put("subject", JournalProcesses.SUBJECT);
        rule.put("priority", 100);
        rule.put("enabled", true);
        rule.put("condition", Map.of("all", List.of(
            Map.of("fact", "manual", "op", "eq", "value", true),
            Map.of("fact", "amount", "op", "gt", "value", 5000))));
        rule.put("levels", List.of(Map.of("permission", FinancePermissions.JOURNAL_APPROVE)));
        rule.put("description", "Manual journal entries above 5,000.00 need an approver of journal entries");
        return rule;
    }

    private Map<String, Object> submit(String date) {
        String id = (String) ok(JournalProcesses.SAVE, accountant, entry(date, "Consulting " + date,
            List.of(line("6400", "6000.00", null, null), line("2100", null, "6000.00", null)))).get("journalId");
        Map<String, Object> submitted = new LinkedHashMap<>(ok(JournalProcesses.SUBMIT, accountant,
            Map.of("journalId", id)));
        submitted.put("journalId", id);
        return submitted;
    }
}
