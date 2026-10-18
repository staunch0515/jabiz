package com.jabiz.app.it.security;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whose credentials a protected grant goes to (decision D48; review M3 and its verification; docs/design/10-security.md
 * section 16.5), after the setup window: the chain of ownership over the account's history. A password the user
 * registered with, changed themselves from an own one, or set through a mailed link at an address no administrator
 * changed is theirs, and the grant applies (ending their sessions); a password an administrator knew, an address an
 * administrator changed, make it fail. A second factor stays the user's when a recovery code is used. The approvers
 * see the whole history.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.mail.enabled=true", "jabiz.mail.from=jabiz@example.com", "jabiz.mail.base-url=https://app.example.com",
    "spring.mail.host=localhost", "spring.mail.port=3033", SignUpItSupport.ENTRY_PROPERTIES_REGISTRATION,
    SignUpItSupport.ENTRY_PROPERTIES_ADMIN, SignUpItSupport.ENTRY_PROPERTIES_LOOSE})
@Import(SignUpItSupport.FixedTimeLimits.class)
class ProtectedGrantCredentialsIT extends SignUpItSupport {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3033, null, ServerSetup.PROTOCOL_SMTP));

    private static final String CHANGED = "changed horse battery";
    private static final String RESET = "reset horse battery";

    private static String adminOne;
    private static String adminTwo;

    @Override
    GreenMailExtension mail() {
        return MAIL;
    }

    @BeforeEach
    void administrators() {
        if (adminOne != null) {
            return;
        }
        String admins = createRole(unique("ADMINS"));
        adminOne = bareUser("admin1");
        adminTwo = bareUser("admin2");
        assign(adminOne, admins, null);
        assign(adminTwo, admins, null);
        assign(bareUser("admin3"), admins, null);
        insert(SecurityEntities.ROLE_PERMISSION_DATASET, Map.of("roleId", admins, "permission", "*"), null);
        createRole(unique("PLAIN"), "price.read");
        assertThat(query("SELECT count(*) AS n FROM sec_protected_grants_armed").getFirst().get("n")).isEqualTo(1L);
    }

    @SuppressWarnings("unchecked")
    private String bareUser(String prefix) {
        String name = unique(prefix);
        Map<String, Object> result = post("/api/processes/SEC_USER_CREATE/latest", admin(),
            Map.of("userName", name, "displayName", name)).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
        return (String) ((Map<String, Object>) result.get("output")).get("userId");
    }

    /** Registers an address in the portal and verifies it with the registrant's password; returns the user id. */
    private String registered(String email) {
        register(PORTAL, email, PASSWORD).expectStatus().isAccepted();
        deliver();
        anonymous("/api/auth/verify-email", Map.of("token", token(VERIFY_LINK, email), "password", PASSWORD))
            .expectStatus().isNoContent();
        return String.valueOf(userOf(email).get("user_id"));
    }

    private void resetBy(String email, String password) {
        anonymous("/api/auth/password-reset/request", Map.of("email", email, "entry", PORTAL)).expectStatus()
            .isAccepted();
        deliver();
        anonymous("/api/auth/password-reset/confirm", Map.of("token", token(RESET_LINK, email), "password", password))
            .expectStatus().isNoContent();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> grant(String user) {
        String role = createRole(unique("POSTER"), "ledger.post");
        Map<String, Object> asked = (Map<String, Object>) post("/api/processes/SEC_USER_ROLE_ASSIGN/latest",
            TestTokens.bearer(tokens, adminOne, "*"), Map.of("userId", user, "roleId", role, "reason", "r"))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody().get("output");
        assertThat(asked).containsEntry("status", "PENDING");
        return asked;
    }

    private String approve(Map<String, Object> asked) {
        post("/api/processes/APPROVAL_DECIDE/latest", TestTokens.bearer(tokens, adminTwo, "*"),
            new LinkedHashMap<>(Map.of("requestId", asked.get("approvalRequestId"), "decision", "APPROVE")))
            .expectStatus().isOk();
        deliver();
        Map<String, Object> change = query("SELECT status, failure FROM sec_protected_change_version WHERE change_id ="
            + " ?::uuid ORDER BY version_no DESC LIMIT 1", asked.get("changeId")).getFirst();
        return change.get("status") + (change.get("failure") == null ? "" : " " + change.get("failure"));
    }

    private static String facts(Map<String, Object> asked) {
        return String.valueOf(query("SELECT facts FROM sys_approval_request_version WHERE request_id = ?::uuid LIMIT 1",
            asked.get("approvalRequestId")).getFirst().get("facts"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPasswordTheUserRegisteredAndChangedIsTheirsAndTheGrantEndsTheirSessions() {
        String email = address("own");
        String user = registered(email);
        Map<String, Object> session = session(email, PASSWORD, PORTAL);
        post("/api/auth/account/password", bearerOf(session), Map.of("currentPassword", PASSWORD,
            "newPassword", CHANGED, "refreshToken", session.get("refreshToken"))).expectStatus().isNoContent();
        Map<String, Object> asked = grant(user);
        assertThat(facts(asked)).contains("(SPONSOR_SIGN_UP): created, password own, address " + email)
            .contains("(SEC_ACCOUNT_PASSWORD_CHANGE): password changed, own").doesNotContain("not the user's own");
        assertThat(approve(asked)).isEqualTo("APPLIED");
        // The session from before the grant ends: the user signs in again.
        post("/api/auth/refresh", null, Map.of("refreshToken", session.get("refreshToken"))).expectStatus()
            .isUnauthorized();
        assertThat(query("SELECT DISTINCT r.reason FROM sec_refresh_family_revocation r JOIN sec_refresh_token t"
            + " ON t.family_id = r.family_id WHERE t.user_id = ?::uuid", user)).extracting(row -> row.get("reason"))
            .contains("PROTECTED_GRANT");
        session(email, CHANGED, PORTAL);
    }

    /** "Until (c) happens": an administrator's password is not the user's until a mailed link replaces it. */
    @Test
    void anAddressAnAdministratorEnteredBecomesTheUsersOnceAMailedResetProvesIt() {
        String email = address("staff");
        String user = createUserWithEmail(unique("staff"), email, PASSWORD);
        assign(user, roleId(CUSTOMER), null);
        verifyEmail(user);
        Map<String, Object> first = grant(user);
        assertThat(facts(first)).contains("(SEC_USER_CREATE): created, password not own, address " + email);
        assertThat(approve(first)).isEqualTo("FAILED TARGET_CREDENTIALS_NOT_OWN");

        resetBy(email, RESET);
        Map<String, Object> second = grant(user);
        assertThat(facts(second)).contains("(SPONSOR_PASSWORD_RESET_CONFIRM): password changed, own");
        assertThat(approve(second)).isEqualTo("APPLIED");
    }

    /** An address an administrator changed is how an account is taken over: a reset there is not the user's. */
    @Test
    void aResetAtAnAddressAnAdministratorChangedIsNotTheUsers() {
        String email = address("victim");
        String user = registered(email);
        String taken = address("taken");
        updateUser(user, Map.of("email", taken));
        verifyEmail(user);
        resetBy(taken, RESET);
        Map<String, Object> asked = grant(user);
        assertThat(facts(asked)).contains("address changed to " + taken).contains("not the user's own");
        assertThat(approve(asked)).isEqualTo("FAILED TARGET_CREDENTIALS_NOT_OWN");
    }

    /**
     * Review M3 verification: signing in with a recovery code writes the second factor again, in a process the user
     * does not run; its first version (the enrolment) is what counts.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aRecoveryCodeSignInLeavesTheSecondFactorTheUsers() {
        String email = address("rc");
        String user = registered(email);
        String bearer = bearerOf(session(email, PASSWORD, PORTAL));
        String secret = (String) post("/api/auth/mfa/enroll", bearer, Map.of()).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody().get("secret");
        List<String> recovery = (List<String>) post("/api/auth/mfa/enroll/confirm", bearer,
            Map.of("code", totp(secret))).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody()
            .get("recoveryCodes");
        Map<String, Object> first = session(email, PASSWORD, PORTAL);
        post("/api/auth/challenge/verify", null, Map.of("challenge", first.get("challenge"), "code",
            recovery.getFirst())).expectStatus().isOk();
        assertThat(query("SELECT p.process_name FROM sec_user_mfa_version v JOIN op_process p"
            + " ON p.process_seq_id = v.process_seq_id WHERE v.user_id = ?::uuid ORDER BY v.version_no", user))
            .extracting(row -> row.get("process_name")).last().isEqualTo("SPONSOR_MFA_VERIFY");
        Map<String, Object> asked = grant(user);
        assertThat(facts(asked)).contains("second factor enrolled, own");
        assertThat(approve(asked)).isEqualTo("APPLIED");
    }
}
