package com.jabiz.app.it.security;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.jabiz.app.it.fixture.SqlStatementLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.jabiz.runtime.security.PasswordHasher;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A signed-in user changes their own password (docs/design/10-security.md section 15.8; decision D43 item 3; ROADMAP
 * phase 16m): only with the current password, whose wrong guesses count towards the lock; the new password ends every
 * other session but the caller's, is recorded ({@code PASSWORD_CHANGED}) and told by mail. Nobody runs the process
 * through the process API, and an account without a password has none to change.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {"it.sql-log.enabled=true",
    "jabiz.mail.enabled=true", "jabiz.mail.from=jabiz@example.com", "jabiz.mail.base-url=https://app.example.com",
    "spring.mail.host=localhost", "spring.mail.port=3036", SignUpItSupport.ENTRY_PROPERTIES_REGISTRATION,
    SignUpItSupport.ENTRY_PROPERTIES_ADMIN, SignUpItSupport.ENTRY_PROPERTIES_LOOSE})
@Import({SignUpItSupport.FixedTimeLimits.class, AccountPasswordIT.CountingHasher.class})
class AccountPasswordIT extends SignUpItSupport {

    /** The password hasher, counting the hashes it computes (the work a password change does). */
    @TestConfiguration
    static class CountingHasher {

        static final AtomicInteger HASHES = new AtomicInteger();

