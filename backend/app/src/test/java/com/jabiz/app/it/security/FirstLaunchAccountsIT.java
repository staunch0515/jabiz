package com.jabiz.app.it.security;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.jabiz.context.RequestContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.firstlaunch.accounts.FirstLaunchAccountProcesses;
import com.jabiz.runtime.firstlaunch.FirstLaunchProcesses;
import com.jabiz.runtime.firstlaunch.accounts.FirstLaunchAccountProcesses.NewAccount;
import com.jabiz.runtime.firstlaunch.accounts.FirstLaunchAccountProcesses.RoleInput;
import com.jabiz.runtime.firstlaunch.accounts.FirstLaunchAccountProcesses.ReviseInput;
import com.jabiz.runtime.firstlaunch.accounts.WaitingRole;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.security.InvitationProcesses;
import com.jabiz.runtime.security.SecurityEntities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The first launch's accounts (decision D82; plan F-LAUNCH section 5.2): created without a password or an invitation,
 * holding the waiting role, which lets the user sign in at the administration only to bind a second factor; the role
 * is the first launch's alone. An account nobody used yet gets a corrected address and an invitation there.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.mail.enabled=true", "jabiz.mail.from=jabiz@example.com", "jabiz.mail.base-url=https://app.example.com",
    "spring.mail.host=localhost", "spring.mail.port=3043", SignUpItSupport.ENTRY_PROPERTIES_REGISTRATION,
    "jabiz.security.entries.admin.accepted-roles=IT_BACK_OFFICE," + WaitingRole.CODE,
    SignUpItSupport.ENTRY_PROPERTIES_LOOSE})
