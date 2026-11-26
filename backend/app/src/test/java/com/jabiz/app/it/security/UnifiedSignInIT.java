package com.jabiz.app.it.security;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.jabiz.app.it.fixture.ItSignInFixtures;
import com.jabiz.security.SignUpAttempt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One "continue" for signing in and signing up (docs/design/10-security.md section 15.19; decision D84): the entry
 * takes no password registration; a link to an address nobody has opens the account only once its owner gives a name
 * (asked with a dry-run check, the link unused until then); a user of another entry joins by link, through the sign-up
 * guards and the four eyes on protected grants; entries without it are as before.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {"it.sql-log.enabled=true",
    "jabiz.mail.enabled=true", "jabiz.mail.from=jabiz@example.com", "jabiz.mail.base-url=https://app.example.com",
    "spring.mail.host=localhost", "spring.mail.port=3051", SignUpItSupport.ENTRY_PROPERTIES_REGISTRATION,
    SignUpItSupport.ENTRY_PROPERTIES_ADMIN, SignUpItSupport.ENTRY_PROPERTIES_LOOSE,
    "jabiz.security.entries.portal.email-link=true",
    "jabiz.security.entries.one.accepted-roles=" + UnifiedSignInIT.MEMBER,
    "jabiz.security.entries.one.self-registration=true",
    "jabiz.security.entries.one.registration-roles=" + UnifiedSignInIT.MEMBER,
    "jabiz.security.entries.one.email-link=true",
    "jabiz.security.entries.one.unified-sign-in=true",
    "jabiz.security.entries.gold.accepted-roles=" + UnifiedSignInIT.GOLD_ROLE,
    "jabiz.security.entries.gold.self-registration=true",
    "jabiz.security.entries.gold.registration-roles=" + UnifiedSignInIT.GOLD_ROLE,
    "jabiz.security.entries.gold.email-link=true",
    "jabiz.security.entries.gold.unified-sign-in=true",
    "jabiz.security.protected-grants.always=true"})
