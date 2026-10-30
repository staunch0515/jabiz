package com.jabiz.app.it.security;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.app.it.fixture.ItTaskFixtures;
import com.jabiz.context.EntryScope;
import com.jabiz.runtime.context.Actor;
import com.jabiz.runtime.task.TaskEntities;
import com.jabiz.runtime.task.TaskPermissions;
import com.jabiz.runtime.event.OutboxDeliverer;
import com.jabiz.runtime.task.NotificationSender;
import com.jabiz.runtime.task.SmtpNotificationSender;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E-mail about new tasks (docs/design/18-numbering-approvals-tasks.md section 5.4) against a local SMTP server: the
 * assignee (or every holder of the permission) with an address hears of a task once, after the commit; a failed
 * attempt is recorded and retried.
 */
@SpringBootTest(properties = {"jabiz.mail.enabled=true", "jabiz.mail.from=jabiz@example.com",
    "jabiz.mail.base-url=https://jabiz.example.com", "spring.mail.host=localhost", "spring.mail.port=3025"})
class NotificationIT extends SecurityItSupport {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(ServerSetupTest.SMTP);

    /**
     * Fails the first attempt for addresses starting with "flaky"; otherwise (and then) sends through the platform's
     * SMTP sender, configured by {@code spring.mail.*} to the test server.
     */
    @TestConfiguration
    static class FlakySender {

        static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

        @Bean
        @Primary
        NotificationSender flakySender(SmtpNotificationSender smtp) {
            return (to, subject, body) -> {
                if (to.startsWith("flaky") && FAILED.add(to)) {
                    throw new IllegalStateException("mail server busy");
                }
                smtp.send(to, subject, body);
            };
        }
    }

    @Autowired
    OutboxDeliverer deliverer;

    void open(String user, String permission, String key) {
        Map<String, Object> input = new HashMap<>(Map.of("key", key, "item", key));
        input.put("user", user);
        input.put("permission", permission);
        post("/api/processes/IT_TASK_OPEN/latest", bearer(ItTaskFixtures.PERMISSION), input).expectStatus().isOk();
        deliverer.deliverPending().block();
    }

    @SuppressWarnings("unchecked")
    String userWithEmail(String email, boolean enabled, String... permissions) {
        String name = unique("n");
        Map<String, Object> input = new HashMap<>(Map.of("userName", name, "password", "password-123",
            "enabled", enabled));
        input.put("email", email);
        Map<String, Object> result = post("/api/processes/SEC_USER_CREATE/latest", admin(), input)
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        String userId = (String) ((Map<String, Object>) result.get("output")).get("userId");
        if (permissions.length > 0) {
            assign(userId, createRole(unique("ROLE"), permissions), null);
        }
        return userId;
    }

    /** The attempts of the notifications of the task opened with {@code key}, oldest first. */
    static List<Map<String, Object>> attempts(String key) {
        return query("SELECT n.address, a.attempt_no, a.outcome FROM sys_notification n"
            + " JOIN sys_notification_attempt a ON a.notification_id = n.notification_id"
            + " JOIN sys_task_version t ON t.task_id = n.task_id WHERE t.source_key = ? AND t.version_no = 1"
            + " ORDER BY n.address, a.attempt_no", key);
    }

