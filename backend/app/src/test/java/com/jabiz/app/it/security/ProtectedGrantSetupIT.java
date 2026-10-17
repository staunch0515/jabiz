package com.jabiz.app.it.security;

import com.jabiz.runtime.event.OutboxDeliverer;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The setup window and the first administrator (decision D48 item 1; docs/design/10-security.md section 16.3): with
 * only the configured first administrator, protected grants take effect at once (dev, e2e, the demo); once another
 * administrator exists, the first one may no longer make or decide protected changes; once two administrators besides
 * it exist, protected changes need approval, and for good - disabling administrators does not reopen the window.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.security.bootstrap-admin.user-name=root",
    "jabiz.security.bootstrap-admin.password=bootstrap password 1"})
class ProtectedGrantSetupIT extends SecurityItSupport {

    private static final String PASSWORD = "password-123";

    @Autowired
    OutboxDeliverer deliverer;

    private String as(String userId) {
        return TestTokens.bearer(tokens, userId, "*");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> assignRole(String bearer, String user, String role, int status) {
        Map<String, Object> body = post("/api/processes/SEC_USER_ROLE_ASSIGN/latest", bearer,
            Map.of("userId", user, "roleId", role, "reason", "setup")).expectStatus().isEqualTo(status)
            .expectBody(MAP).returnResult().getResponseBody();
        return status == 200 ? (Map<String, Object>) body.get("output") : body;
    }

    private static boolean armed() {
        return !query("SELECT 1 FROM sec_protected_grants_armed").isEmpty();
    }

    @Test
    void theWindowClosesForGoodAndTheFirstAdministratorRetires() {
        String root = String.valueOf(query("SELECT user_id::text AS id FROM sec_user_version WHERE user_name = 'root'")
            .getFirst().get("id"));
        String adminRole = String.valueOf(query("SELECT role_id::text AS id FROM sec_role_version WHERE role_code ="
            + " 'ADMIN'").getFirst().get("id"));
        String one = createUser(unique("a1"), PASSWORD);
        String two = createUser(unique("a2"), PASSWORD);
        String three = createUser(unique("a3"), PASSWORD);
        String four = createUser(unique("a4"), PASSWORD);

        // Alone, the first administrator makes the second one directly.
        assertThat(assignRole(as(root), one, adminRole, 200)).containsEntry("status", "APPLIED");
        assertThat(armed()).isFalse();
        // Now it is retired from protected changes, whatever the path...
        assertThat(ruleCode(assignRole(as(root), two, adminRole, 403))).isEqualTo("BOOTSTRAP_ADMIN_RETIRED");
        assertThat(ruleCode(post("/api/datasets/" + SecurityEntities.USER_ROLE_DATASET + "/commit", as(root),
            Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", Map.of("userId", two,
                "roleId", adminRole))))).expectStatus().isForbidden().expectBody(MAP).returnResult()
            .getResponseBody())).isEqualTo("BOOTSTRAP_ADMIN_RETIRED");
        // ...but not from the rest.
        String reader = createRole(unique("READER"), "price.read");
        assertThat(assignRole(as(root), two, reader, 200)).containsEntry("status", "APPLIED");

        // The second administrator makes the third one directly: the window is still open...
        assertThat(assignRole(as(one), two, adminRole, 200)).containsEntry("status", "APPLIED");
        // ...and closes with the next protected change, which waits.
        Map<String, Object> asked = assignRole(as(one), three, adminRole, 200);
        assertThat(asked).containsEntry("status", "PENDING");
        assertThat(armed()).isTrue();
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

        // Taking administrators away does not reopen the window.
        for (String admin : List.of(two, three)) {
            Map<String, Object> current = query("SELECT version_no FROM sec_user_version WHERE user_id = ?::uuid"
                + " ORDER BY version_no DESC LIMIT 1", admin).getFirst();
            Map<String, Object> change = new LinkedHashMap<>(Map.of("action", "UPDATE", "id", admin,
                "version", current.get("version_no"), "attributes", Map.of("enabled", false)));
            post("/api/datasets/" + SecurityEntities.USER_DATASET + "/commit", as(one), Map.of("changes",
                List.of(change))).expectStatus().isOk();
        }
        assertThat(assignRole(as(one), four, adminRole, 200)).containsEntry("status", "PENDING");
        assertThat(query("SELECT 1 FROM sec_user_role_version WHERE user_id = ?::uuid", four)).isEmpty();
    }
}
