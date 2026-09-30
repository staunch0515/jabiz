package com.jabiz.app.it.security;

import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.test.TestOidcProvider;
import com.jabiz.runtime.test.TestTokens;
import com.jabiz.security.Base32;
import com.jabiz.security.Totp;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sign-in through an OpenID Connect provider (docs/design/10-security.md section 12; decision D28 item 6; ROADMAP
 * phase 14g-2), against the test provider on a local port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "it.sql-log.enabled=true")
class OidcIT extends SecurityItSupport {

    static final TestOidcProvider IDP = new TestOidcProvider();

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry registry) {
        registry.add("jabiz.security.oidc.providers[0].id", () -> "test");
        registry.add("jabiz.security.oidc.providers[0].issuer", IDP::issuer);
        registry.add("jabiz.security.oidc.providers[0].client-id", () -> TestOidcProvider.CLIENT_ID);
        registry.add("jabiz.security.oidc.providers[0].client-secret", () -> TestOidcProvider.CLIENT_SECRET);
        registry.add("jabiz.security.oidc.providers[0].redirect-uri", () -> TestOidcProvider.REDIRECT_URI);
        registry.add("jabiz.security.oidc.providers[0].labels.en", () -> "Test directory");
        registry.add("jabiz.security.oidc.providers[0].mfa-amr[0]", () -> "hwk");
    }

    @AfterAll
    static void stop() {
        IDP.close();
    }

    /** An authorization request as the provider receives it, and the binder the starting browser keeps. */
    private record Request(String state, String nonce, String challenge, String binder) {}

    private Request start() {
        Map<String, Object> started = post("/api/auth/oidc/test/start", null, Map.of()).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        Map<String, String> query = TestOidcProvider.query((String) started.get("authorizationUrl"));
        assertThat(query).containsEntry("client_id", TestOidcProvider.CLIENT_ID)
            .containsEntry("redirect_uri", TestOidcProvider.REDIRECT_URI).containsEntry("response_type", "code")
            .containsEntry("code_challenge_method", "S256").containsKeys("state", "nonce", "code_challenge");
        assertThat(query.get("scope")).contains("openid");
        assertThat((String) started.get("authorizationUrl")).doesNotContain((String) started.get("binder"));
        return new Request(query.get("state"), query.get("nonce"), query.get("code_challenge"),
            (String) started.get("binder"));
    }

    private JWTClaimsSet claims(String subject, Request request, UnaryOperator<JWTClaimsSet.Builder> change) {
        return change.apply(IDP.claims(subject, request.nonce(), clock.instant())).build();
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec callback(Request request,
        String idToken) {
        return post("/api/auth/oidc/callback", null, Map.of("state", request.state(),
            "code", IDP.code(request.challenge(), idToken), "binder", request.binder()));
    }

    private Map<String, Object> signedIn(Request request, String idToken) {
        return callback(request, idToken).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
    }

    private void refused(Request request, String idToken) {
        assertThat(ruleCode(callback(request, idToken).expectStatus().isUnauthorized().expectBody(MAP).returnResult()
            .getResponseBody())).isEqualTo("LOGIN_FAILED");
    }

    /** A user without a password, linked to a subject at the test provider; returns the user id. */
    private String linkedUser(String subject, String... permissions) {
        String name = unique("sso");
        Map<String, Object> result = post("/api/processes/SEC_USER_CREATE/latest", admin(),
            Map.of("userName", name, "displayName", name)).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
        @SuppressWarnings("unchecked")
        String userId = (String) ((Map<String, Object>) result.get("output")).get("userId");
        assign(userId, createRole(unique("R"), permissions.length == 0 ? new String[] {"p"} : permissions), null);
        link(userId, subject);
        return userId;
    }

    private void link(String userId, String subject) {
        insert(SecurityEntities.USER_IDENTITY_DATASET, Map.of("userId", userId, "provider", "test",
            "subject", subject), null);
    }

    private static List<Map<String, Object>> records(String userId) {
        return query("SELECT outcome, factor FROM sec_login_record_version WHERE user_id = ?::uuid "
            + "ORDER BY attempt_no", userId);
    }

    @Test
    void theProvidersAreListedForTheSignInPage() {
        List<Map<String, Object>> providers = get("/api/auth/oidc/providers", null).expectStatus().isOk()
            .expectBody(LIST).returnResult().getResponseBody();
        assertThat(providers).singleElement().satisfies(p -> assertThat(p).containsEntry("id", "test")
            .containsEntry("label", "Test directory").hasSize(2));
        post("/api/auth/oidc/nope/start", null, Map.of()).expectStatus().isNotFound();
    }

    @Test
    void aLinkedUserSignsInThroughTheProvider() {
        String subject = unique("sub");
        String userId = linkedUser(subject);
        Request request = start();

        Map<String, Object> session = signedIn(request, IDP.idToken(claims(subject, request, c -> c)));
        assertThat(session).containsEntry("status", "SIGNED_IN").containsEntry("userId", userId);
        Map<String, Object> me = get("/api/auth/me", bearerOf(session)).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(me).containsEntry("userId", userId).containsEntry("mfaAt", null);
        assertThat(records(userId)).singleElement().satisfies(r -> assertThat(r).containsEntry("outcome", "SUCCESS")
            .containsEntry("factor", "OIDC"));
        // Without a password, nobody signs in with one.
        String name = String.valueOf(query("SELECT user_name FROM sec_user_version WHERE user_id = ?::uuid", userId)
            .getFirst().get("user_name"));
        login(name, "").expectStatus().isUnauthorized();
        login(name, "any password at all").expectStatus().isUnauthorized();
        // An elliptic-curve signature works too.
        Request again = start();
        assertThat(signedIn(again, TestOidcProvider.sign(claims(subject, again, c -> c), JWSAlgorithm.ES256,
            IDP.ecKey()))).containsEntry("status", "SIGNED_IN");
    }

    @Test
    void nobodyIsSignedUpByTheProvider() {
        Request request = start();
        refused(request, IDP.idToken(claims(unique("stranger"), request, c -> c)));
    }

    @Test
    void aStateWorksOnceAndNotForLong() {
        String subject = unique("sub");
        linkedUser(subject);
        Request request = start();
        signedIn(request, IDP.idToken(claims(subject, request, c -> c)));
        refused(request, IDP.idToken(claims(subject, request, c -> c)));

        refused(new Request("made-up-state", request.nonce(), request.challenge(), request.binder()),
            IDP.idToken(claims(subject, request, c -> c)));

        Request late = start();
        clock.advance(Duration.ofMinutes(11));
        refused(late, IDP.idToken(claims(subject, late, c -> c)));
    }

    @Test
    void tokensThatAreNotForThisSignInAreRefused() {
        String subject = unique("sub");
        linkedUser(subject);
        List<UnaryOperator<JWTClaimsSet.Builder>> wrong = List.of(
            c -> c.claim("nonce", "another-nonce"),
            c -> c.audience("someone-else"),
            c -> c.audience(List.of(TestOidcProvider.CLIENT_ID, "someone-else")),
            c -> c.claim("azp", "someone-else"),
            c -> c.issuer("http://localhost:1/elsewhere"),
            c -> c.expirationTime(java.util.Date.from(clock.instant().minusSeconds(120))),
            c -> c.issueTime(java.util.Date.from(clock.instant().plusSeconds(600))),
            c -> c.subject(null));
        for (UnaryOperator<JWTClaimsSet.Builder> change : wrong) {
            Request request = start();
            refused(request, IDP.idToken(claims(subject, request, change)));
        }
    }

    @Test
    void onlyTheProvidersAsymmetricSignaturesAreAccepted() throws Exception {
        String subject = unique("sub");
        linkedUser(subject);
        Request hmac = start();
        // HMAC keyed with what an attacker can read: the provider's public key.
        refused(hmac, TestOidcProvider.sign(claims(subject, hmac, c -> c), JWSAlgorithm.HS256,
            IDP.rsaKey().toPublicJWK().toJSONString().getBytes(StandardCharsets.UTF_8)));
        Request none = start();
        refused(none, new PlainJWT(claims(subject, none, c -> c)).serialize());
        Request forged = start();
        refused(forged, TestOidcProvider.sign(claims(subject, forged, c -> c), JWSAlgorithm.RS256,
            new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID(IDP.rsaKey().getKeyID()).generate()));
    }

    @Test
    void aRotatedKeyIsFetchedOnce() {
        String subject = unique("sub");
        linkedUser(subject);
        Request first = start();
        signedIn(first, IDP.idToken(claims(subject, first, c -> c)));
        int reads = IDP.jwksReads();
        clock.advance(Duration.ofMinutes(2));

        var next = IDP.rotate();
        Request request = start();
        assertThat(signedIn(request, TestOidcProvider.sign(claims(subject, request, c -> c), JWSAlgorithm.RS256,
            next))).containsEntry("status", "SIGNED_IN");
        assertThat(IDP.jwksReads()).isEqualTo(reads + 1);
    }

    @Test
    void theProviderChecksThePkceVerifier() {
        String subject = unique("sub");
        linkedUser(subject);
        Request request = start();
        String code = IDP.code("not-the-challenge", IDP.idToken(claims(subject, request, c -> c)));
        post("/api/auth/oidc/callback", null, Map.of("state", request.state(), "code", code,
            "binder", request.binder())).expectStatus().isUnauthorized();
    }

    @Test
    void aStateAndCodeSentToSomeoneElseDoNotSignThemIn() {
        String subject = unique("sub");
        linkedUser(subject);
        // The attacker's own sign-in, stopped before the callback; the victim's page has no (or another) binder.
        Request attackers = start();
        Request victims = start();
        String code = IDP.code(attackers.challenge(), IDP.idToken(claims(subject, attackers, c -> c)));
        post("/api/auth/oidc/callback", null, Map.of("state", attackers.state(), "code", code,
            "binder", victims.binder())).expectStatus().isUnauthorized();
        post("/api/auth/oidc/callback", null, Map.of("state", attackers.state(), "code", code))
            .expectStatus().isUnauthorized();
    }

    @Test
    void aTokenWithoutKeyIdIsCheckedAgainstEveryKeyOfItsKind() {
        String subject = unique("sub");
        linkedUser(subject);
        IDP.rotate();
        Request request = start();
        assertThat(signedIn(request, TestOidcProvider.sign(claims(subject, request, c -> c), JWSAlgorithm.RS256,
            IDP.rsaKey(), false))).containsEntry("status", "SIGNED_IN");
    }

    @Test
    void anOldSecondFactorAtTheProviderIsNotARecentOne() {
        String subject = unique("sub");
        String userId = linkedUser(subject, "security.user.create");
        java.time.Instant longAgo = clock.instant().minus(Duration.ofDays(3));
        Request old = start();
        Map<String, Object> session = signedIn(old, IDP.idToken(claims(subject, old,
            c -> c.claim("amr", List.of("hwk")).claim("auth_time", longAgo.getEpochSecond()))));
        assertThat(get("/api/auth/me", bearerOf(session)).expectBody(MAP).returnResult().getResponseBody()
            .get("mfaAt")).isEqualTo(longAgo.toString());
        assertThat(ruleCode(post("/api/processes/SEC_USER_CREATE/latest", bearerOf(session),
            Map.of("userName", unique("new"))).expectStatus().isForbidden().expectBody(MAP).returnResult()
            .getResponseBody())).isEqualTo("MFA_REQUIRED");

        // Without auth_time the provider's methods count for nothing.
        Request untimed = start();
        Map<String, Object> plain = signedIn(untimed, IDP.idToken(claims(subject, untimed,
            c -> c.claim("amr", List.of("hwk")))));
        assertThat(get("/api/auth/me", bearerOf(plain)).expectBody(MAP).returnResult().getResponseBody()
            .get("mfaAt")).isNull();
        assertThat(userId).isNotBlank();
    }

    @Test
    void unlinkingAnAccountEndsItsSessions() {
        String subject = unique("sub");
        String userId = linkedUser(subject);
        Request request = start();
        Map<String, Object> session = signedIn(request, IDP.idToken(claims(subject, request, c -> c)));
        Map<String, Object> link = query("SELECT user_identity_id, version_no FROM sec_user_identity_version "
            + "WHERE user_id = ?::uuid", userId).getFirst();
        post("/api/datasets/" + SecurityEntities.USER_IDENTITY_DATASET + "/commit", admin(), Map.of("changes",
            List.of(Map.of("action", "DELETE", "id", String.valueOf(link.get("user_identity_id")),
                "version", link.get("version_no"))))).expectStatus().isOk();
        post("/api/auth/refresh", null, Map.of("refreshToken", session.get("refreshToken")))
            .expectStatus().isUnauthorized();
    }

    @Test
    void wrongPasswordsCannotLockAUserWhoHasNone() {
        String subject = unique("sub");
        String userId = linkedUser(subject);
        String name = String.valueOf(query("SELECT user_name FROM sec_user_version WHERE user_id = ?::uuid", userId)
            .getFirst().get("user_name"));
        for (int i = 0; i < 6; i++) {
            login(name, "wrong password " + i).expectStatus().isUnauthorized();
        }
        assertThat(records(userId)).isEmpty();
        Request request = start();
        assertThat(signedIn(request, IDP.idToken(claims(subject, request, c -> c))))
            .containsEntry("status", "SIGNED_IN");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSecondFactorFollowsTheProvidersAuthenticationOrThePlatformsOwn() {
        String subject = unique("sub");
        String userId = linkedUser(subject);
        // A hardware key at the provider counts as a second factor.
        Request strong = start();
        Map<String, Object> session = signedIn(strong, IDP.idToken(claims(subject, strong,
            c -> c.claim("amr", List.of("pwd", "hwk")).claim("auth_time", clock.instant().getEpochSecond()))));
        assertThat(get("/api/auth/me", bearerOf(session)).expectBody(MAP).returnResult().getResponseBody()
            .get("mfaAt")).isNotNull();

        // Enrolled at the platform, a password at the provider leads to the platform's second step.
        String bearer = bearerOf(session);
        String secret = (String) post("/api/auth/mfa/enroll", bearer, Map.of()).expectBody(MAP).returnResult()
            .getResponseBody().get("secret");
        post("/api/auth/mfa/enroll/confirm", bearer, Map.of("code",
            Totp.code(Base32.decode(secret), Totp.step(clock.instant())))).expectStatus().isOk();
        clock.advance(Duration.ofSeconds(30));
        Request weak = start();
        Map<String, Object> second = signedIn(weak, IDP.idToken(claims(subject, weak,
            c -> c.claim("amr", List.of("pwd")))));
        assertThat(second).containsEntry("status", "MFA_REQUIRED").containsEntry("accessToken", null);
        Map<String, Object> verified = post("/api/auth/challenge/verify", null, Map.of("challenge",
            second.get("challenge"), "code", Totp.code(Base32.decode(secret), Totp.step(clock.instant()))))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(verified).containsEntry("status", "SIGNED_IN").containsEntry("userId", userId);
    }

    @Test
    void aRoleRequiringASecondFactorAsksForEnrolment() {
        String subject = unique("sub");
        String userId = linkedUser(subject);
        String roleId = String.valueOf(insert(SecurityEntities.ROLE_DATASET, Map.of("roleCode", unique("TREASURY"),
            "labels", Map.of("en", "Treasury"), "enabled", true, "requireMfa", true), null).get("id"));
        assign(userId, roleId, null);
        Request request = start();
        assertThat(signedIn(request, IDP.idToken(claims(subject, request, c -> c))))
            .containsEntry("status", "MFA_ENROLLMENT_REQUIRED");
    }

    @Test
    void disabledAndLockedUsersAreRefused() {
        String subject = unique("sub");
        String name = unique("pw");
        String userId = userWith(name, "correct horse battery", "p");
        link(userId, subject);
        for (int i = 0; i < 5; i++) {
            login(name, "wrong password " + i).expectStatus().isUnauthorized();
        }
        Request locked = start();
        refused(locked, IDP.idToken(claims(subject, locked, c -> c)));
        assertThat(records(userId)).last().satisfies(r -> assertThat(r).containsEntry("outcome", "LOCKED")
            .containsEntry("factor", "OIDC"));

        String other = unique("sub");
        String disabled = linkedUser(other);
        Map<String, Object> user = get("/api/datasets/" + SecurityEntities.USER_DATASET + "/entities/" + disabled,
            admin()).expectBody(MAP).returnResult().getResponseBody();
        post("/api/datasets/" + SecurityEntities.USER_DATASET + "/commit", admin(), Map.of("changes", List.of(Map.of(
            "action", "UPDATE", "id", disabled, "version", user.get("version"),
            "attributes", Map.of("enabled", false))))).expectStatus().isOk();
        Request request = start();
        refused(request, IDP.idToken(claims(other, request, c -> c)));
    }

    @Test
    void linkingAccountsIsAdministration() {
        String userId = createUser(unique("lnk"), "correct horse battery");
        // A link is a credential of its own: the permission to change users is not enough.
        post("/api/datasets/" + SecurityEntities.USER_IDENTITY_DATASET + "/commit", bearer("security.user.write",
            "security.user.read"), Map.of("changes", List.of(Map.of("action", "INSERT", "attributes",
                Map.of("userId", userId, "provider", "test", "subject", unique("sub"))))))
            .expectStatus().isForbidden();
        String noMfa = TestTokens.withoutMfa(tokens, "it-admin", "*");
        assertThat(ruleCode(post("/api/datasets/" + SecurityEntities.USER_IDENTITY_DATASET + "/commit", noMfa,
            Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", Map.of("userId", userId,
                "provider", "test", "subject", unique("sub")))))).expectStatus().isForbidden().expectBody(MAP)
            .returnResult().getResponseBody())).isEqualTo("MFA_REQUIRED");
        // One subject signs in as one user only.
        String subject = unique("sub");
        link(userId, subject);
        post("/api/datasets/" + SecurityEntities.USER_IDENTITY_DATASET + "/commit", admin(),
            Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", Map.of("userId",
                createUser(unique("lnk"), "correct horse battery"), "provider", "test", "subject", subject)))))
            .expectStatus().isBadRequest();
    }

    @Test
    void theStateTablesAreOnlyInsertedIntoAndNothingSecretIsRecorded() {
        String subject = unique("sub");
        linkedUser(subject);
        SqlStatementLog.STATEMENTS.clear();
        Request request = start();
        String code = IDP.code(request.challenge(), IDP.idToken(claims(subject, request, c -> c)));
        post("/api/auth/oidc/callback", null, Map.of("state", request.state(), "code", code,
            "binder", request.binder())).expectStatus().isOk();
        // Only expired sign-in requests are deleted; everything else is inserted.
        assertThat(SqlStatementLog.STATEMENTS).isNotEmpty()
            .noneSatisfy(sql -> assertThat(sql.trim().toUpperCase()).startsWith("UPDATE"))
            .allSatisfy(sql -> assertThat(sql.trim().toUpperCase().startsWith("DELETE")
                ? sql.contains("sec_oidc_state WHERE expires_at") : true).as(sql).isTrue());
        assertThat(query("SELECT count(*) AS n FROM sec_oidc_state WHERE state_hash = ?", request.state())
            .getFirst().get("n")).isEqualTo(0L);
        assertThat(query("SELECT count(*) AS n FROM op_process WHERE input_summary::text LIKE ? "
            + "OR input_summary::text LIKE ?", "%" + code + "%", "%" + request.state() + "%").getFirst().get("n"))
            .isEqualTo(0L);
    }
}