    static List<Map<String, Object>> awaitSent(String key, int count) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            List<Map<String, Object>> rows = attempts(key);
            if (rows.stream().filter(row -> "SENT".equals(row.get("outcome"))).count() >= count) {
                return rows;
            }
            Thread.sleep(100);
        }
        return attempts(key);
    }

    static List<String> recipients(String subjectPart) throws Exception {
        return Arrays.stream(MAIL.getReceivedMessages())
            .filter(message -> subject(message).contains(subjectPart))
            .map(message -> {
                try {
                    return message.getAllRecipients()[0].toString();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).sorted().toList();
    }

    static String subject(MimeMessage message) {
        try {
            return message.getSubject();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A warehouse of this test's own; returns its id. */
    String warehouse(String prefix) {
        String code = prefix + Long.toString(System.nanoTime() % 1_000_000_000L, 36).toUpperCase();
        return String.valueOf(insert(CommerceEntities.WAREHOUSE_DATASET, Map.of("warehouseCode", code,
            "warehouseName", "Warehouse " + code, "active", true), null).get("id"));
    }

    /** A token of the demo's warehouse entry in the warehouse {@code warehouseId} (decision D64). */
    String warehouseToken(String actor, String warehouseId, String... permissions) {
        return "Bearer " + tokens.issue(new Actor(actor, null, Set.of("WAREHOUSE"), Set.of(permissions), null, null,
            "warehouse", true).inScope(new EntryScope(CommerceEntities.WAREHOUSE_SCOPE, warehouseId))).token();
    }

    /** The task ids of the notifications the caller sees through the notification dataset. */
    @SuppressWarnings("unchecked")
    List<String> notifiedTasks(String authorization) {
        Map<String, Object> page = post("/api/datasets/" + TaskEntities.NOTIFICATION_DATASET + "/query",
            authorization, Map.of("limit", 500)).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
        return ((List<Map<String, Object>>) page.get("items")).stream()
            .map(row -> String.valueOf(((Map<String, Object>) row.get("attributes")).get("taskId"))).toList();
    }

    /**
     * Decision D64: a task of a warehouse is mailed to the holders who see it (the warehouse's own staff, and those who
     * reach it through an entry without a scope), not to another warehouse's staff; and the messages are seen as the
     * task is.
     */
    @Test
    void aScopedTaskIsMailedToThoseWhoSeeIt() throws Exception {
        String permission = unique("it.scoped");
        String north = warehouse("N");
        String south = warehouse("S");
        String roleId = createRole("WAREHOUSE", permission);
        String key = unique("K");
        String northAddress = "north-" + key.toLowerCase() + "@example.com";
        String southAddress = "south-" + key.toLowerCase() + "@example.com";
        String hqAddress = "hq-" + key.toLowerCase() + "@example.com";
        String northStaff = userWithEmail(northAddress, true);
        assign(northStaff, roleId, null);
        insert(CommerceEntities.WAREHOUSE_STAFF_DATASET, Map.of("userId", northStaff, "warehouseId", north), null);
        String southStaff = userWithEmail(southAddress, true);
        assign(southStaff, roleId, null);
        insert(CommerceEntities.WAREHOUSE_STAFF_DATASET, Map.of("userId", southStaff, "warehouseId", south), null);
        userWithEmail(hqAddress, true, permission);

        // Opened in the north's session: the task, and its messages, are the north's.
        Map<String, Object> input = new HashMap<>(Map.of("key", key, "item", key));
        input.put("user", null);
        input.put("permission", permission);
        post("/api/processes/IT_TASK_OPEN/latest", warehouseToken("it-north-opener", north, ItTaskFixtures.PERMISSION),
            input).expectStatus().isOk();
        deliverer.deliverPending().block();
        assertThat(awaitSent(key, 2)).extracting(row -> row.get("address"))
            .containsExactlyInAnyOrder(northAddress, hqAddress);
        assertThat(recipients(key)).containsExactlyInAnyOrder(northAddress, hqAddress);

        String taskId = String.valueOf(query("SELECT task_id FROM sys_task_version WHERE source_key = ?", key)
            .getFirst().get("task_id"));
        assertThat(notifiedTasks(warehouseToken("it-north-reader", north, TaskPermissions.READ))).contains(taskId);
        assertThat(notifiedTasks(warehouseToken("it-south-reader", south, TaskPermissions.READ)))
            .doesNotContain(taskId);
        // Headquarters (no scope) sees them all; the north's staff sees their own message in any session.
        assertThat(notifiedTasks(bearer(TaskPermissions.READ))).contains(taskId);
        assertThat(notifiedTasks(warehouseToken(northStaff, south, TaskPermissions.READ))).contains(taskId);
    }

    @Test
    void theAssigneeHearsOfATaskOnceAfterTheCommit() throws Exception {
        String key = unique("K");
        String address = key.toLowerCase() + "@example.com";
        String user = userWithEmail(address, true);
        open(user, null, key);

        assertThat(awaitSent(key, 1)).extracting(row -> row.get("outcome")).containsExactly("SENT");
        assertThat(recipients(key)).containsExactly(address);
        MimeMessage message = Arrays.stream(MAIL.getReceivedMessages())
            .filter(m -> subject(m).contains(key)).findFirst().orElseThrow();
        assertThat(subject(message)).isEqualTo("Check " + key);
        assertThat(String.valueOf(message.getContent())).contains("https://jabiz.example.com/data");
        assertThat(query("SELECT recipient_id, subject FROM sys_notification WHERE address = ?", address))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("recipient_id", user));

        // Delivering again (the event is consumed) sends nothing more.
        deliverer.deliverPending().block();
        assertThat(recipients(key)).hasSize(1);
    }

    @Test
    void everyEnabledHolderOfThePermissionWithAnAddressHearsOfIt() throws Exception {
        String key = unique("K");
        String permission = unique("it.notify");
        String lower = key.toLowerCase();
        userWithEmail("a-" + lower + "@example.com", true, permission);
        userWithEmail(null, true, permission);
        userWithEmail("off-" + lower + "@example.com", false, permission);
        userWithEmail("root-" + lower + "@example.com", true, "*");
        userWithEmail("other-" + lower + "@example.com", true, "it.other");
        open(null, permission, key);

        assertThat(awaitSent(key, 1)).extracting(row -> row.get("address"))
            .containsExactly("a-" + lower + "@example.com");
        assertThat(recipients(key)).containsExactly("a-" + lower + "@example.com");
    }

    @Test
    void aFailedAttemptIsRecordedAndRetried() throws Exception {
        String key = unique("K");
        String address = "flaky-" + key.toLowerCase() + "@example.com";
        open(userWithEmail(address, true), null, key);

        assertThat(awaitSent(key, 1)).extracting(row -> row.get("attempt_no") + ":" + row.get("outcome"))
            .containsExactly("1:FAILED", "2:SENT");
        assertThat(recipients(key)).containsExactly(address);
    }
}
