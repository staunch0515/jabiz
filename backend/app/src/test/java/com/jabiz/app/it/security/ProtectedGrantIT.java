package com.jabiz.app.it.security;

import com.jabiz.imports.ImportDefinition;
import com.jabiz.imports.ImportFormat;
import com.jabiz.entity.SemanticKind;
import com.jabiz.process.BlockingStep;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.context.RequestContext;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.event.OutboxDeliverer;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.security.protect.ProtectedChangeEntities;
import com.jabiz.runtime.security.protect.ProtectedChangeProcesses;
import com.jabiz.runtime.process.steps.SaveChanges;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Four eyes on protected grants (decision D48 item 1; docs/design/10-security.md section 16), after the setup window:
 * three administrators ({@code *}) exist besides the test's own token. Giving a user a protected permission through a
 * role or a role's new permission, and resetting the password, second factor or identity link of a holder of one, wait
 * for another administrator's approval, which binds what the change gives and to whom; the requester, the users it
 * is for and administrators holding less than it gives cannot approve; a rejection has no effect; a grant to a user
 * whose credentials someone else set is not applied; every other write path is refused, serialized with the other
 * changes of access; unprotected grants are as before.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "jabiz.security.sign-up.rate-limit.per-address=10000")
class ProtectedGrantIT extends SecurityItSupport {

    private static final String PASSWORD = "password-123";
    /** Decides protected changes instead of approval.decide (decision D51). */
    private static final String DECIDE = "security.protected-change.decide";
    private static final String OWN_PASSWORD = "own-password-456";

    /** Shared by the tests of the class: one schema, set up once. */
    private static String adminOne;
    private static String adminTwo;
    private static String adminThree;
    private static String holder;
    private static String holderName;

    static volatile CountDownLatch arrived = new CountDownLatch(1);
    static volatile CountDownLatch go = new CountDownLatch(1);

    @Autowired
    OutboxDeliverer deliverer;
    @Autowired
    ProcessExecutor executor;

    public record GrantBothInput(UUID userId, UUID roleId, boolean permissionFirst) {}

    public record RoleInput(UUID roleId) {}

    public record RowInput(String userId, String roleId) {}

    /** Waits, inside its transaction, until the test lets it go. */
    public static class Hold implements BlockingStep<NoMetadata, ProcessContext> {
        @Override
        public void run(NoMetadata metadata, ProcessContext ctx) throws InterruptedException {
            arrived.countDown();
            go.await(30, TimeUnit.SECONDS);
        }
    }

    /** Business processes and an import that write grants through their change sets. */
    @TestConfiguration
    static class Grants {
        @Bean
        ProcessDefinition<GrantBothInput, GrantBothInput, ProcessContext> itGrantBothProcess() {
            return ProcessDefinition.define("IT_GRANT_BOTH", 1, GrantBothInput.class, GrantBothInput.class,
                ProcessContext.class, pb -> pb
                    .permissions("it.grant")
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
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

        @Bean
        Hold itGrantHold() {
            return new Hold();
        }

        /** Gives a role {@code *}, saves, and holds its transaction open until the test lets it go. */
        @Bean
        ProcessDefinition<RoleInput, RoleInput, ProcessContext> itSlowGrantProcess() {
            return ProcessDefinition.define("IT_SLOW_GRANT", 1, RoleInput.class, RoleInput.class,
                ProcessContext.class, pb -> pb.permissions("it.grant")
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put("input", input);
                        return ctx;
                    })
                    .outputMapper(ctx -> ctx.get("input", RoleInput.class))
                    .compute("Grant", (metadata, ctx) -> ctx.changes().insert(SecurityEntities.ROLE_PERMISSION,
                        Map.of("roleId", ctx.get("input", RoleInput.class).roleId(), "permission", "*")))
                    .step("Save", SaveChanges.now())
                    .step("Hold", Hold.class, NoMetadata.INSTANCE));
        }

