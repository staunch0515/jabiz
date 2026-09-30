package com.jabiz.app.it.security;

import com.jabiz.app.it.fixture.ItApprovalFixtures;
import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ApprovalPermissions;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Approvals (docs/design/18-numbering-approvals-tasks.md section 3, decision D23) through the HTTP API: rules change
 * only with four eyes, the finding is recorded, a request is bound to the content it was made for, levels need their
 * permission, limit and different people, and the rules apply as of the case's business time. Each test uses a
 * payment channel of its own, so the rules of other tests never apply.
 */
@SpringBootTest(properties = "it.sql-log.enabled=true")
class ApprovalIT extends ApprovalItSupport {

    @Test
    void aControlChangesOnlyWhenASecondPersonPublishesIt() {
        String propose = as(PROPOSER, ApprovalPermissions.CONTROL_PROPOSE, ApprovalPermissions.CONTROL_PUBLISH);
        Map<String, Object> input = new HashMap<>(Map.of("targetEntity", ApprovalEntities.RULE, "reason", "new rule",
            "values", Map.of("ruleCode", unique("R"), "subject", ItApprovalFixtures.SUBJECT, "priority", 1,
                "enabled", true, "condition", Map.of("fact", "channel", "op", "eq", "value", unique("C")),
                "levels", List.of())));
        String changeId = (String) run("CONTROL_CHANGE_PROPOSE", propose, input).get("changeId");

        assertThat(ruleCode(refused("CONTROL_CHANGE_PUBLISH", propose, Map.of("changeId", changeId), 422)))
            .isEqualTo("CONTROL_SAME_PERSON");
        String publisher = as("it-publisher", ApprovalPermissions.CONTROL_PUBLISH);
        assertThat(run("CONTROL_CHANGE_PUBLISH", publisher, Map.of("changeId", changeId)))
            .containsEntry("status", "PUBLISHED");
        assertThat(ruleCode(refused("CONTROL_CHANGE_PUBLISH", publisher, Map.of("changeId", changeId), 422)))
            .isEqualTo("CONTROL_CHANGE_NOT_PROPOSED");
        assertThat(query("SELECT published_by FROM sys_control_change_version WHERE change_id = ?::uuid"
            + " ORDER BY version_no DESC LIMIT 1", changeId).getFirst()).containsEntry("published_by", "it-publisher");

        // Everything wrong with a proposal is reported at once, before anybody publishes it.
        Map<String, Object> bad = new HashMap<>(Map.of("targetEntity", ApprovalEntities.RULE, "reason", "bad",
            "values", Map.of("ruleCode", unique("R"), "subject", ItApprovalFixtures.SUBJECT, "priority", 1,
                "enabled", true, "owner", "me",
                "condition", Map.of("fact", "colour", "op", "eq", "value", "red"),
                "levels", List.of(Map.of("permission", "x", "limitFact", "channel")))));
        List<Map<String, Object>> violations = violations(refused("CONTROL_CHANGE_PROPOSE", propose, bad, 422));
        assertThat(violations).extracting(v -> v.get("ruleCode")).containsOnly("CONTROL_CHANGE_INVALID");
        assertThat(violations).extracting(v -> String.valueOf(v.get("message")))
            .anyMatch(m -> m.contains("field 'owner' cannot be set"))
            .anyMatch(m -> m.contains("fact 'colour' is not declared"))
            .anyMatch(m -> m.contains("limitFact 'channel' is not a NUMBER fact"));
        Map<String, Object> unknown = new HashMap<>(Map.of("targetEntity", "SecUser", "reason", "no",
            "values", Map.of("x", 1)));
        assertThat(ruleCode(refused("CONTROL_CHANGE_PROPOSE", propose, unknown, 422)))
            .isEqualTo("CONTROL_CHANGE_INVALID");

        // Neither the dataset API nor the generic entity processes write the controls.
        Map<String, Object> direct = post("/api/datasets/" + ApprovalEntities.RULE_DATASET + "/commit", admin(),
            Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", Map.of("ruleCode", unique("R"),
                "subject", ItApprovalFixtures.SUBJECT, "condition", "{}", "levels", "[]", "priority", 1,
                "enabled", true))))).expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCode(direct)).isEqualTo("PROCESS_ONLY_DATASET");

        // The proposer, and only the proposer, withdraws a proposal.
        String second = (String) run("CONTROL_CHANGE_PROPOSE", propose, input).get("changeId");
        refused("CONTROL_CHANGE_WITHDRAW", as("someone", ApprovalPermissions.CONTROL_PROPOSE),
            Map.of("changeId", second), 422);
        assertThat(run("CONTROL_CHANGE_WITHDRAW", propose, Map.of("changeId", second)))
            .containsEntry("status", "WITHDRAWN");
        assertThat(ruleCode(refused("CONTROL_CHANGE_PUBLISH", publisher, Map.of("changeId", second), 422)))
            .isEqualTo("CONTROL_CHANGE_NOT_PROPOSED");
    }

    @Test
    void aCaseNoRuleAppliesToNeedsNoApprovalAndTheFindingIsRecorded() {
        String channel = unique("C");
        rule(channel, 1000, List.of(Map.of("permission", "it.approve")));
        String paymentId = unique("P");
        assertThat(pay(preparer(), paymentId, channel, 999, null)).containsEntry("status", "NOT_REQUIRED")
            .containsEntry("requestId", null);
        Map<String, Object> evaluation = query("SELECT outcome, matched_rule, rule_versions, facts, request_id"
            + " FROM sys_approval_evaluation WHERE entity_id = ?", paymentId).getFirst();
        assertThat(evaluation).containsEntry("outcome", "NOT_REQUIRED").containsEntry("matched_rule", null)
            .containsEntry("request_id", null);
        assertThat((String) evaluation.get("rule_versions")).contains(":1");
        assertThat((String) evaluation.get("facts")).contains("\"amount\":999").contains(channel);
    }

    @Test
    void aRequestIsBoundToTheContentItWasMadeFor() {
        String channel = unique("C");
        rule(channel, 100, List.of(Map.of("permission", "it.approve")));
        String paymentId = unique("P");
        Map<String, Object> first = pay(preparer(), paymentId, channel, "150.00", "rent");
        assertThat(first).containsEntry("status", "PENDING");
        String requestId = (String) first.get("requestId");
        assertThat(events("jabiz.approval.requested", requestId)).hasSize(1);
        Map<String, Object> request = query("SELECT preparer_id, content_hash, current_level, levels"
            + " FROM sys_approval_request_version WHERE request_id = ?::uuid", requestId).getFirst();
        assertThat(request).containsEntry("preparer_id", "it-preparer").containsEntry("current_level", 1);
        assertThat((String) request.get("content_hash")).hasSize(64);

        // The same content (the amount written differently) finds the same request.
        assertThat(pay(preparer(), paymentId, channel, "150", "rent")).containsEntry("requestId", requestId)
            .containsEntry("status", "PENDING");
        // Changed content supersedes it.
        Map<String, Object> changed = pay(preparer(), paymentId, channel, "150", "rent and fees");
        assertThat(changed).containsEntry("status", "PENDING");
        assertThat(changed.get("requestId")).isNotEqualTo(requestId);
        assertThat(status(requestId)).isEqualTo(ApprovalEntities.SUPERSEDED);

        // Cancelling the payment withdraws the pending request.
        Map<String, Object> cancel = new HashMap<>(Map.of("paymentId", paymentId, "channel", channel, "amount", 1));
        run("IT_PAY_CANCEL", preparer(), cancel);
        assertThat(status((String) changed.get("requestId"))).isEqualTo(ApprovalEntities.WITHDRAWN);
    }

    @Test
    void everyLevelNeedsItsPermissionItsLimitAndAnotherPerson() {
        String channel = unique("C");
        rule(channel, 100, List.of(Map.of("permission", "it.approve.first", "limitFact", "amount"),
            Map.of("permission", "it.approve.second")));
        String carol = createUser(unique("carol"), "carol-password-1");
        String dave = createUser(unique("dave"), "dave-password-1");
        String paymentId = unique("P");
        String requestId = (String) pay(preparer(), paymentId, channel, 5000, "equipment").get("requestId");

        String asPreparer = as("it-preparer", ItApprovalFixtures.PREPARE, ApprovalPermissions.DECIDE, "*");
        assertThat(ruleCode(refused("APPROVAL_DECIDE", asPreparer, Map.of("requestId", requestId,
            "decision", "APPROVE"), 422))).isEqualTo("APPROVAL_OWN_REQUEST");
        assertThat(ruleCode(refused("APPROVAL_DECIDE", as(carol, ApprovalPermissions.DECIDE, "it.approve.second"),
            Map.of("requestId", requestId, "decision", "APPROVE"), 403))).isEqualTo("PERMISSION_DENIED");

        String asCarol = as(carol, ApprovalPermissions.DECIDE, "it.approve.first", "it.approve.second");
        assertThat(ruleCode(refused("APPROVAL_DECIDE", asCarol, Map.of("requestId", requestId, "decision",
            "APPROVE"), 422))).isEqualTo("APPROVAL_LIMIT_EXCEEDED");
        String limitId = control(ApprovalEntities.LIMIT, null, Map.of("userId", carol, "subject",
            ItApprovalFixtures.SUBJECT, "maxValue", "4999.99"), null);
        assertThat(ruleCode(refused("APPROVAL_DECIDE", asCarol, Map.of("requestId", requestId, "decision",
            "APPROVE"), 422))).isEqualTo("APPROVAL_LIMIT_EXCEEDED");
        control(ApprovalEntities.LIMIT, limitId, Map.of("maxValue", 10000), null);

        assertThat(decide(asCarol, requestId, "APPROVE", null)).containsEntry("status", "PENDING")
            .containsEntry("currentLevel", 2);
        assertThat(ruleCode(refused("APPROVAL_DECIDE", asCarol, Map.of("requestId", requestId, "decision",
            "APPROVE"), 422))).isEqualTo("APPROVAL_ALREADY_DECIDED");
        assertThat(pay(preparer(), paymentId, channel, 5000, "equipment")).containsEntry("status", "PENDING");

        assertThat(decide(as(dave, ApprovalPermissions.DECIDE, "it.approve.second"), requestId, "APPROVE", "ok"))
            .containsEntry("status", "APPROVED");
        assertThat(events("jabiz.approval.approved", requestId)).singleElement()
            .satisfies(event -> assertThat(event).containsEntry("approver", dave)
                .containsEntry("preparer", "it-preparer"));
        assertThat(query("SELECT level_no, approver_id FROM sys_approval_decision WHERE request_id = ?::uuid"
            + " ORDER BY level_no", requestId)).extracting(row -> row.get("approver_id")).containsExactly(carol, dave);

        // The approval holds for this content only.
        assertThat(pay(preparer(), paymentId, channel, 5000, "equipment")).containsEntry("status", "APPROVED")
            .containsEntry("requestId", requestId);
        assertThat(pay(preparer(), paymentId, channel, 5001, "equipment")).containsEntry("status", "PENDING");
        assertThat(status(requestId)).isEqualTo(ApprovalEntities.SUPERSEDED);
        assertThat(ruleCode(refused("APPROVAL_DECIDE", asCarol, Map.of("requestId", requestId, "decision",
            "REJECT", "reason", "late"), 422))).isEqualTo("APPROVAL_NOT_PENDING");
    }

    @Test
    void aRejectionNeedsAReasonAndEndsTheRequest() {
        String channel = unique("C");
        rule(channel, 1, List.of(Map.of("permission", "it.approve")));
        String paymentId = unique("P");
        String requestId = (String) pay(preparer(), paymentId, channel, 10, null).get("requestId");
        String approver = as("it-approver", ApprovalPermissions.DECIDE, "it.approve");
        assertThat(ruleCode(refused("APPROVAL_DECIDE", approver, Map.of("requestId", requestId, "decision",
            "REJECT"), 422))).isEqualTo("REASON_REQUIRED");
        assertThat(decide(approver, requestId, "REJECT", "no invoice")).containsEntry("status", "REJECTED");
        assertThat(events("jabiz.approval.rejected", requestId)).singleElement()
            .satisfies(event -> assertThat(event).containsEntry("reason", "no invoice"));
        assertThat(ruleCode(refused("APPROVAL_DECIDE", as("other", ApprovalPermissions.DECIDE, "it.approve"),
            Map.of("requestId", requestId, "decision", "APPROVE"), 422))).isEqualTo("APPROVAL_NOT_PENDING");

        // The audit trail of the payment shows who decided, what and why (docs/design/21-audit-retention.md 1.3).
        String auditor = as("it-auditor", "audit.read");
        String trail = "/api/audit/records?entityId=" + paymentId;
        assertThat(auditItems(trail, auditor)).extracting(r -> r.get("entityType"))
            .doesNotContain(ApprovalEntities.REQUEST, ApprovalEntities.DECISION);
        List<Map<String, Object>> withApprovals = auditItems(trail + "&withApprovals=true", auditor);
        assertThat(withApprovals).filteredOn(r -> ApprovalEntities.REQUEST.equals(r.get("entityType")))
            .extracting(r -> r.get("action")).contains("INSERT", "UPDATE");
        assertThat(withApprovals).filteredOn(r -> ApprovalEntities.DECISION.equals(r.get("entityType")))
            .singleElement().satisfies(r -> {
                assertThat(r).containsEntry("actorId", "it-approver").containsEntry("processName", "APPROVAL_DECIDE");
                assertThat(String.valueOf(r.get("changes"))).contains("REJECT", "no invoice");
            });
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> auditItems(String path, String authorization) {
        return (List<Map<String, Object>>) get(path, authorization).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody().get("items");
    }

    @Test
    void theRulesInEffectAtTheBusinessTimeApply() {
        String channel = unique("C");
        String ruleId = rule(channel, 1, List.of(Map.of("permission", "it.approve")));
        Instant change = clock.instant().plusSeconds(86_400);
        control(ApprovalEntities.RULE, ruleId, Map.of("enabled", false), change);

        Map<String, Object> before = new HashMap<>(Map.of("paymentId", unique("P"), "channel", channel,
            "amount", 10, "businessTime", clock.instant().toString()));
        assertThat(run("IT_PAY", preparer(), before)).containsEntry("status", "PENDING");
        Map<String, Object> after = new HashMap<>(Map.of("paymentId", unique("P"), "channel", channel,
            "amount", 10, "businessTime", change.plusSeconds(1).toString()));
        assertThat(run("IT_PAY", preparer(), after)).containsEntry("status", "NOT_REQUIRED");
    }

    @Test
    void ofConcurrentDecisionsOnALevelOneCounts() {
        String channel = unique("C");
        rule(channel, 1, List.of(Map.of("permission", "it.approve")));
        String requestId = (String) pay(preparer(), unique("P"), channel, 10, null).get("requestId");
        // One call first: Jackson builds the input's deserializer under a lock on its first use.
        refused("APPROVAL_DECIDE", as("it-preparer", ApprovalPermissions.DECIDE, "it.approve"),
            Map.of("requestId", requestId, "decision", "APPROVE"), 422);
        List<Integer> statuses = Flux.range(0, 6)
            .flatMap(i -> Mono.fromCallable(() -> post("/api/processes/APPROVAL_DECIDE/latest",
                    as("approver-" + i, ApprovalPermissions.DECIDE, "it.approve"),
                    Map.of("requestId", requestId, "decision", "APPROVE"))
                    .returnResult(String.class).getStatus().value())
                .subscribeOn(Schedulers.boundedElastic()), 6)
            .collectList().block();
        assertThat(statuses).filteredOn(s -> s == 200).hasSize(1);
        assertThat(statuses).allMatch(s -> s == 200 || s == 409 || s == 422);
        assertThat(query("SELECT count(*) AS n FROM sys_approval_decision WHERE request_id = ?::uuid", requestId)
            .getFirst().get("n")).isEqualTo(1L);
        assertThat(status(requestId)).isEqualTo(ApprovalEntities.APPROVED);
    }

    @Test
    void decisionsAndEvaluationsAreNeverUpdatedOrDeleted() {
        SqlStatementLog.STATEMENTS.clear();
        String channel = unique("C");
        rule(channel, 1, List.of(Map.of("permission", "it.approve")));
        String paymentId = unique("P");
        String requestId = (String) pay(preparer(), paymentId, channel, 10, null).get("requestId");
        decide(as("it-approver", ApprovalPermissions.DECIDE, "it.approve"), requestId, "APPROVE", null);
        assertThat(SqlStatementLog.STATEMENTS).noneMatch(sql -> sql.matches("(?is).*(UPDATE|DELETE FROM)\\s+"
            + "(sys_approval_decision|sys_approval_evaluation|sys_approval_\\w+_version|sys_control_change_version)"
            + "\\b.*"));
        assertThatThrownBy(() -> execute("UPDATE sys_approval_decision SET reason = 'x' WHERE request_id = ?::uuid",
            requestId)).hasMessageContaining("append-only");
        assertThatThrownBy(() -> execute("DELETE FROM sys_approval_evaluation WHERE entity_id = ?", paymentId))
            .hasMessageContaining("append-only");
    }

    @Test
    @SuppressWarnings("unchecked")
    void thePreviewListsTheCasesWhoseFindingChanges() {
        String channel = unique("C");
        String ruleId = rule(channel, 1000, List.of(Map.of("permission", "it.approve")));
        pay(preparer(), unique("P"), channel, 500, null);
        String large = unique("P");
        pay(preparer(), large, channel, 5000, null);
        String small = unique("P");
        pay(preparer(), small, channel, 50, null);

        // Lowering the threshold to 100 makes the 500 case need approval; 50 and 5000 stay as they are.
        Map<String, Object> draft = Map.of("ruleCode", "lower", "priority", 10, "enabled", true,
            "condition", Map.of("all", List.of(Map.of("fact", "channel", "op", "eq", "value", channel),
                Map.of("fact", "amount", "op", "gte", "value", 100))),
            "levels", List.of(Map.of("permission", "it.approve")));
        Map<String, Object> preview = post("/api/approvals/preview", as("reader", ApprovalPermissions.READ),
            Map.of("subject", ItApprovalFixtures.SUBJECT, "ruleId", ruleId, "draft", draft))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat((Integer) preview.get("evaluated")).isGreaterThanOrEqualTo(3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changed = (List<Map<String, Object>>) preview.get("changed");
        assertThat(changed).filteredOn(c -> String.valueOf(c.get("entityId")).equals(large)
            || String.valueOf(c.get("entityId")).equals(small)).isEmpty();
        assertThat(changed).anySatisfy(c -> {
            assertThat((Map<String, Object>) c.get("before")).containsEntry("required", false)
                .containsEntry("levels", List.of());
            assertThat((Map<String, Object>) c.get("after")).containsEntry("required", true)
                .containsEntry("ruleCode", "lower").containsEntry("levels", List.of("it.approve"));
        });

        post("/api/approvals/preview", as("nobody"), Map.of("subject", ItApprovalFixtures.SUBJECT))
            .expectStatus().isForbidden();
        Map<String, Object> invalid = post("/api/approvals/preview", as("reader", ApprovalPermissions.READ),
            Map.of("subject", ItApprovalFixtures.SUBJECT, "draft", Map.of("condition", Map.of("fact", "nope",
                "op", "eq", "value", 1))))
            .expectStatus().isBadRequest().expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCode(invalid)).isEqualTo("CONTROL_CHANGE_INVALID");
    }
}
