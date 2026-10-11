package com.jabiz.app.it.security;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.security.PasswordHasher;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.security.secret.SingleUseSecrets;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Self-registration and address verification (docs/design/10-security.md section 15.7; decision D36 items 2 to 4 and
 * implementation notes 5, 6 and 12; ROADMAP phase 16b-2): the answer never tells whether an address has an account,
 * links are single-use tokens of their purpose, an address registered by somebody else cannot be kept by them once the
 * mailbox's owner proves it, an address an administrator set is never trusted for a mailed link, registered users do
 * not reach the administration, and the anonymous endpoints are rate limited by client and address.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {"it.sql-log.enabled=true",
    "jabiz.mail.enabled=true", "jabiz.mail.from=jabiz@example.com", "jabiz.mail.base-url=https://app.example.com",
    "spring.mail.host=localhost", "spring.mail.port=3028", SignUpItSupport.ENTRY_PROPERTIES_REGISTRATION,
    SignUpItSupport.ENTRY_PROPERTIES_ADMIN, SignUpItSupport.ENTRY_PROPERTIES_LOOSE})
@Import(SignUpItSupport.FixedTimeLimits.class)
class SignUpIT extends SignUpItSupport {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3028, null, ServerSetup.PROTOCOL_SMTP));

    /** Records the passwords hashed, to show that both outcomes of a registration hash the submitted one. */
    @TestConfiguration
    static class RecordingHasher {

        static final Set<String> HASHED = ConcurrentHashMap.newKeySet();

        @Bean
        @Primary
        PasswordHasher recordingPasswordHasher() {
            return new PasswordHasher(4, 10) {
                @Override
                public String hash(String password) {
                    HASHED.add(password);
                    return super.hash(password);
                }
            };
        }
    }

    @Override
    GreenMailExtension mail() {
        return MAIL;
    }

    private static EntityExchangeResult<byte[]> accepted(WebTestClient.ResponseSpec response) {
        return response.expectStatus().isAccepted().expectBody().returnResult();
    }

    private static Map<String, List<String>> withoutRequestId(HttpHeaders headers) {
        Map<String, List<String>> copy = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach(copy::put);
        copy.remove("X-Request-Id");
        return copy;
    }

    private WebTestClient.ResponseSpec from(String client, String path, Map<String, ?> body) {
        return this.client.post().uri(path).header(CONNECTION, client).contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body).exchange();
    }

    private WebTestClient.ResponseSpec verify(Map<String, ?> body) {
        return anonymous("/api/auth/verify-email", body);
    }

    private Map<String, Object> check(String token) {
        return anonymous("/api/auth/verify-email/check", Map.of("token", token)).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aNewAddressGetsAnUnverifiedUserWithTheEntrysRolesUntilTheLinkProvesIt() {
        String email = address("Ann").replace("ann", "Ann");
        register(PORTAL, email, PASSWORD).expectStatus().isAccepted();

        Map<String, Object> user = userOf(email);
        String userId = (String) user.get("user_id");
        // Named after the address in lower case; the address is kept as given, and as the one registered with.
        assertThat(user).containsEntry("user_name", email.toLowerCase(Locale.ROOT)).containsEntry("email", email)
            .containsEntry("registered_email", email).containsEntry("verified_email", null)
            .containsEntry("enabled", true);
        assertThat(rolesOf(userId)).containsExactly(CUSTOMER);

        // The portal requires a verified address: the password is right, so the answer may say so.
        assertThat(refusal(loginTo(email.toLowerCase(Locale.ROOT), PASSWORD, PORTAL), 403))
            .isEqualTo("EMAIL_NOT_VERIFIED");
        // An unverified address does not stand for the user name (when it is not the name itself).
        assertThat(refusal(loginTo(email, PASSWORD, PORTAL), 401)).isEqualTo("LOGIN_FAILED");

        deliver();
        assertThat(to(email)).singleElement().satisfies(message -> assertThat(text(message))
            .contains("https://app.example.com/auth/verify-email?entry=portal&token="));
        String token = token(VERIFY_LINK, email);
        // Somebody may have registered the address of somebody else: the link alone does not verify it.
        assertThat(check(token)).containsEntry("passwordRequired", true);
        Map<String, Object> problem = verify(Map.of("token", token)).expectStatus().isEqualTo(422).expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(violations(problem)).singleElement().satisfies(v -> assertThat(v)
            .containsEntry("ruleCode", "REQUIRED").containsEntry("field", "password"));
        assertThat(refusal(verify(Map.of("token", token, "password", "wrong horse battery")), 422))
            .isEqualTo("PASSWORD_INCORRECT");
        // The registrant keeps the account with its password.
        verify(Map.of("token", token, "password", PASSWORD)).expectStatus().isNoContent();

        assertThat(userOf(email)).containsEntry("verified_email", email);
        Map<String, Object> session = session(email, PASSWORD, PORTAL);
        assertThat(tokens.verify((String) session.get("accessToken")).emailVerified()).isTrue();
        assertThat((List<String>) session.get("roles")).containsExactly(CUSTOMER);
    }

    @Test
    void registeredUsersDoNotReachTheAdministration() {
        String email = address("cust");
        register(PORTAL, email, PASSWORD).expectStatus().isAccepted();
        deliver();
        verify(Map.of("token", token(VERIFY_LINK, email), "password", PASSWORD)).expectStatus().isNoContent();

        session(email, PASSWORD, PORTAL);
        // Without an entry is the administration's, which accepts the back office only.
        assertThat(refusal(loginTo(email, PASSWORD, null), 401)).isEqualTo("LOGIN_FAILED");
        assertThat(refusal(loginTo(email, PASSWORD, "admin"), 401)).isEqualTo("LOGIN_FAILED");
    }

    @Test
    void theAnswerIsTheSameWhetherOrNotTheAddressHasAnAccount() {
        String email = address("same");
        String first = unique("first horse battery");
        EntityExchangeResult<byte[]> fresh = accepted(register(PORTAL, email, first));
        assertThat(RecordingHasher.HASHED).contains(first);
        String userId = (String) userOf(email).get("user_id");
        String hashBefore = (String) userOf(email).get("password_hash");

        // Again, in other letters and with another password: the same answer, and the password is hashed, not used.
        String second = unique("another horse battery");
        EntityExchangeResult<byte[]> taken = accepted(register(PORTAL, email.toUpperCase(Locale.ROOT), second));
        assertThat(RecordingHasher.HASHED).contains(second);

        assertThat(taken.getStatus()).isEqualTo(fresh.getStatus());
        // Every header alike but the request's own id.
        assertThat(withoutRequestId(taken.getResponseHeaders())).isEqualTo(withoutRequestId(fresh.getResponseHeaders()));
        assertThat(taken.getResponseHeaders().getFirst("X-Request-Id")).isNotBlank();
        // Said to have no body, so that clients do not wait for one.
        assertThat(taken.getResponseHeaders().getContentLength()).isZero();
        assertThat(taken.getResponseBodyContent()).isEqualTo(fresh.getResponseBodyContent());
        assertThat(query("SELECT 1 FROM sec_user_version WHERE lower(email) = ?", email)).hasSize(1);
        assertThat(userOf(email)).containsEntry("user_id", userId).containsEntry("password_hash", hashBefore);

        // Only the mail differs: a verification for the new user, "you already have an account" for the second.
        deliver();
        assertThat(to(email)).hasSize(2);
        assertThat(text(to(email).get(0))).contains("/auth/verify-email?entry=portal&token=");
        assertThat(text(to(email).get(1))).contains("/auth/join?entry=portal&token=");
    }

    @Test
    void twoRegistrationsOfOneNewAddressAtOnceMakeOneUserAndBothMails() throws Exception {
        String email = address("race");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> attempt = () -> {
                start.await();
                return register(PORTAL, email, PASSWORD).returnResult(Void.class).getStatus().value();
            };
            List<Future<Integer>> results = List.of(pool.submit(attempt), pool.submit(attempt));
            start.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(202);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(query("SELECT DISTINCT user_id FROM sec_user_version WHERE lower(email) = ?", email)).hasSize(1);
        deliver();
        // The one that came second answered as for an address in use: its user hears of it.
        assertThat(to(email)).extracting(SignUpItSupport::text)
            .anySatisfy(text -> assertThat(text).contains("/auth/verify-email?"))
            .anySatisfy(text -> assertThat(text).contains("/auth/join?"));
    }

    @Test
    void anExistingVerifiedAccountGetsTheEntrysRolesOnlyThroughTheLinkOfItsMail() {
        String email = address("bea");
        String name = unique("bea");
        String userId = createUserWithEmail(name, email, PASSWORD);
        verifyEmail(userId);

        register(PORTAL, email, "attacker horse battery").expectStatus().isAccepted();
        assertThat(rolesOf(userId)).isEmpty();
        deliver();
        String join = token(JOIN_LINK, email);
        assertThat(rolesOf(userId)).isEmpty();

        // A verified address needs no new password: the roles are added, the password stays.
        anonymous("/api/auth/join", Map.of("token", join, "password", "ignored horse battery")).expectStatus()
            .isNoContent();
        assertThat(rolesOf(userId)).containsExactly(CUSTOMER);
        loginTo(name, PASSWORD, PORTAL).expectStatus().isOk();
        assertThat(refusal(loginTo(name, "attacker horse battery", PORTAL), 401)).isEqualTo("LOGIN_FAILED");
        assertThat(refusal(loginTo(name, "ignored horse battery", PORTAL), 401)).isEqualTo("LOGIN_FAILED");

        // Once only.
        assertThat(refusal(anonymous("/api/auth/join", Map.of("token", join)), 422)).isEqualTo("TOKEN_INVALID");
        // Joining again later does not add the role twice.
        register(PORTAL, email, PASSWORD).expectStatus().isAccepted();
        deliver();
        anonymous("/api/auth/join", Map.of("token", token(JOIN_LINK, email))).expectStatus().isNoContent();
        assertThat(rolesOf(userId)).containsExactly(CUSTOMER);
    }

    @Test
    void anAddressAnAdministratorSetGetsNoMailUntilItsUserVerifiesIt() {
        // Created with an address nobody verified (as every account created before phase 16b-2).
        String email = address("set");
        String name = unique("set");
        String userId = createUserWithEmail(name, email, PASSWORD);
        assign(userId, roleId(CUSTOMER), null);

        register(PORTAL, email, PASSWORD).expectStatus().isAccepted();
        anonymous("/api/auth/verify-email/resend", Map.of("email", email, "entry", PORTAL)).expectStatus()
            .isAccepted();
        deliver();
        assertThat(to(email)).isEmpty();
        assertThat(rolesOf(userId)).containsExactly(CUSTOMER);

        // Its user, signed in, asks for the link; it needs no password (the account is no registration).
        String bearer = bearerOf(session(name, PASSWORD, LOOSE));
        post("/api/auth/account/verify-email", bearer, Map.of()).expectStatus().isAccepted();
        post("/api/auth/account/verify-email", null, Map.of()).expectStatus().isUnauthorized();
        deliver();
        assertThat(text(to(email).getFirst())).contains("/auth/verify-email?entry=loose&token=");
        String token = token(VERIFY_LINK, email);
        assertThat(check(token)).containsEntry("passwordRequired", false);
        verify(Map.of("token", token)).expectStatus().isNoContent();
        assertThat(userOf(email)).containsEntry("verified_email", email);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anAddressRegisteredBySomebodyElseIsTakenOverByJoining() {
        String victim = address("victim");
        String attackerPassword = "attacker horse battery";
        // The attacker registers the victim's address first, signs in where no verified address is required and sets
        // up a second factor of their own.
        register(PORTAL, victim, attackerPassword).expectStatus().isAccepted();
        String userId = (String) userOf(victim).get("user_id");
        Map<String, Object> attacker = session(victim, attackerPassword, LOOSE);
        enrollMfa(bearerOf(attacker));
        assertThat(hasMfa(userId)).isTrue();

        // The victim registers too and gets the "you already have an account" mail.
        register(PORTAL, victim, PASSWORD).expectStatus().isAccepted();
        deliver();
        String join = token(JOIN_LINK, victim);

        // The address was never proven: joining needs a new password.
        Map<String, Object> problem = anonymous("/api/auth/join", Map.of("token", join)).expectStatus()
            .isEqualTo(422).expectBody(MAP).returnResult().getResponseBody();
        assertThat(violations(problem)).singleElement().satisfies(v -> assertThat(v)
            .containsEntry("ruleCode", "REQUIRED").containsEntry("field", "password"));
        anonymous("/api/auth/join", Map.of("token", join, "password", PASSWORD)).expectStatus().isNoContent();

        // The attacker's password, session and second factor are gone; the victim's address is verified.
        assertThat(refusal(loginTo(victim, attackerPassword, LOOSE), 401)).isEqualTo("LOGIN_FAILED");
        assertThat(refusal(post("/api/auth/refresh", null, Map.of("refreshToken", attacker.get("refreshToken"),
            "entry", LOOSE)), 401)).isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(query("SELECT reason FROM sec_refresh_family_revocation r JOIN sec_refresh_token t "
            + "ON t.family_id = r.family_id WHERE t.user_id = ?::uuid", userId))
            .extracting(row -> row.get("reason")).contains("JOIN");
        assertThat(hasMfa(userId)).isFalse();
        Map<String, Object> session = session(victim, PASSWORD, PORTAL);
        assertThat(session).containsEntry("status", "SIGNED_IN");
        assertThat((List<String>) session.get("roles")).containsExactly(CUSTOMER);
        assertThat(userOf(victim)).containsEntry("verified_email", victim);
    }

    @Test
    void anAddressRegisteredBySomebodyElseIsTakenOverByVerifyingWithANewPassword() {
        String victim = address("prey");
        String attackerPassword = "attacker horse battery";
        register(PORTAL, victim, attackerPassword).expectStatus().isAccepted();
        String userId = (String) userOf(victim).get("user_id");
        Map<String, Object> attacker = session(victim, attackerPassword, LOOSE);
        enrollMfa(bearerOf(attacker));

        // The verification link of the registration reached the victim's mailbox.
        deliver();
        String token = token(VERIFY_LINK, victim);
        assertThat(refusal(verify(Map.of("token", token, "newPassword", "short")), 400))
            .isEqualTo("PASSWORD_TOO_SHORT");
        verify(Map.of("token", token, "newPassword", PASSWORD)).expectStatus().isNoContent();

        assertThat(refusal(loginTo(victim, attackerPassword, LOOSE), 401)).isEqualTo("LOGIN_FAILED");
        assertThat(refusal(post("/api/auth/refresh", null, Map.of("refreshToken", attacker.get("refreshToken"),
            "entry", LOOSE)), 401)).isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(query("SELECT reason FROM sec_refresh_family_revocation r JOIN sec_refresh_token t "
            + "ON t.family_id = r.family_id WHERE t.user_id = ?::uuid", userId))
            .extracting(row -> row.get("reason")).contains("CLAIM");
        assertThat(hasMfa(userId)).isFalse();
        assertThat(session(victim, PASSWORD, PORTAL)).containsEntry("status", "SIGNED_IN");
    }

    @Test
    void onlyEntriesThatAllowItTakeRegistrations() {
        String email = address("adm");
        assertThat(refusal(register("admin", email, PASSWORD), 422)).isEqualTo("SIGN_UP_DISABLED");
        assertThat(refusal(register(null, email, PASSWORD), 422)).isEqualTo("SIGN_UP_DISABLED");
        assertThat(refusal(register("no-such-entry", email, PASSWORD), 422)).isEqualTo("SIGN_UP_DISABLED");
        assertThat(userOf(email)).isNull();

        // What a front end may know of its entry.
        assertThat(get("/api/auth/entries/portal", null).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody()).containsEntry("name", "portal").containsEntry("selfRegistration", true)
            .containsEntry("requireVerifiedEmail", true).containsEntry("appPath", "/");
        assertThat(get("/api/auth/entries/admin", null).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody()).containsEntry("selfRegistration", false);
        get("/api/auth/entries/no-such-entry", null).expectStatus().isNotFound();
    }

    @Test
    void aJoinRefusedForTheEntryLeavesTheLinkUsable() {
        String email = address("dis");
        String userId = createUserWithEmail(unique("dis"), email, PASSWORD);
        verifyEmail(userId);
        register(PORTAL, email, PASSWORD).expectStatus().isAccepted();
        deliver();
        String join = token(JOIN_LINK, email);

        String role = roleId(CUSTOMER);
        setRoleEnabled(role, false);
        try {
            assertThat(refusal(anonymous("/api/auth/join", Map.of("token", join)), 422))
                .isEqualTo("SIGN_UP_DISABLED");
        } finally {
            setRoleEnabled(role, true);
        }
        anonymous("/api/auth/join", Map.of("token", join)).expectStatus().isNoContent();
        assertThat(rolesOf(userId)).containsExactly(CUSTOMER);
    }

    private void setRoleEnabled(String roleId, boolean enabled) {
        Map<String, Object> role = get("/api/datasets/" + SecurityEntities.ROLE_DATASET + "/entities/" + roleId,
            admin()).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        post("/api/datasets/" + SecurityEntities.ROLE_DATASET + "/commit", admin(), Map.of("changes", List.of(Map.of(
            "action", "UPDATE", "id", roleId, "version", role.get("version"), "attributes",
            Map.of("enabled", enabled))))).expectStatus().isOk();
    }

    @Test
    void everyValueIsCheckedBeforeTheAddressIsLookedUp() {
        String email = address("val");
        createUserWithEmail(unique("val"), email, PASSWORD);
        for (String address : List.of(email, address("val-new"))) {
            assertThat(refusal(register(PORTAL, address, "short"), 400)).isEqualTo("PASSWORD_TOO_SHORT");
            Map<String, Object> body = new HashMap<>(Map.of("entry", PORTAL, "email", address, "password", PASSWORD,
                "locale", "fr"));
            assertThat(refusal(anonymous("/api/auth/register", body), 400)).isEqualTo("INVALID_VALUE");
        }
        assertThat(refusal(register(PORTAL, "not-an-address", PASSWORD), 400)).isEqualTo("INVALID_VALUE");
        assertThat(refusal(register(PORTAL, "a@example.com\r\nBcc: b@example.com", PASSWORD), 400))
            .isEqualTo("INVALID_VALUE");
        assertThat(refusal(register(PORTAL, null, PASSWORD), 400)).isEqualTo("REQUIRED");
    }

    @Test
    void linksAreSingleUseTokensOfTheirPurpose() {
        String email = address("tok");
        register(PORTAL, email, PASSWORD).expectStatus().isAccepted();
        deliver();
        String verify = token(VERIFY_LINK, email);

        // Stored as a hash only, of the platform's purpose: the token is nowhere in the database.
        assertThat(query("SELECT purpose FROM sys_mail_token WHERE token_hash = ?",
            SingleUseSecrets.sha256Hex(verify))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("purpose", "jabiz.verify"));
        assertThat(query("SELECT 1 FROM sys_mail_token WHERE token_hash = ?", verify)).isEmpty();
        anonymous("/api/auth/verify-email/resend", Map.of("email", email, "entry", PORTAL)).expectStatus()
            .isAccepted();

        // A verification token is no join or reset token; nor is an access or challenge token a mail token.
        assertThat(refusal(anonymous("/api/auth/join", Map.of("token", verify, "password", PASSWORD)), 422))
            .isEqualTo("TOKEN_INVALID");
        assertThat(refusal(anonymous("/api/auth/password-reset/confirm", Map.of("token", verify, "password",
            PASSWORD)), 422)).isEqualTo("TOKEN_INVALID");
        String access = TestTokens.bearer(tokens, "it-x", "p").substring("Bearer ".length());
        assertThat(refusal(verify(Map.of("token", access)), 422)).isEqualTo("TOKEN_INVALID");
        String challenge = tokens.issueChallenge(String.valueOf(userOf(email).get("user_id")),
            JwtService.Purpose.VERIFY, 1, null, PORTAL, Duration.ofMinutes(5)).token();
        assertThat(refusal(verify(Map.of("token", challenge)), 422)).isEqualTo("TOKEN_INVALID");
        assertThat(refusal(verify(Map.of()), 422)).isEqualTo("TOKEN_INVALID");
        assertThat(refusal(anonymous("/api/auth/verify-email/check", Map.of("token", access)), 422))
            .isEqualTo("TOKEN_INVALID");

        // The resent mail supersedes the first link; the second is good once, and checking it does not use it.
        deliver();
        String second = token(VERIFY_LINK, email);
        assertThat(second).isNotEqualTo(verify);
        assertThat(refusal(verify(Map.of("token", verify, "password", PASSWORD)), 422)).isEqualTo("TOKEN_INVALID");
        check(second);
        check(second);
        verify(Map.of("token", second, "password", PASSWORD)).expectStatus().isNoContent();
        assertThat(refusal(verify(Map.of("token", second, "password", PASSWORD)), 422)).isEqualTo("TOKEN_INVALID");
        assertThat(refusal(anonymous("/api/auth/verify-email/check", Map.of("token", second)), 422))
            .isEqualTo("TOKEN_INVALID");

        // Nothing of the tokens was recorded with the operations or their events.
        for (String token : List.of(verify, second)) {
            assertThat(query("SELECT 1 FROM op_process WHERE input_summary::text LIKE ?", "%" + token + "%"))
                .isEmpty();
            assertThat(query("SELECT 1 FROM sys_mail_message WHERE params LIKE ?", "%" + token + "%")).isEmpty();
            assertThat(query("SELECT 1 FROM sys_outbox_event WHERE payload::text LIKE ?", "%" + token + "%"))
                .isEmpty();
            assertThat(query("SELECT 1 FROM sys_audit_record WHERE changes LIKE ?", "%" + token + "%")).isEmpty();
        }
    }

    @Test
    void aLinkExpiresAndIsVoidOnceTheAddressChanged() {
        String expired = address("exp");
        register(PORTAL, expired, PASSWORD).expectStatus().isAccepted();
        deliver();
        String token = token(VERIFY_LINK, expired);
        clock.advance(Duration.ofHours(24));
        assertThat(refusal(verify(Map.of("token", token, "password", PASSWORD)), 422)).isEqualTo("TOKEN_INVALID");

        String moved = address("mov");
        register(PORTAL, moved, PASSWORD).expectStatus().isAccepted();
        deliver();
        String link = token(VERIFY_LINK, moved);
        updateUser(String.valueOf(userOf(moved).get("user_id")), Map.of("email", address("elsewhere")));
        assertThat(refusal(verify(Map.of("token", link, "password", PASSWORD)), 422)).isEqualTo("TOKEN_INVALID");
    }

    @Test
    void aVerificationIsResentOnlyToRegistrationsNotVerifiedAndAlwaysAnsweredAlike() {
        String unknown = address("nobody");
        anonymous("/api/auth/verify-email/resend", Map.of("email", unknown, "entry", PORTAL)).expectStatus()
            .isAccepted();
        String verified = address("done");
        verifyEmail(createUserWithEmail(unique("done"), verified, PASSWORD));
        anonymous("/api/auth/verify-email/resend", Map.of("email", verified, "entry", PORTAL)).expectStatus()
            .isAccepted();
        assertThat(refusal(anonymous("/api/auth/verify-email/resend", Map.of("email", verified, "entry", "nope")),
            400)).isEqualTo("INVALID_VALUE");
        String registered = address("reg");
        register(PORTAL, registered, PASSWORD).expectStatus().isAccepted();
        anonymous("/api/auth/verify-email/resend", Map.of("email", registered, "entry", PORTAL)).expectStatus()
            .isAccepted();
        deliver();
        assertThat(to(unknown)).isEmpty();
        assertThat(to(verified)).isEmpty();
        assertThat(to(registered)).hasSize(2);
    }

    @Test
    void registrationsAreLimitedByAddressAlikeForEveryAddressAndByClient() {
        String taken = address("lim");
        createUserWithEmail(unique("lim"), taken, PASSWORD);
        String free = address("lim-free");
        for (String email : List.of(taken, free)) {
            for (int i = 0; i < 3; i++) {
                register(PORTAL, email, PASSWORD).expectStatus().isAccepted();
            }
            EntityExchangeResult<byte[]> result = register(PORTAL, email, PASSWORD).expectStatus().isEqualTo(429)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER).expectBody().returnResult();
            assertThat(new String(result.getResponseBodyContent())).contains("RATE_LIMITED");
            // Another client is limited by the address too.
            from(nextClient(), "/api/auth/register", Map.of("entry", PORTAL, "email", email, "password", PASSWORD))
                .expectStatus().isEqualTo(429);
        }
        // An hour later, the address may try again.
        FixedTimeLimits.NANOS.addAndGet(Duration.ofMinutes(21).toNanos());
        register(PORTAL, free, PASSWORD).expectStatus().isAccepted();

        // One client, any addresses: 20 an hour; a forwarded header from an untrusted connection changes nothing.
        String client = nextClient();
        for (int i = 0; i < 20; i++) {
            from(client, "/api/auth/password-reset/request", Map.of("email", address("many"))).expectStatus()
                .isAccepted();
        }
        this.client.post().uri("/api/auth/password-reset/request").header(CONNECTION, client)
            .header("X-Forwarded-For", "192.0.2.200").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("email", address("many"))).exchange().expectStatus().isEqualTo(429);
    }

    @Test
    void registeringAndVerifyingOnlyAppendRows() {
        String email = address("app");
        SqlStatementLog.STATEMENTS.clear();
        register(PORTAL, email, PASSWORD).expectStatus().isAccepted();
        register(PORTAL, email, PASSWORD).expectStatus().isAccepted();
        deliver();
        String join = token(JOIN_LINK, email);
        verify(Map.of("token", token(VERIFY_LINK, email), "password", PASSWORD)).expectStatus().isNoContent();
        anonymous("/api/auth/join", Map.of("token", join)).expectStatus().isNoContent();

        assertThat(SqlStatementLog.STATEMENTS).isNotEmpty()
            .noneSatisfy(sql -> assertThat(sql.trim().toUpperCase(Locale.ROOT)).startsWith("UPDATE"))
            .noneSatisfy(sql -> assertThat(sql.trim().toUpperCase(Locale.ROOT)).startsWith("DELETE"));
    }
}
