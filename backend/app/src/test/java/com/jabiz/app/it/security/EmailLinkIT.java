package com.jabiz.app.it.security;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.jabiz.runtime.security.SecurityEntities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Signing in with a mailed one-time link (docs/design/10-security.md section 15.11; decision D54): the request answers
 * alike for every address; the link signs in an account's user, takes over an account nobody verified, or opens an
 * account for an address nobody uses, and leads back to the path it carries; the sign-in is recorded, a second factor
 * is still asked, and the link is good once.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {"it.sql-log.enabled=true",
    "jabiz.mail.enabled=true", "jabiz.mail.from=jabiz@example.com", "jabiz.mail.base-url=https://app.example.com",
    "spring.mail.host=localhost", "spring.mail.port=3029", SignUpItSupport.ENTRY_PROPERTIES_REGISTRATION,
    SignUpItSupport.ENTRY_PROPERTIES_ADMIN, SignUpItSupport.ENTRY_PROPERTIES_LOOSE,
    "jabiz.security.entries.portal.email-link=true", "jabiz.security.entries.loose.email-link=true",
    "jabiz.security.entries.ops.accepted-roles=" + SignUpItSupport.BACK_OFFICE, "jabiz.security.entries.ops.email-link=true"})
@Import(SignUpItSupport.FixedTimeLimits.class)
class EmailLinkIT extends SignUpItSupport {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3029, null, ServerSetup.PROTOCOL_SMTP));

    static final Pattern LINK = Pattern.compile(
        "https://app\\.example\\.com/(?:portal/)?auth/email-link\\?entry=([a-z0-9-]+)&token=([A-Za-z0-9_-]+)&to=([^)\\s]*)");

    @Override
    GreenMailExtension mail() {
        return MAIL;
    }

    private WebTestClient.ResponseSpec ask(String entry, String email, String to) {
        Map<String, Object> body = new HashMap<>();
        body.put("entry", entry);
        body.put("email", email);
        if (to != null) {
            body.put("to", to);
        }
        return anonymous("/api/auth/email-link", body);
    }

    private WebTestClient.ResponseSpec confirm(String token) {
        return anonymous("/api/auth/email-link/confirm", Map.of("token", token));
    }

    private Map<String, Object> signedIn(String token) {
        return confirm(token).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
    }

    private static List<Map<String, Object>> factorsOf(String userId) {
        return query("SELECT outcome, factor FROM sec_login_record_version WHERE user_id = ?::uuid ORDER BY row_id",
            userId);
    }

    @Test
    void aLinkOpensAnAccountForANewAddressAndLeadsBack() {
        String email = address("new");
        ask(PORTAL, email, "/portal/p/123?ref=share").expectStatus().isAccepted();
        // Nothing opened before the mailbox is proven.
        assertThat(query("SELECT 1 FROM sec_user_version WHERE lower(email) = lower(?)", email)).isEmpty();
        deliver();
        assertThat(to(email)).singleElement().satisfies(m -> assertThat(text(m))
            .contains("https://app.example.com/portal/auth/email-link?entry=portal&token=")
            .contains("&to=%2Fportal%2Fp%2F123%3Fref%3Dshare"));
        String token = token(LINK, email);

        Map<String, Object> session = signedIn(token);
        assertThat(session).containsEntry("status", "SIGNED_IN").containsKey("accessToken");
        Map<String, Object> user = userOf(email);
        // Verified, never pre-claimable, no password; the entry's registration roles.
        assertThat(user).containsEntry("verified_email", email).containsEntry("registered_email", null)
            .containsEntry("password_hash", null).containsEntry("enabled", true);
        assertThat(rolesOf((String) user.get("user_id"))).containsExactly(CUSTOMER);
        assertThat(factorsOf((String) user.get("user_id"))).singleElement().satisfies(r -> assertThat(r)
            .containsEntry("outcome", "SUCCESS").containsEntry("factor", "EMAIL_LINK"));

        // Good once.
        assertThat(refusal(confirm(token), 422)).isEqualTo("TOKEN_INVALID");
        // Asked again, and again later: the account's user's links, each signing in the same user.
        for (int i = 0; i < 2; i++) {
            clock.advance(Duration.ofMinutes(1));
            ask(PORTAL, email, null).expectStatus().isAccepted();
            deliver();
            assertThat(signedIn(token(LINK, email))).containsEntry("status", "SIGNED_IN");
        }
        assertThat(query("SELECT DISTINCT user_id FROM sec_user_version WHERE lower(email) = lower(?)", email))
            .hasSize(1);
        assertThat(factorsOf((String) user.get("user_id"))).hasSize(3);
    }

    @Test
    void ofTwoMailsOutAtOnceTheFirstUsedWins() {
        String email = address("two");
        String userId = createUserWithEmail(unique("two"), email, PASSWORD);
        verifyEmail(userId);
        roleOf(userId, CUSTOMER);
        ask(PORTAL, email, null).expectStatus().isAccepted();
        deliver();
        String first = token(LINK, email);
        ask(PORTAL, email, null).expectStatus().isAccepted();
        deliver();
        String second = token(LINK, email);
        assertThat(second).isNotEqualTo(first);
        // The earlier mail is still good (a resend, or the outbox sending again); the other is then used up.
        signedIn(first);
        assertThat(refusal(confirm(second), 422)).isEqualTo("TOKEN_INVALID");
    }

    @Test
    void aVerifiedUserSignsInAndAnUnverifiedRegistrationIsTakenOver() {
        String verified = address("ver");
        String userId = createUserWithEmail(unique("ver"), verified, PASSWORD);
        verifyEmail(userId);
        roleOf(userId, CUSTOMER);
        ask(PORTAL, verified, "/portal/").expectStatus().isAccepted();
        deliver();
        signedIn(token(LINK, verified));
        assertThat(userOf(verified)).containsEntry("user_id", userId);

        // Somebody registered an address that is not theirs: the mailbox's owner takes the account, password and all.
        String claimed = address("claim");
        register(PORTAL, claimed, PASSWORD).expectStatus().isAccepted();
        ask(PORTAL, claimed, null).expectStatus().isAccepted();
        deliver();
        String registrant = (String) userOf(claimed).get("user_id");
        // The registrant set up a second factor: it goes with the account, as do the registrant's sessions.
        Map<String, Object> theirs = session((String) userOf(claimed).get("user_name"), PASSWORD, LOOSE);
        enrollMfa(bearerOf(theirs));
        assertThat(hasMfa(registrant)).isTrue();
        ask(PORTAL, claimed, null).expectStatus().isAccepted();
        deliver();
        assertThat(signedIn(token(LINK, claimed))).containsEntry("status", "SIGNED_IN");
        assertThat(userOf(claimed)).containsEntry("user_id", registrant).containsEntry("password_hash", null)
            .containsEntry("verified_email", claimed);
        assertThat(hasMfa(registrant)).isFalse();
        assertThat(refusal(loginTo(claimed, PASSWORD, PORTAL), 401)).isEqualTo("LOGIN_FAILED");
        anonymous("/api/auth/refresh", Map.of("refreshToken", theirs.get("refreshToken"), "entry", LOOSE))
            .expectStatus().isUnauthorized();
    }

    @Test
    void onlyUsersTheEntryWouldSignInGetALinkAndTheAdministrationNeverCounts() {
        // A back-office user at the portal: the portal does not accept the role.
        String staff = address("staff");
        String staffId = createUserWithEmail(unique("staff"), staff, PASSWORD);
        verifyEmail(staffId);
        roleOf(staffId, BACK_OFFICE);
        ask(PORTAL, staff, null).expectStatus().isAccepted();
        // An entry that accepts it, by link: a role granting every permission does not count there.
        ask("ops", staff, null).expectStatus().isAccepted();
        deliver();
        assertThat(to(staff)).isEmpty();

        // The role went after the link was mailed: the link is refused before anything changes.
        String gone = address("gone");
        String goneId = createUserWithEmail(unique("gone"), gone, PASSWORD);
        verifyEmail(goneId);
        roleOf(goneId, CUSTOMER);
        ask(PORTAL, gone, null).expectStatus().isAccepted();
        deliver();
        String token = token(LINK, gone);
        unassign(goneId);
        assertThat(refusal(confirm(token), 422)).isEqualTo("TOKEN_INVALID");
    }

    @Test
    void theAnswerIsTheSameForEveryAddressAndOnlyTrustedOnesGetALink() {
        // An address an administrator set and nobody verified: nothing.
        String set = address("set");
        createUserWithEmail(unique("set"), set, PASSWORD);
        ask(PORTAL, set, null).expectStatus().isAccepted();
        // An entry that opens no accounts: nothing for an address nobody uses.
        String nobody = address("nobody");
        ask(LOOSE, nobody, null).expectStatus().isAccepted();
        deliver();
        assertThat(to(set)).isEmpty();
        assertThat(to(nobody)).isEmpty();

        // Not where it is off (the administration), and only a path of the front end.
        assertThat(refusal(ask("admin", nobody, null), 422)).isEqualTo("SIGN_UP_DISABLED");
        for (String bad : List.of("//evil.example", "https://evil.example/", "/\\evil", "p/1", "/portal/a/../b",
            "/portal/%2e%2e/admin", "/portal/a/..?x", "/admin/", "/p/1", "/portalx/")) {
            assertThat(refusal(ask(PORTAL, nobody, bad), 400)).as(bad).isEqualTo("INVALID_VALUE");
        }
        assertThat(refusal(ask(PORTAL, "not an address", null), 400)).isEqualTo("INVALID_VALUE");
        assertThat(refusal(confirm("no-such-token"), 422)).isEqualTo("TOKEN_INVALID");
    }

    @Test
    void aSecondFactorIsStillAskedAndADisabledUserIsRefused() {
        String email = address("mfa");
        String name = unique("mfa");
        String userId = createUserWithEmail(name, email, PASSWORD);
        verifyEmail(userId);
        roleOf(userId, CUSTOMER);
        enrollMfa(bearerOf(session(name, PASSWORD, LOOSE)));
        ask(PORTAL, email, null).expectStatus().isAccepted();
        deliver();
        assertThat(signedIn(token(LINK, email))).containsEntry("status", "MFA_REQUIRED").containsKey("challenge");

        String off = address("off");
        String offId = createUserWithEmail(unique("off"), off, PASSWORD);
        verifyEmail(offId);
        roleOf(offId, CUSTOMER);
        ask(PORTAL, off, null).expectStatus().isAccepted();
        deliver();
        String token = token(LINK, off);
        updateUser(offId, Map.of("enabled", false));
        assertThat(refusal(confirm(token), 422)).isEqualTo("TOKEN_INVALID");
    }

    private void roleOf(String userId, String role) {
        assign(userId, roleId(role), null);
    }

    /** Removes the user's (only) role assignment, as an administrator. */
    private void unassign(String userId) {
        Map<String, Object> assignment = query("""
            SELECT user_role_id::text AS id, version_no FROM sec_user_role_version WHERE user_id = ?::uuid
            ORDER BY version_no DESC LIMIT 1""", userId).getFirst();
        post("/api/datasets/" + SecurityEntities.USER_ROLE_DATASET + "/commit", admin(), Map.of("changes", List.of(
            Map.of("action", "DELETE", "id", assignment.get("id"), "version", assignment.get("version_no")))))
            .expectStatus().isOk();
    }
}
