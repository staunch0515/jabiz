package com.jabiz.app.it.security;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.jabiz.app.it.fixture.ItSignInFixtures;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.security.SignUpAttempt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sign-up guards on every path that gives an account an entry's registration roles, and a signed-in user joining
 * another entry (docs/design/10-security.md section 15.15; decision D66; ROADMAP phase 17za). The test guard
 * ({@link ItSignInFixtures}) refuses addresses starting with {@value ItSignInFixtures#SIGN_UP_REFUSED_PREFIX} and users
 * linked to {@value ItSignInFixtures#SIGN_UP_BLOCKED}, throws for the failing ones, and lets everybody else through.
 * The entry {@value #CLUB} takes registrations with the role {@value #MEMBER}; {@value #VIP} with {@value #VIP_ROLE},
 * which holds a protected permission (decision D48), with no setup window ({@code protected-grants.always}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.mail.enabled=true", "jabiz.mail.from=jabiz@example.com", "jabiz.mail.base-url=https://app.example.com",
    "spring.mail.host=localhost", "spring.mail.port=3047", SignUpItSupport.ENTRY_PROPERTIES_REGISTRATION,
    SignUpItSupport.ENTRY_PROPERTIES_ADMIN, SignUpItSupport.ENTRY_PROPERTIES_LOOSE,
    "jabiz.security.entries.portal.email-link=true",
    "jabiz.security.entries.club.accepted-roles=" + SignUpGuardIT.MEMBER,
    "jabiz.security.entries.club.self-registration=true",
    "jabiz.security.entries.club.registration-roles=" + SignUpGuardIT.MEMBER,
    "jabiz.security.entries.vip.accepted-roles=" + SignUpGuardIT.VIP_ROLE,
    "jabiz.security.entries.vip.self-registration=true",
    "jabiz.security.entries.vip.registration-roles=" + SignUpGuardIT.VIP_ROLE,
    "jabiz.security.protected-grants.always=true"})
@Import(SignUpItSupport.FixedTimeLimits.class)
class SignUpGuardIT extends SignUpItSupport {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3047, null, ServerSetup.PROTOCOL_SMTP));

    static final String CLUB = "club";
    static final String MEMBER = "IT_MEMBER";
    static final String VIP = "vip";
    static final String VIP_ROLE = "IT_VIP";

    static final Pattern EMAIL_LINK = Pattern.compile(
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
            createRole(VIP_ROLE, "ledger.post");
        }
    }

    private WebTestClient.ResponseSpec joinAt(String entry, String bearer) {
        return client.post().uri("/api/auth/account/entries/{entry}/join", entry)
            .header(HttpHeaders.AUTHORIZATION, bearer).exchange();
    }

    /** Registers at the portal and verifies the address; returns a session at the portal. */
    private Map<String, Object> verifiedCustomer(String email) {
        register(PORTAL, email, PASSWORD).expectStatus().isAccepted();
        deliver();
        anonymous("/api/auth/verify-email", Map.of("token", token(VERIFY_LINK, email), "password", PASSWORD))
            .expectStatus().isNoContent();
        return session(email, PASSWORD, PORTAL);
    }

    private static String idOf(String email) {
        return String.valueOf(userOf(email).get("user_id"));
    }

    private void link(String userId, String provider) {
        insert(SecurityEntities.USER_IDENTITY_DATASET, Map.of("userId", userId, "provider", provider,
            "subject", unique("s")), null);
    }

    private static long assignmentRows(String userId) {
        return ((Number) query("SELECT count(*) AS n FROM sec_user_role_version WHERE user_id = ?::uuid", userId)
            .getFirst().get("n")).longValue();
    }

    private static Map<String, Object> problem(WebTestClient.ResponseSpec response, int status) {
        return response.expectStatus().isEqualTo(status).expectBody(MAP).returnResult().getResponseBody();
    }

    private static List<SignUpAttempt> seen(String key) {
        return ItSignInFixtures.SEEN_SIGN_UPS.getOrDefault(key.toLowerCase(), List.of());
    }

    // ---- the signed-in join ---------------------------------------------------------------------------------------

    @Test
    void aSignedInUserJoinsAnotherEntryOnceAndSignsInThere() {
        String email = address("joiner");
        Map<String, Object> portal = verifiedCustomer(email);
        String userId = idOf(email);
        assertThat(refusal(loginTo(email, PASSWORD, CLUB), 401)).isEqualTo("LOGIN_FAILED");

        joinAt(CLUB, bearerOf(portal)).expectStatus().isNoContent();
        assertThat(rolesOf(userId)).containsExactly(CUSTOMER, MEMBER);
        // The session it was asked in stays as it is; the user signs in at the joined entry now.
        get("/api/auth/me", bearerOf(portal)).expectStatus().isOk();
        loginTo(email, PASSWORD, CLUB).expectStatus().isOk();
        // Recorded as the user's operation, as the mailed join is.
        assertThat(query("SELECT actor_id FROM op_process WHERE process_name = 'SEC_ACCOUNT_ENTRY_JOIN'"
            + " AND actor_id = ?", userId)).hasSize(1);
        assertThat(seen(email)).filteredOn(attempt -> attempt.path() == SignUpAttempt.Path.SIGNED_IN_JOIN)
            .singleElement().satisfies(attempt -> {
                assertThat(attempt.entry()).isEqualTo(CLUB);
                assertThat(attempt.userId()).isEqualTo(userId);
                assertThat(attempt.emailVerified()).isTrue();
                assertThat(attempt.data()).containsKey(ItSignInFixtures.SIGN_UP_LOAD);
            });

        // Again: nothing changes, and nothing needs deciding.
        long rows = assignmentRows(userId);
        joinAt(CLUB, bearerOf(portal)).expectStatus().isNoContent();
        assertThat(assignmentRows(userId)).isEqualTo(rows);
        assertThat(rolesOf(userId)).containsExactly(CUSTOMER, MEMBER);
        assertThat(seen(email)).filteredOn(attempt -> attempt.path() == SignUpAttempt.Path.SIGNED_IN_JOIN)
            .hasSize(1);
        // From the joined entry's own session, too.
        joinAt(PORTAL, bearerOf(session(email, PASSWORD, CLUB))).expectStatus().isNoContent();
        assertThat(assignmentRows(userId)).isEqualTo(rows);
    }

    @Test
    void joiningNeedsAVerifiedAddressAndAnEntryThatTakesRegistrations() {
        String email = address("unverified");
        register(PORTAL, email, PASSWORD).expectStatus().isAccepted();
        // The loose entry takes customers without a verified address.
        Map<String, Object> loose = session(email, PASSWORD, LOOSE);
        assertThat(refusal(joinAt(CLUB, bearerOf(loose)), 403)).isEqualTo("EMAIL_NOT_VERIFIED");
        assertThat(rolesOf(idOf(email))).containsExactly(CUSTOMER);

        Map<String, Object> verified = verifiedCustomer(address("verified"));
        // An entry without registration and an entry that does not exist: the same answer.
        Map<String, Object> closed = problem(joinAt(LOOSE, bearerOf(verified)), 404);
        Map<String, Object> missing = problem(joinAt("nowhere", bearerOf(verified)), 404);
        assertThat(closed.get("status")).isEqualTo(missing.get("status"));
        // Signed in, or not at all.
        client.post().uri("/api/auth/account/entries/{entry}/join", CLUB).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void aProtectedRegistrationRoleIsNeverObtainedThisWay() {
        String email = address("vip");
        Map<String, Object> portal = verifiedCustomer(email);
        String userId = idOf(email);
        long rows = assignmentRows(userId);

        assertThat(refusal(joinAt(VIP, bearerOf(portal)), 422)).isEqualTo("PROTECTED_CHANGE_REQUIRES_APPROVAL");
        assertThat(rolesOf(userId)).containsExactly(CUSTOMER);
        assertThat(assignmentRows(userId)).isEqualTo(rows);
    }

    @Test
    void aGuardRefusesTheSignedInJoinAndAFailingGuardRefusesToo() {
        String blocked = address("blocked");
        Map<String, Object> session = verifiedCustomer(blocked);
        link(idOf(blocked), ItSignInFixtures.SIGN_UP_BLOCKED);
        Map<String, Object> refused = problem(joinAt(CLUB, bearerOf(session)), 403);
        assertThat(ruleCode(refused)).isEqualTo("SIGN_UP_REFUSED");
        assertThat(refused).containsEntry("reason", "refused");
        assertThat(violations(refused).getFirst()).containsEntry("message",
            "Registration here is not possible for this account.");
        assertThat(rolesOf(idOf(blocked))).containsExactly(CUSTOMER);

        String failing = address("failing");
        Map<String, Object> other = verifiedCustomer(failing);
        link(idOf(failing), ItSignInFixtures.SIGN_UP_FAILING);
        assertThat(refusal(joinAt(CLUB, bearerOf(other)), 403)).isEqualTo("SIGN_UP_REFUSED");
        assertThat(rolesOf(idOf(failing))).containsExactly(CUSTOMER);
    }

    // ---- the other paths ------------------------------------------------------------------------------------------

    @Test
    void aRegistrationIsAskedWithoutAUserAndAnsweredAlikeForEveryAddress() {
        // Allowed: 202 alike, and the guard saw the same for the new address and for the one in use.
        String fresh = address("fresh");
        String taken = address("taken");
        verifyEmail(createUserWithEmail(unique("taken"), taken, PASSWORD));
        EntityExchangeResult<byte[]> one = register(PORTAL, fresh, PASSWORD).expectStatus().isAccepted()
            .expectBody().returnResult();
        EntityExchangeResult<byte[]> two = register(PORTAL, taken, PASSWORD).expectStatus().isAccepted()
            .expectBody().returnResult();
        assertThat(one.getResponseBodyContent()).isEqualTo(two.getResponseBodyContent());
        for (String email : List.of(fresh, taken)) {
            assertThat(seen(email)).singleElement().satisfies(attempt -> {
                assertThat(attempt.path()).isEqualTo(SignUpAttempt.Path.REGISTER);
                assertThat(attempt.userId()).isNull();
                assertThat(attempt.existingUser()).isFalse();
                assertThat(attempt.emailVerified()).isFalse();
                assertThat(attempt.rows(ItSignInFixtures.SIGN_UP_LOAD)).isEmpty();
            });
        }

        // Refused: 403 alike for a new address and for one in use, with no user, no role and no mail.
        String refusedNew = address(ItSignInFixtures.SIGN_UP_REFUSED_PREFIX);
        String refusedTaken = address(ItSignInFixtures.SIGN_UP_REFUSED_PREFIX);
        String holder = createUserWithEmail(unique("held"), refusedTaken, PASSWORD);
        verifyEmail(holder);
        EntityExchangeResult<Map<String, Object>> a = register(PORTAL, refusedNew, PASSWORD).expectStatus()
            .isForbidden().expectBody(MAP).returnResult();
        EntityExchangeResult<Map<String, Object>> b = register(PORTAL, refusedTaken, PASSWORD).expectStatus()
            .isForbidden().expectBody(MAP).returnResult();
        assertThat(ruleCode(a.getResponseBody())).isEqualTo("SIGN_UP_REFUSED");
        assertThat(a.getResponseBody()).isEqualTo(b.getResponseBody());
        assertThat(userOf(refusedNew)).isNull();
        assertThat(rolesOf(holder)).isEmpty();
        // A guard that throws refuses a registration too.
        assertThat(refusal(register(PORTAL, address(ItSignInFixtures.SIGN_UP_THROWS_PREFIX), PASSWORD), 403))
            .isEqualTo("SIGN_UP_REFUSED");
        deliver();
        assertThat(to(refusedNew)).isEmpty();
        assertThat(to(refusedTaken)).isEmpty();
    }

    @Test
    void aGuardRefusesTheMailedJoinAtTheLinkWhichStaysUnused() {
        String email = address("mailjoin");
        String userId = createUserWithEmail(unique("mailjoin"), email, PASSWORD);
        verifyEmail(userId);
        link(userId, ItSignInFixtures.SIGN_UP_BLOCKED);
        // Registering tells nothing: 202, and the mail goes out.
        register(PORTAL, email, PASSWORD).expectStatus().isAccepted();
        deliver();
        String join = token(JOIN_LINK, email);

        assertThat(refusal(anonymous("/api/auth/join", Map.of("token", join)), 403)).isEqualTo("SIGN_UP_REFUSED");
        assertThat(rolesOf(userId)).isEmpty();
        // Not used up: asked again, the guard answers again.
        assertThat(refusal(anonymous("/api/auth/join", Map.of("token", join)), 403)).isEqualTo("SIGN_UP_REFUSED");
        assertThat(seen(email)).filteredOn(attempt -> attempt.path() == SignUpAttempt.Path.MAIL_JOIN)
            .allSatisfy(attempt -> {
                assertThat(attempt.userId()).isEqualTo(userId);
                assertThat(attempt.rows(ItSignInFixtures.SIGN_UP_LOAD)).extracting(row -> row.get("provider"))
                    .containsExactly(ItSignInFixtures.SIGN_UP_BLOCKED);
            });
    }

    @Test
    void aGuardRefusesAnAccountAMailedSignInLinkWouldOpen() {
        String email = address(ItSignInFixtures.SIGN_UP_REFUSED_PREFIX);
        anonymous("/api/auth/email-link", Map.of("entry", PORTAL, "email", email)).expectStatus().isAccepted();
        deliver();
        String token = token(EMAIL_LINK, email);
        assertThat(refusal(anonymous("/api/auth/email-link/confirm", Map.of("token", token)), 403))
            .isEqualTo("SIGN_UP_REFUSED");
        assertThat(userOf(email)).isNull();
        assertThat(seen(email)).singleElement().satisfies(attempt -> {
            assertThat(attempt.path()).isEqualTo(SignUpAttempt.Path.EMAIL_LINK);
            assertThat(attempt.emailVerified()).isTrue();
        });
    }
}
