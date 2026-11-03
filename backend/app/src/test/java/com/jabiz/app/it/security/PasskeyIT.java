package com.jabiz.app.it.security;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.test.TestAuthenticator;
import org.junit.jupiter.api.BeforeEach;
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
 * Passkeys (decision D71; docs/design/10-security.md section 15.16), through the HTTP API with an authenticator in
 * software ({@link TestAuthenticator}): added by a user who proved who they are (password, recent second factor, or a
 * mailed link), signing in without a name as two factors, the second step after a password and enrolment under a
 * challenge, step-up; challenges good once for their own ceremony; answers that do not hold leave no login record;
 * the limit, duplicates, renaming and removal; entries that do not take passkeys.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {"it.sql-log.enabled=true",
    "jabiz.mail.enabled=true", "jabiz.mail.from=jabiz@example.com", "jabiz.mail.base-url=https://app.example.com",
    "spring.mail.host=localhost", "spring.mail.port=3049", SignUpItSupport.ENTRY_PROPERTIES_REGISTRATION,
    SignUpItSupport.ENTRY_PROPERTIES_ADMIN, SignUpItSupport.ENTRY_PROPERTIES_LOOSE,
    "jabiz.security.entries.portal.email-link=true", "jabiz.security.entries.portal.passkeys=true",
    "jabiz.security.entries.admin.passkeys=true",
    "jabiz.security.entries.ops.accepted-roles=" + PasskeyIT.OPS, "jabiz.security.entries.ops.passkeys=true",
    "jabiz.security.passkeys.rp-id=localhost", "jabiz.security.passkeys.rp-name=Jabiz tests",
    "jabiz.security.passkeys.origins=" + TestAuthenticator.ORIGIN, "jabiz.security.passkeys.max-per-user=3"})
@Import(SignUpItSupport.FixedTimeLimits.class)
class PasskeyIT extends SignUpItSupport {