        @Bean
        @Primary
        PasswordHasher countingPasswordHasher() {
            return new PasswordHasher(4, 10) {
                @Override
                public String hash(String password) {
                    HASHES.incrementAndGet();
                    return super.hash(password);
                }
            };
        }
    }

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3036, null, ServerSetup.PROTOCOL_SMTP));

    private static final String NEW_PASSWORD = "brand new horse battery";
    private static final String PATH = "/api/auth/account/password";

    @Override
    GreenMailExtension mail() {
        return MAIL;
    }

    /** A customer with an address and a password; returns the user id. */
    private String customer(String name, String email) {
        String userId = createUserWithEmail(name, email, PASSWORD);
        assign(userId, roleId(CUSTOMER), null);
        return userId;
    }

    private WebTestClient.ResponseSpec change(Map<String, Object> session, String current, String next,
        boolean keep) {
        Map<String, Object> body = new HashMap<>();
        body.put("currentPassword", current);
        body.put("newPassword", next);
        if (keep) {
            body.put("refreshToken", session.get("refreshToken"));
        }
        return post(PATH, bearerOf(session), body);
    }

    private WebTestClient.ResponseSpec refresh(Map<String, Object> session) {
        return post("/api/auth/refresh", null, Map.of("refreshToken", session.get("refreshToken"), "entry", LOOSE));
    }

    private static List<Map<String, Object>> records(String userId) {
        return query("SELECT outcome, failure_count, locked_until, entry, factor, user_agent "
            + "FROM sec_login_record_version WHERE user_id = ?::uuid ORDER BY attempt_no", userId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theNewPasswordEndsTheOtherSessionsButTheCallers() {
        String name = unique("cam");
        String email = address("cam");
        String userId = customer(name, email);
        Map<String, Object> mine = session(name, PASSWORD, LOOSE);
        Map<String, Object> other = session(name, PASSWORD, LOOSE);
        verifyEmail(userId);
        Map<String, Object> elsewhere = session(name, PASSWORD, PORTAL);

        client.post().uri(PATH).header(HttpHeaders.AUTHORIZATION, bearerOf(mine))
            .header(HttpHeaders.USER_AGENT, "it-agent/change")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD,
                "refreshToken", mine.get("refreshToken")))
            .exchange().expectStatus().isNoContent();

        // The caller's session lives on; the others end.
        refresh(mine).expectStatus().isOk();
        assertThat(refusal(refresh(other), 401)).isEqualTo("INVALID_REFRESH_TOKEN");
        post("/api/auth/refresh", null, Map.of("refreshToken", elsewhere.get("refreshToken"), "entry", PORTAL))
            .expectStatus().isUnauthorized();
        assertThat(query("SELECT DISTINCT reason FROM sec_refresh_family_revocation r JOIN sec_refresh_token t "
            + "ON t.family_id = r.family_id WHERE t.user_id = ?::uuid", userId))
            .extracting(row -> row.get("reason")).containsExactly("PASSWORD_CHANGE");

        // The old password is gone, the new one works.
        assertThat(refusal(loginTo(name, PASSWORD, LOOSE), 401)).isEqualTo("LOGIN_FAILED");
        session(name, NEW_PASSWORD, LOOSE);

        assertThat(records(userId)).filteredOn(row -> "PASSWORD_CHANGED".equals(row.get("outcome"))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("entry", LOOSE).containsEntry("factor", "PASSWORD")
                .containsEntry("user_agent", "it-agent/change").containsEntry("failure_count", 0));

        deliver();
        assertThat(to(email)).singleElement().satisfies(message -> {
            assertThat(message.getSubject()).isEqualTo("Your password was changed");
            assertThat(text(message)).contains("https://app.example.com/auth/password-reset?entry=loose")
                .doesNotContain("token=");
        });
        // No secret reaches the operation record.
        assertThat(query("SELECT input_summary::text AS summary FROM op_process WHERE process_name = ?",
            "SEC_ACCOUNT_PASSWORD_CHANGE")).allSatisfy(row -> assertThat(String.valueOf(row.get("summary")))
            .doesNotContain(PASSWORD).doesNotContain(NEW_PASSWORD)
            .doesNotContain(String.valueOf(mine.get("refreshToken"))));
    }

    @Test
    void withoutATokenOfTheCallersSessionEverySessionEnds() {
        String name = unique("dee");
        String userId = customer(name, address("dee"));
        verifyEmail(userId);
        Map<String, Object> mine = session(name, PASSWORD, LOOSE);
        Map<String, Object> other = session(name, PASSWORD, LOOSE);

        // The user's own token, but of a session at another entry than the caller's: it keeps nothing.
        Map<String, Object> portal = session(name, PASSWORD, PORTAL);
        Map<String, Object> body = new HashMap<>(Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD,
            "refreshToken", portal.get("refreshToken")));
        post(PATH, bearerOf(mine), body).expectStatus().isNoContent();

        refresh(mine).expectStatus().isUnauthorized();
        refresh(other).expectStatus().isUnauthorized();
        post("/api/auth/refresh", null, Map.of("refreshToken", portal.get("refreshToken"), "entry", PORTAL))
            .expectStatus().isUnauthorized();

        // Nor does somebody else's token (a live one of another user).
        String otherName = unique("eve");
        customer(otherName, address("eve"));
        Map<String, Object> eve = session(otherName, PASSWORD, LOOSE);
        Map<String, Object> mine2 = session(name, NEW_PASSWORD, LOOSE);
        post(PATH, bearerOf(mine2), Map.of("currentPassword", NEW_PASSWORD, "newPassword", PASSWORD,
            "refreshToken", eve.get("refreshToken"))).expectStatus().isNoContent();
        refresh(mine2).expectStatus().isUnauthorized();
        refresh(eve).expectStatus().isOk();
    }

    @Test
    void aWrongCurrentPasswordIsRefusedAndCountsTowardsTheLock() {
        String name = unique("fay");
        String email = address("fay");
        String userId = customer(name, email);
        Map<String, Object> mine = session(name, PASSWORD, LOOSE);

        for (int i = 0; i < 5; i++) {
            assertThat(refusal(change(mine, "wrong horse battery", NEW_PASSWORD, true), 422))
                .isEqualTo("PASSWORD_INCORRECT");
        }
        assertThat(records(userId)).extracting(row -> row.get("outcome")).containsExactly("SUCCESS",
            "BAD_CREDENTIALS", "BAD_CREDENTIALS", "BAD_CREDENTIALS", "BAD_CREDENTIALS", "BAD_CREDENTIALS");
        assertThat(records(userId).getLast().get("locked_until")).isNotNull();

        // Locked: even the right password is not considered, and the account cannot sign in.
        assertThat(refusal(change(mine, PASSWORD, NEW_PASSWORD, true), 422)).isEqualTo("PASSWORD_INCORRECT");
        assertThat(records(userId).getLast()).containsEntry("outcome", "LOCKED");
        assertThat(refusal(loginTo(name, PASSWORD, LOOSE), 401)).isEqualTo("LOGIN_FAILED");
        // The session ends at its next refresh (a locked user is not renewed).
        refresh(mine).expectStatus().isUnauthorized();

        deliver();
        assertThat(to(email)).isEmpty();
        assertThat(userOf(email).get("password_hash")).isNotNull();
    }

    @Test
    void concurrentWrongAttemptsEachCountTowardsTheLock() throws Exception {
        String name = unique("kai");
        String userId = customer(name, address("kai"));
        Map<String, Object> mine = session(name, PASSWORD, LOOSE);
        String bearer = bearerOf(mine);

        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            List<Callable<Integer>> attempts = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                String client = nextClient();
                attempts.add(() -> this.client.post().uri(PATH).header(HttpHeaders.AUTHORIZATION, bearer)
                    .header(CONNECTION, client).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("currentPassword", "wrong horse battery", "newPassword", NEW_PASSWORD))
                    .exchange().returnResult(Void.class).getStatus().value());
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> status : pool.invokeAll(attempts)) {
                statuses.add(status.get());
            }
            assertThat(statuses).containsOnly(422);
        } finally {
            pool.shutdownNow();
        }
        // Not one guess got lost in a race: five failures, and the account is locked.
        assertThat(records(userId)).extracting(row -> row.get("outcome")).containsExactly("SUCCESS",
            "BAD_CREDENTIALS", "BAD_CREDENTIALS", "BAD_CREDENTIALS", "BAD_CREDENTIALS", "BAD_CREDENTIALS");
        assertThat(records(userId).getLast().get("locked_until")).isNotNull();
        assertThat(refusal(loginTo(name, PASSWORD, LOOSE), 401)).isEqualTo("LOGIN_FAILED");
    }

    @Test
    void aRightAndAWrongCurrentPasswordCostTheSameWork() {
        String name = unique("lee");
        customer(name, address("lee"));
        Map<String, Object> mine = session(name, PASSWORD, LOOSE);

        int before = CountingHasher.HASHES.get();
        change(mine, "wrong horse battery", NEW_PASSWORD, false).expectStatus().isEqualTo(422);
        int wrong = CountingHasher.HASHES.get() - before;
        before = CountingHasher.HASHES.get();
        change(mine, PASSWORD, NEW_PASSWORD, false).expectStatus().isNoContent();
        int right = CountingHasher.HASHES.get() - before;
        // Both hash the new password: whether the current one was right does not show in the time taken.
        assertThat(wrong).isEqualTo(right).isEqualTo(1);
    }

    @Test
    void theNewPasswordFollowsTheRulesBeforeAnythingIsCounted() {
        String name = unique("gil");
        String userId = customer(name, address("gil"));
        Map<String, Object> mine = session(name, PASSWORD, LOOSE);

        assertThat(refusal(change(mine, PASSWORD, "short", true), 400)).isEqualTo("PASSWORD_TOO_SHORT");
        assertThat(refusal(change(mine, null, NEW_PASSWORD, true), 400)).isEqualTo("REQUIRED");
        assertThat(records(userId)).extracting(row -> row.get("outcome")).containsExactly("SUCCESS");
    }

    @Test
    void aUserWithoutAPasswordHasNoneToChange() {
        String name = unique("oid");
        String email = address("oid");
        String userId = createUserWithEmail(name, email, null);
        assign(userId, roleId(CUSTOMER), null);
        String bearer = "Bearer " + tokens.issue(new com.jabiz.runtime.context.Actor(userId, null,
            java.util.Set.of(CUSTOMER), java.util.Set.of(), null, null, LOOSE, false)).token();

        assertThat(refusal(post(PATH, bearer, Map.of("currentPassword", "anything at all", "newPassword",
            NEW_PASSWORD)), 422)).isEqualTo("PASSWORD_NOT_SET");
        assertThat(records(userId)).isEmpty();
    }

    @Test
    void theProcessRunsOnlyThroughItsEndpointForTheCallerOnly() {
        String name = unique("hal");
        String userId = customer(name, address("hal"));
        Map<String, Object> input = Map.of("userId", userId, "currentPassword", PASSWORD, "newPassword", NEW_PASSWORD);
        assertThat(refusal(post("/api/processes/SEC_ACCOUNT_PASSWORD_CHANGE/latest", admin(), input), 403))
            .isEqualTo("PROCESS_INTERNAL");
        // Actors that are no users of the platform have no password here.
        post(PATH, admin(), Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD))
            .expectStatus().isForbidden();
        post(PATH, null, Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD))
            .expectStatus().isUnauthorized();
        loginTo(name, PASSWORD, LOOSE).expectStatus().isOk();
    }

    @Test
    void attemptsAreLimitedPerUser() {
        String name = unique("ida");
        customer(name, address("ida"));
        Map<String, Object> mine = session(name, PASSWORD, LOOSE);
        // Each attempt from an address of its own: the limit of the user (10 an hour) still holds. The access token
        // outlives the sessions each change ends.
        for (int i = 0; i < 10; i++) {
            client.post().uri(PATH).header(HttpHeaders.AUTHORIZATION, bearerOf(mine)).header(CONNECTION, nextClient())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("currentPassword", i % 2 == 0 ? PASSWORD : NEW_PASSWORD,
                    "newPassword", i % 2 == 0 ? NEW_PASSWORD : PASSWORD))
                .exchange().expectStatus().isNoContent();
        }
        // Beyond the limit no password is even checked.
        client.post().uri(PATH).header(HttpHeaders.AUTHORIZATION, bearerOf(mine)).header(CONNECTION, nextClient())
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD))
            .exchange().expectStatus().isEqualTo(429).expectHeader().exists(HttpHeaders.RETRY_AFTER);
    }

    @Test
    void changingOnlyAppendsRows() {
        String name = unique("jo");
        customer(name, address("jo"));
        Map<String, Object> mine = session(name, PASSWORD, LOOSE);
        session(name, PASSWORD, LOOSE);
        SqlStatementLog.STATEMENTS.clear();
        change(mine, "wrong horse battery", NEW_PASSWORD, true).expectStatus().isEqualTo(422);
        change(mine, PASSWORD, NEW_PASSWORD, true).expectStatus().isNoContent();
        deliver();

        assertThat(SqlStatementLog.STATEMENTS).isNotEmpty()
            .noneSatisfy(sql -> assertThat(sql.trim().toUpperCase(Locale.ROOT)).startsWith("UPDATE"))
            .noneSatisfy(sql -> assertThat(sql.trim().toUpperCase(Locale.ROOT)).startsWith("DELETE"));
    }
}
