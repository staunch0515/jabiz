package com.jabiz.app.it.security;

import com.jabiz.runtime.event.OutboxDeliverer;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The setup window and the first administrator (decision D48 item 1; docs/design/10-security.md section 16.3): with
 * only the configured first administrator, protected grants take effect at once (dev, e2e, the demo); once another
 * administrator exists, the first one may no longer make or decide protected changes, for good; once two
 * administrators besides it exist, protected changes need approval, for good - disabling administrators reopens
 * neither. An administrator whose removal is scheduled far ahead still counts until then.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.security.bootstrap-admin.user-name=root",
    "jabiz.security.bootstrap-admin.password=bootstrap password 1"})
class ProtectedGrantSetupIT extends SecurityItSupport {

    @Autowired
    OutboxDeliverer deliverer;

    @Autowired
    com.jabiz.runtime.security.protect.ProtectedGrants grants;

    private String as(String userId) {
        return TestTokens.bearer(tokens, userId, "*");
    }

    /** A user without a password: credentials nobody else set. */
    @SuppressWarnings("unchecked")
    private String bareUser(String prefix) {
        String name = unique(prefix);
        Map<String, Object> result = post("/api/processes/SEC_USER_CREATE/latest", admin(),
            Map.of("userName", name, "displayName", name)).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
        return (String) ((Map<String, Object>) result.get("output")).get("userId");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> assignRole(String bearer, String user, String role, int status) {
        Map<String, Object> body = post("/api/processes/SEC_USER_ROLE_ASSIGN/latest", bearer,
            Map.of("userId", user, "roleId", role, "reason", "setup")).expectStatus().isEqualTo(status)
            .expectBody(MAP).returnResult().getResponseBody();
        return status == 200 ? (Map<String, Object>) body.get("output") : body;
    }

    private static boolean recorded(String table) {
        return !query("SELECT 1 FROM " + table).isEmpty();
    }

    private void disable(String bearer, String user) {
        Map<String, Object> current = query("SELECT version_no FROM sec_user_version WHERE user_id = ?::uuid"
            + " ORDER BY version_no DESC LIMIT 1", user).getFirst();
        Map<String, Object> change = new LinkedHashMap<>(Map.of("action", "UPDATE", "id", user,
            "version", current.get("version_no"), "attributes", Map.of("enabled", false)));
        post("/api/datasets/" + SecurityEntities.USER_DATASET + "/commit", bearer, Map.of("changes",
            List.of(change))).expectStatus().isOk();
    }

    @Test
    void theWindowClosesForGoodAndTheFirstAdministratorRetiresForGood() {
        String root = String.valueOf(query("SELECT user_id::text AS id FROM sec_user_version WHERE user_name = 'root'")
            .getFirst().get("id"));
        String adminRole = String.valueOf(query("SELECT role_id::text AS id FROM sec_role_version WHERE role_code ="
            + " 'ADMIN'").getFirst().get("id"));
        String one = bareUser("a1");
        String two = bareUser("a2");
        String three = bareUser("a3");
        String four = bareUser("a4");

        // Alone, the first administrator makes the second one directly.
        assertThat(assignRole(as(root), one, adminRole, 200)).containsEntry("status", "APPLIED");
        assertThat(recorded("sec_protected_grants_armed")).isFalse();
        // Now it is retired from protected changes, whatever the path...
        assertThat(ruleCode(assignRole(as(root), two, adminRole, 403))).isEqualTo("BOOTSTRAP_ADMIN_RETIRED");
        assertThat(ruleCode(post("/api/datasets/" + SecurityEntities.USER_ROLE_DATASET + "/commit", as(root),
            Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", Map.of("userId", two,
                "roleId", adminRole))))).expectStatus().isForbidden().expectBody(MAP).returnResult()
            .getResponseBody())).isEqualTo("BOOTSTRAP_ADMIN_RETIRED");
        // ...but not from the rest; and the retirement is on record with the next write.
        String reader = createRole(unique("READER"), "price.read");
        assertThat(assignRole(as(root), two, reader, 200)).containsEntry("status", "APPLIED");
        assertThat(recorded("sec_bootstrap_retired")).isTrue();

        // The second administrator makes the third one directly: the window is still open...
        assertThat(assignRole(as(one), two, adminRole, 200)).containsEntry("status", "APPLIED");
        // ...and a removal scheduled a year ahead keeps the third one an administrator until then (review L1): the
        // write that schedules it records the end of the window.
        String twoAssignment = String.valueOf(query("SELECT user_role_id::text AS id FROM sec_user_role_version"
            + " WHERE user_id = ?::uuid AND role_id = ?::uuid", two, adminRole).getFirst().get("id"));
        post("/api/datasets/" + SecurityEntities.USER_ROLE_DATASET + "/commit", as(one), Map.of("changes",
            List.of(Map.of("action", "DELETE", "id", twoAssignment, "version", 1, "effectiveTime",
                clock.instant().plus(Duration.ofDays(365)).toString())))).expectStatus().isOk();
        assertThat(recorded("sec_protected_grants_armed")).isTrue();
        assertThat(grants.admins(clock.instant()).block()).contains(root, one, two);
        assertThat(grants.admins(clock.instant().plus(Duration.ofDays(366))).block()).doesNotContain(two);

        Map<String, Object> asked = assignRole(as(one), three, adminRole, 200);
        assertThat(asked).containsEntry("status", "PENDING");
        String request = (String) asked.get("approvalRequestId");

        // The first administrator may not decide it either.
        assertThat(ruleCode(post("/api/processes/APPROVAL_DECIDE/latest", as(root), Map.of("requestId", request,
            "decision", "APPROVE")).expectStatus().isForbidden().expectBody(MAP).returnResult().getResponseBody()))
            .isEqualTo("BOOTSTRAP_ADMIN_RETIRED");
        post("/api/processes/APPROVAL_DECIDE/latest", as(two), Map.of("requestId", request, "decision", "APPROVE"))
            .expectStatus().isOk();
        deliverer.deliverPending().block();
        assertThat(query("SELECT 1 FROM sec_user_role_version WHERE user_id = ?::uuid AND role_id = ?::uuid", three,
            adminRole)).hasSize(1);

        // Taking administrators away reopens neither the window nor the first administrator's powers.
        for (String admin : List.of(one, two, three)) {
            disable(as(one), admin);
        }
        assertThat(assignRole(as(one), four, adminRole, 200)).containsEntry("status", "PENDING");
        assertThat(ruleCode(assignRole(as(root), four, adminRole, 403))).isEqualTo("BOOTSTRAP_ADMIN_RETIRED");
        assertThat(query("SELECT 1 FROM sec_user_role_version WHERE user_id = ?::uuid", four)).isEmpty();
    }
}
