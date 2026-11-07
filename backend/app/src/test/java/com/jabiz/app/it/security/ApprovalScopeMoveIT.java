package com.jabiz.app.it.security;

import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.app.it.fixture.ItApprovalFixtures;
import com.jabiz.app.it.fixture.ItApprovalScopeFixtures;
import com.jabiz.app.it.fixture.ItTaskFixtures;
import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.context.EntryScope;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ApprovalPermissions;
import com.jabiz.runtime.approval.ApprovalScopeMove;
import com.jabiz.runtime.context.Actor;
import com.jabiz.runtime.task.TaskEntities;
import com.jabiz.runtime.task.TaskPermissions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Moving pending approval requests and their open tasks to another value of their entry scope (ROADMAP phase 17zo,
 * decision D76; docs/design/18-numbering-approvals-tasks.md section 3.8): only through the sub-process
 * {@code APPROVAL_SCOPE_MOVE}, called by an application's reassignment process whose caller holds
 * {@code approval.scope.move}. The new scope's session sees and decides them, the old one no longer does, the next
 * level follows; every other change of the scope fields is refused, and the tables are only appended to.
 */
@SpringBootTest(properties = "it.sql-log.enabled=true")
class ApprovalScopeMoveIT extends ApprovalItSupport {

    private static final String ROLE = "WAREHOUSE";
    private static final String SCOPE = CommerceEntities.WAREHOUSE_SCOPE;
    private static String roleId;

    @BeforeEach
    void role() {
        if (roleId == null) {
            roleId = createRole(ROLE, "commerce.stock.read");
        }
    }

    /** A bearer token of the warehouse entry carrying the scope value {@code value}. */
    private String scoped(String actor, String value, String... permissions) {
        Actor issued = new Actor(actor, null, Set.of(ROLE), Set.of(permissions), null, null, "warehouse", true)
            .inScope(new EntryScope(SCOPE, value));
        return "Bearer " + tokens.issue(issued).token();
    }

    private String approver(String actor, String value) {
        return scoped(actor, value, ApprovalPermissions.DECIDE, ApprovalPermissions.READ, "it.approve",
            "it.approve2", TaskPermissions.READ);
    }

    private String mover() {
        return as("it-hq-mover", ItApprovalScopeFixtures.PERMISSION, ApprovalPermissions.SCOPE_MOVE);
    }

