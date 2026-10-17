package com.jabiz.app.it.security;

import com.jabiz.runtime.event.OutboxDeliverer;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.test.TestTokens;
import com.jabiz.security.Base32;
import com.jabiz.security.Totp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Four eyes on protected grants (decision D48 item 1; docs/design/10-security.md section 16), after the setup window:
 * two administrators ({@code *}) exist besides the test's own token. Giving a user a protected permission through a
 * role or a role's new permission, and resetting the password, second factor or identity link of a holder of one, wait
 * for another administrator's approval; the requester and the users it is for cannot approve; a rejection has no
 * effect; every other write path is refused; unprotected grants are as before.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ProtectedGrantIT extends SecurityItSupport {

    private static final String PASSWORD = "password-123";

    /** Shared by the tests of the class: one schema, set up once. */
    private static String adminOne;
    private static String adminTwo;
    private static String holder;
    private static String holderName;
    private static String holderSecret;

    @Autowired
    OutboxDeliverer deliverer;

    public record GrantBothInput(java.util.UUID userId, java.util.UUID roleId, boolean permissionFirst) {}

    /** A business process granting a role a protected permission and assigning it in one change set. */
    @org.springframework.boot.test.context.TestConfiguration
    static class GrantBoth {
        @org.springframework.context.annotation.Bean
        com.jabiz.process.ProcessDefinition<GrantBothInput, GrantBothInput, com.jabiz.process.ProcessContext>
            itGrantBothProcess() {
            return com.jabiz.process.ProcessDefinition.define("IT_GRANT_BOTH", 1, GrantBothInput.class,
                GrantBothInput.class, com.jabiz.process.ProcessContext.class, pb -> pb
                    .permissions("it.grant")
                    .contextFactory((start, input) -> {
                        com.jabiz.process.ProcessContext ctx = new com.jabiz.process.ProcessContext(start);
                        ctx.put("input", input);
                        return ctx;
                    })
                    .outputMapper(ctx -> ctx.get("input", GrantBothInput.class))
                    .compute("Grant and assign", (metadata, ctx) -> {
                        GrantBothInput input = ctx.get("input", GrantBothInput.class);
                        Runnable permission = () -> ctx.changes().insert(SecurityEntities.ROLE_PERMISSION,
                            Map.of("roleId", input.roleId(), "permission", "approval.decide"));
                        Runnable assignment = () -> ctx.changes().insert(SecurityEntities.USER_ROLE,
                            Map.of("userId", input.userId(), "roleId", input.roleId()));
                        (input.permissionFirst() ? permission : assignment).run();
                        (input.permissionFirst() ? assignment : permission).run();
                    }));
        }
    }

    @BeforeEach
    void administrators() {
        if (adminOne != null) {
            return;
        }
        // During the setup window everything takes effect at once: a holder of ledger.post and two administrators.
        holderName = unique("holder");
        holder = userWith(holderName, PASSWORD, "ledger.post");
        String admins = createRole(unique("ADMINS"), "*");
        adminOne = createUser(unique("admin1"), PASSWORD);
        assign(adminOne, admins, null);
        adminTwo = createUser(unique("admin2"), PASSWORD);
        assign(adminTwo, admins, null);
        // The holder's second factor, for the reset below.
        String bearer = bearerOf(signIn(holderName, PASSWORD));
        holderSecret = (String) post("/api/auth/mfa/enroll", bearer, Map.of()).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody().get("secret");
        post("/api/auth/mfa/enroll/confirm", bearer, Map.of("code",
            Totp.code(Base32.decode(holderSecret), Totp.step(clock.instant())))).expectStatus().isOk();
        // Any security write now ends the setup window.
        createRole(unique("PLAIN"), "price.read");
        assertThat(query("SELECT count(*) AS n FROM sec_protected_grants_armed").getFirst().get("n")).isEqualTo(1L);
    }

    private String as(String userId) {
        return TestTokens.bearer(tokens, userId, "*");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(String process, String bearer, Map<String, Object> input) {
        return (Map<String, Object>) post("/api/processes/" + process + "/latest", bearer, input).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody().get("output");
    }

    private Map<String, Object> decide(String requestId, String bearer, String decision, int status) {
        Map<String, Object> input = new LinkedHashMap<>(Map.of("requestId", requestId, "decision", decision));
        if (decision.equals("REJECT")) {
            input.put("reason", "not now");
        }
        return post("/api/processes/APPROVAL_DECIDE/latest", bearer, input).expectStatus().isEqualTo(status)
            .expectBody(MAP).returnResult().getResponseBody();
    }

    private static List<Map<String, Object>> assignments(String userId, String roleId) {
        return query("SELECT DISTINCT ON (user_role_id) is_deleted FROM sec_user_role_version WHERE user_id = ?::uuid"
            + " AND role_id = ?::uuid ORDER BY user_role_id, version_no DESC", userId, roleId);
    }

    private static Map<String, Object> change(String changeId) {
        return query("SELECT status, decided_by, failure, result_id, kind FROM sec_protected_change_version"
            + " WHERE change_id = ?::uuid ORDER BY version_no DESC LIMIT 1", changeId).getFirst();
    }

    @Test
    void aRoleGivingAProtectedPermissionWaitsForAnotherAdministrator() {
        String user = createUser(unique("clerk"), PASSWORD);
        String role = createRole(unique("POSTER"), "ledger.post", "ledger.read");
        Map<String, Object> asked = run("SEC_USER_ROLE_ASSIGN", as(adminOne),
            Map.of("userId", user, "roleId", role, "reason", "month end"));
        assertThat(asked).containsEntry("status", "PENDING").containsEntry("approval", "PENDING");
        String requestId = (String) asked.get("approvalRequestId");
        String changeId = (String) asked.get("changeId");
        // Pending: no effect.
        assertThat(assignments(user, role)).isEmpty();
        assertThat(change(changeId)).containsEntry("status", "PENDING").containsEntry("kind", "USER_ROLE");
        // The approvers see it among their tasks.
        assertThat(query("SELECT title_params FROM sys_task_version WHERE source_key = ?", "approval:" + requestId))
            .isNotEmpty();

        // Not by the requester, not by the user it is for.
        assertThat(ruleCode(decide(requestId, as(adminOne), "APPROVE", 422))).isEqualTo("APPROVAL_OWN_REQUEST");
        assertThat(ruleCode(decide(requestId, as(user), "APPROVE", 422))).isEqualTo("APPROVAL_TARGET_USER");
        // A decision needs a recent second factor.
        decide(requestId, TestTokens.withoutMfa(tokens, adminTwo, "*"), "APPROVE", 403);
        deliverer.deliverPending().block();
        assertThat(assignments(user, role)).isEmpty();

        assertThat(decide(requestId, as(adminTwo), "APPROVE", 200).get("output")).isNotNull();
        deliverer.deliverPending().block();
        assertThat(assignments(user, role)).singleElement().satisfies(row ->
            assertThat(row.get("is_deleted")).isEqualTo(false));
        assertThat(change(changeId)).containsEntry("status", "APPLIED").containsEntry("decided_by", adminTwo);
        // Once: the event delivered again changes nothing.
        deliverer.deliverPending().block();
        assertThat(assignments(user, role)).hasSize(1);
    }

    @Test
    void aRejectionHasNoEffect() {
        String user = createUser(unique("clerk"), PASSWORD);
        String role = createRole(unique("APPROVER"), "approval.decide");
        Map<String, Object> asked = run("SEC_USER_ROLE_ASSIGN", as(adminOne),
            Map.of("userId", user, "roleId", role, "reason", "cover"));
        decide((String) asked.get("approvalRequestId"), as(adminTwo), "REJECT", 200);
        deliverer.deliverPending().block();
        assertThat(assignments(user, role)).isEmpty();
        assertThat(change((String) asked.get("changeId"))).containsEntry("status", "REJECTED");
    }

    @Test
    void aProtectedPermissionForARoleWithHoldersWaitsAndItsHoldersCannotApprove() {
        String user = userWith(unique("clerk"), PASSWORD, "price.read");
        String role = String.valueOf(query("SELECT role_id::text AS id FROM sec_user_role_version WHERE user_id ="
            + " ?::uuid", user).getFirst().get("id"));
        Map<String, Object> asked = run("SEC_ROLE_PERMISSION_ADD", as(adminOne),
            Map.of("roleId", role, "permission", "platform.param.write", "reason", "pricing"));
        assertThat(asked).containsEntry("status", "PENDING");
        assertThat(query("SELECT 1 FROM sec_role_permission_version WHERE role_id = ?::uuid AND permission = ?",
            role, "platform.param.write")).isEmpty();
        assertThat(ruleCode(decide((String) asked.get("approvalRequestId"), as(user), "APPROVE", 422)))
            .isEqualTo("APPROVAL_TARGET_USER");
        decide((String) asked.get("approvalRequestId"), as(adminTwo), "APPROVE", 200);
        deliverer.deliverPending().block();
        assertThat(query("SELECT 1 FROM sec_role_permission_version WHERE role_id = ?::uuid AND permission = ?",
            role, "platform.param.write")).hasSize(1);

        // A role nobody holds gets one at once: assigning it is what needs the approval.
        String empty = createRole(unique("EMPTY"));
        assertThat(run("SEC_ROLE_PERMISSION_ADD", as(adminOne), Map.of("roleId", empty, "permission", "ledger.post",
            "reason", "later"))).containsEntry("status", "APPLIED");
    }

    @Test
    void thePasswordOfAProtectedHolderIsResetOnlyAfterApproval() {
        Map<String, Object> asked = run("SEC_USER_SET_PASSWORD", as(adminOne),
            Map.of("userId", holder, "password", "new-password-456"));
        assertThat(asked).containsEntry("status", "PENDING").containsEntry("userId", holder);
        // The old password still works; the holder's sessions stay.
        signIn(holderName, PASSWORD);
        assertThat(ruleCode(decide((String) asked.get("approvalRequestId"), as(holder), "APPROVE", 422)))
            .isEqualTo("APPROVAL_TARGET_USER");
        decide((String) asked.get("approvalRequestId"), as(adminTwo), "APPROVE", 200);
        deliverer.deliverPending().block();
        login(holderName, PASSWORD).expectStatus().isUnauthorized();
        // The new password is the one (the second step follows when the second factor is still there).
        login(holderName, "new-password-456").expectStatus().isOk();
        // The new hash waits in a sensitive field: the dataset does not return it.
        Map<String, Object> listed = post("/api/datasets/urn:jabiz:dataset:platform:SecProtectedChange/query",
            admin(), Map.of()).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(String.valueOf(listed)).contains(String.valueOf(asked.get("changeId")))
            .doesNotContain("passwordHash").doesNotContain("$2");

        // An ordinary user's password is set at once, as before.
        String plain = userWith(unique("plain"), PASSWORD, "price.read");
        assertThat(run("SEC_USER_SET_PASSWORD", as(adminOne), Map.of("userId", plain, "password", "other-pass-789")))
            .containsEntry("status", "APPLIED");
    }

    @Test
    void theSecondFactorOfAProtectedHolderIsResetOnlyAfterApproval() {
        Map<String, Object> asked = run("SEC_MFA_RESET", as(adminOne), Map.of("userId", holder, "reason", "phone lost"));
        assertThat(asked).containsEntry("status", "PENDING");
        assertThat(query("SELECT DISTINCT ON (user_mfa_id) is_deleted FROM sec_user_mfa_version WHERE user_id ="
            + " ?::uuid ORDER BY user_mfa_id, version_no DESC", holder)).extracting(row -> row.get("is_deleted"))
            .containsExactly(false);
        decide((String) asked.get("approvalRequestId"), as(adminTwo), "REJECT", 200);
        deliverer.deliverPending().block();
        assertThat(query("SELECT DISTINCT ON (user_mfa_id) is_deleted FROM sec_user_mfa_version WHERE user_id ="
            + " ?::uuid ORDER BY user_mfa_id, version_no DESC", holder)).extracting(row -> row.get("is_deleted"))
            .containsExactly(false);

        Map<String, Object> again = run("SEC_MFA_RESET", as(adminOne), Map.of("userId", holder, "reason", "phone lost"));
        decide((String) again.get("approvalRequestId"), as(adminTwo), "APPROVE", 200);
        deliverer.deliverPending().block();
        assertThat(query("SELECT DISTINCT ON (user_mfa_id) is_deleted FROM sec_user_mfa_version WHERE user_id ="
            + " ?::uuid ORDER BY user_mfa_id, version_no DESC", holder)).extracting(row -> row.get("is_deleted"))
            .containsExactly(true);
        assertThat(change((String) again.get("changeId"))).containsEntry("status", "APPLIED");
    }

    @Test
    void anIdentityLinkForAProtectedHolderWaitsAndTheDatasetRefusesIt() {
        String subject = unique("sub");
        Map<String, Object> refused = post("/api/datasets/" + SecurityEntities.USER_IDENTITY_DATASET + "/commit",
            as(adminOne), Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", Map.of("userId", adminTwo,
                "provider", "test", "subject", subject))))).expectStatus().isEqualTo(422).expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(ruleCode(refused)).isEqualTo("PROTECTED_CHANGE_REQUIRES_APPROVAL");
        Map<String, Object> asked = run("SEC_USER_IDENTITY_LINK", as(adminOne), Map.of("userId", adminTwo,
            "provider", "test", "subject", subject, "reason", "sso"));
        assertThat(asked).containsEntry("status", "PENDING");
        assertThat(ruleCode(decide((String) asked.get("approvalRequestId"), as(adminTwo), "APPROVE", 422)))
            .isEqualTo("APPROVAL_TARGET_USER");
        decide((String) asked.get("approvalRequestId"), as(holderLess()), "APPROVE", 200);
        deliverer.deliverPending().block();
        assertThat(query("SELECT 1 FROM sec_user_identity_version WHERE subject = ?", subject)).hasSize(1);
    }

    /** An approver who is not a user (the tests' token holder). */
    private static String holderLess() {
        return "it-approver";
    }

    @Test
    void everyOtherWritePathIsRefused() {
        String user = createUser(unique("clerk"), PASSWORD);
        String role = createRole(unique("POSTER"), "ledger.post");
        Map<String, Object> grant = Map.of("userId", user, "roleId", role);
        // The dataset API, as any administrator would, even holding everything.
        Map<String, Object> viaDataset = post("/api/datasets/" + SecurityEntities.USER_ROLE_DATASET + "/commit",
            admin(), Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", grant))))
            .expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCode(viaDataset)).isEqualTo("PROTECTED_CHANGE_REQUIRES_APPROVAL");
        assertThat(String.valueOf(violations(viaDataset).getFirst().get("message"))).contains("SEC_USER_ROLE_ASSIGN");
        // The entity API and the generic entity process.
        post("/api/entities/SecUserRole", admin(), grant).expectStatus().isEqualTo(422);
        post("/api/processes/ADD_ENTITY/latest", admin(), Map.of("entityType", "SecUserRole", "attributes", grant))
            .expectStatus().is4xxClientError();
        // A role's new permission while users hold it.
        String plain = userWith(unique("plain"), PASSWORD, "price.read");
        String plainRole = String.valueOf(query("SELECT role_id::text AS id FROM sec_user_role_version WHERE user_id"
            + " = ?::uuid", plain).getFirst().get("id"));
        assertThat(ruleCode(post("/api/datasets/" + SecurityEntities.ROLE_PERMISSION_DATASET + "/commit", admin(),
            Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", Map.of("roleId", plainRole,
                "permission", "*"))))).expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody()))
            .isEqualTo("PROTECTED_CHANGE_REQUIRES_APPROVAL");
        // Both halves in one change set of a business process, in either order: each check sees what the
        // transaction wrote before it.
        for (boolean permissionFirst : List.of(true, false)) {
            String empty = createRole(unique("EMPTY"));
            assertThat(ruleCode(post("/api/processes/IT_GRANT_BOTH/latest", admin(), Map.of("userId", user,
                "roleId", empty, "permissionFirst", permissionFirst)).expectStatus().isEqualTo(422).expectBody(MAP)
                .returnResult().getResponseBody())).isEqualTo("PROTECTED_CHANGE_REQUIRES_APPROVAL");
            assertThat(assignments(user, empty)).isEmpty();
        }
        // A scheduled grant is a grant.
        post("/api/datasets/" + SecurityEntities.USER_ROLE_DATASET + "/commit", admin(), Map.of("changes",
            List.of(Map.of("action", "INSERT", "attributes", grant, "effectiveTime",
                clock.instant().plus(Duration.ofDays(3)).toString())))).expectStatus().isEqualTo(422);
        assertThat(assignments(user, role)).isEmpty();
        // Changes are written by their processes only.
        post("/api/datasets/urn:jabiz:dataset:platform:SecProtectedChange/commit", admin(), Map.of("changes",
            List.of(Map.of("action", "INSERT", "attributes", Map.of("kind", "USER_ROLE", "userId", user,
                "roleId", role, "reason", "x", "requestedBy", "x", "status", "PENDING")))))
            .expectStatus().is4xxClientError();
        // The apply process is internal.
        post("/api/processes/SEC_PROTECTED_CHANGE_APPLY/latest", admin(), Map.of("subject",
            "jabiz.security.protected-change", "entityId", user, "status", "APPROVED")).expectStatus().isForbidden();
    }

    @Test
    void revertingTheRemovalOfAProtectedAssignmentIsRefused() {
        String user = createUser(unique("clerk"), PASSWORD);
        String role = createRole(unique("POSTER"), "ledger.post");
        Map<String, Object> asked = run("SEC_USER_ROLE_ASSIGN", as(adminOne),
            Map.of("userId", user, "roleId", role, "reason", "r"));
        decide((String) asked.get("approvalRequestId"), as(adminTwo), "APPROVE", 200);
        deliverer.deliverPending().block();
        String assignment = String.valueOf(query("SELECT user_role_id::text AS id FROM sec_user_role_version WHERE"
            + " user_id = ?::uuid", user).getFirst().get("id"));
        post("/api/datasets/" + SecurityEntities.USER_ROLE_DATASET + "/commit", admin(), Map.of("changes",
            List.of(Map.of("action", "DELETE", "id", assignment, "version", 1)))).expectStatus().isOk();
        Object deletion = query("SELECT process_seq_id FROM sec_user_role_version WHERE user_role_id = ?::uuid AND"
            + " version_no = 2", assignment).getFirst().get("process_seq_id");
        Map<String, Object> refused = post("/api/processes/executions/" + deletion + "/revert", admin(),
            Map.of("reason", "undo")).expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCode(refused)).isEqualTo("PROTECTED_CHANGE_REQUIRES_APPROVAL");
    }

    @Test
    void unprotectedGrantsAreAsBefore() {
        String user = createUser(unique("clerk"), PASSWORD);
        String role = createRole(unique("READER"), "price.read", "ledger.read");
        assign(user, role, null);
        String other = createRole(unique("READER2"), "price.read");
        assertThat(run("SEC_USER_ROLE_ASSIGN", as(adminOne), Map.of("userId", user, "roleId", other,
            "reason", "r"))).containsEntry("status", "APPLIED");
        insert(SecurityEntities.ROLE_PERMISSION_DATASET, Map.of("roleId", role, "permission", "report.issue"), null);
        // A protected permission the user already holds through another role is no gain.
        String poster = createRole(unique("POSTER"), "ledger.post");
        String alsoPoster = createRole(unique("POSTER2"), "ledger.post");
        Map<String, Object> first = run("SEC_USER_ROLE_ASSIGN", as(adminOne), Map.of("userId", user, "roleId", poster,
            "reason", "r"));
        decide((String) first.get("approvalRequestId"), as(adminTwo), "APPROVE", 200);
        deliverer.deliverPending().block();
        assertThat(run("SEC_USER_ROLE_ASSIGN", as(adminOne), Map.of("userId", user, "roleId", alsoPoster,
            "reason", "r"))).containsEntry("status", "APPLIED");
        // The user changing nothing protected: new roles for an administrator who holds everything.
        assertThat(run("SEC_USER_ROLE_ASSIGN", as(adminOne), Map.of("userId", adminTwo, "roleId", poster,
            "reason", "r"))).containsEntry("status", "APPLIED");
    }
}
