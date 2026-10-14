package com.jabiz.app.it.security;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.test.TestOidcProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Accounts opened by identity providers (docs/design/10-security.md section 12; decision D39 items 1, 2 and 4; ROADMAP
 * phase 16f-1): only when the provider may, the entry lets people register and the provider vouches for an address
 * nobody uses; never linked to an account that has the address; Google's addresses only where Google owns them; then
 * signed in like any user. A takeover of an account somebody else registered removes its provider links
 * (section 15.6).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {"it.sql-log.enabled=true",
    "jabiz.mail.enabled=true", "jabiz.mail.from=jabiz@example.com", "jabiz.mail.base-url=https://app.example.com",
    "spring.mail.host=localhost", "spring.mail.port=3031", SignUpItSupport.ENTRY_PROPERTIES_REGISTRATION,
    SignUpItSupport.ENTRY_PROPERTIES_ADMIN, SignUpItSupport.ENTRY_PROPERTIES_LOOSE, OidcItSupport.STRICT_ACCEPTS,
    OidcItSupport.STRICT_REGISTERS, OidcItSupport.STRICT_GRANTS})
@Import(SignUpItSupport.FixedTimeLimits.class)
class OidcProvisionIT extends OidcItSupport {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3031, null, ServerSetup.PROTOCOL_SMTP));

    static final TestOidcProvider IDP = new TestOidcProvider();

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry registry) {
        providers(registry, IDP);
    }

    @AfterAll
    static void stop() {
        IDP.close();
    }

    @Override
    GreenMailExtension mail() {
        return MAIL;
    }

    @Override
    TestOidcProvider idp() {
        return IDP;
    }

    @BeforeEach
    void strictRole() {
        if (query("SELECT 1 FROM sec_role_version WHERE role_code = ?", STRICT_ROLE).isEmpty()) {
            insert(SecurityEntities.ROLE_DATASET, Map.of("roleCode", STRICT_ROLE, "labels", Map.of("en", "Strict"),
                "enabled", true, "requireMfa", true), null);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void aVerifiedUnusedAddressGetsAnAccountThatSignsIn() {
        String subject = unique("sub");
        String email = address("Fresh").replace("fresh", "Fresh");
        Request request = start(OPEN, PORTAL);
        Map<String, Object> session = callback(request, verified(request, subject, email)).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat(session).containsEntry("status", "SIGNED_IN");
        assertThat((List<String>) session.get("roles")).containsExactly(CUSTOMER);
        assertThat(tokens.verify((String) session.get("accessToken")).emailVerified()).isTrue();

        // Verified at once, without a password, never pre-claimable; named after the address.
        Map<String, Object> user = userOf(email);
        String userId = (String) user.get("user_id");
        assertThat(user).containsEntry("user_name", email.toLowerCase()).containsEntry("email", email)
            .containsEntry("verified_email", email).containsEntry("registered_email", null)
            .containsEntry("password_hash", null);
        assertThat(user.get("email_verified_at")).isNotNull();
        assertThat(query("SELECT display_name, locale FROM sec_user_version WHERE user_id = ?::uuid", userId))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("display_name", "Ada Lovelace"));
        assertThat(rolesOf(userId)).containsExactly(CUSTOMER);
        assertThat(linkedUser(OPEN, subject)).isEqualTo(userId);
        assertThat(query("SELECT email FROM sec_user_identity_version WHERE user_id = ?::uuid", userId))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("email", email));
        assertThat(query("SELECT outcome, factor, entry FROM sec_login_record_version WHERE user_id = ?::uuid",
            userId)).singleElement().satisfies(row -> assertThat(row).containsEntry("outcome", "SUCCESS")
            .containsEntry("factor", "OIDC").containsEntry("entry", PORTAL));
        // The writes are audited like any other (decision D27).
        assertThat(query("SELECT entity_type FROM sys_audit_record WHERE entity_id = ?", userId)).isNotEmpty();

        // The next sign-in finds the link; nothing more is opened, and the session refreshes.
        Request again = start(OPEN, PORTAL);
        assertThat(callback(again, verified(again, subject, email)).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody()).containsEntry("userId", userId);
        assertThat(usersWith(email)).isEqualTo(1);
        post("/api/auth/refresh", null, Map.of("refreshToken", session.get("refreshToken"), "entry", PORTAL))
            .expectStatus().isOk();
        // Nobody signs in with a password it does not have.
        assertThat(refusal(loginTo(email, "", PORTAL), 401)).isEqualTo("LOGIN_FAILED");
    }

    @Test
    void theTextTrueCountsAsVerified() {
        String email = address("text");
        Request request = start(OPEN, PORTAL);
        callback(request, idToken(request, unique("sub"), c -> c.claim("email", email).claim("email_verified", "true")))
            .expectStatus().isOk();
        assertThat(userOf(email)).isNotNull();
    }

    @Test
    void nothingIsOpenedUnlessAllFourHold() {
        // The provider opens no accounts.
        String closed = address("closed");
        Request request = start(CLOSED, PORTAL);
        assertThat(refusal(callback(request, verified(request, unique("sub"), closed)), 401)).isEqualTo("LOGIN_FAILED");
        // The entry takes no registrations: the administration never, nor an entry without registration.
        for (String entry : List.of("admin", LOOSE)) {
            Request at = start(OPEN, entry);
            assertThat(refusal(callback(at, verified(at, unique("sub"), closed)), 401)).isEqualTo("LOGIN_FAILED");
        }
        // The address is not verified, or missing.
        Request unverified = start(OPEN, PORTAL);
        assertThat(refusal(callback(unverified, idToken(unverified, unique("sub"), c -> c.claim("email", closed)
            .claim("email_verified", false))), 401)).isEqualTo("LOGIN_FAILED");
        Request without = start(OPEN, PORTAL);
        assertThat(refusal(callback(without, idToken(without, unique("sub"), c -> c.claim("email_verified", true))),
            401)).isEqualTo("LOGIN_FAILED");
        assertThat(usersWith(closed)).isZero();
    }

    @Test
    void googleVouchesOnlyForItsOwnAddressesAndWorkspaceDomains() {
        String elsewhere = address("goog");
        Request request = start(GOOGLE, PORTAL);
        assertThat(refusal(callback(request, verified(request, unique("sub"), elsewhere)), 401))
            .isEqualTo("LOGIN_FAILED");
        assertThat(usersWith(elsewhere)).isZero();

        Request workspace = start(GOOGLE, PORTAL);
        callback(workspace, idToken(workspace, unique("sub"), c -> c.claim("email", elsewhere)
            .claim("email_verified", true).claim("hd", "example.com"))).expectStatus().isOk();
        assertThat(usersWith(elsewhere)).isEqualTo(1);

        String gmail = unique("g").toLowerCase() + "@gmail.com";
        Request own = start(GOOGLE, PORTAL);
        callback(own, verified(own, unique("sub"), gmail)).expectStatus().isOk();
        assertThat(usersWith(gmail)).isEqualTo(1);
    }

    @Test
    void anAddressInUseIsNeverLinked() {
        // An account registered with the address (never verified: pre-claimable), in another case.
        String registered = address("reg");
        register(PORTAL, registered, PASSWORD).expectStatus().isAccepted();
        // An account an administrator made and its user verified.
        String verifiedAddress = address("ver");
        String verifiedUser = createUserWithEmail(unique("v"), verifiedAddress, PASSWORD);
        verifyEmail(verifiedUser);

        for (String email : List.of(registered.toUpperCase(), verifiedAddress)) {
            String subject = unique("sub");
            Request request = start(OPEN, PORTAL);
            assertThat(refusal(callback(request, verified(request, subject, email)), 403))
                .isEqualTo("OIDC_ACCOUNT_EXISTS");
            assertThat(linkedUser(OPEN, subject)).isNull();
            assertThat(usersWith(email)).isEqualTo(1);
        }
    }

    @Test
    void aRoleRequiringASecondFactorAsksForItsEnrolment() {
        String email = address("strict");
        Request request = start(OPEN, STRICT);
        Map<String, Object> answer = callback(request, verified(request, unique("sub"), email)).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat(answer).containsEntry("status", "MFA_ENROLLMENT_REQUIRED").containsEntry("accessToken", null);
        assertThat(rolesOf((String) userOf(email).get("user_id"))).containsExactly(STRICT_ROLE);
    }

    @Test
    void twoCallbacksOfOneSubjectAtOnceOpenOneAccount() throws Exception {
        String subject = unique("sub");
        String email = address("twice");
        List<Request> requests = List.of(start(OPEN, PORTAL), start(OPEN, PORTAL));
        List<String> tokens = requests.stream().map(r -> verified(r, subject, email)).toList();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> answers = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Request request = requests.get(i);
                String token = tokens.get(i);
                Callable<Integer> call = () -> {
                    go.await();
                    return callback(request, token).returnResult(Void.class).getStatus().value();
                };
                answers.add(pool.submit(call));
            }
            go.countDown();
            for (Future<Integer> answer : answers) {
                assertThat(answer.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(usersWith(email)).isEqualTo(1);
        assertThat(linkedUser(OPEN, subject)).isEqualTo(userOf(email).get("user_id"));
    }

    @Test
    void accountsOpenedAreLimitedByClient() {
        for (int i = 0; i < 20; i++) {
            Request request = start(OPEN, PORTAL);
            callback(request, verified(request, unique("sub"), address("many"))).expectStatus().isOk();
        }
        String last = address("limit");
        Request request = start(OPEN, PORTAL);
        assertThat(refusal(callback(request, verified(request, unique("sub"), last)), 429)).isEqualTo("RATE_LIMITED");
        assertThat(usersWith(last)).isZero();
    }

    @Test
    void startsAreLimitedByClient() {
        for (int i = 0; i < 60; i++) {
            anonymous("/api/auth/oidc/" + OPEN + "/start?entry=" + PORTAL, Map.of()).expectStatus().isOk();
        }
        assertThat(refusal(anonymous("/api/auth/oidc/" + OPEN + "/start?entry=" + PORTAL, Map.of()), 429))
            .isEqualTo("RATE_LIMITED");
    }

    @Test
    void theProvidersSayWhatKindTheyAre() {
        List<Map<String, Object>> offered = client.get().uri("/api/auth/oidc/providers?entry=" + PORTAL).exchange()
            .expectStatus().isOk().expectBody(LIST).returnResult().getResponseBody();
        assertThat(offered).extracting(p -> p.get("id") + ":" + p.get("kind"))
            .containsExactly("open:generic", "closed:generic", "google:google", BLOCKED + ":generic");
    }

    @Test
    void takingOverARegisteredAccountByJoiningRemovesItsProviderLinks() {
        String victim = address("taken");
        String attackerPassword = "attacker horse battery";
        register(PORTAL, victim, attackerPassword).expectStatus().isAccepted();
        String userId = (String) userOf(victim).get("user_id");
        // An administrator linked a provider account to it (an attacker's, say), which signs in where unverified
        // addresses may.
        String subject = unique("sub");
        insert(SecurityEntities.USER_IDENTITY_DATASET, Map.of("userId", userId, "provider", CLOSED,
            "subject", subject), null);
        Request request = start(CLOSED, LOOSE);
        Map<String, Object> attacker = callback(request, idToken(request, subject, c -> c)).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();

        // The mailbox's owner registers too and joins with the link of the "you already have an account" mail.
        register(PORTAL, victim, PASSWORD).expectStatus().isAccepted();
        deliver();
        anonymous("/api/auth/join", Map.of("token", token(JOIN_LINK, victim), "password", PASSWORD)).expectStatus()
            .isNoContent();

        assertThat(linkedUser(CLOSED, subject)).isNull();
        assertThat(refusal(post("/api/auth/refresh", null, Map.of("refreshToken", attacker.get("refreshToken"),
            "entry", LOOSE)), 401)).isEqualTo("INVALID_REFRESH_TOKEN");
        Request again = start(CLOSED, LOOSE);
        assertThat(refusal(callback(again, idToken(again, subject, c -> c)), 401)).isEqualTo("LOGIN_FAILED");
    }

    @Test
    void takingOverARegisteredAccountByAResetRemovesItsProviderLinks() {
        String victim = address("reset");
        register(PORTAL, victim, "attacker horse battery").expectStatus().isAccepted();
        String userId = (String) userOf(victim).get("user_id");
        String subject = unique("sub");
        insert(SecurityEntities.USER_IDENTITY_DATASET, Map.of("userId", userId, "provider", CLOSED,
            "subject", subject), null);

        anonymous("/api/auth/password-reset/request", Map.of("email", victim, "entry", PORTAL)).expectStatus()
            .isAccepted();
        deliver();
        anonymous("/api/auth/password-reset/confirm", Map.of("token", token(RESET_LINK, victim), "password",
            PASSWORD)).expectStatus().isNoContent();
        assertThat(linkedUser(CLOSED, subject)).isNull();
    }

    @Test
    void takingOverARegisteredAccountByVerifyingWithANewPasswordRemovesItsProviderLinks() {
        String victim = address("verify");
        register(PORTAL, victim, "attacker horse battery").expectStatus().isAccepted();
        String userId = (String) userOf(victim).get("user_id");
        String subject = unique("sub");
        insert(SecurityEntities.USER_IDENTITY_DATASET, Map.of("userId", userId, "provider", CLOSED,
            "subject", subject), null);
        deliver();
        anonymous("/api/auth/verify-email", Map.of("token", token(VERIFY_LINK, victim), "newPassword", PASSWORD))
            .expectStatus().isNoContent();
        assertThat(linkedUser(CLOSED, subject)).isNull();
    }

    @Test
    void aGuardRefusingTheNewAccountIsA403AndTheAccountStays() {
        String email = address("guarded");
        String subject = unique("sub");
        Request request = start(BLOCKED, PORTAL);
        assertThat(refusal(callback(request, verified(request, subject, email)), 403)).isEqualTo("SIGN_IN_REFUSED");
        String userId = (String) userOf(email).get("user_id");
        assertThat(linkedUser(BLOCKED, subject)).isEqualTo(userId);
        assertThat(query("SELECT outcome FROM sec_login_record_version WHERE user_id = ?::uuid", userId))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("outcome", "REFUSED"));
    }

    @Test
    void twoSubjectsOpeningOneAddressAtOnceGetOneAccount() throws Exception {
        String email = address("race");
        List<Request> requests = List.of(start(OPEN, PORTAL), start(OPEN, PORTAL));
        List<String> tokens = List.of(verified(requests.get(0), unique("sub"), email),
            verified(requests.get(1), unique("sub"), email));
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> answers = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Request request = requests.get(i);
                String token = tokens.get(i);
                answers.add(pool.submit(() -> {
                    go.await();
                    return callback(request, token).returnResult(Void.class).getStatus().value();
                }));
            }
            go.countDown();
            for (Future<Integer> answer : answers) {
                statuses.add(answer.get());
            }
        } finally {
            pool.shutdownNow();
        }
        // One opens the account; the other finds the address in use and is linked to nothing.
        assertThat(statuses).containsExactlyInAnyOrder(200, 403);
        assertThat(usersWith(email)).isEqualTo(1);
        assertThat(query("SELECT count(*) AS n FROM sec_user_identity_version WHERE user_id = ?::uuid",
            userOf(email).get("user_id")).getFirst().get("n")).isEqualTo(1L);
    }

    @Test
    void openingAnAccountOnlyAppendsRows() {
        Request request = start(OPEN, PORTAL);
        SqlStatementLog.STATEMENTS.clear();
        callback(request, verified(request, unique("sub"), address("append"))).expectStatus().isOk();
        // Only the temporal tables: the pending sign-ins are not kept (section 12).
        assertThat(SqlStatementLog.STATEMENTS).isNotEmpty()
            .filteredOn(sql -> sql.contains("_version"))
            .noneSatisfy(sql -> assertThat(sql.trim().toUpperCase(java.util.Locale.ROOT)).startsWith("UPDATE"))
            .noneSatisfy(sql -> assertThat(sql.trim().toUpperCase(java.util.Locale.ROOT)).startsWith("DELETE"));
    }
}