@Import(SignUpItSupport.FixedTimeLimits.class)
class UnifiedSignInIT extends SignUpItSupport {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3051, null, ServerSetup.PROTOCOL_SMTP));

    static final String ONE = "one";
    static final String MEMBER = "IT_ONE_MEMBER";
    static final String GOLD = "gold";
    static final String GOLD_ROLE = "IT_GOLD";

    static final Pattern LINK = Pattern.compile(
        "https://app\\.example\\.com/(?:portal/)?auth/email-link\\?entry=([a-z0-9-]+)&token=([A-Za-z0-9_-]+)");

    @Override
    GreenMailExtension mail() {
        return MAIL;
    }

    @BeforeEach
    void entryRoles() {
        if (query("SELECT 1 FROM sec_role_version WHERE role_code = ?", MEMBER).isEmpty()) {
            createRole(MEMBER, "it.member");
            // Protected: whoever gets it may post to the books (decision D48).
            createRole(GOLD_ROLE, "ledger.post");
        }
    }

    private void ask(String entry, String email) {
        anonymous("/api/auth/email-link", Map.of("entry", entry, "email", email)).expectStatus().isAccepted();
        deliver();
    }

    private WebTestClient.ResponseSpec check(String token) {
        return anonymous("/api/auth/email-link/check", Map.of("token", token));
    }

    private boolean signUp(String token) {
        return Boolean.TRUE.equals(check(token).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody().get("signUp"));
    }

    private WebTestClient.ResponseSpec confirm(String token, String displayName, String locale) {
        Map<String, Object> body = new HashMap<>();
        body.put("token", token);
        if (displayName != null) {
            body.put("displayName", displayName);
        }
        if (locale != null) {
            body.put("locale", locale);
        }
        return anonymous("/api/auth/email-link/confirm", body);
    }

    private static List<SignUpAttempt> seen(String email) {
        return ItSignInFixtures.SEEN_SIGN_UPS.getOrDefault(email.toLowerCase(), List.of());
    }

    @Test
    void aNewAddressFinishesSigningUpBeforeTheAccountExists() {
        String email = address("fresh");
        ask(ONE, email);
        String token = token(LINK, email);

        // Asked as often as the page likes: nothing changes, the link stays good.
        assertThat(signUp(token)).isTrue();
        assertThat(signUp(token)).isTrue();
        assertThat(userOf(email)).isNull();

        // No name: refused, the link unused.
        Map<String, Object> problem = confirm(token, "  ", null).expectStatus().isEqualTo(422).expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(String.valueOf(problem.get("violations"))).contains("REQUIRED").contains("displayName");
        assertThat(userOf(email)).isNull();
        assertThat(refusal(confirm(token, "x".repeat(201), null), 400)).isEqualTo("INVALID_VALUE");
        assertThat(refusal(confirm(token, "Sora", "xx"), 400)).isEqualTo("INVALID_VALUE");

        Map<String, Object> session = confirm(token, " Sora ", "ja").expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(session).containsEntry("status", "SIGNED_IN").containsKey("accessToken");
        Map<String, Object> user = userOf(email);
        assertThat(user).containsEntry("verified_email", email).containsEntry("password_hash", null)
            .containsEntry("registered_email", null);
        assertThat(query("""
            SELECT display_name, locale FROM sec_user_version WHERE user_id = ?::uuid ORDER BY version_no DESC
            LIMIT 1""", user.get("user_id")).getFirst()).containsEntry("display_name", "Sora")
            .containsEntry("locale", "ja");
        assertThat(rolesOf((String) user.get("user_id"))).containsExactly(MEMBER);
        // Asked on every check too, so that a refusal shows before the name is asked for.
        assertThat(seen(email)).hasSize(4).allSatisfy(attempt -> {
            assertThat(attempt.path()).isEqualTo(SignUpAttempt.Path.EMAIL_LINK);
            assertThat(attempt.userId()).isNull();
        });

        // Used: the check and the link are no good any more.
        assertThat(refusal(check(token), 422)).isEqualTo("TOKEN_INVALID");
        assertThat(refusal(confirm(token, "Sora", null), 422)).isEqualTo("TOKEN_INVALID");

        // Next time the address is an account's: a sign-in link, no name asked.
        ask(ONE, email);
        String again = token(LINK, email);
        assertThat(signUp(again)).isFalse();
        assertThat(confirm(again, null, null).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody()).containsEntry("status", "SIGNED_IN");
    }

    @Test
    void aUserOfAnotherEntryJoinsByLink() {
        String email = address("joiner");
        String userId = createUserWithEmail(unique("joiner"), email, PASSWORD);
        verifyEmail(userId);
        assign(userId, roleId(CUSTOMER), null);

        ask(ONE, email);
        String token = token(LINK, email);
        assertThat(signUp(token)).isFalse();
        // The check is a dry run: no role yet.
        assertThat(rolesOf(userId)).containsExactly(CUSTOMER);

        assertThat(confirm(token, null, null).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody()).containsEntry("status", "SIGNED_IN");
        assertThat(rolesOf(userId)).containsExactlyInAnyOrder(CUSTOMER, MEMBER);
        // The password stays: a verified account is not taken over.
        assertThat(userOf(email).get("password_hash")).isNotNull();
        assertThat(seen(email)).filteredOn(attempt -> attempt.path() == SignUpAttempt.Path.EMAIL_LINK)
            .isNotEmpty().allSatisfy(attempt -> assertThat(attempt.userId()).isEqualTo(userId));
    }

    @Test
    void aJoinIsAskedOfTheGuardsAndProtectedGrantsNeedApproval() {
        String refused = address(ItSignInFixtures.SIGN_UP_REFUSED_PREFIX + "-join");
        String refusedId = createUserWithEmail(unique("refused"), refused, PASSWORD);
        verifyEmail(refusedId);
        assign(refusedId, roleId(CUSTOMER), null);
        ask(ONE, refused);
        String token = token(LINK, refused);
        assertThat(refusal(confirm(token, null, null), 403)).isEqualTo("SIGN_UP_REFUSED");
        assertThat(rolesOf(refusedId)).containsExactly(CUSTOMER);

        String gold = address("gold");
        String goldId = createUserWithEmail(unique("gold"), gold, PASSWORD);
        verifyEmail(goldId);
        assign(goldId, roleId(CUSTOMER), null);
        ask(GOLD, gold);
        assertThat(refusal(confirm(token(LINK, gold), null, null), 422))
            .isEqualTo("PROTECTED_CHANGE_REQUIRES_APPROVAL");
        assertThat(rolesOf(goldId)).containsExactly(CUSTOMER);
    }

    @Test
    void theEntryTakesNoPasswordRegistrationAndTellsTheFrontEnd() {
        assertThat(refusal(register(ONE, address("pw"), PASSWORD), 422)).isEqualTo("SIGN_UP_DISABLED");
        assertThat(client.get().uri("/api/auth/entries/one").exchange().expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody()).containsEntry("unifiedSignIn", true);
        assertThat(client.get().uri("/api/auth/entries/portal").exchange().expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody()).containsEntry("unifiedSignIn", false);
    }

    @Test
    void elsewhereALinkStillOpensTheAccountAtOnceAndNobodyJoins() {
        // The portal is as before: an address nobody has gets its account on using the link, without a name.
        String email = address("portal");
        ask(PORTAL, email);
        String token = token(LINK, email);
        assertThat(signUp(token)).isFalse();
        assertThat(confirm(token, null, null).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody()).containsEntry("status", "SIGNED_IN");
        assertThat(rolesOf((String) userOf(email).get("user_id"))).containsExactly(CUSTOMER);

        // A member of the unified entry at the portal: the portal does not accept the role, and nobody joins it by link.
        String member = address("member");
        ask(ONE, member);
        confirm(token(LINK, member), "Mei", null).expectStatus().isOk();
        ask(PORTAL, member);
        assertThat(to(member)).hasSize(1);
    }
}