        @Bean
        ProcessDefinition<RowInput, RowInput, ProcessContext> itGrantRowProcess() {
            return ProcessDefinition.define("IT_GRANT_ROW", 1, RowInput.class, RowInput.class,
                ProcessContext.class, pb -> pb.permissions("it.grant")
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put("input", input);
                        return ctx;
                    })
                    .outputMapper(ctx -> ctx.get("input", RowInput.class))
                    .compute("Assign", (metadata, ctx) -> ctx.changes().insert(SecurityEntities.USER_ROLE,
                        Map.of("userId", UUID.fromString(ctx.get("input", RowInput.class).userId()),
                            "roleId", UUID.fromString(ctx.get("input", RowInput.class).roleId())))));
        }

        @Bean
        ImportDefinition<ImportDefinition.NoParams> itGrantImport() {
            return ImportDefinition.define("it.grants", 1)
                .file("app.import", ImportFormat.csv())
                .field("user", new SemanticKind.Text(36, false), true, "User")
                .field("role", new SemanticKind.Text(36, false), true, "Role")
                .perRow("IT_GRANT_ROW", 1, (row, params) -> new RowInput(row.text("user"), row.text("role")))
                .permissions("it.grant")
                .build();
        }
    }

    @BeforeEach
    void administrators() {
        if (adminOne != null) {
            return;
        }
        // During the setup window everything takes effect at once: a holder of ledger.post, and three
        // administrators (a role that gets * once they hold it, so that nobody is the second or third one alone).
        holderName = unique("holder");
        holder = userWith(holderName, PASSWORD, "ledger.post");
        String admins = createRole(unique("ADMINS"));
        adminOne = bareUser("admin1");
        adminTwo = bareUser("admin2");
        adminThree = bareUser("admin3");
        for (String admin : List.of(adminOne, adminTwo, adminThree)) {
            assign(admin, admins, null);
        }
        insert(SecurityEntities.ROLE_PERMISSION_DATASET, Map.of("roleId", admins, "permission", "*"), null);
        // The holder's second factor, for the reset below.
        String bearer = bearerOf(signIn(holderName, PASSWORD));
        String secret = (String) post("/api/auth/mfa/enroll", bearer, Map.of()).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody().get("secret");
        post("/api/auth/mfa/enroll/confirm", bearer, Map.of("code",
            com.jabiz.security.Totp.code(com.jabiz.security.Base32.decode(secret),
                com.jabiz.security.Totp.step(clock.instant())))).expectStatus().isOk();
        // Any security write now ends the setup window.
        createRole(unique("PLAIN"), "price.read");
        assertThat(query("SELECT count(*) AS n FROM sec_protected_grants_armed").getFirst().get("n")).isEqualTo(1L);
    }

    // ================= helpers =================

    /** A user without a password (an identity provider's user): credentials nobody else set. */
    @SuppressWarnings("unchecked")
    private String bareUser(String prefix) {
        String name = unique(prefix);
        Map<String, Object> result = post("/api/processes/SEC_USER_CREATE/latest", admin(),
            Map.of("userName", name, "displayName", name)).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
        return (String) ((Map<String, Object>) result.get("output")).get("userId");
    }

    /** A bare user holding a role with the given (unprotected) permissions; returns the user id. */
    private String bareUserWith(String... permissions) {
        String user = bareUser("clerk");
        assign(user, createRole(unique("ROLE"), permissions), null);
        return user;
    }

    private String as(String userId) {
        return TestTokens.bearer(tokens, userId, "*");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(String process, String bearer, Map<String, Object> input) {
        return (Map<String, Object>) post("/api/processes/" + process + "/latest", bearer, input).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody().get("output");
    }

    private Map<String, Object> decide(Object requestId, String bearer, String decision, int status) {
        Map<String, Object> input = new LinkedHashMap<>(Map.of("requestId", requestId, "decision", decision));
        if (decision.equals("REJECT")) {
            input.put("reason", "not now");
        }
        return post("/api/processes/APPROVAL_DECIDE/latest", bearer, input).expectStatus().isEqualTo(status)
            .expectBody(MAP).returnResult().getResponseBody();
    }

    private void approve(Map<String, Object> asked, String approver) {
        decide(asked.get("approvalRequestId"), as(approver), "APPROVE", 200);
        deliverer.deliverPending().block();
    }

    private Map<String, Object> assignRole(String user, String role) {
        return run("SEC_USER_ROLE_ASSIGN", as(adminOne), Map.of("userId", user, "roleId", role, "reason", "r"));
    }

    private static List<Map<String, Object>> assignments(String userId, String roleId) {
        return query("SELECT DISTINCT ON (user_role_id) is_deleted FROM sec_user_role_version WHERE user_id = ?::uuid"
            + " AND role_id = ?::uuid ORDER BY user_role_id, version_no DESC", userId, roleId);
    }

    private static Map<String, Object> change(Object changeId) {
        return query("SELECT status, decided_by, failure, result_id, kind, gained, affected_users FROM"
            + " sec_protected_change_version WHERE change_id = ?::uuid ORDER BY version_no DESC LIMIT 1", changeId)
            .getFirst();
    }

    private static String facts(Object requestId) {
        return String.valueOf(query("SELECT facts, reference FROM sys_approval_request_version WHERE request_id ="
            + " ?::uuid LIMIT 1", requestId).getFirst());
    }

    private static boolean holds(String user, String permission) {
        return !query("SELECT 1 FROM sec_user_role_version ur JOIN sec_role_permission_version rp"
            + " ON rp.role_id = ur.role_id WHERE ur.user_id = ?::uuid AND rp.permission = ?", user, permission)
            .isEmpty();
    }

    // ================= grants =================

    @Test
    void aRoleGivingAProtectedPermissionWaitsForAnotherAdministrator() {
        String user = bareUser("clerk");
        String role = createRole(unique("POSTER"), "ledger.post", "ledger.read");
        Map<String, Object> asked = assignRole(user, role);
        assertThat(asked).containsEntry("status", "PENDING").containsEntry("approval", "PENDING");
        Object requestId = asked.get("approvalRequestId");
        Object changeId = asked.get("changeId");
        // Pending: no effect. The approval names what it gives and to whom.
        assertThat(assignments(user, role)).isEmpty();
        assertThat(change(changeId)).containsEntry("status", "PENDING").containsEntry("kind", "USER_ROLE")
            .containsEntry("gained", "ledger.post").containsEntry("affected_users", user);
        assertThat(facts(requestId)).contains("ledger.post").contains("no password");
        assertThat(query("SELECT 1 FROM sys_task_version WHERE source_key = ?", "approval:" + requestId)).isNotEmpty();

        // Not by the requester, not by the user it is for, not without a recent second factor, not by someone who
        // holds less than it gives (an administrator's permission but not ledger.post), not by a helpdesk.
        assertThat(ruleCode(decide(requestId, as(adminOne), "APPROVE", 422))).isEqualTo("APPROVAL_OWN_REQUEST");
        assertThat(ruleCode(decide(requestId, as(user), "APPROVE", 422))).isEqualTo("APPROVAL_TARGET_USER");
        decide(requestId, TestTokens.withoutMfa(tokens, adminTwo, "*"), "APPROVE", 403);
        decide(requestId, TestTokens.bearer(tokens, adminThree, DECIDE, "security.user-role.write"),
            "APPROVE", 403);
        decide(requestId, TestTokens.bearer(tokens, adminThree, DECIDE, "security.user.password"),
            "APPROVE", 403);
        deliverer.deliverPending().block();
        assertThat(assignments(user, role)).isEmpty();

        approve(asked, adminTwo);
        assertThat(assignments(user, role)).singleElement().satisfies(row ->
            assertThat(row.get("is_deleted")).isEqualTo(false));
        assertThat(change(changeId)).containsEntry("status", "APPLIED").containsEntry("decided_by", adminTwo);
        // Once: the event delivered again changes nothing.
        deliverer.deliverPending().block();
        assertThat(assignments(user, role)).hasSize(1);
    }

    @Test
    void anAdministratorHoldingEverythingTheChangeGivesMayApprove() {
        String user = bareUser("clerk");
        String role = createRole(unique("POSTER"), "ledger.post");
        Map<String, Object> asked = assignRole(user, role);
        decide(asked.get("approvalRequestId"), TestTokens.bearer(tokens, adminThree, DECIDE,
            "security.user-role.write", "ledger.post"), "APPROVE", 200);
        deliverer.deliverPending().block();
        // adminThree holds * in fact, so the check at apply time agrees.
        assertThat(change(asked.get("changeId"))).containsEntry("status", "APPLIED");
    }

    @Test
    void aRejectionHasNoEffect() {
        String user = bareUser("clerk");
        String role = createRole(unique("APPROVER"), "approval.decide");
        Map<String, Object> asked = assignRole(user, role);
        decide(asked.get("approvalRequestId"), as(adminTwo), "REJECT", 200);
        deliverer.deliverPending().block();
        assertThat(assignments(user, role)).isEmpty();
        assertThat(change(asked.get("changeId"))).containsEntry("status", "REJECTED");
    }

    @Test
    void aProtectedPermissionForARoleWithHoldersWaitsAndItsHoldersCannotApprove() {
        String user = bareUserWith("price.read");
        String role = String.valueOf(query("SELECT role_id::text AS id FROM sec_user_role_version WHERE user_id ="
            + " ?::uuid", user).getFirst().get("id"));
        Map<String, Object> asked = run("SEC_ROLE_PERMISSION_ADD", as(adminOne),
            Map.of("roleId", role, "permission", "platform.param.write", "reason", "pricing"));
        assertThat(asked).containsEntry("status", "PENDING");
        assertThat(change(asked.get("changeId"))).containsEntry("affected_users", user);
        assertThat(holds(user, "platform.param.write")).isFalse();
        assertThat(ruleCode(decide(asked.get("approvalRequestId"), as(user), "APPROVE", 422)))
            .isEqualTo("APPROVAL_TARGET_USER");
        approve(asked, adminTwo);
        assertThat(holds(user, "platform.param.write")).isTrue();

        // A role nobody holds gets one at once: assigning it is what needs the approval.
        String empty = createRole(unique("EMPTY"));
        assertThat(run("SEC_ROLE_PERMISSION_ADD", as(adminOne), Map.of("roleId", empty, "permission", "ledger.post",
            "reason", "later"))).containsEntry("status", "APPLIED");
    }

    /**
     * Review H1: what the approval covers is what the role gave when it was requested, not the role's id. A growth of
     * the role after the request waits for its own approval, which names the pending assignment's user (so that what
     * it gives that user is approved there): in either order both apply, each as approved.
     */
    @Test
    void aRoleThatGrowsAfterTheRequestIsAppliedOnlyAsTheApprovalsCover() {
        for (boolean growthFirst : List.of(true, false)) {
            String sock = bareUser("sock");
            String role = createRole(unique("POSTER"), "ledger.post");
            Map<String, Object> assignment = assignRole(sock, role);
            assertThat(assignment).containsEntry("status", "PENDING");
            // The role is empty, but the pending assignment counts: giving it * needs an approval naming the sock.
            Map<String, Object> growth = run("SEC_ROLE_PERMISSION_ADD", as(adminOne), Map.of("roleId", role,
                "permission", "*", "reason", "x"));
            assertThat(growth).containsEntry("status", "PENDING");
            assertThat(change(growth.get("changeId"))).containsEntry("affected_users", sock)
                .containsEntry("gained", "*");
            approve(growthFirst ? growth : assignment, adminTwo);
            approve(growthFirst ? assignment : growth, adminTwo);
            assertThat(change(growth.get("changeId"))).as("growth first: " + growthFirst)
                .containsEntry("status", "APPLIED");
            assertThat(change(assignment.get("changeId"))).as("growth first: " + growthFirst)
                .containsEntry("status", "APPLIED");
            assertThat(holds(sock, "*")).isTrue();
        }
    }

    /** Review H1: a role that grew beyond every approval (written outside them) is not assigned as approved. */
    @Test
    void aRoleThatGrewOutsideTheApprovalsIsNotAppliedAsApproved() {
        String sock = bareUser("sock");
        String role = createRole(unique("POSTER"), "ledger.post");
        Map<String, Object> assignment = assignRole(sock, role);
        // As the database's operator could, or as happened while the four eyes were off: no approval names the sock.
        execute("INSERT INTO entity_registry (entity_id, entity_type, created_seq_id)"
            + " SELECT ?::uuid, 'SecRolePermission', max(process_seq_id) FROM op_process", OUT_OF_BAND);
        execute("INSERT INTO sec_role_permission_version (role_permission_id, version_no, effect_start_time,"
            + " created_time, process_seq_id, role_id, permission) SELECT ?::uuid, 1, now(), now(),"
            + " max(process_seq_id), ?::uuid, '*' FROM op_process", OUT_OF_BAND, role);
        approve(assignment, adminTwo);
        assertThat(change(assignment.get("changeId"))).containsEntry("status", "FAILED")
            .containsEntry("failure", "CHANGE_GREW");
        assertThat(assignments(sock, role)).isEmpty();
    }

    private static final String OUT_OF_BAND = UUID.randomUUID().toString();

    /**
     * Review H1 liveness (verification probe): an assignment requested after a pending permission does not make that
     * permission give more than approved; the assignment's own approval covered it.
     */
    @Test
    void aLaterPendingAssignmentDoesNotMakeAnEarlierPendingPermissionFail() {
        String role = createRole(unique("CLERKS"), "price.read");
        String u = bareUser("u");
        assign(u, role, null);
        Map<String, Object> permission = run("SEC_ROLE_PERMISSION_ADD", as(adminOne),
            Map.of("roleId", role, "permission", "ledger.post", "reason", "a"));
        String v = bareUser("v");
        Map<String, Object> assignment = run("SEC_USER_ROLE_ASSIGN", as(adminOne),
            Map.of("userId", v, "roleId", role, "reason", "b"));
        assertThat(change(assignment.get("changeId"))).containsEntry("gained", "ledger.post");
        approve(permission, adminTwo);
        assertThat(change(permission.get("changeId"))).containsEntry("status", "APPLIED");
        approve(assignment, adminTwo);
        assertThat(change(assignment.get("changeId"))).containsEntry("status", "APPLIED");
        assertThat(holds(u, "ledger.post")).isTrue();
        assertThat(holds(v, "ledger.post")).isTrue();
    }

    /** Review H1, the other way round: a pending permission makes the role give it already. */
    @Test
    void aPendingPermissionMakesAnAssignmentOfItsRoleWait() {
        String holderOfRole = bareUserWith("price.read");
        String role = String.valueOf(query("SELECT role_id::text AS id FROM sec_user_role_version WHERE user_id ="
            + " ?::uuid", holderOfRole).getFirst().get("id"));
        assertThat(run("SEC_ROLE_PERMISSION_ADD", as(adminOne), Map.of("roleId", role, "permission", "ledger.post",
            "reason", "x"))).containsEntry("status", "PENDING");
        String other = bareUser("other");
        assertThat(assignRole(other, role)).containsEntry("status", "PENDING");
        assertThat(assignments(other, role)).isEmpty();
        // Nor through the dataset.
        assertThat(ruleCode(post("/api/datasets/" + SecurityEntities.USER_ROLE_DATASET + "/commit", admin(),
            Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", Map.of("userId", bareUser("third"),
                "roleId", role))))).expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody()))
            .isEqualTo("PROTECTED_CHANGE_REQUIRES_APPROVAL");
    }

    /** Review M3: a grant to someone whose credentials another person set would give that person the power. */
    @Test
    void aGrantToAUserWhoseCredentialsAnotherSetIsNotApplied() {
        String name = unique("set-up");
        String user = createUser(name, PASSWORD);
        String role = createRole(unique("POSTER"), "ledger.post");
        Map<String, Object> asked = assignRole(user, role);
        assertThat(facts(asked.get("approvalRequestId"))).contains("password set by it-admin");
        approve(asked, adminTwo);
        assertThat(change(asked.get("changeId"))).containsEntry("status", "FAILED")
            .containsEntry("failure", "TARGET_CREDENTIALS_NOT_OWN");
        assertThat(assignments(user, role)).isEmpty();
    }

    /**
     * Review M3 verification: the administrator who set a user's password signs in as the user and "changes their own
     * password". What the user derives from a password someone else knew is not theirs: the grant is not applied, and
     * the approvers saw the whole history.
     */
    @Test
    void aPasswordChangedFromOneAnAdministratorSetIsNotTheUsers() {
        String name = unique("puppet");
        String user = userWith(name, PASSWORD, "price.read");
        Map<String, Object> session = signIn(name, PASSWORD);
        post("/api/auth/account/password", bearerOf(session), Map.of("currentPassword", PASSWORD,
            "newPassword", OWN_PASSWORD, "refreshToken", session.get("refreshToken"))).expectStatus().isNoContent();
        String role = createRole(unique("POSTER"), "ledger.post");
        Map<String, Object> asked = assignRole(user, role);
        assertThat(facts(asked.get("approvalRequestId"))).contains("password set by " + name)
            .contains("not the user's own").contains("(SEC_USER_CREATE): created, password not own")
            .contains("(SEC_ACCOUNT_PASSWORD_CHANGE): password changed, not own");
        approve(asked, adminTwo);
        assertThat(change(asked.get("changeId"))).containsEntry("status", "FAILED")
            .containsEntry("failure", "TARGET_CREDENTIALS_NOT_OWN");
        assertThat(assignments(user, role)).isEmpty();
        assertThat(holds(user, "ledger.post")).isFalse();
    }

    /**
     * Review L2 verification: the apply takes the approver from a decision on the change's own request. An event that
     * names another request (any process could publish one) is not this change's, whatever it says.
     */
    @Test
    void anEventNamingAnotherRequestIsNotTheChanges() {
        Map<String, Object> other = assignRole(bareUser("x"), createRole(unique("POSTER"), "ledger.post"));
        approve(other, adminTwo);
        // A fourth administrator approves change C, then loses *.
        String fourth = bareUser("admin4");
        String solo = createRole(unique("SOLO"));
        assign(fourth, solo, null);
        approve(run("SEC_ROLE_PERMISSION_ADD", as(adminOne), Map.of("roleId", solo, "permission", "*",
            "reason", "fourth")), adminTwo);
        Map<String, Object> c = assignRole(bareUser("y"), createRole(unique("POSTER"), "ledger.post"));
        decide(c.get("approvalRequestId"), as(fourth), "APPROVE", 200);
        String permissionId = String.valueOf(query("SELECT role_permission_id::text AS id FROM"
            + " sec_role_permission_version WHERE role_id = ?::uuid", solo).getFirst().get("id"));
        post("/api/datasets/" + SecurityEntities.ROLE_PERMISSION_DATASET + "/commit", admin(), Map.of("changes",
            List.of(Map.of("action", "DELETE", "id", permissionId, "version", 1)))).expectStatus().isOk();
        // The forged event: C's change, the other request (decided by adminTwo, who holds *).
        executor.execute(ProtectedChangeProcesses.applyProcess(), new ProtectedChangeProcesses.ApplyInput(
                String.valueOf(other.get("approvalRequestId")), ProtectedChangeEntities.SUBJECT,
                String.valueOf(c.get("changeId")), "APPROVED", null, adminTwo))
            .contextWrite(view -> RequestContexts.put(view, RequestContext.system(Locale.ENGLISH, "it")))
            .block();
        assertThat(change(c.get("changeId"))).containsEntry("status", "PENDING");
        // The genuine event: the approver as the change's request records it, who no longer holds enough.
        deliverer.deliverPending().block();
        assertThat(change(c.get("changeId"))).containsEntry("status", "FAILED")
            .containsEntry("failure", "APPROVER_NOT_ALLOWED").containsEntry("decided_by", fourth);
    }

    /**
     * Review H2 verification: when the change is applied the approver must still hold the permission deciding
     * protected changes (security.protected-change.decide, decision D51) and the level's permission, not only what
     * the change gives.
     */
    @Test
    void anApproverWhoLostThePermissionToDecideBeforeTheApplyIsNotEnough() {
        String approver = bareUser("approver");
        String approvers = createRole(unique("APPROVERS"), "security.user-role.write", "ledger.post", DECIDE);
        approve(assignRole(approver, approvers), adminTwo);
        assertThat(holds(approver, DECIDE)).isTrue();
        Map<String, Object> asked = assignRole(bareUser("clerk"), createRole(unique("POSTER"), "ledger.post"));
        decide(asked.get("approvalRequestId"), TestTokens.bearer(tokens, approver, DECIDE,
            "security.user-role.write", "ledger.post"), "APPROVE", 200);
        String permissionId = String.valueOf(query("SELECT role_permission_id::text AS id FROM"
            + " sec_role_permission_version WHERE role_id = ?::uuid AND permission = ?", approvers, DECIDE)
            .getFirst().get("id"));
        post("/api/datasets/" + SecurityEntities.ROLE_PERMISSION_DATASET + "/commit", admin(), Map.of("changes",
            List.of(Map.of("action", "DELETE", "id", permissionId, "version", 1)))).expectStatus().isOk();
        deliverer.deliverPending().block();
        assertThat(change(asked.get("changeId"))).containsEntry("status", "FAILED")
            .containsEntry("failure", "APPROVER_NOT_ALLOWED");
    }

    /**
     * Decision D51: approval.decide (which a business approver holds) decides no protected change, even with an
     * administrator's permission and everything the change gives.
     */
    @Test
    void approvalDecideAloneNoLongerDecidesAProtectedChange() {
        Map<String, Object> asked = assignRole(bareUser("clerk"), createRole(unique("POSTER"), "ledger.post"));
        Object requestId = asked.get("approvalRequestId");
        decide(requestId, TestTokens.bearer(tokens, adminThree, "approval.decide", "security.user-role.write",
            "ledger.post"), "APPROVE", 403);
        decide(requestId, TestTokens.bearer(tokens, adminThree, "approval.decide", "security.user-role.write",
            "ledger.post"), "REJECT", 403);
        decide(requestId, TestTokens.bearer(tokens, adminThree, DECIDE, "security.user-role.write",
            "ledger.post"), "APPROVE", 200);
        deliverer.deliverPending().block();
        assertThat(change(asked.get("changeId"))).containsEntry("status", "APPLIED");
    }

    @Test
    void theSameGrantRequestedTwiceIsAppliedOnce() {
        String user = bareUser("clerk");
        String role = createRole(unique("POSTER"), "ledger.post");
        Map<String, Object> first = assignRole(user, role);
        Map<String, Object> second = assignRole(user, role);
        assertThat(second).containsEntry("status", "PENDING");
        approve(first, adminTwo);
        approve(second, adminTwo);
        assertThat(change(first.get("changeId"))).containsEntry("status", "APPLIED");
        assertThat(change(second.get("changeId"))).containsEntry("status", "FAILED")
            .containsEntry("failure", "ALREADY_ASSIGNED");
        assertThat(assignments(user, role)).hasSize(1);
    }

    /** The approval binds its period and effective time through the database's microseconds. */
    @Test
    void aScheduledAssignmentWithADataPeriodIsAppliedAsApproved() {
        String user = bareUser("auditor");
        String role = createRole(unique("POSTER"), "ledger.post");
        Instant from = Instant.parse("2025-01-01T00:00:00.123456789Z");
        Instant to = Instant.parse("2026-01-01T00:00:00.987654321Z");
        Instant effective = clock.instant().plus(Duration.ofDays(3)).plusNanos(123_456_789);
        Map<String, Object> asked = run("SEC_USER_ROLE_ASSIGN", as(adminOne), Map.of("userId", user, "roleId", role,
            "dataFrom", from.toString(), "dataTo", to.toString(), "effectiveTime", effective.toString(),
            "reason", "audit"));
        approve(asked, adminTwo);
        assertThat(change(asked.get("changeId"))).containsEntry("status", "APPLIED");
        Map<String, Object> written = query("SELECT data_from, data_to, effect_start_time FROM sec_user_role_version"
            + " WHERE user_id = ?::uuid", user).getFirst();
        assertThat(String.valueOf(written)).contains("2025-01-01").contains("2026-01-01");
    }

    /** The approver is read from the decision, and must still be allowed when the change is applied. */
    @Test
    void anApproverWhoLostTheirPermissionsBeforeTheApplyIsNotEnough() {
        String fourth = bareUser("admin4");
        String solo = createRole(unique("SOLO"));
        assign(fourth, solo, null);
        Map<String, Object> grant = run("SEC_ROLE_PERMISSION_ADD", as(adminOne), Map.of("roleId", solo,
            "permission", "*", "reason", "fourth"));
        approve(grant, adminTwo);
        assertThat(holds(fourth, "*")).isTrue();

        String user = bareUser("clerk");
        String role = createRole(unique("POSTER"), "ledger.post");
        Map<String, Object> asked = assignRole(user, role);
        decide(asked.get("approvalRequestId"), as(fourth), "APPROVE", 200);
        // Before the event is delivered, the fourth administrator's role loses *.
        String permissionId = String.valueOf(query("SELECT role_permission_id::text AS id FROM"
            + " sec_role_permission_version WHERE role_id = ?::uuid", solo).getFirst().get("id"));
        post("/api/datasets/" + SecurityEntities.ROLE_PERMISSION_DATASET + "/commit", admin(), Map.of("changes",
            List.of(Map.of("action", "DELETE", "id", permissionId, "version", 1)))).expectStatus().isOk();
        deliverer.deliverPending().block();
        assertThat(change(asked.get("changeId"))).containsEntry("status", "FAILED")
            .containsEntry("failure", "APPROVER_NOT_ALLOWED").containsEntry("decided_by", fourth);
        assertThat(assignments(user, role)).isEmpty();
    }

    // ================= credentials =================

    @Test
    void thePasswordOfAProtectedHolderIsResetOnlyAfterAnAdministratorHoldingAsMuchApproves() {
        Map<String, Object> asked = run("SEC_USER_SET_PASSWORD", as(adminOne),
            Map.of("userId", holder, "password", "new-password-456"));
        assertThat(asked).containsEntry("status", "PENDING").containsEntry("userId", holder);
        // The old password still works; the holder's sessions stay.
        signIn(holderName, PASSWORD);
        Object requestId = asked.get("approvalRequestId");
        assertThat(ruleCode(decide(requestId, as(holder), "APPROVE", 422))).isEqualTo("APPROVAL_TARGET_USER");
        // Review H2: who may reset passwords does not approve the takeover of an account holding ledger.post.
        decide(requestId, TestTokens.bearer(tokens, adminThree, DECIDE, "security.user.password"),
            "APPROVE", 403);
        decide(requestId, TestTokens.bearer(tokens, adminThree, DECIDE, "security.user-role.write",
            "security.user.password"), "APPROVE", 403);
        approve(asked, adminTwo);
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
        String factors = "SELECT DISTINCT ON (user_mfa_id) is_deleted FROM sec_user_mfa_version WHERE user_id ="
            + " ?::uuid ORDER BY user_mfa_id, version_no DESC";
        Map<String, Object> asked = run("SEC_MFA_RESET", as(adminOne), Map.of("userId", holder, "reason", "phone lost"));
        assertThat(asked).containsEntry("status", "PENDING");
        assertThat(query(factors, holder)).extracting(row -> row.get("is_deleted")).containsExactly(false);
        decide(asked.get("approvalRequestId"), as(adminTwo), "REJECT", 200);
        deliverer.deliverPending().block();
        assertThat(query(factors, holder)).extracting(row -> row.get("is_deleted")).containsExactly(false);

        Map<String, Object> again = run("SEC_MFA_RESET", as(adminOne), Map.of("userId", holder, "reason", "phone lost"));
        approve(again, adminTwo);
        assertThat(query(factors, holder)).extracting(row -> row.get("is_deleted")).containsExactly(true);
        assertThat(change(again.get("changeId"))).containsEntry("status", "APPLIED");
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
        assertThat(ruleCode(decide(asked.get("approvalRequestId"), as(adminTwo), "APPROVE", 422)))
            .isEqualTo("APPROVAL_TARGET_USER");
        approve(asked, adminThree);
        assertThat(query("SELECT 1 FROM sec_user_identity_version WHERE subject = ?", subject)).hasSize(1);
    }

    @Test
    void anIdentityTakenMeanwhileIsNotLinked() {
        String subject = unique("sub");
        Map<String, Object> asked = run("SEC_USER_IDENTITY_LINK", as(adminOne), Map.of("userId", adminTwo,
            "provider", "test", "subject", subject, "reason", "sso"));
        // Meanwhile the account at the provider is linked to an ordinary user.
        insert(SecurityEntities.USER_IDENTITY_DATASET, Map.of("userId", bareUser("plain"), "provider", "test",
            "subject", subject), null);
        approve(asked, adminThree);
        assertThat(change(asked.get("changeId"))).containsEntry("status", "FAILED")
            .containsEntry("failure", "IDENTITY_IN_USE");
    }

    // ================= addresses (review M-b) =================

    private static String emailOf(String user) {
        return (String) query("SELECT email FROM sec_user_version WHERE user_id = ?::uuid ORDER BY version_no DESC"
            + " LIMIT 1", user).getFirst().get("email");
    }

    private Map<String, Object> updateEmail(String user, String email, int status) {
        Map<String, Object> current = query("SELECT version_no FROM sec_user_version WHERE user_id = ?::uuid"
            + " ORDER BY version_no DESC LIMIT 1", user).getFirst();
        WebTestClient.ResponseSpec response = post("/api/datasets/" + SecurityEntities.USER_DATASET + "/commit",
            admin(), Map.of("changes", List.of(Map.of("action", "UPDATE", "id", user, "version",
                current.get("version_no"), "attributes", Map.of("email", email))))).expectStatus().isEqualTo(status);
        return status == 200 ? Map.of() : response.expectBody(MAP).returnResult().getResponseBody();
    }

    /**
     * Review M-b: the address is a way back into the account (a reset link goes there). A holder's address changes
     * only after another administrator approves, who holds as much as the holder; the old address is told.
     */
    @Test
    void aHoldersAddressChangesOnlyAfterApprovalAndTheOldAddressIsTold() {
        String user = bareUser("addressed");
        approve(assignRole(user, createRole(unique("POSTER"), "ledger.post")), adminTwo);
        assertThat(ruleCode(updateEmail(user, unique("first") + "@example.com", 422)))
            .isEqualTo("PROTECTED_CHANGE_REQUIRES_APPROVAL");
        String first = unique("first") + "@example.com";
        Map<String, Object> asked = run("SEC_USER_EMAIL_SET", as(adminOne), Map.of("userId", user, "email", first,
            "reason", "new mailbox"));
        assertThat(asked).containsEntry("status", "PENDING");
        assertThat(emailOf(user)).isNull();
        assertThat(facts(asked.get("approvalRequestId"))).contains(first).contains("ledger.post");
        // Who may only reset passwords holds less than the holder: no.
        decide(asked.get("approvalRequestId"), TestTokens.bearer(tokens, adminThree, DECIDE,
            "security.user-role.write"), "APPROVE", 403);
        approve(asked, adminTwo);
        assertThat(change(asked.get("changeId"))).containsEntry("status", "APPLIED").containsEntry("kind", "EMAIL");
        assertThat(emailOf(user)).isEqualTo(first);

        String second = unique("second") + "@example.com";
        approve(run("SEC_USER_EMAIL_SET", as(adminOne), Map.of("userId", user, "email", second, "reason", "again")),
            adminTwo);
        assertThat(emailOf(user)).isEqualTo(second);
        assertThat(query("SELECT address, params FROM sys_mail_message WHERE template = 'jabiz.auth.email-changed'"
            + " AND user_id = ?::uuid", user)).singleElement().satisfies(mail -> {
                assertThat(mail).containsEntry("address", first);
                assertThat(String.valueOf(mail.get("params"))).contains(second);
            });

        // An ordinary user's address changes at once, as before.
        String plain = bareUser("plain");
        assertThat(run("SEC_USER_EMAIL_SET", as(adminOne), Map.of("userId", plain, "email",
            unique("plain") + "@example.com", "reason", "typo"))).containsEntry("status", "APPLIED");
        updateEmail(plain, unique("again") + "@example.com", 200);
    }

    /**
     * Review M-b (probe P5): the address of a user a protected grant waits for is protected too - otherwise it could be
     * swapped for one the requester reads between the request and the approval.
     */
    @Test
    void theAddressOfAUserAGrantWaitsForIsProtected() {
        String user = bareUser("waiting");
        Map<String, Object> asked = assignRole(user, createRole(unique("POSTER"), "ledger.post"));
        assertThat(asked).containsEntry("status", "PENDING");
        assertThat(ruleCode(updateEmail(user, unique("swap") + "@example.com", 422)))
            .isEqualTo("PROTECTED_CHANGE_REQUIRES_APPROVAL");
        assertThat(run("SEC_USER_EMAIL_SET", as(adminOne), Map.of("userId", user, "email",
            unique("swap") + "@example.com", "reason", "swap"))).containsEntry("status", "PENDING");
    }

    /**
     * Review L-c: a later change's approval covers what an earlier one gives only while what it wrote lives. Here V
     * holds ledger.post through ROLE2 by an approved assignment, so joining R (whose ledger.post waits) gives nothing
     * new; that assignment is then revoked, and R's ledger.post would now reach V unapproved.
     */
    @Test
    void anApprovalWhoseGrantWasRevokedCoversNothing() {
        String role = createRole(unique("CLERKS"), "price.read");
        assign(bareUser("u"), role, null);
        Map<String, Object> permission = run("SEC_ROLE_PERMISSION_ADD", as(adminOne),
            Map.of("roleId", role, "permission", "ledger.post", "reason", "c"));
        String v = bareUser("v");
        String other = createRole(unique("ROLE2"), "ledger.post");
        Map<String, Object> viaOther = assignRole(v, other);
        approve(viaOther, adminTwo);
        assertThat(change(viaOther.get("changeId"))).containsEntry("status", "APPLIED");
        // V holds ledger.post already: R gives nothing protected, and is assigned at once.
        assertThat(assignRole(v, role)).containsEntry("status", "APPLIED");
        String assignment = String.valueOf(change(viaOther.get("changeId")).get("result_id"));
        post("/api/datasets/" + SecurityEntities.USER_ROLE_DATASET + "/commit", admin(), Map.of("changes",
            List.of(Map.of("action", "DELETE", "id", assignment, "version", 1)))).expectStatus().isOk();
        approve(permission, adminTwo);
        assertThat(change(permission.get("changeId"))).containsEntry("status", "FAILED")
            .containsEntry("failure", "CHANGE_GREW");
    }

    /**
     * Review L-d: a rejection is taken from the request's records, not from the event: a forged one, even with the
     * right content hash, leaves the change pending.
     */
    @Test
    void aForgedRejectionIsNotTheChanges() {
        Map<String, Object> asked = assignRole(bareUser("clerk"), createRole(unique("POSTER"), "ledger.post"));
        String hash = String.valueOf(query("SELECT content_hash FROM sys_approval_request_version WHERE request_id ="
            + " ?::uuid LIMIT 1", asked.get("approvalRequestId")).getFirst().get("content_hash")).strip();
        executor.execute(ProtectedChangeProcesses.applyProcess(), new ProtectedChangeProcesses.ApplyInput(
                String.valueOf(asked.get("approvalRequestId")), ProtectedChangeEntities.SUBJECT,
                String.valueOf(asked.get("changeId")), "REJECTED", hash, adminThree))
            .contextWrite(view -> RequestContexts.put(view, RequestContext.system(Locale.ENGLISH, "it")))
            .block();
        assertThat(change(asked.get("changeId"))).containsEntry("status", "PENDING");
        decide(asked.get("approvalRequestId"), as(adminTwo), "REJECT", 200);
        deliverer.deliverPending().block();
        assertThat(change(asked.get("changeId"))).containsEntry("status", "REJECTED")
            .containsEntry("decided_by", adminTwo);
    }

    // ================= other paths =================

    @Test
    void everyOtherWritePathIsRefused() {
        String user = bareUser("clerk");
        String role = createRole(unique("POSTER"), "ledger.post");
        Map<String, Object> grant = Map.of("userId", user, "roleId", role);
        // The dataset API, as any administrator would, even holding everything.
        Map<String, Object> viaDataset = post("/api/datasets/" + SecurityEntities.USER_ROLE_DATASET + "/commit",
            admin(), Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", grant))))
            .expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCode(viaDataset)).isEqualTo("PROTECTED_CHANGE_REQUIRES_APPROVAL");
        assertThat(String.valueOf(violations(viaDataset).getFirst().get("message"))).contains("SEC_USER_ROLE_ASSIGN");
        // The entity API, which runs the generic entity process ADD_ENTITY; that process runs through it only.
        assertThat(ruleCode(post("/api/entities/SecUserRole", admin(), grant).expectStatus().isEqualTo(422)
            .expectBody(MAP).returnResult().getResponseBody())).isEqualTo("PROTECTED_CHANGE_REQUIRES_APPROVAL");
        assertThat(ruleCode(post("/api/processes/ADD_ENTITY/latest", admin(), Map.of("entityType", "SecUserRole",
            "attributes", grant)).expectStatus().isForbidden().expectBody(MAP).returnResult().getResponseBody()))
            .isEqualTo("PROCESS_INTERNAL");
        // A role's new permission while users hold it.
        String plain = bareUserWith("price.read");
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
        // The records of the setup window cannot be undone.
        assertThatThrownBy(() -> execute("DELETE FROM sec_protected_grants_armed")).hasMessageContaining("append");
    }

    @Test
    @SuppressWarnings("unchecked")
    void anImportThatGrantsIsRefused() {
        String user = bareUser("imported");
        String role = createRole(unique("POSTER"), "ledger.post");
        String fileId = upload(("User,Role\n" + user + "," + role + "\n").getBytes(StandardCharsets.UTF_8));
        Map<String, Object> problem = post("/api/imports/it.grants/commit", TestTokens.bearer(tokens,
            "it-importer", "it.grant", "app.import.read", "app.import.upload"), Map.of("fileId", fileId))
            .expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody();
        Map<String, Object> report = (Map<String, Object>) problem.get("report");
        assertThat(report).containsEntry("accepted", false).containsEntry("committed", false);
        assertThat(String.valueOf(report.get("issues"))).contains("PROTECTED_CHANGE_REQUIRES_APPROVAL");
        assertThat(assignments(user, role)).isEmpty();
    }

    private String upload(byte[] content) {
        String boundary = "it-boundary";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"g.csv\""
            + "\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(content);
        out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        Map<String, Object> file = client.post().uri("/api/files?policy=app.import")
            .header(HttpHeaders.AUTHORIZATION, admin())
            .contentType(MediaType.parseMediaType("multipart/form-data; boundary=" + boundary))
            .bodyValue(out.toByteArray()).exchange()
            .expectStatus().isCreated().expectBody(MAP).returnResult().getResponseBody();
        return String.valueOf(file.get("fileId"));
    }

    @Test
    void revertingTheRemovalOfAProtectedAssignmentIsRefused() {
        String user = bareUser("clerk");
        String role = createRole(unique("POSTER"), "ledger.post");
        approve(assignRole(user, role), adminTwo);
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
    void cancellingTheScheduledRemovalOfAProtectedAssignmentIsRefused() {
        String user = bareUser("clerk");
        String role = createRole(unique("POSTER"), "ledger.post");
        approve(assignRole(user, role), adminTwo);
        String assignment = String.valueOf(query("SELECT user_role_id::text AS id FROM sec_user_role_version WHERE"
            + " user_id = ?::uuid", user).getFirst().get("id"));
        Instant later = clock.instant().plus(Duration.ofDays(10));
        post("/api/datasets/" + SecurityEntities.USER_ROLE_DATASET + "/commit", admin(), Map.of("changes",
            List.of(Map.of("action", "DELETE", "id", assignment, "version", 1, "effectiveTime", later.toString()))))
            .expectStatus().isOk();
        Map<String, Object> refused = post("/api/datasets/" + SecurityEntities.USER_ROLE_DATASET + "/commit", admin(),
            Map.of("changes", List.of(Map.of("action", "CANCEL_SCHEDULED", "id", assignment, "version", 2,
                "effectiveTime", later.toString())))).expectStatus().isEqualTo(422).expectBody(MAP).returnResult()
            .getResponseBody();
        assertThat(ruleCode(refused)).isEqualTo("PROTECTED_CHANGE_REQUIRES_APPROVAL");
    }

    /**
     * Review M2: a grant judged while another change of access is still open waits for it (the {@code jabiz.sod}
     * lock), and then sees it - on the request path and on the revert path.
     */
    @Test
    void aGrantWaitsForAnOpenChangeOfTheRoleAndThenSeesIt() throws Exception {
        String sock = bareUser("sock");
        String role = createRole(unique("EMPTY"), "price.read");
        Map<String, Object> assigned = withRoleBeingGivenStar(role, () -> post(
            "/api/processes/SEC_USER_ROLE_ASSIGN/latest", as(adminOne), Map.of("userId", sock, "roleId", role,
                "reason", "race")).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody());
        assertThat(String.valueOf(assigned.get("output"))).contains("status=PENDING");
        assertThat(assignments(sock, role)).isEmpty();
    }

    @Test
    void aRevertWaitsForAnOpenChangeOfTheRoleAndThenSeesIt() throws Exception {
        String sock = bareUser("sock");
        String role = createRole(unique("EMPTY"), "price.read");
        assign(sock, role, null);
        String assignment = String.valueOf(query("SELECT user_role_id::text AS id FROM sec_user_role_version WHERE"
            + " user_id = ?::uuid", sock).getFirst().get("id"));
        post("/api/datasets/" + SecurityEntities.USER_ROLE_DATASET + "/commit", admin(), Map.of("changes",
            List.of(Map.of("action", "DELETE", "id", assignment, "version", 1)))).expectStatus().isOk();
        Object deletion = query("SELECT process_seq_id FROM sec_user_role_version WHERE user_role_id = ?::uuid AND"
            + " version_no = 2", assignment).getFirst().get("process_seq_id");
        Map<String, Object> reverted = withRoleBeingGivenStar(role, () -> post("/api/processes/executions/"
            + deletion + "/revert", admin(), Map.of("reason", "undo")).expectBody(MAP).returnResult()
            .getResponseBody());
        assertThat(ruleCode(reverted)).isEqualTo("PROTECTED_CHANGE_REQUIRES_APPROVAL");
        assertThat(assignments(sock, role)).extracting(row -> row.get("is_deleted")).containsExactly(true);
    }

    /**
     * Runs {@code competitor} while {@code IT_SLOW_GRANT} has given {@code role} {@code *} (the role has no users, so
     * that is applied at once) and keeps its transaction open; lets it commit after the competitor has had time to
     * read.
     */
    private Map<String, Object> withRoleBeingGivenStar(String role,
        java.util.function.Supplier<Map<String, Object>> competitor) throws Exception {
        arrived = new CountDownLatch(1);
        go = new CountDownLatch(1);
        AtomicReference<Throwable> slowFailed = new AtomicReference<>();
        Thread slow = new Thread(() -> {
            try {
                post("/api/processes/IT_SLOW_GRANT/latest", admin(), Map.of("roleId", role)).expectStatus().isOk();
            } catch (Throwable e) {
                slowFailed.set(e);
            }
        });
        slow.start();
        assertThat(arrived.await(30, TimeUnit.SECONDS)).isTrue();
        AtomicReference<Object> result = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                result.set(competitor.get());
            } catch (Throwable e) {
                result.set(e);
            }
        });
        other.start();
        // The competitor waits for the lock the slow grant holds.
        Thread.sleep(1500);
        assertThat(other.isAlive()).isTrue();
        go.countDown();
        other.join(30_000);
        slow.join(30_000);
        assertThat(slowFailed.get()).isNull();
        assertThat(result.get()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> answer = (Map<String, Object>) result.get();
        return answer;
    }

    @Test
    void unprotectedGrantsAreAsBefore() {
        String user = bareUser("clerk");
        String role = createRole(unique("READER"), "price.read", "ledger.read");
        assign(user, role, null);
        String other = createRole(unique("READER2"), "price.read");
        assertThat(assignRole(user, other)).containsEntry("status", "APPLIED");
        insert(SecurityEntities.ROLE_PERMISSION_DATASET, Map.of("roleId", role, "permission", "report.issue"), null);
        // A protected permission the user already holds through another role is no gain.
        String poster = createRole(unique("POSTER"), "ledger.post");
        String alsoPoster = createRole(unique("POSTER2"), "ledger.post");
        approve(assignRole(user, poster), adminTwo);
        assertThat(assignRole(user, alsoPoster)).containsEntry("status", "APPLIED");
        // New roles for an administrator who holds everything.
        assertThat(assignRole(adminTwo, poster)).containsEntry("status", "APPLIED");
    }
}
