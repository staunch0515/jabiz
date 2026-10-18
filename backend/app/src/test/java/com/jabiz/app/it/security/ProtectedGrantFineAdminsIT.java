package com.jabiz.app.it.security;

import com.jabiz.runtime.event.OutboxDeliverer;
import com.jabiz.runtime.security.protect.ProtectedGrants;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decision D51: administrators managed with fine-grained permissions instead of {@code *}. Holding
 * {@code security.user-role.write} or {@code security.role.write} makes an administrator, so two of them besides the
 * first administrator end the setup window and the first one retires once one exists; a protected change is decided
 * with {@code security.protected-change.decide} (which is protected itself) and an administrator's permission covering
 * what the change gives, never with {@code approval.decide}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.security.bootstrap-admin.user-name=root",
    "jabiz.security.bootstrap-admin.password=bootstrap password 1"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProtectedGrantFineAdminsIT extends SecurityItSupport {

    private static final String DECIDE = "security.protected-change.decide";
    /** What a security administrator holds - and what an SoD rule may keep apart from approval.decide. */
    private static final String[] SECURITY_ADMIN = {"security.user-role.write", "security.role.write", DECIDE};

    @Autowired
    OutboxDeliverer deliverer;

    @Autowired
    ProtectedGrants grants;

    /** A user without a password: credentials nobody else set. */
    @SuppressWarnings("unchecked")
    private String bareUser(String prefix) {
        String name = unique(prefix);
        Map<String, Object> result = post("/api/processes/SEC_USER_CREATE/latest", admin(),
            Map.of("userName", name, "displayName", name)).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
        return (String) ((Map<String, Object>) result.get("output")).get("userId");
    }

    private String securityAdmin(String userId) {
        return TestTokens.bearer(tokens, userId, SECURITY_ADMIN);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(String process, String bearer, Map<String, Object> input, int status) {
        Map<String, Object> body = post("/api/processes/" + process + "/latest", bearer, input).expectStatus()
            .isEqualTo(status).expectBody(MAP).returnResult().getResponseBody();
        return status == 200 ? (Map<String, Object>) body.get("output") : body;
    }

    private Map<String, Object> assignRole(String bearer, String user, String role, int status) {
        return run("SEC_USER_ROLE_ASSIGN", bearer, Map.of("userId", user, "roleId", role, "reason", "setup"), status);
    }

    private Map<String, Object> decide(Object requestId, String bearer, String decision, int status) {
        Map<String, Object> input = new LinkedHashMap<>(Map.of("requestId", requestId, "decision", decision));
        if (decision.equals("REJECT")) {
            input.put("reason", "no");
        }
        return post("/api/processes/APPROVAL_DECIDE/latest", bearer, input).expectStatus().isEqualTo(status)
            .expectBody(MAP).returnResult().getResponseBody();
    }

    private static Map<String, Object> change(Object changeId) {
        return query("SELECT status, gained, failure FROM sec_protected_change_version WHERE change_id = ?::uuid"
            + " ORDER BY version_no DESC LIMIT 1", changeId).getFirst();
    }

    private static boolean recorded(String table) {
        return !query("SELECT 1 FROM " + table).isEmpty();
    }

    private static boolean assigned(String user, String role) {
        return !query("SELECT 1 FROM sec_user_role_version WHERE user_id = ?::uuid AND role_id = ?::uuid", user, role)
            .isEmpty();
    }

    @Test
    @Order(1)
    void securityAdministratorsWithoutTheWildcardEndTheWindowAndDecideWithTheirOwnPermission() {
        String root = String.valueOf(query("SELECT user_id::text AS id FROM sec_user_version WHERE user_name = 'root'")
            .getFirst().get("id"));
        String security = createRole(unique("SECADMIN"), SECURITY_ADMIN);
        String one = bareUser("s1");
        String two = bareUser("s2");
        String three = bareUser("s3");

        // Alone, the first administrator makes the first security administrator directly...
        assertThat(assignRole(TestTokens.bearer(tokens, root, "*"), one, security, 200))
            .containsEntry("status", "APPLIED");
        assertThat(grants.admins(clock.instant()).block()).contains(root, one);
        // ...who is an administrator by its permissions: the first administrator retires for good.
        assertThat(ruleCode(assignRole(TestTokens.bearer(tokens, root, "*"), two, security, 403)))
            .isEqualTo("BOOTSTRAP_ADMIN_RETIRED");
        // The refused write rolled back; the next one records the retirement.
        createRole(unique("PLAIN"), "price.read");
        assertThat(recorded("sec_bootstrap_retired")).isTrue();
        assertThat(recorded("sec_protected_grants_armed")).isFalse();

        // The first security administrator makes the second one directly: the window is still open; the next
        // write records its end.
        assertThat(assignRole(securityAdmin(one), two, security, 200)).containsEntry("status", "APPLIED");
        createRole(unique("PLAIN"), "price.read");
        assertThat(recorded("sec_protected_grants_armed")).isTrue();

        // The third needs approval. What it gives includes the permission to decide: that is protected itself.
        Map<String, Object> asked = assignRole(securityAdmin(one), three, security, 200);
        assertThat(asked).containsEntry("status", "PENDING");
        assertThat(String.valueOf(change(asked.get("changeId")).get("gained"))).contains(DECIDE)
            .contains("security.user-role.write");
        Object request = asked.get("approvalRequestId");

        // Not by the requester, not by the user it is for, not with approval.decide instead of the permission to
        // decide, not without a recent second factor.
        assertThat(ruleCode(decide(request, securityAdmin(one), "APPROVE", 422))).isEqualTo("APPROVAL_OWN_REQUEST");
        assertThat(ruleCode(decide(request, securityAdmin(three), "APPROVE", 422)))
            .isEqualTo("APPROVAL_TARGET_USER");
        decide(request, TestTokens.bearer(tokens, two, "approval.decide", "security.user-role.write",
            "security.role.write"), "APPROVE", 403);
        decide(request, TestTokens.withoutMfa(tokens, two, SECURITY_ADMIN), "APPROVE", 403);
        deliverer.deliverPending().block();
        assertThat(assigned(three, security)).isFalse();

        // The other security administrator approves: it holds everything the change gives.
        decide(request, securityAdmin(two), "APPROVE", 200);
        deliverer.deliverPending().block();
        assertThat(change(asked.get("changeId"))).containsEntry("status", "APPLIED");
        assertThat(assigned(three, security)).isTrue();

        // Coverage still applies: a security administrator does not approve ledger.post it does not hold.
        String clerk = bareUser("clerk");
        String poster = createRole(unique("POSTER"), "ledger.post");
        Map<String, Object> posting = assignRole(securityAdmin(one), clerk, poster, 200);
        assertThat(posting).containsEntry("status", "PENDING");
        decide(posting.get("approvalRequestId"), securityAdmin(two), "APPROVE", 403);
        decide(posting.get("approvalRequestId"), TestTokens.bearer(tokens, two, DECIDE, "security.user-role.write",
            "ledger.post"), "APPROVE", 200);
        deliverer.deliverPending().block();
        // At apply time two does not hold ledger.post in fact: not applied.
        assertThat(change(posting.get("changeId"))).containsEntry("status", "FAILED")
            .containsEntry("failure", "APPROVER_NOT_ALLOWED");
        assertThat(assigned(clerk, poster)).isFalse();
    }

    @Test
    @Order(2)
    void thePermissionToDecideIsGivenOnlyWithApproval() {
        // After the first test: the window has ended.
        assertThat(recorded("sec_protected_grants_armed")).isTrue();

        // A role giving only the permission to decide.
        String deciders = createRole(unique("DECIDERS"), DECIDE);
        String four = bareUser("d4");
        Map<String, Object> asked = assignRole(admin(), four, deciders, 200);
        assertThat(asked).containsEntry("status", "PENDING");
        assertThat(change(asked.get("changeId"))).containsEntry("gained", DECIDE);
        assertThat(assigned(four, deciders)).isFalse();

        // Adding it to a role whose users lack it.
        String readers = createRole(unique("READERS"), "price.read");
        String five = bareUser("d5");
        assertThat(assignRole(admin(), five, readers, 200)).containsEntry("status", "APPLIED");
        Map<String, Object> added = run("SEC_ROLE_PERMISSION_ADD", admin(), Map.of("roleId", readers,
            "permission", DECIDE, "reason", "deciders"), 200);
        assertThat(added).containsEntry("status", "PENDING");
        assertThat(query("SELECT 1 FROM sec_role_permission_version WHERE role_id = ?::uuid AND permission = ?",
            readers, DECIDE)).isEmpty();
    }
}