@Import(SignUpItSupport.FixedTimeLimits.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FirstLaunchAccountsIT extends SignUpItSupport {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3043, null, ServerSetup.PROTOCOL_SMTP));

    private static final String OWN = "own horse battery";
    private static final String B = "fl-b@example.com";
    private static final String C_WRONG = "fl-c.yamda@example.com";
    private static final String C_RIGHT = "fl-c.yamada@example.com";

    private static String roleId;
    private static final Map<String, String> USERS = new java.util.HashMap<>();

    @Autowired
    ProcessExecutor processes;

    @Autowired
    ProcessDefinition<InvitationProcesses.InviteInput, InvitationProcesses.InviteOutput,
        com.jabiz.process.ProcessContext> invite;

    @Override
    GreenMailExtension mail() {
        return MAIL;
    }

    private <I, O> O asSystem(ProcessDefinition<I, O, ?> process, I input) {
        RequestContext system = RequestContext.system(Locale.ENGLISH, "first-launch-it");
        return processes.execute(process, input).contextWrite(view -> RequestContexts.put(view, system)).block();
    }

    @BeforeEach
    void accounts() {
        if (roleId == null) {
            asSystem(FirstLaunchProcesses.START_PROCESS, new FirstLaunchProcesses.StartInput("it-hash"));
            roleId = asSystem(FirstLaunchAccountProcesses.WAITING_ROLE_PROCESS, new RoleInput()).roleId();
            USERS.put("B", asSystem(FirstLaunchAccountProcesses.ACCOUNT_PROCESS, new NewAccount("B", 0, "fl-b",
                "田中 美咲", B, "ja", List.of("IT_BACK_OFFICE"), true)).userId());
            USERS.put("C", asSystem(FirstLaunchAccountProcesses.ACCOUNT_PROCESS, new NewAccount("C", 1, "fl-c",
                "山田 健", C_WRONG, "ja", List.of("CUSTOMER"), false)).userId());
        }
    }

    private String user(String key) {
        return USERS.get(key);
    }

    @Test
    @Order(1)
    void theAccountsHoldTheWaitingRoleOnlyAndNoPasswordAndAreNotInvitedYet() {
        assertThat(query("SELECT account_key, roles, report FROM sys_first_launch_account ORDER BY position"))
            .extracting(row -> row.get("account_key") + " " + row.get("roles") + " " + row.get("report"))
            .containsExactly("B IT_BACK_OFFICE true", "C CUSTOMER false");
        assertThat(userOf(B)).containsEntry("password_hash", null).containsEntry("verified_email", null);
        assertThat(rolesOf(user("B"))).containsExactly(WaitingRole.CODE);
        assertThat(query("SELECT enabled, require_mfa FROM sec_role_version WHERE role_code = ?", WaitingRole.CODE))
            .singleElement().satisfies(role -> assertThat(role).containsEntry("enabled", true)
                .containsEntry("require_mfa", true));
        assertThat(query("SELECT 1 FROM sec_role_permission_version WHERE role_id = ?::uuid", roleId))
            .isEmpty();
        deliver();
        assertThat(to(B)).isEmpty();
        // One run only.
        assertThatThrownBy(() -> asSystem(FirstLaunchAccountProcesses.WAITING_ROLE_PROCESS, new RoleInput()))
            .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> assertThat(e.violations())
                .extracting(com.jabiz.entity.Violation::ruleCode).containsExactly("FIRST_LAUNCH_ROLE_RESERVED"));
    }

    @Test
    @Order(2)
    @SuppressWarnings("unchecked")
    void anInvitedAccountSignsInAtTheAdministrationOnlyToBindASecondFactor() {
        post("/api/processes/SEC_USER_INVITE/latest", admin(), Map.of("userId", user("B"))).expectStatus().isOk();
        deliver();
        anonymous("/api/auth/invitation/confirm", Map.of("token", token(InvitationIT.INVITE_LINK, B), "password", OWN))
            .expectStatus().isNoContent();

        Map<String, Object> first = session(B, OWN, null);
        assertThat(first).containsEntry("status", "MFA_ENROLLMENT_REQUIRED").containsEntry("accessToken", null);
        String challenge = (String) first.get("challenge");
        String secret = (String) post("/api/auth/challenge/enroll", null, Map.of("challenge", challenge))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody().get("secret");
        post("/api/auth/challenge/enroll/confirm", null, Map.of("challenge", challenge, "code", totp(secret)))
            .expectStatus().isOk();
        clock.advance(Duration.ofSeconds(30));
        Map<String, Object> again = session(B, OWN, null);
        assertThat(again).containsEntry("status", "MFA_REQUIRED");
        Map<String, Object> signedIn = post("/api/auth/challenge/verify", null, Map.of("challenge",
            again.get("challenge"), "code", totp(secret))).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
        assertThat(signedIn).containsEntry("status", "SIGNED_IN");
        String bearer = bearerOf(signedIn);

        // A user with no permission: the administration answers, with no menu.
        Map<String, Object> me = get("/api/auth/me", bearer).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
        assertThat((List<String>) me.get("roles")).containsExactly(WaitingRole.CODE);
        assertThat((List<String>) me.get("permissions")).isEmpty();
        assertThat(get("/api/auth/menus", bearer).expectStatus().isOk().expectBody(LIST).returnResult()
            .getResponseBody()).isEmpty();
        // Not at an entry that does not accept the waiting role.
        assertThat(refusal(loginTo(B, OWN, LOOSE), 401)).isEqualTo("LOGIN_FAILED");
    }

    @Test
    @Order(3)
    void theWaitingRoleIsTheFirstLaunchsAlone() {
        String other = createUserWithEmail(unique("other"), address("other"), null);
        assertThat(refusal(post("/api/processes/SEC_USER_ROLE_ASSIGN/latest", admin(), Map.of("userId", other,
            "roleId", roleId, "reason", "r")), 422)).isEqualTo("FIRST_LAUNCH_ROLE_RESERVED");
        assertThat(refusal(commit(SecurityEntities.USER_ROLE_DATASET, Map.of("action", "INSERT", "attributes",
            Map.of("userId", other, "roleId", roleId))), 422)).isEqualTo("FIRST_LAUNCH_ROLE_RESERVED");
        assertThat(refusal(commit(SecurityEntities.ROLE_PERMISSION_DATASET, Map.of("action", "INSERT", "attributes",
            Map.of("roleId", roleId, "permission", "price.read"))), 422))
            .isEqualTo("FIRST_LAUNCH_ROLE_RESERVED");
        assertThat(refusal(updateRole(Map.of("requireMfa", false)), 422)).isEqualTo("FIRST_LAUNCH_ROLE_RESERVED");
        // Disabling is anybody's who may write roles; enabling again is not.
        updateRole(Map.of("enabled", false)).expectStatus().isOk();
        assertThat(refusal(updateRole(Map.of("enabled", true)), 422)).isEqualTo("FIRST_LAUNCH_ROLE_RESERVED");
    }

    @Test
    @Order(4)
    void anUnusedAccountGetsItsCorrectedAddressAndAnInvitationThere() {
        post("/api/processes/SEC_USER_INVITE/latest", admin(), Map.of("userId", user("C"))).expectStatus().isOk();
        deliver();
        String wrongLink = token(InvitationIT.INVITE_LINK, C_WRONG);

        // Only in the waiting phase, and only for the run's own accounts.
        assertThatThrownBy(() -> asSystem(FirstLaunchAccountProcesses.REVISE_PROCESS,
            new ReviseInput(UUID.fromString(user("C")), C_RIGHT))).hasMessageContaining("waiting phase");
        String runId = String.valueOf(query("SELECT run_id::text AS id FROM sys_first_launch_run").getFirst().get("id"));
        asSystem(FirstLaunchProcesses.RECORD_PROCESS, new FirstLaunchProcesses.RecordInput(runId, "CONTENT_DONE", null,
            null, null, "it-hash", null));
        String stranger = createUserWithEmail(unique("stranger"), address("stranger"), null);
        assertThatThrownBy(() -> asSystem(FirstLaunchAccountProcesses.REVISE_PROCESS,
            new ReviseInput(UUID.fromString(stranger), address("elsewhere")))).hasMessageContaining("waiting phase");
        asSystem(FirstLaunchAccountProcesses.REVISE_PROCESS, new ReviseInput(UUID.fromString(user("C")), C_RIGHT));
        asSystem(invite, new InvitationProcesses.InviteInput(UUID.fromString(user("C")), null));
        deliver();
        assertThat(refusal(anonymous("/api/auth/invitation/confirm", Map.of("token", wrongLink, "password", OWN)), 422))
            .isEqualTo("TOKEN_INVALID");
        anonymous("/api/auth/invitation/confirm", Map.of("token", token(InvitationIT.INVITE_LINK, C_RIGHT),
            "password", OWN)).expectStatus().isNoContent();
        assertThat(userOf(C_RIGHT)).containsEntry("verified_email", C_RIGHT);

        // Accepted: no longer corrected by the first launch.
        assertThatThrownBy(() -> asSystem(FirstLaunchAccountProcesses.REVISE_PROCESS,
            new ReviseInput(UUID.fromString(user("C")), "fl-c.other@example.com")))
            .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> assertThat(e.violations())
                .extracting(com.jabiz.entity.Violation::ruleCode).containsExactly("INVITATION_NOT_NEEDED"));
    }

    /** Only the system's own correction counts as entered at creation: an administrator's change is not invited. */
    @Test
    @Order(5)
    void anAdministratorsCorrectionIsNotInvited() {
        String user = createUserWithEmail(unique("admin-made"), address("first"), null);
        updateUser(user, Map.of("email", address("second")));
        assertThat(refusal(post("/api/processes/SEC_USER_INVITE/latest", admin(), Map.of("userId", user)), 422))
            .isEqualTo("INVITATION_ADDRESS_CHANGED");
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec commit(String dataset,
        Map<String, Object> change) {
        return post("/api/datasets/" + dataset + "/commit", admin(), Map.of("changes", List.of(change)));
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec updateRole(
        Map<String, Object> attributes) {
        Map<String, Object> role = get("/api/datasets/" + SecurityEntities.ROLE_DATASET + "/entities/"
            + roleId, admin()).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return commit(SecurityEntities.ROLE_DATASET, Map.of("action", "UPDATE", "id", roleId, "version",
            role.get("version"), "attributes", attributes));
    }
}
