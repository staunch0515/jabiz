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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invitations of an administrator's new users (decision D63; docs/design/10-security.md section 15.14): the mailed link
 * sets the user's password and proves the address, so the password is the user's own and a protected grant applies
 * after the setup window. Only an enabled user with an address that is not verified and that nobody else changed
 * since the account was created is invited; a newer invitation supersedes the earlier link, and accepting one voids the
 * others.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.mail.enabled=true", "jabiz.mail.from=jabiz@example.com", "jabiz.mail.base-url=https://app.example.com",
    "spring.mail.host=localhost", "spring.mail.port=3042", SignUpItSupport.ENTRY_PROPERTIES_REGISTRATION,
    SignUpItSupport.ENTRY_PROPERTIES_ADMIN, SignUpItSupport.ENTRY_PROPERTIES_LOOSE,
    "jabiz.security.protected-grants.trusted-email-domains=example.com"})
@Import(SignUpItSupport.FixedTimeLimits.class)
class InvitationIT extends SignUpItSupport {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3042, null, ServerSetup.PROTOCOL_SMTP));

    static final Pattern INVITE_LINK = Pattern.compile(
        "https://app\\.example\\.com/(?:portal/)?auth/invitation\\?entry=([a-z0-9-]+)&token=([A-Za-z0-9_-]+)");

    private static final String TEMPORARY = "temporary horse battery";
    private static final String OWN = "own horse battery";

    private static String adminOne;
    private static String adminTwo;

    @Override
    GreenMailExtension mail() {
        return MAIL;
    }

    /** Three administrators: the setup window is over, so protected grants need an approval (D48). */
    @BeforeEach
    void administrators() {
        if (adminOne != null) {
            return;
        }
        String admins = createRole(unique("ADMINS"));
        adminOne = createUserWithEmail(unique("admin1"), address("admin1"), null);
        adminTwo = createUserWithEmail(unique("admin2"), address("admin2"), null);
        assign(adminOne, admins, null);
        assign(adminTwo, admins, null);
        assign(createUserWithEmail(unique("admin3"), address("admin3"), null), admins, null);
        insert(SecurityEntities.ROLE_PERMISSION_DATASET, Map.of("roleId", admins, "permission", "*"), null);
        createRole(unique("PLAIN"), "price.read");
        assertThat(query("SELECT count(*) AS n FROM sec_protected_grants_armed").getFirst().get("n")).isEqualTo(1L);
    }

    @SuppressWarnings("unchecked")
    private String createInvited(String email) {
        Map<String, Object> result = post("/api/processes/SEC_USER_CREATE/latest", admin(), Map.of(
            "userName", unique("staff"), "email", email, "invite", true)).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        return (String) ((Map<String, Object>) result.get("output")).get("userId");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invite(String userId, String entry) {
        Map<String, Object> input = new HashMap<>(Map.of("userId", userId));
        if (entry != null) {
            input.put("entry", entry);
        }
        return (Map<String, Object>) post("/api/processes/SEC_USER_INVITE/latest", admin(), input).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody().get("output");
    }

    private String inviteRefused(String userId, Map<String, Object> extra) {
        Map<String, Object> input = new HashMap<>(Map.of("userId", userId));
        input.putAll(extra);
        return refusal(post("/api/processes/SEC_USER_INVITE/latest", admin(), input), 422);
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec accept(String token,
        String password) {
        return anonymous("/api/auth/invitation/confirm", Map.of("token", token, "password", password));
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
    void anInvitedUserSetsTheirOwnPasswordAndProvesTheAddress() {
        String email = address("invited");
        String user = createInvited(email);
        assertThat(userOf(email)).containsEntry("verified_email", null).containsEntry("password_hash", null);
        deliver();
        assertThat(to(email)).hasSize(1);
        assertThat(text(to(email).getFirst())).contains("auth/invitation?entry=admin&token=");
        String token = token(INVITE_LINK, email);

        accept(token, OWN).expectStatus().isNoContent();
        assertThat(userOf(email)).containsEntry("verified_email", email);
        assertThat(userOf(email).get("password_hash")).isNotNull();
        // Once only.
        assertThat(refusal(accept(token, "another horse battery"), 422)).isEqualTo("TOKEN_INVALID");
        // Signs in with the verified address and the chosen password.
        assign(user, roleId(CUSTOMER), null);
        session(email, OWN, LOOSE);
        // A verified address resets the password itself: no further invitation.
        assertThat(inviteRefused(user, Map.of())).isEqualTo("INVITATION_NOT_NEEDED");

        // The password is the user's own: a protected grant applies after the setup window.
        Map<String, Object> asked = grant(user);
        assertThat(facts(asked)).contains("(SPONSOR_INVITATION_ACCEPT): password changed, own")
            .doesNotContain("not the user's own");
        assertThat(approve(asked)).isEqualTo("APPLIED");
    }

    /** An account whose password an administrator knows: the invitation replaces it with the user's own. */
    @Test
    void anInvitationReplacesAPasswordTheAdministratorKnew() {
        String email = address("temp");
        String name = unique("temp");
        String user = createUserWithEmail(name, email, TEMPORARY);
        assertThat(invite(user, null)).containsEntry("userId", user).containsEntry("status", "SENT");
        deliver();
        accept(token(INVITE_LINK, email), OWN).expectStatus().isNoContent();
        assign(user, roleId(CUSTOMER), null);
        loginTo(name, TEMPORARY, LOOSE).expectStatus().isUnauthorized();
        session(name, OWN, LOOSE);
        assertThat(approve(grant(user))).isEqualTo("APPLIED");
    }

    @Test
    void aNewerInvitationSupersedesTheEarlierLinkAndNamesItsEntry() {
        String email = address("twice");
        String user = createUserWithEmail(unique("twice"), email, null);
        invite(user, null);
        deliver();
        String first = token(INVITE_LINK, email);
        invite(user, LOOSE);
        deliver();
        assertThat(text(to(email).getLast())).contains("auth/invitation?entry=loose&token=");
        String second = token(INVITE_LINK, email);
        assertThat(refusal(accept(first, OWN), 422)).isEqualTo("TOKEN_INVALID");
        accept(second, OWN).expectStatus().isNoContent();
        assertThat(userOf(email)).containsEntry("verified_email", email);
    }

    @Test
    void aLinkIsVoidOnceTheAddressChanged() {
        String email = address("moved");
        String user = createUserWithEmail(unique("moved"), email, null);
        invite(user, null);
        deliver();
        String token = token(INVITE_LINK, email);
        updateUser(user, Map.of("email", address("elsewhere")));
        assertThat(refusal(accept(token, OWN), 422)).isEqualTo("TOKEN_INVALID");
        assertThat(query("SELECT password_hash, verified_email FROM sec_user_version WHERE user_id = ?::uuid"
            + " ORDER BY version_no DESC LIMIT 1", user).getFirst()).containsEntry("password_hash", null)
            .containsEntry("verified_email", null);
    }

    /** Changing the address is how an account is taken over: only the address entered at creation is invited. */
    @Test
    void anAddressSomeoneElseChangedIsNotInvited() {
        String user = createUserWithEmail(unique("changed"), address("first"), null);
        updateUser(user, Map.of("email", address("second")));
        assertThat(inviteRefused(user, Map.of())).isEqualTo("INVITATION_ADDRESS_CHANGED");
        // An address added after the account was created is someone else's change too.
        @SuppressWarnings("unchecked")
        String bare = (String) ((Map<String, Object>) post("/api/processes/SEC_USER_CREATE/latest", admin(),
            Map.of("userName", unique("bare"))).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody()
            .get("output")).get("userId");
        updateUser(bare, Map.of("email", address("added")));
        assertThat(inviteRefused(bare, Map.of())).isEqualTo("INVITATION_ADDRESS_CHANGED");
    }

    @Test
    void onlyAnEnabledUserWithAnUnverifiedAddressIsInvited() {
        String verified = createUserWithEmail(unique("verified"), address("verified"), null);
        verifyEmail(verified);
        assertThat(inviteRefused(verified, Map.of())).isEqualTo("INVITATION_NOT_NEEDED");

        String disabled = createUserWithEmail(unique("disabled"), address("disabled"), null);
        updateUser(disabled, Map.of("enabled", false));
        assertThat(inviteRefused(disabled, Map.of())).isEqualTo("INVITATION_USER_DISABLED");

        @SuppressWarnings("unchecked")
        String noAddress = (String) ((Map<String, Object>) post("/api/processes/SEC_USER_CREATE/latest", admin(),
            Map.of("userName", unique("none"))).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody()
            .get("output")).get("userId");
        assertThat(inviteRefused(noAddress, Map.of())).isEqualTo("MAIL_NO_ADDRESS");

        String unknownEntry = createUserWithEmail(unique("entry"), address("entry"), null);
        assertThat(inviteRefused(unknownEntry, Map.of("entry", "nowhere"))).isEqualTo("INVALID_VALUE");
        deliver();
        assertThat(to(address("entry"))).isEmpty();
    }

    @Test
    void creatingWithAnInvitationNeedsAnAddressAndNoPassword() {
        String withPassword = unique("both");
        assertThat(refusal(post("/api/processes/SEC_USER_CREATE/latest", admin(), Map.of("userName", withPassword,
            "email", address("both"), "password", TEMPORARY, "invite", true)), 422)).isEqualTo("INVALID_VALUE");
        String withoutAddress = unique("noaddress");
        assertThat(refusal(post("/api/processes/SEC_USER_CREATE/latest", admin(), Map.of("userName", withoutAddress,
            "invite", true)), 422)).isEqualTo("MAIL_NO_ADDRESS");
        assertThat(query("SELECT 1 FROM sec_user_version WHERE user_name IN (?, ?)", withPassword, withoutAddress))
            .isEmpty();
    }

    /**
     * Inviting is setting the password of a holder of a protected permission (D48): after the setup window it is refused,
     * since whoever entered the address may read that mailbox. Withdrawn, invited, granted again: the approvers then see
     * the address proven by mail.
     */
    @Test
    void aHolderOfAProtectedPermissionIsNotInvitedAfterTheSetupWindow() {
        String email = address("holder");
        String user = createUserWithEmail(unique("holder"), email, null);
        // No credentials yet: nobody else can sign in, so the grant applies.
        assertThat(approve(grant(user))).isEqualTo("APPLIED");
        assertThat(inviteRefused(user, Map.of())).isEqualTo("PROTECTED_CHANGE_REQUIRES_APPROVAL");
        deliver();
        assertThat(to(email)).isEmpty();
    }

    /** Security review: a protected grant that came after the invitation was sent voids its link. */
    @Test
    void anInvitationIsNotAcceptedForAUserWhoGainedAProtectedPermissionSince() {
        String email = address("later");
        String user = createInvited(email);
        deliver();
        String token = token(INVITE_LINK, email);
        assertThat(approve(grant(user))).isEqualTo("APPLIED");
        assertThat(refusal(accept(token, OWN), 422)).isEqualTo("TOKEN_INVALID");
        assertThat(query("SELECT password_hash, verified_email FROM sec_user_version WHERE user_id = ?::uuid"
            + " ORDER BY version_no DESC LIMIT 1", user).getFirst()).containsEntry("password_hash", null)
            .containsEntry("verified_email", null);
    }

    /** Inviting a user who has a password replaces it: as setting a password, it needs security.user.password. */
    @Test
    void replacingAPasswordNeedsThePermissionToSetPasswords() {
        String user = createUserWithEmail(unique("haspw"), address("haspw"), TEMPORARY);
        post("/api/processes/SEC_USER_INVITE/latest", TestTokens.bearer(tokens, "it-creator", "security.user.create"),
            Map.of("userId", user)).expectStatus().isForbidden();
        post("/api/processes/SEC_USER_INVITE/latest", TestTokens.bearer(tokens, "it-creator", "security.user.create",
            "security.user.password"), Map.of("userId", user)).expectStatus().isOk();
    }

    @Test
    void creatingWithAnInvitationChecksTheAddressAndTheUser() {
        String malformed = unique("malformed");
        assertThat(refusal(post("/api/processes/SEC_USER_CREATE/latest", admin(), Map.of("userName", malformed,
            "email", "not-an-address", "invite", true)), 422)).isEqualTo("INVALID_VALUE");
        String disabled = unique("disabled");
        assertThat(refusal(post("/api/processes/SEC_USER_CREATE/latest", admin(), Map.of("userName", disabled,
            "email", address("off"), "enabled", false, "invite", true)), 422)).isEqualTo("INVITATION_USER_DISABLED");
        assertThat(query("SELECT 1 FROM sec_user_version WHERE user_name IN (?, ?)", malformed, disabled)).isEmpty();

        // Blanks around the address are not kept: the link holds for the address the mail went to.
        String email = address("padded");
        post("/api/processes/SEC_USER_CREATE/latest", admin(), Map.of("userName", unique("padded"),
            "email", " " + email + "\t", "invite", true)).expectStatus().isOk();
        deliver();
        accept(token(INVITE_LINK, email), OWN).expectStatus().isNoContent();
        assertThat(userOf(email)).containsEntry("email", email).containsEntry("verified_email", email);
    }

    @Test
    void inviteNeedsThePermissionToCreateUsers() {
        String user = createUserWithEmail(unique("perm"), address("perm"), null);
        post("/api/processes/SEC_USER_INVITE/latest", TestTokens.bearer(tokens, "it-reader", "security.user.read"),
            Map.of("userId", user)).expectStatus().isForbidden();
        post("/api/processes/SEC_USER_INVITE/latest", TestTokens.bearer(tokens, "it-creator", "security.user.create"),
            Map.of("userId", user)).expectStatus().isOk();
    }
}
