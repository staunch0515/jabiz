package com.jabiz.app.it.security;

import com.jabiz.app.it.fixture.ItApprovalFixtures;
import com.jabiz.app.it.fixture.ItTaskFixtures;
import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.runtime.approval.ApprovalPermissions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tasks (docs/design/18-numbering-approvals-tasks.md section 5): a user sees the open tasks assigned to them or to a
 * permission they hold; processes open and close them; every pending approval request has one task for the holders of
 * its current level's permission, passed on level by level and closed with the request.
 */
@SpringBootTest(properties = "it.sql-log.enabled=true")
class TaskIT extends ApprovalItSupport {

    String opener() {
        return as("it-opener", ItTaskFixtures.PERMISSION);
    }

    void open(String user, String permission, String key, String item, Instant due) {
        Map<String, Object> input = new HashMap<>(Map.of("key", key, "item", item));
        input.put("user", user);
        input.put("permission", permission);
        input.put("due", due == null ? null : due.toString());
        run("IT_TASK_OPEN", opener(), input);
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> mine(String authorization, String language) {
        Map<String, Object> result = client.get().uri("/api/tasks/mine")
            .header(HttpHeaders.AUTHORIZATION, authorization).header(HttpHeaders.ACCEPT_LANGUAGE, language)
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(((Number) result.get("total")).intValue()).isEqualTo(((List<?>) result.get("tasks")).size());
        return (List<Map<String, Object>>) result.get("tasks");
    }

    List<String> items(List<Map<String, Object>> tasks, String key) {
        return tasks.stream().filter(task -> String.valueOf(task.get("title")).contains(key))
            .map(task -> String.valueOf(task.get("subjectId"))).toList();
    }

    @Test
    void aUserSeesTheTasksAssignedToThemOrToAPermissionTheyHold() {
        String key = unique("K");
        String permission = unique("it.task.do");
        Instant soon = clock.instant().plusSeconds(3600);
        open("it-alice", null, key, key + "-a", null);
        open(null, permission, key, key + "-p", soon);

        String alice = as("it-alice");
        assertThat(items(mine(alice, "en"), key)).containsExactly(key + "-a");
        assertThat(items(mine(as("it-bob", permission), "en"), key)).containsExactly(key + "-p");
        // Holders of "*" see every task given to a permission, and tasks due sooner come first.
        assertThat(items(mine(as("it-root", "*"), "en"), key)).containsExactly(key + "-p");
        assertThat(items(mine(as("it-carol", "other"), "en"), key)).isEmpty();

        Map<String, Object> task = mine(alice, "en").stream()
            .filter(t -> (key + "-a").equals(t.get("subjectId"))).findFirst().orElseThrow();
        assertThat(task).containsEntry("type", "it.check").containsEntry("title", "Check " + key + "-a")
            .containsEntry("titleKey", "it.task.check").containsEntry("subjectEntity", "Thing")
            .containsEntry("link", "/data");

        run("IT_TASK_CLOSE", opener(), Map.of("key", key));
        assertThat(items(mine(alice, "en"), key)).isEmpty();
        assertThat(items(mine(as("it-bob", permission), "en"), key)).isEmpty();
        assertThat(query("SELECT DISTINCT ON (task_id) status, closed_by FROM sys_task_version WHERE source_key = ?"
            + " ORDER BY task_id, version_no DESC", key)).hasSize(2)
            .allSatisfy(row -> assertThat(row).containsEntry("status", "DONE").containsEntry("closed_by", "it-opener"));
        assertThat(query("SELECT count(*) AS n FROM sys_outbox_event WHERE event_type = 'jabiz.task.created'"
            + " AND payload->>'type' = 'it.check' AND payload->>'taskId' IN (SELECT task_id::text FROM"
            + " sys_task_version WHERE source_key = ?)", key).getFirst().get("n")).isEqualTo(2L);

        get("/api/tasks/mine", null).expectStatus().isUnauthorized();
    }

    @Test
    void anApprovalRequestHasOneTaskForItsCurrentLevel() {
        String channel = unique("C");
        String first = unique("it.first");
        String second = unique("it.second");
        rule(channel, 1, List.of(Map.of("permission", first), Map.of("permission", second)));
        String paymentId = unique("P");
        String requestId = (String) pay(preparer(), paymentId, channel, 10, null).get("requestId");

        String firstHolder = as("it-first", ApprovalPermissions.DECIDE, first);
        List<Map<String, Object>> tasks = mine(firstHolder, "ja");
        assertThat(tasks).filteredOn(t -> requestId.equals(t.get("subjectId"))).singleElement().satisfies(t -> {
            assertThat(t).containsEntry("type", "approval").containsEntry("subjectEntity", "SysApprovalRequest")
                .containsEntry("link", "/tasks");
            assertThat((String) t.get("title")).contains(paymentId).contains("承認");
        });
        String secondHolder = as("it-second", ApprovalPermissions.DECIDE, second);
        assertThat(mine(secondHolder, "en")).noneMatch(t -> requestId.equals(t.get("subjectId")));

        decide(firstHolder, requestId, "APPROVE", null);
        assertThat(mine(firstHolder, "en")).noneMatch(t -> requestId.equals(t.get("subjectId")));
        assertThat(mine(secondHolder, "en")).filteredOn(t -> requestId.equals(t.get("subjectId"))).singleElement()
            .satisfies(t -> assertThat((String) t.get("title")).contains("level 2"));

        decide(secondHolder, requestId, "APPROVE", null);
        assertThat(mine(secondHolder, "en")).noneMatch(t -> requestId.equals(t.get("subjectId")));
        assertThat(query("SELECT DISTINCT ON (task_id) status FROM sys_task_version WHERE source_key = ?"
            + " ORDER BY task_id, version_no DESC", "approval:" + requestId))
            .extracting(row -> row.get("status")).containsExactly("DONE", "DONE");
    }

    @Test
    void aSupersededOrWithdrawnRequestCancelsItsTask() {
        String channel = unique("C");
        String permission = unique("it.approve");
        rule(channel, 1, List.of(Map.of("permission", permission)));
        String paymentId = unique("P");
        String first = (String) pay(preparer(), paymentId, channel, 10, "a").get("requestId");
        String second = (String) pay(preparer(), paymentId, channel, 10, "b").get("requestId");
        String holder = as("it-holder", permission);
        assertThat(mine(holder, "en")).extracting(t -> t.get("subjectId")).contains(second).doesNotContain(first);
        assertThat(query("SELECT status FROM sys_task_version WHERE source_key = ? ORDER BY version_no DESC LIMIT 1",
            "approval:" + first).getFirst()).containsEntry("status", "CANCELLED");

        run("IT_PAY_CANCEL", preparer(), new HashMap<>(Map.of("paymentId", paymentId, "channel", channel,
            "amount", 1)));
        assertThat(mine(holder, "en")).extracting(t -> t.get("subjectId")).doesNotContain(second);
    }

    @Test
    void tasksAndNotificationsAreNeverUpdatedOrDeleted() {
        SqlStatementLog.STATEMENTS.clear();
        String key = unique("K");
        open("it-alice", null, key, key, null);
        run("IT_TASK_CLOSE", opener(), Map.of("key", key));
        assertThat(SqlStatementLog.STATEMENTS).noneMatch(sql -> sql.matches(
            "(?is).*(UPDATE|DELETE FROM)\\s+(sys_task_version|sys_notification\\w*)\\b.*"));
        assertThatThrownBy(() -> execute("UPDATE sys_task_version SET status = 'OPEN' WHERE source_key = ?", key))
            .hasMessageContaining("append-only");
    }
}
