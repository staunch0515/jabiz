package com.jabiz.app.it.security;

import com.jabiz.app.it.fixture.ItSignInFixtures;
import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.context.RequestContext;
import com.jabiz.runtime.context.Actor;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.test.TestTokens;
import com.jabiz.security.Base32;
import com.jabiz.security.SignInAttempt;
import com.jabiz.security.Totp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sign-in entries, sign-in guards and verified e-mail addresses (docs/design/10-security.md section 15; decision D36;
 * ROADMAP phase 16b-1). The administration entry is left unconfigured (every role, as before); {@code portal} accepts
 * the customer role only; {@code strict} also requires a verified address.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "it.sql-log.enabled=true",
    "jabiz.security.entries.portal.accepted-roles=IT_CUSTOMER,IT_STAFF",
    "jabiz.security.entries.portal.app-path=/portal/",
    "jabiz.security.entries.strict.accepted-roles=IT_CUSTOMER",
    "jabiz.security.entries.strict.require-verified-email=true"})
class SignInEntryIT extends SecurityItSupport {

    private static final String PASSWORD = "correct horse battery";
    private static final String CUSTOMER = "IT_CUSTOMER";
    private static final String STAFF = "IT_STAFF";
    private static final String BACK = "IT_BACK";

    /** Roles of fixed codes, which the entries name: created once per test class (its schema is its own). */
    private static final Map<String, String> ROLES = new HashMap<>();

    @Autowired
    ProcessExecutor processes;

    @BeforeEach
    void roles() {
        if (ROLES.isEmpty()) {
            ROLES.put(CUSTOMER, createRole(CUSTOMER, ItSignInFixtures.VERIFIED_PERMISSION, "it.customer"));
            ROLES.put(BACK, createRole(BACK, "it.read"));
            // A role that requires a second factor; the portal accepts it too.
            String staff = String.valueOf(insert(SecurityEntities.ROLE_DATASET, Map.of("roleCode", STAFF,
                "labels", Map.of("en", STAFF), "enabled", true, "requireMfa", true), null).get("id"));
            insert(SecurityEntities.ROLE_PERMISSION_DATASET, Map.of("roleId", staff, "permission", "it.staff"), null);
            ROLES.put(STAFF, staff);
        }
    }

    /** A user holding the roles of the given codes; returns the user id. */
    private String user(String name, String email, String... roleCodes) {
        Map<String, Object> input = new HashMap<>(Map.of("userName", name, "displayName", name,
            "password", PASSWORD));
        if (email != null) {
            input.put("email", email);
        }
        @SuppressWarnings("unchecked")
        String userId = (String) ((Map<String, Object>) post("/api/processes/SEC_USER_CREATE/latest", admin(), input)
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody().get("output")).get("userId");
        for (String role : roleCodes) {
            assign(userId, ROLES.get(role), null);
        }
        return userId;
    }