    private static Map<String, Object> move(String scopeName, String toValue, List<String> payments,
        List<String> taskKeys) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("scopeName", scopeName);
        input.put("toValue", toValue);
        input.put("requests", payments.stream().map(payment -> Map.of("subject", ItApprovalFixtures.SUBJECT,
            "entityId", payment)).toList());
        input.put("taskSourceKeys", taskKeys);
        input.put("reason", "The sponsor moved to another operator");
        return input;
    }

    private static String scopeValue(String table, String keyColumn, String key) {
        return (String) query("SELECT scope_value FROM " + table + " WHERE " + keyColumn + " = ?"
            + " ORDER BY version_no DESC LIMIT 1", key).getFirst().get("scope_value");
    }

    private static List<Map<String, Object>> openTasks(String sourceKey) {
        return query("SELECT t.task_id::text AS task_id, t.scope_value, t.assignee_permission FROM sys_task_version t"
            + " WHERE t.source_key = ? AND t.version_no = (SELECT max(v.version_no) FROM sys_task_version v"
            + " WHERE v.task_id = t.task_id) AND t.status = 'OPEN'", sourceKey);
    }

    private void openTask(String authorization, String key) {
        Map<String, Object> open = new HashMap<>(Map.of("key", key, "item", key, "permission", "it.approve"));
        open.put("user", null);
        open.put("due", null);
        run("IT_TASK_OPEN", authorization, open);
    }

    @Test
    void aPendingRequestAndItsTasksMoveToTheNewScope() {
        SqlStatementLog.STATEMENTS.clear();
        String channel = unique("ch");
        rule(channel, 1, List.of(Map.of("permission", "it.approve"), Map.of("permission", "it.approve2")));
        String payment = unique("P");
        String requestId = (String) pay(scoped("it-north-clerk", "north", ItApprovalFixtures.PREPARE), payment,
            channel, 5, "north").get("requestId");
        String key = unique("K");
        openTask(scoped("it-north-opener", "north", ItTaskFixtures.PERMISSION), key);
        String approvalKey = "approval:" + requestId;
        assertThat(openTasks(approvalKey)).extracting(t -> t.get("scope_value")).containsExactly("north");
        String north = approver("it-north-approver", "north");
        String south = approver("it-south-approver", "south");
        assertThat(taskSubjects(north)).contains(requestId, key);
        assertThat(taskSubjects(south)).doesNotContain(requestId, key);

        Map<String, Object> moved = run(ItApprovalScopeFixtures.REASSIGN, mover(), move(SCOPE, "south",
            List.of(payment), List.of(key)));
        assertThat(moved.get("requestIds")).isEqualTo(List.of(requestId));
        List<String> taskIds = List.of(String.valueOf(openTasks(approvalKey).getFirst().get("task_id")),
            String.valueOf(openTasks(key).getFirst().get("task_id")));
        assertThat(moved.get("taskIds")).isEqualTo(taskIds);
        assertThat(moved.get("skipped")).isEqualTo(List.of());
        assertThat(scopeValue("sys_approval_request_version", "request_id::text", requestId)).isEqualTo("south");
        assertThat(openTasks(approvalKey)).extracting(t -> t.get("scope_value")).containsExactly("south");
        assertThat(openTasks(key)).extracting(t -> t.get("scope_value")).containsExactly("south");

        // The new scope's session sees and decides them; the old one's no longer does (not found, as if not there).
        assertThat(taskSubjects(north)).doesNotContain(requestId, key);
        assertThat(taskSubjects(south)).contains(requestId, key);
        assertThat(requests(north, channel)).isEmpty();
        assertThat(requests(south, channel)).containsExactly(requestId);
        refused("APPROVAL_DECIDE", north, decision(requestId), 404);
        decide(south, requestId, "APPROVE", "ok");
        // The next level's task takes the request's scope: the new one.
        assertThat(openTasks(approvalKey)).extracting(t -> t.get("scope_value"), t -> t.get("assignee_permission"))
            .containsExactly(org.assertj.core.groups.Tuple.tuple("south", "it.approve2"));
        assertThat(query("SELECT scope_value FROM sys_approval_decision WHERE request_id = ?::uuid", requestId)
            .getFirst()).containsEntry("scope_value", "south");

        // The platform recorded the move in the audit trail, with the operation of the sub-process.
        List<Map<String, Object>> audit = query("SELECT a.action, a.changes, o.process_name FROM sys_audit_record a"
            + " JOIN op_process o ON o.process_seq_id = a.process_seq_id WHERE a.entity_type = ? AND a.entity_id = ?"
            + " AND a.action = 'UPDATE' AND 'scopeValue' = ANY(a.changed_fields)", ApprovalEntities.REQUEST, requestId);
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst()).containsEntry("action", "UPDATE")
            .containsEntry("process_name", ApprovalScopeMove.MOVE);
        assertThat(String.valueOf(audit.getFirst().get("changes"))).contains("north", "south");
        assertThat(query("SELECT input_summary::text AS input FROM op_process WHERE process_name = ?"
            + " AND input_summary::text LIKE ?", ApprovalScopeMove.MOVE, "%" + payment + "%").getFirst().get("input"))
            .asString().contains("another operator");

        // Only ever appended to.
        assertThat(SqlStatementLog.STATEMENTS).noneMatch(sql -> sql.matches("(?is).*(UPDATE|DELETE FROM)\\s+"
            + "(sys_approval_request_version|sys_task_version|sys_approval_decision)\\b.*"));
    }

    @Test
    void nothingPendingOrThereAlreadyIsNotMoved() {
        String channel = unique("ch");
        rule(channel, 1, List.of(Map.of("permission", "it.approve")));
        String payment = unique("P");
        pay(scoped("it-north-clerk", "north", ItApprovalFixtures.PREPARE), payment, channel, 5, "n");
        String nothing = unique("P");
        Map<String, Object> moved = run(ItApprovalScopeFixtures.REASSIGN, mover(), move(SCOPE, "north",
            List.of(payment, nothing), null));
        assertThat(moved.get("requestIds")).isEqualTo(List.of());
        assertThat(moved.get("taskIds")).isEqualTo(List.of());
        assertThat(moved.get("skipped")).isEqualTo(List.of(
            Map.of("subject", ItApprovalFixtures.SUBJECT, "entityId", payment, "why", "ALREADY_THERE"),
            Map.of("subject", ItApprovalFixtures.SUBJECT, "entityId", nothing, "why", "NOT_PENDING")));
    }

    @Test
    void anotherScopeNameOrNoScopeIsRefused() {
        String channel = unique("ch");
        rule(channel, 1, List.of(Map.of("permission", "it.approve")));
        String scopedPayment = unique("P");
        String scopedRequest = (String) pay(scoped("it-north-clerk", "north", ItApprovalFixtures.PREPARE),
            scopedPayment, channel, 5, "n").get("requestId");
        String plainPayment = unique("P");
        pay(preparer(), plainPayment, channel, 5, "hq");

        assertThat(ruleCode(refused(ItApprovalScopeFixtures.REASSIGN, mover(), move("region", "south",
            List.of(scopedPayment), null), 422))).isEqualTo("APPROVAL_SCOPE_MISMATCH");
        // One case of no scope refuses the whole move: the other is not moved either.
        assertThat(ruleCode(refused(ItApprovalScopeFixtures.REASSIGN, mover(), move(SCOPE, "south",
            List.of(scopedPayment, plainPayment), null), 422))).isEqualTo("APPROVAL_SCOPE_MISMATCH");
        // A task of no scope, named by its source key, likewise.
        String key = unique("K");
        openTask(as("it-hq-opener", ItTaskFixtures.PERMISSION), key);
        assertThat(ruleCode(refused(ItApprovalScopeFixtures.REASSIGN, mover(), move(SCOPE, "south", List.of(),
            List.of(key)), 422))).isEqualTo("APPROVAL_SCOPE_MISMATCH");
        assertThat(scopeValue("sys_approval_request_version", "request_id::text", scopedRequest)).isEqualTo("north");
        assertThat(openTasks("approval:" + scopedRequest)).extracting(t -> t.get("scope_value"))
            .containsExactly("north");
    }

    @Test
    void onlyTheSubProcessWithItsPermissionMoves() {
        String channel = unique("ch");
        rule(channel, 1, List.of(Map.of("permission", "it.approve")));
        String payment = unique("P");
        String requestId = (String) pay(scoped("it-north-clerk", "north", ItApprovalFixtures.PREPARE), payment,
            channel, 5, "n").get("requestId");
        Map<String, Object> input = move(SCOPE, "south", List.of(payment), null);

        // The application's permission alone is not enough.
        assertThat(ruleCode(refused(ItApprovalScopeFixtures.REASSIGN, as("it-hq", ItApprovalScopeFixtures.PERMISSION),
            input, 403))).isEqualTo("PERMISSION_DENIED");
        // Never on its own, whatever the caller holds.
        assertThat(ruleCode(refused(ApprovalScopeMove.MOVE, admin(), input, 403))).isEqualTo("PROCESS_INTERNAL");
        assertThat(ruleCode(refused(ApprovalScopeMove.MOVE, as("it-hq", ApprovalPermissions.SCOPE_MOVE), input,
            403))).isEqualTo("PROCESS_INTERNAL");
        // A session of a scope cannot hand its approvals to another value.
        assertThat(ruleCode(refused(ItApprovalScopeFixtures.REASSIGN, scoped("it-north-mover", "north",
            ItApprovalScopeFixtures.PERMISSION, ApprovalPermissions.SCOPE_MOVE), input, 422)))
            .isEqualTo("OUT_OF_SCOPE");
        assertThat(scopeValue("sys_approval_request_version", "request_id::text", requestId)).isEqualTo("north");
    }

    @Test
    void noOtherWriteChangesTheScope() {
        String channel = unique("ch");
        rule(channel, 1, List.of(Map.of("permission", "it.approve")));
        String requestId = (String) pay(scoped("it-north-clerk", "north", ItApprovalFixtures.PREPARE), unique("P"),
            channel, 5, "n").get("requestId");
        String approvalKey = "approval:" + requestId;
        Map<String, Object> task = query("SELECT task_id::text AS task_id, version_no FROM sys_task_version"
            + " WHERE source_key = ? ORDER BY version_no DESC LIMIT 1", approvalKey).getFirst();

        // A process of the application without the move's grant.
        assertThat(ruleCode(refused(ItApprovalScopeFixtures.RESCOPE_TASK, as("it-hq",
            ItApprovalScopeFixtures.PERMISSION), Map.of("sourceKey", approvalKey, "scopeValue", "south"), 422)))
            .isEqualTo("IMMUTABLE_FIELD");
        // The dataset API: only processes write tasks and requests.
        for (Map.Entry<String, String> target : Map.of(TaskEntities.TASK_DATASET, String.valueOf(task.get("task_id")),
            ApprovalEntities.REQUEST_DATASET, requestId).entrySet()) {
            post("/api/datasets/" + target.getKey() + "/commit", admin(), Map.of("changes", List.of(Map.of(
                "action", "UPDATE", "id", target.getValue(), "version", 1, "attributes",
                Map.of("scopeValue", "south"))))).expectStatus().is4xxClientError();
        }
        assertThat(openTasks(approvalKey)).extracting(t -> t.get("scope_value")).containsExactly("north");
        assertThat(scopeValue("sys_approval_request_version", "request_id::text", requestId)).isEqualTo("north");
    }

    private static Map<String, Object> decision(String requestId) {
        Map<String, Object> input = new HashMap<>();
        input.put("requestId", requestId);
        input.put("decision", "APPROVE");
        input.put("reason", "ok");
        return input;
    }

    /** The approval requests of {@code channel}'s payments the caller sees through the dataset API. */
    @SuppressWarnings("unchecked")
    private List<Object> requests(String authorization, String channel) {
        Map<String, Object> page = post("/api/datasets/" + ApprovalEntities.REQUEST_DATASET + "/query", authorization,
            Map.of("limit", 100)).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return ((List<Map<String, Object>>) page.get("items")).stream()
            .filter(row -> String.valueOf(((Map<String, Object>) row.get("attributes")).get("facts")).contains(channel))
            .map(row -> row.get("id")).toList();
    }

    /** The subjects of the caller's open tasks: approval requests, or the items of the test's own tasks. */
    @SuppressWarnings("unchecked")
    private List<Object> taskSubjects(String authorization) {
        Map<String, Object> result = get("/api/tasks/mine?limit=200", authorization).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        return ((List<Map<String, Object>>) result.get("tasks")).stream().map(task -> task.get("subjectId")).toList();
    }
}