    static final String OPS = "IT_OPS";
    static final String ADMIN = "admin";

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3049, null, ServerSetup.PROTOCOL_SMTP));

    static final Pattern EMAIL_LINK = Pattern.compile(
        "https://app\\.example\\.com/(?:portal/)?auth/email-link\\?entry=([a-z0-9-]+)&token=([A-Za-z0-9_-]+)");
    static final Pattern CONFIRM_LINK = Pattern.compile(
        "https://app\\.example\\.com/(?:portal/)?auth/passkey-confirm\\?entry=([a-z0-9-]+)&token=([A-Za-z0-9_-]+)");

    TestAuthenticator authenticator;

    @Override
    GreenMailExtension mail() {
        return MAIL;
    }

    @BeforeEach
    void authenticator() {
        authenticator = new TestAuthenticator();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** The address of a test's user of that (unique) name. */
    static String mailOf(String name) {
        return name.toLowerCase() + "@example.com";
    }

    /** A portal customer with a verified address and a password; returns its id. */
    String customer(String name) {
        String userId = createUserWithEmail(name, mailOf(name), PASSWORD);
        verifyEmail(userId);
        assign(userId, roleId(CUSTOMER), null);
        return userId;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> publicKey(WebTestClient.ResponseSpec response) {
        Map<String, Object> body = response.expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return (Map<String, Object>) body.get("publicKey");
    }

    Map<String, Object> addOptions(String bearer, Map<String, Object> proof) {
        return publicKey(post("/api/auth/account/passkeys/options", bearer, proof));
    }

    WebTestClient.ResponseSpec add(String bearer, Map<String, Object> credential, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("credential", credential);
        body.put("name", name);
        return post("/api/auth/account/passkeys", bearer, body);
    }

    /** Adds a passkey to the session's user, proven by the password; returns its id. */
    String addPasskey(String bearer, String name) {
        Map<String, Object> options = addOptions(bearer, Map.of("currentPassword", PASSWORD));
        return (String) add(bearer, authenticator.create(options, TestAuthenticator.Answer.right()), name)
            .expectStatus().isCreated().expectBody(MAP).returnResult().getResponseBody().get("passkeyId");
    }

    Map<String, Object> signInOptions(String entry) {
        return publicKey(anonymous("/api/auth/passkey/options", Map.of("entry", entry)));
    }

    WebTestClient.ResponseSpec passkeySignIn(String entry, Map<String, Object> credential) {
        return anonymous("/api/auth/passkey", Map.of("entry", entry, "credential", credential));
    }

    Map<String, Object> signedInWithPasskey(String entry) {
        return passkeySignIn(entry, authenticator.get(signInOptions(entry), TestAuthenticator.Answer.right()))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
    }

    Map<String, Object> me(String bearer) {
        return get("/api/auth/me", bearer).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
    }

    static List<Map<String, Object>> factorsOf(String userId) {
        return query("SELECT outcome, factor FROM sec_login_record_version WHERE user_id = ?::uuid ORDER BY row_id",
            userId);
    }

    static List<Map<String, Object>> passkeysOf(String userId) {
        return query("SELECT passkey_id, name, sign_count, last_used_at, backed_up, transports FROM sec_user_passkey "
            + "WHERE user_id = ?::uuid ORDER BY created_at", userId);
    }

    // ---- tests --------------------------------------------------------------------------------------------------

    @Test
    void aPasskeyAddedWithThePasswordSignsInWithoutANameAsTwoFactors() {
        String name = unique("ann");
        String userId = customer(name);
        String bearer = bearerOf(session(mailOf(name), PASSWORD, PORTAL));
        Map<String, Object> options = addOptions(bearer, Map.of("currentPassword", PASSWORD));
        // A discoverable passkey, the user verified; the user's verified address as its name.
        assertThat(options).containsEntry("attestation", "none")
            .containsEntry("rp", Map.of("id", "localhost", "name", "Jabiz tests"))
            .containsEntry("authenticatorSelection", Map.of("residentKey", "required", "requireResidentKey", true,
                "userVerification", "required"));
        assertThat(options.get("user")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("name", mailOf(name));
        String passkeyId = (String) add(bearer, authenticator.create(options, TestAuthenticator.Answer.right()),
                "Ann's phone").expectStatus().isCreated().expectBody(MAP).returnResult().getResponseBody()
            .get("passkeyId");
        assertThat(passkeysOf(userId)).singleElement().satisfies(p -> assertThat(p)
            .containsEntry("passkey_id", passkeyId).containsEntry("name", "Ann's phone")
            .containsEntry("backed_up", true).containsEntry("transports", "hybrid,internal")
            .containsEntry("last_used_at", null));
        assertThat(get("/api/auth/account/passkeys", bearer).expectStatus().isOk().expectBody(LIST).returnResult()
            .getResponseBody()).singleElement().satisfies(p -> assertThat(p).containsEntry("id", passkeyId)
            .containsEntry("name", "Ann's phone").containsEntry("backedUp", true).doesNotContainKey("credentialData"));
        deliver();
        assertThat(to(mailOf(name))).anySatisfy(m -> assertThat(text(m)).contains("Ann's phone")
            .contains("https://app.example.com/portal/auth/password-reset?entry=portal"));
        assertThat(get("/api/auth/mfa", bearer).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody()).containsEntry("passkeys", 1).containsEntry("enrolled", false);

        clock.advance(Duration.ofMinutes(1));
        Map<String, Object> session = signedInWithPasskey(PORTAL);
        assertThat(session).containsEntry("status", "SIGNED_IN").containsEntry("userId", userId);
        // Two factors: the session passed a second factor now.
        assertThat(me(bearerOf(session))).containsEntry("mfaAt", clock.instant().toString())
            .containsEntry("entry", PORTAL);
        assertThat(factorsOf(userId)).last().satisfies(r -> assertThat(r).containsEntry("outcome", "SUCCESS")
            .containsEntry("factor", "PASSKEY"));
        assertThat(passkeysOf(userId)).singleElement().satisfies(p -> assertThat(p).containsEntry("sign_count", 1L)
            .doesNotContainEntry("last_used_at", null));
        // The public key never leaves through the dataset.
        assertThat(get("/api/datasets/" + com.jabiz.runtime.security.PasskeyEntities.PASSKEY_DATASET + "/entities/"
            + passkeyId, admin()).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody())
            .extracting("attributes").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .doesNotContainKey("credentialData");
    }

    @Test
    void addingAPasskeyNeedsTheUserProven() {
        String name = unique("bob");
        customer(name);
        // Signed in with the password only: no recent second factor.
        String bearer = bearerOf(session(mailOf(name), PASSWORD, PORTAL));
        assertThat(refusal(post("/api/auth/account/passkeys/options", bearer, Map.of()), 422))
            .isEqualTo("REAUTHENTICATION_REQUIRED");
        assertThat(refusal(post("/api/auth/account/passkeys/options", bearer, Map.of("currentPassword", "wrong")),
            422)).isEqualTo("PASSWORD_INCORRECT");
        addPasskey(bearer, null);
        // Signed in with the passkey: a recent second factor proves the user.
        String recent = bearerOf(signedInWithPasskey(PORTAL));
        Map<String, Object> options = addOptions(recent, Map.of());
        // The passkeys the user has are not created again.
        assertThat(options.get("excludeCredentials")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .singleElement().satisfies(e -> assertThat(((Map<?, ?>) e).get("id")).isEqualTo(authenticator.last().id()));
        // Not past the step-up's age.
        clock.advance(Duration.ofMinutes(11));
        assertThat(refusal(post("/api/auth/account/passkeys/options", recent, Map.of()), 422))
            .isEqualTo("REAUTHENTICATION_REQUIRED");
    }

    @Test
    void aUserWithoutAPasswordConfirmsByAMailedLink() {
        String email = address("cid");
        anonymous("/api/auth/email-link", Map.of("entry", PORTAL, "email", email)).expectStatus().isAccepted();
        deliver();
        Map<String, Object> session = anonymous("/api/auth/email-link/confirm", Map.of("token",
            token(EMAIL_LINK, email))).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        String bearer = bearerOf(session);
        assertThat(refusal(post("/api/auth/account/passkeys/options", bearer, Map.of()), 422))
            .isEqualTo("REAUTHENTICATION_UNAVAILABLE");
        post("/api/auth/account/passkeys/confirmation", bearer, Map.of()).expectStatus().isAccepted();
        deliver();
        String token = token(CONFIRM_LINK, email);
        // Someone else's session cannot use it.
        String dan = unique("dan");
        customer(dan);
        String other = bearerOf(session(mailOf(dan), PASSWORD, PORTAL));
        assertThat(refusal(post("/api/auth/account/passkeys/options", other, Map.of("passkeyToken", token)), 422))
            .isEqualTo("TOKEN_INVALID");
        Map<String, Object> options = addOptions(bearer, Map.of("passkeyToken", token));
        add(bearer, authenticator.create(options, TestAuthenticator.Answer.right()), null).expectStatus().isCreated();
        // Good once.
        assertThat(refusal(post("/api/auth/account/passkeys/options", bearer, Map.of("passkeyToken", token)), 422))
            .isEqualTo("TOKEN_INVALID");
        assertThat(passkeysOf((String) session.get("userId"))).singleElement()
            .satisfies(p -> assertThat(p).containsEntry("name", "Passkey"));
    }

    @Test
    void aChallengeIsGoodOnceForItsOwnCeremonyEntryAndTime() {
        String name = unique("eva");
        String userId = customer(name);
        String bearer = bearerOf(session(mailOf(name), PASSWORD, PORTAL));
        addPasskey(bearer, "key");
        int records = factorsOf(userId).size();

        Map<String, Object> answer = authenticator.get(signInOptions(PORTAL), TestAuthenticator.Answer.right());
        passkeySignIn(PORTAL, answer).expectStatus().isOk();
        // The same answer again: its challenge is used.
        passkeySignIn(PORTAL, answer).expectStatus().isUnauthorized();
        // A challenge of another entry.
        Map<String, Object> elsewhere = authenticator.get(signInOptions(ADMIN), TestAuthenticator.Answer.right());
        passkeySignIn(PORTAL, elsewhere).expectStatus().isUnauthorized();
        // Expired.
        Map<String, Object> late = authenticator.get(signInOptions(PORTAL), TestAuthenticator.Answer.right());
        clock.advance(Duration.ofMinutes(6));
        passkeySignIn(PORTAL, late).expectStatus().isUnauthorized();
        // A sign-in's challenge does not register a passkey.
        addOptions(bearer, Map.of("currentPassword", PASSWORD));
        Map<String, Object> created = authenticator.create(Map.of("challenge", signInOptions(PORTAL).get("challenge"),
            "rp", Map.of("id", "localhost"), "user", Map.of("id", "x")), TestAuthenticator.Answer.right());
        assertThat(refusal(add(bearer, created, null), 422)).isEqualTo("PASSKEY_INVALID");
        // Only the one sign-in was recorded.
        assertThat(factorsOf(userId).subList(records, factorsOf(userId).size()))
            .extracting(r -> r.get("factor")).containsExactly("PASSKEY");
    }

    @Test
    void answersThatDoNotHoldLeaveNoRecordAndLockNobody() {
        String name = unique("fay");
        String userId = customer(name);
        addPasskey(bearerOf(session(mailOf(name), PASSWORD, PORTAL)), "key");
        int records = factorsOf(userId).size();
        for (int i = 0; i < 8; i++) {
            passkeySignIn(PORTAL, authenticator.get(signInOptions(PORTAL),
                TestAuthenticator.Answer.right().origin("https://evil.example.com"))).expectStatus().isUnauthorized();
        }
        passkeySignIn(PORTAL, authenticator.get(signInOptions(PORTAL), TestAuthenticator.Answer.right().unverified()))
            .expectStatus().isUnauthorized();
        assertThat(factorsOf(userId)).hasSize(records);
        // Still signs in: nothing counted towards the lock.
        signedInWithPasskey(PORTAL);
        // A counter that goes back: a copied passkey.
        passkeySignIn(PORTAL, authenticator.get(signInOptions(PORTAL), TestAuthenticator.Answer.right().counter(1)))
            .expectStatus().isUnauthorized();
        assertThat(factorsOf(userId)).hasSize(records + 1);
    }

    @Test
    void aPasskeyIsTheSecondStepAfterAPassword() {
        String name = unique("gus");
        String userId = customer(name);
        addPasskey(bearerOf(session(mailOf(name), PASSWORD, PORTAL)), "key");
        Map<String, Object> first = session(mailOf(name), PASSWORD, PORTAL);
        assertThat(first).containsEntry("status", "MFA_REQUIRED").containsEntry("accessToken", null);
        String challenge = (String) first.get("challenge");
        Map<String, Object> options = publicKey(anonymous("/api/auth/challenge/passkey/options",
            Map.of("challenge", challenge)));
        assertThat(options.get("allowCredentials")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .singleElement().satisfies(e -> assertThat(((Map<?, ?>) e).get("id")).isEqualTo(authenticator.last().id()));
        Map<String, Object> answer = authenticator.get(options, TestAuthenticator.Answer.right());
        Map<String, Object> second = anonymous("/api/auth/challenge/passkey", Map.of("challenge", challenge,
            "credential", answer)).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(second).containsEntry("status", "SIGNED_IN").containsEntry("userId", userId);
        assertThat(me(bearerOf(second)).get("mfaAt")).isNotNull();
        // Another user's passkey does not complete this user's sign-in.
        String other = unique("hal");
        customer(other);
        TestAuthenticator theirs = new TestAuthenticator();
        Map<String, Object> theirOptions = addOptions(bearerOf(session(mailOf(other), PASSWORD, PORTAL)),
            Map.of("currentPassword", PASSWORD));
        add(bearerOf(session(mailOf(other), PASSWORD, PORTAL)), theirs.create(theirOptions,
            TestAuthenticator.Answer.right()), null);
        String again = (String) session(mailOf(name), PASSWORD, PORTAL).get("challenge");
        Map<String, Object> next = publicKey(anonymous("/api/auth/challenge/passkey/options",
            Map.of("challenge", again)));
        anonymous("/api/auth/challenge/passkey", Map.of("challenge", again, "credential", theirs.get(next,
            theirs.last(), TestAuthenticator.Answer.right()))).expectStatus().isUnauthorized();
    }

    @Test
    void aUserWithTotpOnlyIsOfferedNoPasskeyStep() {
        String name = unique("ned");
        customer(name);
        enrollMfa(bearerOf(session(mailOf(name), PASSWORD, PORTAL)));
        Map<String, Object> first = session(mailOf(name), PASSWORD, PORTAL);
        assertThat(first).containsEntry("status", "MFA_REQUIRED");
        assertThat(refusal(anonymous("/api/auth/challenge/passkey/options", Map.of("challenge",
            first.get("challenge"))), 422)).isEqualTo("MFA_NOT_ENROLLED");
        // Nor is a challenge of another kind taken.
        anonymous("/api/auth/challenge/passkey/register/options", Map.of("challenge", first.get("challenge")))
            .expectStatus().isUnauthorized();
    }

    @Test
    void aRoleRequiringASecondFactorEnrolsAPasskeyInstead() {
        String name = unique("ivy");
        String userId = createUser(name, PASSWORD);
        if (query("SELECT 1 FROM sec_role_version WHERE role_code = ?", OPS).isEmpty()) {
            insert(SecurityEntities.ROLE_DATASET, Map.of("roleCode", OPS, "labels", Map.of("en", OPS), "enabled", true,
                "requireMfa", true), null);
        }
        assign(userId, roleId(OPS), null);
        Map<String, Object> first = session(name, PASSWORD, "ops");
        assertThat(first).containsEntry("status", "MFA_ENROLLMENT_REQUIRED");
        String challenge = (String) first.get("challenge");
        Map<String, Object> options = publicKey(anonymous("/api/auth/challenge/passkey/register/options",
            Map.of("challenge", challenge)));
        Map<String, Object> body = new HashMap<>();
        body.put("challenge", challenge);
        body.put("credential", authenticator.create(options, TestAuthenticator.Answer.right()));
        body.put("name", "laptop");
        anonymous("/api/auth/challenge/passkey/register", body).expectStatus().isCreated();
        // The next sign-in asks for the passkey, which completes it.
        Map<String, Object> next = session(name, PASSWORD, "ops");
        assertThat(next).containsEntry("status", "MFA_REQUIRED");
        assertThat(signedInWithPasskey("ops")).containsEntry("status", "SIGNED_IN").containsEntry("userId", userId);
    }

    @Test
    void aSignedInUserStepsUpWithAPasskey() {
        String name = unique("jon");
        String userId = createUser(name, PASSWORD);
        assign(userId, roleId(BACK_OFFICE), null);
        String bearer = bearerOf(session(name, PASSWORD, null));
        assertThat(me(bearer).get("mfaAt")).isNull();
        addPasskey(bearer, "desk");
        Map<String, Object> options = publicKey(post("/api/auth/step-up/passkey/options", bearer, Map.of()));
        clock.advance(Duration.ofSeconds(30));
        Map<String, Object> stepped = post("/api/auth/step-up/passkey", bearer, Map.of("credential",
            authenticator.get(options, TestAuthenticator.Answer.right()))).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(stepped).containsEntry("mfaAt", clock.instant().toString());
        assertThat(me("Bearer " + stepped.get("accessToken"))).containsEntry("mfaAt", clock.instant().toString())
            .containsEntry("sessionId", me(bearer).get("sessionId"));
        // A wrong answer is the step-up's 422.
        Map<String, Object> more = publicKey(post("/api/auth/step-up/passkey/options", bearer, Map.of()));
        assertThat(refusal(post("/api/auth/step-up/passkey", bearer, Map.of("credential", authenticator.get(more,
            TestAuthenticator.Answer.right().unverified()))), 422)).isEqualTo("PASSKEY_INVALID");
    }

    @Test
    void theLimitDuplicatesRenamingAndRemoval() {
        String name = unique("kim");
        String userId = customer(name);
        String bearer = bearerOf(session(mailOf(name), PASSWORD, PORTAL));
        String first = addPasskey(bearer, "one");
        addPasskey(bearer, "two");
        addPasskey(bearer, "three");
        Map<String, Object> options = addOptions(bearer, Map.of("currentPassword", PASSWORD));
        assertThat(refusal(add(bearer, authenticator.create(options, TestAuthenticator.Answer.right()), null), 422))
            .isEqualTo("PASSKEY_LIMIT");

        // One passkey belongs to one account.
        String other = unique("lea");
        customer(other);
        String otherBearer = bearerOf(session(mailOf(other), PASSWORD, PORTAL));
        Map<String, Object> theirOptions = addOptions(otherBearer, Map.of("currentPassword", PASSWORD));
        assertThat(refusal(add(otherBearer, authenticator.createAgain(theirOptions, authenticator.credentials
            .getFirst()), null), 422)).isEqualTo("PASSKEY_EXISTS");

        post("/api/auth/account/passkeys/" + first + "/rename", bearer, Map.of("name", "  old\u0007 phone  "))
            .expectStatus().isOk();
        assertThat(passkeysOf(userId).getFirst()).containsEntry("name", "old phone");
        // Another user's passkey is no passkey of theirs.
        post("/api/auth/account/passkeys/" + first + "/remove", otherBearer, Map.of()).expectStatus().isNotFound();
        post("/api/auth/account/passkeys/" + first + "/remove", bearer, Map.of()).expectStatus().isNoContent();
        assertThat(passkeysOf(userId)).hasSize(2);
        // The removed passkey signs in no more.
        TestAuthenticator.Credential removed = authenticator.credentials.getFirst();
        passkeySignIn(PORTAL, authenticator.get(signInOptions(PORTAL), removed, TestAuthenticator.Answer.right()))
            .expectStatus().isUnauthorized();

        // An administrator removes one, with a reason.
        String second = (String) passkeysOf(userId).getFirst().get("passkey_id");
        post("/api/processes/SEC_PASSKEY_REMOVE_ADMIN/latest", admin(), Map.of("userId", userId, "passkeyId", second,
            "reason", "phone lost")).expectStatus().isOk();
        assertThat(passkeysOf(userId)).hasSize(1);
        assertThat(query("SELECT count(*) AS n FROM sys_audit_record WHERE entity_type = 'SecUserPasskey' "
            + "AND entity_id = ?", second).getFirst().get("n")).isEqualTo(2L);
    }

    @Test
    void entriesWithoutPasskeysRefuseThem() {
        assertThat(refusal(anonymous("/api/auth/passkey/options", Map.of("entry", LOOSE)), 422))
            .isEqualTo("PASSKEY_DISABLED");
        assertThat(refusal(anonymous("/api/auth/passkey/options", Map.of("entry", "nowhere")), 422))
            .isEqualTo("PASSKEY_DISABLED");
        String name = unique("max");
        customer(name);
        addPasskey(bearerOf(session(mailOf(name), PASSWORD, PORTAL)), "key");
        Map<String, Object> answer = authenticator.get(signInOptions(PORTAL), TestAuthenticator.Answer.right());
        passkeySignIn(LOOSE, answer).expectStatus().isUnauthorized();
        assertThat(get("/api/auth/entries/" + PORTAL, null).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody()).containsEntry("passkeys", true);
        assertThat(get("/api/auth/entries/" + LOOSE, null).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody()).containsEntry("passkeys", false);
        // At an entry without passkeys, a password sign-in does not ask for the passkey as a second step.
        assertThat(session(mailOf(name), PASSWORD, LOOSE)).containsEntry("status", "SIGNED_IN");
    }
}
