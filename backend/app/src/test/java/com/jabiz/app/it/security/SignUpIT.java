package com.jabiz.app.it.security;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.security.PasswordHasher;
import com.jabiz.runtime.security.secret.SingleUseSecrets;
import com.jabiz.runtime.test.TestTokens;
import com.jabiz.security.Base32;
import com.jabiz.security.Totp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Self-registration and address verification (docs/design/10-security.md section 15; decision D36 items 2 to 4 and
 * implementation notes 5, 6 and 12; ROADMAP phase 16b-2): the answer never tells whether an address has an account,
 * links are single-use tokens of their purpose, an address registered by somebody else cannot be taken over, and the
 * anonymous endpoints are rate limited by client and address.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {"it.sql-log.enabled=true",
    "jabiz.mail.enabled=true", "jabiz.mail.from=jabiz@example.com", "jabiz.mail.base-url=https://app.example.com",
    "spring.mail.host=localhost", "spring.mail.port=3028"})
class SignUpIT extends SignUpItSupport {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3028, null, ServerSetup.PROTOCOL_SMTP));

    /** Counts the passwords hashed, to show that both outcomes of a registration hash one. */
    @TestConfiguration
    static class CountingHasher {

        static final AtomicInteger HASHED = new AtomicInteger();

        @Bean
        @Primary
        PasswordHasher countingPasswordHasher() {
            return new PasswordHasher(4, 10) {
                @Override
                public String hash(String password) {
                    HASHED.incrementAndGet();
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

    @Test
    @SuppressWarnings("unchecked")
    void aNewAddressGetsAnUnverifiedUserWithTheEntrysRolesUntilTheLinkProvesIt() {
        String email = address("Ann").replace("ann", "Ann");
        register(PORTAL, email, PASSWORD).expectStatus().isAccepted();

        Map<String, Object> user = userOf(email);
        String userId = (String) user.get("user_id");
        // Named after the address in lower case; the address is kept as given.
        assertThat(user).containsEntry("user_name", email.toLowerCase(Locale.ROOT)).containsEntry("email", email)
            .containsEntry("verified_email", null).containsEntry("enabled", true);
        assertThat(rolesOf(userId)).containsExactly(CUSTOMER);

        // The portal requires a verified address: the password is right, so the answer may say so.
        assertThat(refusal(loginTo(email.toLowerCase(Locale.ROOT), PASSWORD, PORTAL), 403))
            .isEqualTo("EMAIL_NOT_VERIFIED");
        // An unverified address does not stand for the user name (when it is not the name itself).
        assertThat(refusal(loginTo(email, PASSWORD, PORTAL), 401)).isEqualTo("LOGIN_FAILED");

        deliver();
        assertThat(to(email)).singleElement().satisfies(message -> assertThat(text(message))
            .contains("https://app.example.com/auth/verify-email?entry=portal&token="));
        anonymous("/api/auth/verify-email", Map.of("token", token(VERIFY_LINK, email))).expectStatus().isNoContent();

        assertThat(userOf(email)).containsEntry("verified_email", email);
        Map<String, Object> session = loginTo(email, PASSWORD, PORTAL).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(tokens.verify((String) session.get("accessToken")).emailVerified()).isTrue();
        assertThat((List<String>) session.get("roles")).containsExactly(CUSTOMER);
    }

    @Test
    void theAnswerIsTheSameWhetherOrNotTheAddressHasAnAccount() throws Exception {
        String email = address("same");
        int before = CountingHasher.HASHED.get();
        EntityExchangeResult<byte[]> fresh = accepted(register(PORTAL, email, PASSWORD));
        assertThat(CountingHasher.HASHED.get()).isEqualTo(before + 1);
        String userId = (String) userOf(email).get("user_id");
        String hashBefore = (String) userOf(email).get("password_hash");

        // Again, in other letters and with another password: the same answer, and the password is not used.
        before = CountingHasher.HASHED.get();
        EntityExchangeResult<byte[]> taken = accepted(register(PORTAL,
            email.toUpperCase(Locale.ROOT), "another horse battery"));
        assertThat(CountingHasher.HASHED.get()).isEqualTo(before + 1);

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
    void anExistingAccountGetsTheEntrysRolesOnlyThroughTheLinkOfItsMail() {
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
        anonymous("/api/auth/join", Map.of("token", join)).expectStatus().isNoContent();
        assertThat(rolesOf(userId)).containsExactly(CUSTOMER);
        loginTo(name, PASSWORD, PORTAL).expectStatus().isOk();
        assertThat(refusal(loginTo(name, "attacker horse battery", PORTAL), 401)).isEqualTo("LOGIN_FAILED");

        // Once only.
        assertThat(refusal(anonymous("/api/auth/join", Map.of("token", join)), 422)).isEqualTo("TOKEN_INVALID");
        // Joining again later does not add the role twice.
        register(PORTAL, email, PASSWORD).expectStatus().isAccepted();
        deliver();
        anonymous("/api/auth/join", Map.of("token", token(JOIN_LINK, email))).expectStatus().isNoContent();
        assertThat(rolesOf(userId)).containsExactly(CUSTOMER);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anAddressRegisteredBySomebodyElseCannotBeTakenOver() {
        String victim = address("victim");
        String attackerPassword = "attacker horse battery";
        // The attacker registers the victim's address first, signs in where no verified address is required and sets
        // up a second factor of their own.
        register(PORTAL, victim, attackerPassword).expectStatus().isAccepted();
        String userId = (String) userOf(victim).get("user_id");
        Map<String, Object> attacker = loginTo(victim, attackerPassword, null).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        String secret = (String) post("/api/auth/mfa/enroll", bearerOf(attacker), Map.of()).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody().get("secret");
        post("/api/auth/mfa/enroll/confirm", bearerOf(attacker), Map.of("code",
            Totp.code(Base32.decode(secret), Totp.step(clock.instant())))).expectStatus().isOk();
        assertThat(query("SELECT 1 FROM sec_user_mfa_version WHERE user_id = ?::uuid", userId)).isNotEmpty();

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
        assertThat(refusal(loginTo(victim, attackerPassword, null), 401)).isEqualTo("LOGIN_FAILED");
        assertThat(refusal(post("/api/auth/refresh", null, Map.of("refreshToken", attacker.get("refreshToken"))),
            401)).isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(query("SELECT reason FROM sec_refresh_family_revocation r JOIN sec_refresh_token t "
            + "ON t.family_id = r.family_id WHERE t.user_id = ?::uuid", userId))
            .extracting(row -> row.get("reason")).contains("JOIN");
        Map<String, Object> session = loginTo(victim, PASSWORD, PORTAL).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(session).containsEntry("status", "SIGNED_IN");
        assertThat((List<String>) session.get("roles")).containsExactly(CUSTOMER);
        assertThat(userOf(victim)).containsEntry("verified_email", victim);
    }

    @Test
    void onlyEntriesThatAllowItTakeRegistrations() {
        String email = address("adm");
        assertThat(refusal(register("admin", email, PASSWORD), 403)).isEqualTo("SIGN_UP_DISABLED");
        assertThat(refusal(register(null, email, PASSWORD), 403)).isEqualTo("SIGN_UP_DISABLED");
        assertThat(refusal(register("no-such-entry", email, PASSWORD), 403)).isEqualTo("SIGN_UP_DISABLED");
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

        // Stored as a hash only: the token is nowhere in the database.
        assertThat(query("SELECT 1 FROM sys_mail_token WHERE token_hash = ?", SingleUseSecrets.sha256Hex(verify)))
            .hasSize(1);
        assertThat(query("SELECT 1 FROM sys_mail_token WHERE token_hash = ?", verify)).isEmpty();
        anonymous("/api/auth/verify-email/resend", Map.of("email", email, "entry", PORTAL)).expectStatus()
            .isAccepted();

        // A verification token is no join or reset token; nor is an access or challenge token a mail token.
        assertThat(refusal(anonymous("/api/auth/join", Map.of("token", verify, "password", PASSWORD)), 422))
            .isEqualTo("TOKEN_INVALID");
        assertThat(refusal(anonymous("/api/auth/password-reset/confirm", Map.of("token", verify, "password",
            PASSWORD)), 422)).isEqualTo("TOKEN_INVALID");
        String access = TestTokens.bearer(tokens, "it-x", "p").substring("Bearer ".length());
        assertThat(refusal(anonymous("/api/auth/verify-email", Map.of("token", access)), 422))
            .isEqualTo("TOKEN_INVALID");
        String challenge = tokens.issueChallenge(String.valueOf(userOf(email).get("user_id")),
            JwtService.Purpose.VERIFY, 1, null, PORTAL, Duration.ofMinutes(5)).token();
        assertThat(refusal(anonymous("/api/auth/verify-email", Map.of("token", challenge)), 422))
            .isEqualTo("TOKEN_INVALID");
        assertThat(refusal(anonymous("/api/auth/verify-email", Map.of()), 422)).isEqualTo("TOKEN_INVALID");

        // The resent mail supersedes the first link; the second is good once.
        deliver();
        String second = token(VERIFY_LINK, email);
        assertThat(second).isNotEqualTo(verify);
        assertThat(refusal(anonymous("/api/auth/verify-email", Map.of("token", verify)), 422))
            .isEqualTo("TOKEN_INVALID");
        anonymous("/api/auth/verify-email", Map.of("token", second)).expectStatus().isNoContent();
        assertThat(refusal(anonymous("/api/auth/verify-email", Map.of("token", second)), 422))
            .isEqualTo("TOKEN_INVALID");

        // Nothing of the tokens was recorded with the operations or their events.
        for (String token : List.of(verify, second)) {
            assertThat(query("SELECT 1 FROM op_process WHERE input_summary::text LIKE ?", "%" + token + "%"))
                .isEmpty();
            assertThat(query("SELECT 1 FROM sys_mail_message WHERE params LIKE ?", "%" + token + "%")).isEmpty();
            assertThat(query("SELECT 1 FROM sys_outbox_event WHERE payload::text LIKE ?", "%" + token + "%"))
                .isEmpty();
            assertThat(query("SELECT 1 FROM sys_audit_record WHERE changes LIKE ?", "%" + token + "%"))
                .isEmpty();
        }
    }

    @Test
    void aLinkExpiresAndIsVoidOnceTheAddressChanged() {
        String expired = address("exp");
        register(PORTAL, expired, PASSWORD).expectStatus().isAccepted();
        deliver();
        String token = token(VERIFY_LINK, expired);
        clock.advance(Duration.ofHours(24));
        assertThat(refusal(anonymous("/api/auth/verify-email", Map.of("token", token)), 422))
            .isEqualTo("TOKEN_INVALID");

        String moved = address("mov");
        register(PORTAL, moved, PASSWORD).expectStatus().isAccepted();
        deliver();
        String link = token(VERIFY_LINK, moved);
        String userId = String.valueOf(userOf(moved).get("user_id"));
        Map<String, Object> user = get("/api/datasets/" + userDataset() + "/entities/" + userId, admin())
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        post("/api/datasets/" + userDataset() + "/commit", admin(), Map.of("changes", List.of(Map.of(
            "action", "UPDATE", "id", userId, "version", user.get("version"), "attributes",
            Map.of("email", address("elsewhere")))))).expectStatus().isOk();
        assertThat(refusal(anonymous("/api/auth/verify-email", Map.of("token", link)), 422))
            .isEqualTo("TOKEN_INVALID");
    }

    @Test
    void aVerificationIsResentOnlyToUnverifiedUsersAndAlwaysAnsweredAlike() {
        String unknown = address("nobody");
        anonymous("/api/auth/verify-email/resend", Map.of("email", unknown, "entry", PORTAL)).expectStatus()
            .isAccepted();
        String verified = address("done");
        verifyEmail(createUserWithEmail(unique("done"), verified, PASSWORD));
        anonymous("/api/auth/verify-email/resend", Map.of("email", verified, "entry", PORTAL)).expectStatus()
            .isAccepted();
        assertThat(refusal(anonymous("/api/auth/verify-email/resend", Map.of("email", verified, "entry", "nope")),
            400)).isEqualTo("INVALID_VALUE");
        deliver();
        assertThat(to(unknown)).isEmpty();
        assertThat(to(verified)).isEmpty();

        // A signed-in user asks for their own, for the entry of the session.
        String own = address("own");
        String name = unique("own");
        String userId = createUserWithEmail(name, own, PASSWORD);
        assign(userId, roleId(CUSTOMER), null);
        String bearer = bearerOf(loginTo(name, PASSWORD, null).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody());
        post("/api/auth/account/verify-email", bearer, Map.of()).expectStatus().isAccepted();
        post("/api/auth/account/verify-email", null, Map.of()).expectStatus().isUnauthorized();
        deliver();
        assertThat(text(to(own).getFirst())).contains("/auth/verify-email?entry=admin&token=");
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
            from("203.0.113.77", "/api/auth/register", Map.of("entry", PORTAL, "email", email, "password",
                PASSWORD)).expectStatus().isEqualTo(429);
        }

        // One client, any addresses: 20 an hour; a forwarded header from an untrusted connection changes nothing.
        String client = "203.0.113.9";
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
        anonymous("/api/auth/verify-email", Map.of("token", token(VERIFY_LINK, email))).expectStatus().isNoContent();
        anonymous("/api/auth/join", Map.of("token", token(JOIN_LINK, email))).expectStatus().isNoContent();

        assertThat(SqlStatementLog.STATEMENTS).isNotEmpty()
            .noneSatisfy(sql -> assertThat(sql.trim().toUpperCase(Locale.ROOT)).startsWith("UPDATE"))
            .noneSatisfy(sql -> assertThat(sql.trim().toUpperCase(Locale.ROOT)).startsWith("DELETE"));
    }
}