    private WebTestClient.ResponseSpec login(String name, String password, String entry) {
        Map<String, Object> body = new HashMap<>(Map.of("userName", name, "password", password));
        if (entry != null) {
            body.put("entry", entry);
        }
        return client.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.USER_AGENT, "it-agent/1.0").bodyValue(body).exchange();
    }

    private Map<String, Object> signInTo(String name, String entry) {
        return login(name, PASSWORD, entry).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
    }

    private WebTestClient.ResponseSpec refresh(Map<String, Object> session, String entry) {
        Map<String, Object> body = new HashMap<>(Map.of("refreshToken", session.get("refreshToken")));
        if (entry != null) {
            body.put("entry", entry);
        }
        return post("/api/auth/refresh", null, body);
    }

    private Map<String, Object> me(Map<String, Object> session) {
        return get("/api/auth/me", bearerOf(session)).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
    }

    private String refused(WebTestClient.ResponseSpec response, int status) {
        return ruleCode(response.expectStatus().isEqualTo(status).expectBody(MAP).returnResult().getResponseBody());
    }

    private static List<Map<String, Object>> records(String userId) {
        return query("SELECT outcome, factor, entry, client_ip, user_agent, user_name, failure_count "
            + "FROM sec_login_record_version WHERE user_id = ?::uuid ORDER BY attempt_no", userId);
    }

    private void link(String userId, String provider) {
        insert(SecurityEntities.USER_IDENTITY_DATASET, Map.of("userId", userId, "provider", provider,
            "subject", unique("s")), null);
    }

    private void verifyEmail(String userId) {
        post("/api/processes/IT_EMAIL_VERIFY/latest", admin(), Map.of("userId", userId)).expectStatus().isOk();
    }

    private void changeEmail(String userId, String email) {
        Map<String, Object> user = get("/api/datasets/" + SecurityEntities.USER_DATASET + "/entities/" + userId, admin())
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        post("/api/datasets/" + SecurityEntities.USER_DATASET + "/commit", admin(), Map.of("changes", List.of(Map.of(
            "action", "UPDATE", "id", userId, "version", user.get("version"), "attributes", Map.of("email", email)))))
            .expectStatus().isOk();
    }

    @Test
    @SuppressWarnings("unchecked")
    void withoutAnEntryEverythingIsAsBefore() {
        String name = unique("ann");
        String userId = user(name, name + "@example.com", CUSTOMER, BACK);

        Map<String, Object> session = signInTo(name, null);
        assertThat((List<String>) session.get("roles")).containsExactlyInAnyOrder(CUSTOMER, BACK);
        assertThat(me(session)).containsEntry("entry", "admin").containsEntry("email", name + "@example.com")
            .containsEntry("emailVerified", false);
        assertThat(tokens.verify((String) session.get("accessToken")).entry()).isEqualTo("admin");
        Map<String, Object> next = refresh(session, null).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
        assertThat(tokens.verify((String) next.get("accessToken")).entry()).isEqualTo("admin");
        // "admin" named explicitly is the same entry.
        refresh(next, "admin").expectStatus().isOk();

        assertThat(records(userId)).singleElement().satisfies(r -> assertThat(r).containsEntry("entry", "admin")
            .containsEntry("user_agent", "it-agent/1.0").containsEntry("outcome", "SUCCESS"));
    }

    @Test
    void withoutTrustedProxiesAForwardedHeaderIsIgnored() {
        String name = unique("nia");
        String userId = user(name, null, CUSTOMER);
        client.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .header("X-Forwarded-For", "198.51.100.66").bodyValue(Map.of("userName", name, "password", PASSWORD))
            .exchange().expectStatus().isOk();
        // The mock server has no connection address: nothing is recorded rather than the client's claim.
        assertThat(records(userId)).singleElement().satisfies(r -> assertThat(r).containsEntry("client_ip", null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPortalSessionHasOnlyThePortalsRoles() {
        String name = unique("bob");
        user(name, null, CUSTOMER, BACK);

        Map<String, Object> session = signInTo(name, "portal");
        assertThat((List<String>) session.get("roles")).containsExactly(CUSTOMER);
        assertThat((List<String>) session.get("permissions")).containsExactly("it.customer",
            ItSignInFixtures.VERIFIED_PERMISSION);
        Actor actor = tokens.verify((String) session.get("accessToken"));
        assertThat(actor.entry()).isEqualTo("portal");
        assertThat(me(session)).containsEntry("entry", "portal");
        // The administration's permission does not come along.
        post("/api/datasets/urn:jabiz:dataset:it:ItTicket/query", bearerOf(session), Map.of())
            .expectStatus().isForbidden();
        post("/api/datasets/urn:jabiz:dataset:it:ItTicket/query", bearerOf(signInTo(name, null)), Map.of())
            .expectStatus().isOk();
    }

    @Test
    @SuppressWarnings("unchecked")
    void refreshingKeepsTheEntryAndAnotherEntryConsumesNothing() {
        String name = unique("cid");
        user(name, null, CUSTOMER, BACK);
        Map<String, Object> session = signInTo(name, "portal");
        int uses = query("SELECT * FROM sec_refresh_token_use").size();

        assertThat(refused(refresh(session, null), 401)).isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(refused(refresh(session, "strict"), 401)).isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(refused(refresh(session, "nowhere"), 401)).isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(query("SELECT * FROM sec_refresh_token_use")).hasSize(uses);
        assertThat(query("SELECT entry FROM sec_refresh_token t JOIN sec_refresh_family_revocation r "
            + "ON r.family_id = t.family_id WHERE t.user_id = (SELECT user_id FROM sec_user_version WHERE "
            + "user_name = ? LIMIT 1)", name)).isEmpty();

        Map<String, Object> next = refresh(session, "portal").expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
        assertThat((List<String>) next.get("roles")).containsExactly(CUSTOMER);
        assertThat(tokens.verify((String) next.get("accessToken")).entry()).isEqualTo("portal");
    }

    @Test
    void anEntryAcceptsNobodyWithoutItsRoles() {
        String name = unique("dan");
        String userId = user(name, null, BACK);

        assertThat(refused(login(name, PASSWORD, "portal"), 401)).isEqualTo("LOGIN_FAILED");
        assertThat(records(userId)).singleElement().satisfies(r -> assertThat(r).containsEntry("outcome", "NO_ROLE")
            .containsEntry("entry", "portal"));
        // An entry that does not exist: refused before anything is looked up.
        assertThat(refused(login(name, PASSWORD, "nowhere"), 401)).isEqualTo("LOGIN_FAILED");
        assertThat(records(userId)).hasSize(1);
        signInTo(name, null);
    }

    @Test
    void aRoleRequiringASecondFactorCountsOnlyWhereItIsAccepted() {
        String name = unique("eve");
        user(name, null, CUSTOMER, STAFF);

        // The administration and the portal accept the staff role, which requires a second factor; strict accepts
        // the customer role only, so no second factor is needed there (a verified address is).
        assertThat(signInTo(name, null)).containsEntry("status", "MFA_ENROLLMENT_REQUIRED");
        assertThat(signInTo(name, "portal")).containsEntry("status", "MFA_ENROLLMENT_REQUIRED");
        String other = unique("eva");
        String otherId = user(other, other + "@example.com", CUSTOMER, STAFF);
        verifyEmail(otherId);
        assertThat(signInTo(other, "strict")).containsEntry("status", "SIGNED_IN");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPortalChallengeLeadsIntoThePortalOnly() {
        String name = unique("fay");
        String userId = user(name, null, CUSTOMER, BACK);
        String bearer = bearerOf(signInTo(name, null));
        String secret = (String) post("/api/auth/mfa/enroll", bearer, Map.of()).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody().get("secret");
        post("/api/auth/mfa/enroll/confirm", bearer, Map.of("code", code(secret))).expectStatus().isOk();
        clock.advance(Duration.ofSeconds(30));

        Map<String, Object> first = signInTo(name, "portal");
        assertThat(first).containsEntry("status", "MFA_REQUIRED");
        assertThat(tokens.verifyChallenge((String) first.get("challenge"),
            com.jabiz.runtime.security.JwtService.Purpose.VERIFY).entry()).isEqualTo("portal");
        // The request cannot name another entry: the challenge decides.
        Map<String, Object> session = post("/api/auth/challenge/verify", null, Map.of("challenge",
            first.get("challenge"), "code", code(secret), "entry", "admin")).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat((List<String>) session.get("roles")).containsExactly(CUSTOMER);
        assertThat(tokens.verify((String) session.get("accessToken")).entry()).isEqualTo("portal");
        assertThat(records(userId).getLast()).containsEntry("entry", "portal").containsEntry("factor", "TOTP");
        refresh(session, "portal").expectStatus().isOk();

        // The guard is asked in the second step too.
        clock.advance(Duration.ofSeconds(30));
        Map<String, Object> again = signInTo(name, "portal");
        link(userId, ItSignInFixtures.BLOCKED);
        assertThat(refused(post("/api/auth/challenge/verify", null, Map.of("challenge", again.get("challenge"),
            "code", code(secret))), 403)).isEqualTo("SIGN_IN_REFUSED");
        assertThat(ItSignInFixtures.SEEN.get(userId).factor()).isEqualTo(SignInAttempt.Factor.TOTP);
    }

    private String code(String secret) {
        return Totp.code(Base32.decode(secret), Totp.step(clock.instant()));
    }

    @Test
    void aGuardRefusesWithoutLockingTheAccount() {
        String name = unique("gus");
        String userId = user(name, null, CUSTOMER);
        link(userId, ItSignInFixtures.BLOCKED);

        for (int i = 0; i < 7; i++) {
            assertThat(refused(login(name, PASSWORD, "portal"), 403)).isEqualTo("SIGN_IN_REFUSED");
        }
        // A wrong password is still just a wrong password: no hint that the account is refused.
        assertThat(refused(login(name, "wrong horse battery", "portal"), 401)).isEqualTo("LOGIN_FAILED");
        List<Map<String, Object>> records = records(userId);
        assertThat(records).hasSize(8);
        assertThat(records.subList(0, 7)).allSatisfy(r -> assertThat(r).containsEntry("outcome", "REFUSED")
            .containsEntry("entry", "portal"));
        assertThat(records.getLast()).containsEntry("outcome", "BAD_CREDENTIALS");
        assertThat(((Number) records.getLast().get("failure_count")).intValue()).isEqualTo(1);

        // The guard got the data it declared.
        SignInAttempt seen = ItSignInFixtures.SEEN.get(userId);
        assertThat(seen.entry()).isEqualTo("portal");
        assertThat(seen.roles()).containsExactly(CUSTOMER);
        assertThat(seen.factor()).isEqualTo(SignInAttempt.Factor.PASSWORD);
        assertThat(seen.rows(ItSignInFixtures.LOAD)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("provider", ItSignInFixtures.BLOCKED));
    }

    @Test
    void aGuardThatFailsRefuses() {
        String name = unique("hal");
        String userId = user(name, null, CUSTOMER);
        link(userId, ItSignInFixtures.FAILING);

        assertThat(refused(login(name, PASSWORD, null), 403)).isEqualTo("SIGN_IN_REFUSED");
        assertThat(records(userId)).singleElement().satisfies(r -> assertThat(r).containsEntry("outcome", "REFUSED"));
    }

    @Test
    void aRefusalAtRefreshEndsTheSession() {
        String name = unique("ida");
        String userId = user(name, null, CUSTOMER);
        Map<String, Object> session = signInTo(name, "portal");
        Map<String, Object> next = refresh(session, "portal").expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
        int recorded = records(userId).size();

        link(userId, ItSignInFixtures.BLOCKED);
        assertThat(refused(refresh(next, "portal"), 401)).isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(ItSignInFixtures.SEEN.get(userId).factor()).isEqualTo(SignInAttempt.Factor.REFRESH);
        // The family is revoked as refused (not as reused), and refreshing is no process: no login record.
        assertThat(query("SELECT DISTINCT r.reason FROM sec_refresh_family_revocation r JOIN sec_refresh_token t "
            + "ON t.family_id = r.family_id WHERE t.user_id = ?::uuid", userId)).singleElement()
            .satisfies(r -> assertThat(r).containsEntry("reason", "REFUSED"));
        assertThat(records(userId)).hasSize(recorded);
        assertThat(refused(refresh(next, "portal"), 401)).isEqualTo("INVALID_REFRESH_TOKEN");
    }

    @Test
    @SuppressWarnings("unchecked")
    void operationsRequiringAVerifiedAddressRefuseOthersAtEveryEntryPoint() {
        String unverified = TestTokens.unverified(tokens, "it-unverified", ItSignInFixtures.VERIFIED_PERMISSION,
            "it.read", "it.write", "entity.write", "it.verified-only.import", "app.import.read");
        String verified = TestTokens.bearer(tokens, "it-verified", ItSignInFixtures.VERIFIED_PERMISSION, "it.read",
            "it.write", "entity.write");

        assertThat(refused(post("/api/processes/IT_VERIFIED_ONLY/latest", unverified, Map.of("text", "x")), 403))
            .isEqualTo("EMAIL_NOT_VERIFIED");
        post("/api/processes/IT_VERIFIED_ONLY/latest", verified, Map.of("text", "x")).expectStatus().isOk();

        Map<String, Object> note = Map.of("noteId", UUID.randomUUID().toString(), "text", "hello");
        assertThat(refused(post("/api/datasets/" + ItSignInFixtures.NOTE_DATASET + "/commit", unverified,
            Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", note)))), 403))
            .isEqualTo("EMAIL_NOT_VERIFIED");
        post("/api/datasets/" + ItSignInFixtures.NOTE_DATASET + "/commit", verified,
            Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", note)))).expectStatus().isOk();
        // Only writes need it.
        post("/api/datasets/" + ItSignInFixtures.NOTE_DATASET + "/query", unverified, Map.of()).expectStatus().isOk();

        Map<String, Object> other = Map.of("noteId", UUID.randomUUID().toString(), "text", "entity");
        assertThat(refused(post("/api/entities/ItVerifiedNote", unverified, other), 403))
            .isEqualTo("EMAIL_NOT_VERIFIED");
        post("/api/entities/ItVerifiedNote", verified, other).expectStatus().isCreated();

        assertThat(refused(post("/api/imports/it.verified-only/preview", unverified,
            Map.of("fileId", UUID.randomUUID().toString())), 403)).isEqualTo("EMAIL_NOT_VERIFIED");

        // The catalogs say so.
        List<Map<String, Object>> catalog = get("/api/meta/processes", verified).expectStatus().isOk()
            .expectBody(LIST).returnResult().getResponseBody();
        assertThat(catalog).filteredOn(p -> "IT_VERIFIED_ONLY".equals(p.get("name"))).singleElement()
            .satisfies(p -> assertThat(p).containsEntry("requiresVerifiedEmail", true));
        List<Map<String, Object>> datasets = get("/api/meta/datasets", verified).expectStatus().isOk()
            .expectBody(LIST).returnResult().getResponseBody();
        assertThat(datasets).filteredOn(d -> ItSignInFixtures.NOTE_DATASET.equals(d.get("id"))).singleElement()
            .satisfies(d -> assertThat(d).containsEntry("writeRequiresVerifiedEmail", true));
        assertThat(datasets).filteredOn(d -> "urn:jabiz:dataset:it:ItTicket".equals(d.get("id"))).singleElement()
            .satisfies(d -> assertThat(d).containsEntry("writeRequiresVerifiedEmail", false));

        // The system is no entry point: it runs the process whatever its address.
        Object output = processes.execute(ItSignInFixtures.VERIFIED_ONLY, new ItSignInFixtures.NoteInput("system"))
            .contextWrite(view -> RequestContexts.put(view, RequestContext.system(Locale.ENGLISH, "it-system")))
            .block();
        assertThat(output).isEqualTo(new ItSignInFixtures.Done("system"));
    }

    @Test
    void aSignInCarriesWhetherTheAddressIsVerified() {
        String name = unique("jo");
        String userId = user(name, name + "@Example.com", CUSTOMER);

        Map<String, Object> before = signInTo(name, null);
        assertThat(me(before)).containsEntry("emailVerified", false);
        assertThat(refused(post("/api/processes/IT_VERIFIED_ONLY/latest", bearerOf(before), Map.of("text", "x")),
            403)).isEqualTo("EMAIL_NOT_VERIFIED");

        verifyEmail(userId);
        Map<String, Object> after = signInTo(name, null);
        assertThat(me(after)).containsEntry("emailVerified", true);
        assertThat(tokens.verify((String) after.get("accessToken")).emailVerified()).isTrue();
        post("/api/processes/IT_VERIFIED_ONLY/latest", bearerOf(after), Map.of("text", "x")).expectStatus().isOk();
    }

    @Test
    void anEntryMayRequireAVerifiedAddress() {
        String name = unique("kim");
        String userId = user(name, name + "@example.com", CUSTOMER);
        login(name, "wrong horse battery", "strict").expectStatus().isUnauthorized();

        assertThat(refused(login(name, PASSWORD, "strict"), 403)).isEqualTo("EMAIL_NOT_VERIFIED");
        assertThat(records(userId).getLast()).containsEntry("outcome", "EMAIL_NOT_VERIFIED")
            .containsEntry("entry", "strict");
        // Not a failure: the counter of the wrong password stays.
        assertThat(((Number) records(userId).getLast().get("failure_count")).intValue()).isEqualTo(1);

        verifyEmail(userId);
        Map<String, Object> session = signInTo(name, "strict");
        refresh(session, "strict").expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();

        // A new address is not verified: the entry refuses again, and so does the refresh of the session.
        Map<String, Object> later = signInTo(name, "strict");
        changeEmail(userId, name + "@other.example.com");
        assertThat(refused(login(name, PASSWORD, "strict"), 403)).isEqualTo("EMAIL_NOT_VERIFIED");
        assertThat(refused(refresh(later, "strict"), 401)).isEqualTo("INVALID_REFRESH_TOKEN");
    }

    @Test
    void aVerifiedAddressSignsInLikeTheUserName() {
        String name = unique("lea");
        String email = name + "@Example.com";
        String userId = user(name, email, CUSTOMER);

        // Unverified: like an unknown name, without a record.
        assertThat(refused(login(email, PASSWORD, null), 401)).isEqualTo("LOGIN_FAILED");
        assertThat(records(userId)).isEmpty();

        verifyEmail(userId);
        assertThat(signInTo(email.toUpperCase(Locale.ROOT), null)).containsEntry("userId", userId);
        assertThat(records(userId)).singleElement().satisfies(r -> assertThat(r).containsEntry("user_name", name)
            .containsEntry("outcome", "SUCCESS"));
        assertThat(refused(login(email, "wrong horse battery", null), 401)).isEqualTo("LOGIN_FAILED");
        assertThat(records(userId).getLast()).containsEntry("outcome", "BAD_CREDENTIALS");
    }

    @Test
    void addressesAreUniqueRegardlessOfCase() throws Exception {
        String local = unique("Dup");
        user(local + "a", local + "@Example.com");
        Map<String, Object> clash = post("/api/processes/SEC_USER_CREATE/latest", admin(), Map.of("userName",
            local + "b", "password", PASSWORD, "email", local.toLowerCase(Locale.ROOT) + "@example.COM"))
            .expectStatus().isBadRequest().expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCode(clash)).isEqualTo("UNIQUE_VIOLATION");

        // Concurrent creations of one address: exactly one succeeds.
        String race = unique("race");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> attempts = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                String address = (i % 2 == 0 ? race.toUpperCase(Locale.ROOT) : race) + "@example.com";
                String userName = race + "-" + i;
                attempts.add(() -> post("/api/processes/SEC_USER_CREATE/latest", admin(), Map.of("userName", userName,
                    "password", PASSWORD, "email", address)).returnResult(Void.class).getStatus().value());
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> status : pool.invokeAll(attempts)) {
                statuses.add(status.get());
            }
            assertThat(statuses).filteredOn(s -> s == 200).hasSize(1);
            assertThat(statuses).filteredOn(s -> s != 200).allSatisfy(s -> assertThat(s).isIn(400, 409));
        } finally {
            pool.shutdown();
        }

        // Ordinary entities: the database's unique index on lower(code).
        post("/api/datasets/" + ItSignInFixtures.UNIQUE_CI_DATASET + "/commit", admin(), Map.of("changes", List.of(
            Map.of("action", "INSERT", "attributes", Map.of("uniqueCiId", UUID.randomUUID().toString(),
                "code", "ABC"))))).expectStatus().isOk();
        assertThat(ruleCode(post("/api/datasets/" + ItSignInFixtures.UNIQUE_CI_DATASET + "/commit", admin(),
            Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", Map.of("uniqueCiId",
                UUID.randomUUID().toString(), "code", "abc"))))).expectStatus().isBadRequest().expectBody(MAP)
            .returnResult().getResponseBody())).isEqualTo("UNIQUE_VIOLATION");
    }

    @Test
    void theMigrationStopsAtAddressesThatAlreadyCollide() throws Exception {
        String migration = new String(new ClassPathResource("db/jabiz/V32__sign_in_entries.sql").getInputStream()
            .readAllBytes(), StandardCharsets.UTF_8);
        String check = migration.substring(migration.indexOf("DO $$"), migration.indexOf("END $$;") + "END $$;".length())
            .replace("sec_user_version", "it_old_users");
        execute("CREATE TABLE it_old_users (user_id uuid, email varchar(320), is_deleted boolean, "
            + "effect_start_time timestamptz, version_no integer)");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        // Collided once, but the second user's current address is another one: fine.
        execute("INSERT INTO it_old_users VALUES (?, 'Ann@x.com', false, now(), 1), (?, 'ann@X.com', false, now(), 1),"
            + " (?, 'bea@x.com', false, now() + interval '1 second', 2)", first, second, second);
        execute(check);
        // A deleted user does not count.
        execute("INSERT INTO it_old_users VALUES (?, 'bea@x.com', true, now(), 1)", UUID.randomUUID());
        execute(check);

        execute("INSERT INTO it_old_users VALUES (?, 'BEA@x.com', false, now(), 1)", UUID.randomUUID());
        assertThatThrownBy(() -> execute(check)).hasMessageContaining("bea@x.com")
            .hasMessageContaining("share e-mail addresses");
    }

    @Test
    void theTablesWithNewColumnsAreOnlyAppendedTo() {
        String name = unique("max");
        String userId = user(name, name + "@example.com", CUSTOMER);
        SqlStatementLog.STATEMENTS.clear();

        verifyEmail(userId);
        login(name, "wrong horse battery", "strict").expectStatus().isUnauthorized();
        Map<String, Object> session = signInTo(name, "strict");
        Map<String, Object> next = refresh(session, "strict").expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
        refresh(next, "admin").expectStatus().isUnauthorized();
        post("/api/auth/logout", null, Map.of("refreshToken", next.get("refreshToken"))).expectStatus().isNoContent();

        assertThat(SqlStatementLog.STATEMENTS).isNotEmpty()
            .noneSatisfy(sql -> assertThat(sql.trim().toUpperCase(Locale.ROOT)).startsWith("UPDATE"))
            .noneSatisfy(sql -> assertThat(sql.trim().toUpperCase(Locale.ROOT)).startsWith("DELETE"));
        assertThat(query("SELECT DISTINCT entry FROM sec_refresh_token WHERE user_id = ?::uuid", userId))
            .singleElement().satisfies(r -> assertThat(r).containsEntry("entry", "strict"));
    }

}
