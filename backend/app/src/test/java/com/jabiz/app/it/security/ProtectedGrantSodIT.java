package com.jabiz.app.it.security;

import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ApprovalPermissions;
import com.jabiz.runtime.event.OutboxDeliverer;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decision D51, amendment (review H1), and review M3: published SoD rules and protected changes.
 * <ul>
 *   <li>A security administrator whom an SoD rule forbids to hold approval.decide and control.publish approves a
 *       grant of them anyway: the system keeps them from holding those, so they are not required of them. Everything
 *       else still is - {@code *} above all - and a rule unpublished before the change is applied requires them
 *       again.</li>
 *   <li>Segregation of duties is checked for the permissions a decision actually uses: the level's permission of an
 *       ordinary approval (no longer passed at the entry), and not approval.decide for a protected change it does
 *       not use.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.security.bootstrap-admin.user-name=root",
    "jabiz.security.bootstrap-admin.password=bootstrap password 1"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProtectedGrantSodIT extends ApprovalItSupport {

    private static final String DECIDE = "security.protected-change.decide";
    private static final String[] SECURITY_ADMIN = {"security.user-role.write", "security.role.write", DECIDE};

    private static String apartRule;
    private static String security;
    private static String one;
    private static String two;
    private static String finance;

    @Autowired
    OutboxDeliverer deliverer;

    @SuppressWarnings("unchecked")
    private String bareUser(String prefix) {
        String name = unique(prefix);
        Map<String, Object> result = post("/api/processes/SEC_USER_CREATE/latest", admin(),
            Map.of("userName", name, "displayName", name)).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
        return (String) ((Map<String, Object>) result.get("output")).get("userId");
    }

    private String securityAdmin(String userId, String... more) {
        List<String> all = new java.util.ArrayList<>(List.of(SECURITY_ADMIN));
        all.addAll(List.of(more));
        return TestTokens.bearer(tokens, userId, all.toArray(String[]::new));
    }

    private Map<String, Object> assignRole(String bearer, String user, String role) {
        return run("SEC_USER_ROLE_ASSIGN", bearer, Map.of("userId", user, "roleId", role, "reason", "appoint"));
    }

    private Map<String, Object> decideChange(Object requestId, String bearer, int status) {
        Map<String, Object> input = new LinkedHashMap<>(Map.of("requestId", requestId, "decision", "APPROVE"));
        return post("/api/processes/APPROVAL_DECIDE/latest", bearer, input).expectStatus().isEqualTo(status)
            .expectBody(MAP).returnResult().getResponseBody();
    }

    private static Map<String, Object> change(Object changeId) {
        return query("SELECT status, failure FROM sec_protected_change_version WHERE change_id = ?::uuid"
            + " ORDER BY version_no DESC LIMIT 1", changeId).getFirst();
    }

    private String sodRule(String left, String right) {
        return control(ApprovalEntities.SOD_RULE, null, Map.of("ruleCode", unique("SOD"), "leftPermissions", left,
            "rightPermissions", right, "enabled", true), null);
    }

    @Test
    @Order(1)
    void aSecurityAdministratorAppointsAFinanceAdministratorTheRuleKeepsThemFromBeing() {
        // As QuizBuks: who administers security does not decide approvals nor publish controls.
        apartRule = sodRule("security.user-role.write, security.role.write", "approval.decide, control.publish");
        String root = String.valueOf(query("SELECT user_id::text AS id FROM sec_user_version WHERE user_name ="
            + " 'root'").getFirst().get("id"));
        security = createRole(unique("SECADMIN"), SECURITY_ADMIN);
        finance = createRole(unique("FINADMIN"), "approval.decide", "control.publish");
        one = bareUser("s1");
        two = bareUser("s2");
        assertThat(assignRole(TestTokens.bearer(tokens, root, "*"), one, security)).containsEntry("status", "APPLIED");
        assertThat(assignRole(securityAdmin(one), two, security)).containsEntry("status", "APPLIED");
        createRole(unique("PLAIN"), "price.read");
        assertThat(query("SELECT 1 FROM sec_protected_grants_armed")).hasSize(1);

        String clerk = bareUser("fin");
        Map<String, Object> asked = assignRole(securityAdmin(one), clerk, finance);
        assertThat(asked).containsEntry("status", "PENDING");
        // The facts say why the approver need not hold them.
        assertThat(String.valueOf(query("SELECT facts FROM sys_approval_request_version WHERE request_id = ?::uuid"
            + " LIMIT 1", asked.get("approvalRequestId")).getFirst().get("facts")))
            .contains("cannot hold approval.decide by rule").contains("cannot hold control.publish by rule");
        decideChange(asked.get("approvalRequestId"), securityAdmin(two), 200);
        deliverer.deliverPending().block();
        assertThat(change(asked.get("changeId"))).containsEntry("status", "APPLIED");
        assertThat(query("SELECT 1 FROM sec_user_role_version WHERE user_id = ?::uuid AND role_id = ?::uuid", clerk,
            finance)).hasSize(1);
    }

    @Test
    @Order(2)
    void theWildcardIsNeverExempt() {
        String all = createRole(unique("ALL"), "*");
        Map<String, Object> asked = assignRole(securityAdmin(one), bareUser("root2"), all);
        assertThat(asked).containsEntry("status", "PENDING");
        decideChange(asked.get("approvalRequestId"), securityAdmin(two), 403);
    }

    @Test
    @Order(3)
    void aRuleUnpublishedBeforeTheApplyRequiresCoverageAgain() {
        Map<String, Object> asked = assignRole(securityAdmin(one), bareUser("fin"), finance);
        decideChange(asked.get("approvalRequestId"), securityAdmin(two), 200);
        control(ApprovalEntities.SOD_RULE, apartRule, Map.of("enabled", false), null);
        deliverer.deliverPending().block();
        assertThat(change(asked.get("changeId"))).containsEntry("status", "FAILED")
            .containsEntry("failure", "APPROVER_NOT_ALLOWED");

        // Without the rule, coverage as before: a security administrator does not approve approval.decide.
        Map<String, Object> again = assignRole(securityAdmin(one), bareUser("fin"), finance);
        decideChange(again.get("approvalRequestId"), securityAdmin(two), 403);
    }

    /** Review M3: approval.decide, which a protected change does not use, does not stop its decision. */
    @Test
    @Order(4)
    void aRuleOnApprovalDecideDoesNotStopTheDecisionOfAProtectedChange() {
        String other = unique("it.other");
        sodRule("approval.decide", other);
        Map<String, Object> asked = assignRole(securityAdmin(one), bareUser("s3"), security);
        // two holds both groups of the rule (published after the roles were given), but decides with DECIDE.
        decideChange(asked.get("approvalRequestId"), securityAdmin(two, "approval.decide", other), 200);
        deliverer.deliverPending().block();
        assertThat(change(asked.get("changeId"))).containsEntry("status", "APPLIED");
    }

    /** Review M3: the level's permission of an ordinary approval is checked too (the entry did not). */
    @Test
    @Order(5)
    void aRuleOnTheLevelsPermissionStopsTheDecision() {
        String channel = unique("C");
        String level = unique("it.level");
        String conflicting = unique("it.conflicting");
        rule(channel, 1, List.of(Map.of("permission", level)));
        sodRule(level, conflicting);
        String requestId = (String) pay(preparer(), unique("P"), channel, 10, null).get("requestId");
        assertThat(ruleCode(refused("APPROVAL_DECIDE", as("it-approver", ApprovalPermissions.DECIDE, level,
            conflicting), Map.of("requestId", requestId, "decision", "APPROVE"), 403))).isEqualTo("SOD_CONFLICT");
        assertThat(decide(as("it-approver", ApprovalPermissions.DECIDE, level), requestId, "APPROVE", null))
            .containsEntry("status", "APPROVED");
    }
}
