package com.jabiz.app.it.security;

import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.runtime.security.SecurityEntities;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Signing in through {@code SPONSOR_SIGN_IN}, the lock after repeated wrong passwords, sessions (refresh tokens) and
 * menus (docs/design/10-security.md sections 2 to 4; ROADMAP phase 7).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "it.sql-log.enabled=true")
class SignInIT extends SecurityItSupport {

    private static final String PASSWORD = "correct horse battery";
    private static final String WRONG = "wrong horse battery";

    @Test
    @SuppressWarnings("unchecked")
    void signingInIssuesTokensCarryingTheUsersPermissions() {
        String name = unique("alice");
        String userId = userWith(name, PASSWORD, "pricing.price.read");

        Map<String, Object> session = signIn(name, PASSWORD);

        assertThat(session).containsEntry("tokenType", "Bearer").containsEntry("userId", userId)
            .containsEntry("permissions", List.of("pricing.price.read"));
        assertThat(session.get("accessTokenExpiresAt")).isEqualTo(START.plus(Duration.ofMinutes(15)).toString());
        Map<String, Object> me = get("/api/auth/me", bearerOf(session)).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(me).containsEntry("userId", userId).containsEntry("permissions", List.of("pricing.price.read"));
        post("/api/datasets/urn:jabiz:dataset:default:Price/query", bearerOf(session), Map.of()).expectStatus().isOk();
        post("/api/datasets/" + SecurityEntities.USER_DATASET + "/query", bearerOf(session), Map.of())
            .expectStatus().isForbidden();

        List<Map<String, Object>> records = loginRecords(userId);
        assertThat(records).singleElement().satisfies(r -> assertThat(r).containsEntry("outcome", "SUCCESS"));
        // The sign-in is an operation of its own, recorded under the anonymous caller.
        assertThat(query("SELECT process_name, actor_id FROM op_process WHERE process_name = 'SPONSOR_SIGN_IN'"))
            .isNotEmpty();
    }

    @Test
    void everyRefusalLooksTheSame() {
        // Wrong password, unknown user, missing password, and a right password without any role.
        String name = unique("bob");
        createUser(name, PASSWORD);

        for (Map<String, Object> body : List.<Map<String, Object>>of(Map.of("userName", name, "password", WRONG),
            Map.of("userName", unique("nobody"), "password", PASSWORD), Map.of("userName", name),
            Map.of("userName", name, "password", PASSWORD))) {
            Map<String, Object> problem = post("/api/auth/login", null, body).expectStatus().isUnauthorized()
                .expectBody(MAP).returnResult().getResponseBody();
            assertThat(ruleCode(problem)).isEqualTo("LOGIN_FAILED");
        }
    }

    @Test
    void consecutiveWrongPasswordsLockTheAccount() {
        String name = unique("carol");
        String userId = userWith(name, PASSWORD, "p");

        for (int i = 0; i < 5; i++) {
            login(name, WRONG).expectStatus().isUnauthorized();
        }
        // Locked: even the right password is refused.
        login(name, PASSWORD).expectStatus().isUnauthorized();

        List<Map<String, Object>> records = loginRecords(userId);
        assertThat(records).extracting(r -> r.get("outcome")).containsExactly(
            "BAD_CREDENTIALS", "BAD_CREDENTIALS", "BAD_CREDENTIALS", "BAD_CREDENTIALS", "BAD_CREDENTIALS", "LOCKED");
        assertThat(records).extracting(r -> ((Number) r.get("failure_count")).intValue())
            .containsExactly(1, 2, 3, 4, 5, 5);

        // The lock ends by itself.
        clock.advance(Duration.ofMinutes(15));
        signIn(name, PASSWORD);
        assertThat(loginRecords(userId).getLast()).containsEntry("outcome", "SUCCESS");
    }

    @Test
    void anAdministratorCanLiftALock() {
        String name = unique("dave");
        String userId = userWith(name, PASSWORD, "p");
        for (int i = 0; i < 5; i++) {
            login(name, WRONG).expectStatus().isUnauthorized();
        }
        login(name, PASSWORD).expectStatus().isUnauthorized();

        post("/api/processes/SEC_USER_UNLOCK/latest", bearer("security.user.read"), Map.of("userId", userId))
            .expectStatus().isForbidden();
        post("/api/processes/SEC_USER_UNLOCK/latest", bearer("security.user.unlock"), Map.of("userId", userId))
            .expectStatus().isOk();

        signIn(name, PASSWORD);
        assertThat(loginRecords(userId)).extracting(r -> r.get("outcome")).endsWith("UNLOCKED", "SUCCESS");
    }

    @Test
    void concurrentWrongPasswordsCannotEscapeTheCount() throws Exception {
        String name = unique("erin");
        String userId = userWith(name, PASSWORD, "p");

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> attempts = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                attempts.add(() -> login(name, WRONG).returnResult(Void.class).getStatus().value());
            }
            for (Future<Integer> status : pool.invokeAll(attempts)) {
                assertThat(status.get()).isEqualTo(401);
            }
        } finally {
            pool.shutdown();
        }

        // Attempts that lost a race left no record; those recorded form one unbroken series.
        List<Map<String, Object>> records = loginRecords(userId);
        assertThat(records).isNotEmpty();
        for (int i = 0; i < records.size(); i++) {
            Map<String, Object> record = records.get(i);
            int attempt = i + 1;
            assertThat(((Number) record.get("attempt_no")).intValue()).isEqualTo(attempt);
            assertThat(record.get("outcome")).isEqualTo(attempt <= 5 ? "BAD_CREDENTIALS" : "LOCKED");
            assertThat(((Number) record.get("failure_count")).intValue()).isEqualTo(Math.min(attempt, 5));
        }
    }

    @Test
    void onlyEnabledUsersWithARoleInEffectCanSignIn() {
        // No role at all.
        String lonely = unique("frank");
        String lonelyId = createUser(lonely, PASSWORD);
        login(lonely, PASSWORD).expectStatus().isUnauthorized();
        assertThat(loginRecords(lonelyId)).extracting(r -> r.get("outcome")).containsExactly("NO_ROLE");

        // A role assigned from tomorrow on.
        String later = unique("grace");
        String laterId = createUser(later, PASSWORD);
        assign(laterId, createRole(unique("ROLE"), "p"), START.plus(Duration.ofDays(1)));
        login(later, PASSWORD).expectStatus().isUnauthorized();
        clock.advance(Duration.ofDays(1));
        signIn(later, PASSWORD);

        // A disabled role grants nothing.
        String roleless = unique("heidi");
        String rolelessId = createUser(roleless, PASSWORD);
        String roleId = String.valueOf(insert(SecurityEntities.ROLE_DATASET,
            Map.of("roleCode", unique("OFF"), "labels", Map.of(), "enabled", false), null).get("id"));
        assign(rolelessId, roleId, null);
        login(roleless, PASSWORD).expectStatus().isUnauthorized();

        // A disabled user.
        String disabled = unique("ivan");
        String disabledId = userWith(disabled, PASSWORD, "p");
        disable(disabledId);
        login(disabled, PASSWORD).expectStatus().isUnauthorized();
        assertThat(loginRecords(disabledId)).extracting(r -> r.get("outcome")).containsExactly("DISABLED");
    }

    @Test
    void refreshTokensRotateAndAreSingleUse() {
        String name = unique("judy");
        userWith(name, PASSWORD, "p");
        Map<String, Object> first = signIn(name, PASSWORD);

        Map<String, Object> second = post("/api/auth/refresh", null, Map.of("refreshToken", first.get("refreshToken")))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(second.get("refreshToken")).isNotEqualTo(first.get("refreshToken"));
        get("/api/auth/me", bearerOf(second)).expectStatus().isOk();

        // Presenting the used token again ends the whole session, including the token that replaced it.
        Map<String, Object> reused = post("/api/auth/refresh", null, Map.of("refreshToken", first.get("refreshToken")))
            .expectStatus().isUnauthorized().expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCode(reused)).isEqualTo("INVALID_REFRESH_TOKEN");
        post("/api/auth/refresh", null, Map.of("refreshToken", second.get("refreshToken")))
            .expectStatus().isUnauthorized();
        post("/api/auth/refresh", null, Map.of("refreshToken", "made-up")).expectStatus().isUnauthorized();
        post("/api/auth/refresh", null, Map.of()).expectStatus().isUnauthorized();
    }

    @Test
    void aStaleAccessTokenSentAlongDoesNotStopRefreshingOrSigningIn() {
        String name = unique("nick");
        userWith(name, PASSWORD, "p");
        Map<String, Object> session = signIn(name, PASSWORD);
        clock.advance(Duration.ofMinutes(20));

        String stale = bearerOf(session);
        get("/api/auth/me", stale).expectStatus().isUnauthorized();
        Map<String, Object> renewed = post("/api/auth/refresh", stale, Map.of("refreshToken", session.get("refreshToken")))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        get("/api/auth/me", bearerOf(renewed)).expectStatus().isOk();
        post("/api/auth/login", "Bearer garbage", Map.of("userName", name, "password", PASSWORD)).expectStatus().isOk();
        post("/api/auth/logout", "Bearer garbage", Map.of("refreshToken", renewed.get("refreshToken")))
            .expectStatus().isNoContent();
    }

    @Test
    void aNewPasswordEndsTheUsersSessions() {
        String name = unique("olivia");
        String userId = userWith(name, PASSWORD, "p");
        Map<String, Object> first = signIn(name, PASSWORD);
        Map<String, Object> second = signIn(name, PASSWORD);

        post("/api/processes/SEC_USER_SET_PASSWORD/latest", bearer("security.user.password"),
            Map.of("userId", userId, "password", "a brand new password")).expectStatus().isOk();

        for (Map<String, Object> session : List.of(first, second)) {
            post("/api/auth/refresh", null, Map.of("refreshToken", session.get("refreshToken")))
                .expectStatus().isUnauthorized();
        }
        login(name, PASSWORD).expectStatus().isUnauthorized();
        signIn(name, "a brand new password");
        assertThat(query("SELECT reason FROM sec_refresh_family_revocation r JOIN sec_refresh_token t "
            + "ON t.family_id = r.family_id WHERE t.user_id = ?::uuid", userId))
            .extracting(r -> r.get("reason")).containsOnly("PASSWORD");
    }

    @Test
    void theTokenTablesRefuseUpdatesAndDeletes() {
        String name = unique("peggy");
        userWith(name, PASSWORD, "p");
        // A row in each table: a token, its use, a revocation.
        Map<String, Object> next = post("/api/auth/refresh", null,
            Map.of("refreshToken", signIn(name, PASSWORD).get("refreshToken")))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        post("/api/auth/logout", null, Map.of("refreshToken", next.get("refreshToken"))).expectStatus().isNoContent();

        for (String sql : List.of("UPDATE sec_refresh_token SET expires_at = expires_at",
            "DELETE FROM sec_refresh_token_use", "DELETE FROM sec_refresh_family_revocation")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> execute(sql))
                .hasMessageContaining("append-only");
        }
    }

    @Test
    void signingOutAndExpiryEndTheSession() {
        String name = unique("ken");
        String userId = userWith(name, PASSWORD, "p");

        Map<String, Object> session = signIn(name, PASSWORD);
        post("/api/auth/logout", null, Map.of("refreshToken", session.get("refreshToken"))).expectStatus().isNoContent();
        post("/api/auth/refresh", null, Map.of("refreshToken", session.get("refreshToken")))
            .expectStatus().isUnauthorized();

        Map<String, Object> expiring = signIn(name, PASSWORD);
        clock.advance(Duration.ofHours(8));
        post("/api/auth/refresh", null, Map.of("refreshToken", expiring.get("refreshToken")))
            .expectStatus().isUnauthorized();

        // A refresh reads the user afresh: a disabled user gets no new tokens.
        clock.set(START);
        Map<String, Object> live = signIn(name, PASSWORD);
        disable(userId);
        post("/api/auth/refresh", null, Map.of("refreshToken", live.get("refreshToken")))
            .expectStatus().isUnauthorized();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theMenuShowsTheEntriesTheUserMayUse() {
        String name = unique("leo");
        userWith(name, PASSWORD, "pricing.price.read");
        String top = unique("m");
        insert(SecurityEntities.MENU_DATASET, Map.of("menuCode", top, "labels", Map.of("en", "Prices", "ja", "価格"),
            "path", "/prices", "sortOrder", 1, "permission", "pricing.price.read", "enabled", true), null);
        insert(SecurityEntities.MENU_DATASET, Map.of("menuCode", unique("m"), "parentCode", top,
            "labels", Map.of("en", "Users"), "sortOrder", 2, "permission", "security.user.read", "enabled", true), null);

        List<Map<String, Object>> menu = client.get().uri("/api/auth/menus")
            .header("Authorization", bearerOf(signIn(name, PASSWORD))).header("Accept-Language", "ja")
            .exchange().expectStatus().isOk().expectBody(LIST).returnResult().getResponseBody();

        assertThat(menu).filteredOn(m -> top.equals(m.get("code"))).singleElement().satisfies(m -> {
            assertThat(m).containsEntry("label", "価格").containsEntry("path", "/prices");
            assertThat((List<Object>) m.get("children")).isEmpty();
        });
    }

    @Test
    void securityTablesAreOnlyEverInsertedInto() {
        String name = unique("mia");
        String userId = userWith(name, PASSWORD, "p");
        SqlStatementLog.STATEMENTS.clear();

        login(name, WRONG).expectStatus().isUnauthorized();
        Map<String, Object> session = signIn(name, PASSWORD);
        Map<String, Object> next = post("/api/auth/refresh", null, Map.of("refreshToken", session.get("refreshToken")))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        post("/api/auth/logout", null, Map.of("refreshToken", next.get("refreshToken"))).expectStatus().isNoContent();
        post("/api/processes/SEC_USER_SET_PASSWORD/latest", admin(),
            Map.of("userId", userId, "password", "another long password")).expectStatus().isOk();

        assertThat(SqlStatementLog.STATEMENTS).isNotEmpty()
            .noneSatisfy(sql -> assertThat(sql.trim().toUpperCase()).startsWith("UPDATE"))
            .noneSatisfy(sql -> assertThat(sql.trim().toUpperCase()).startsWith("DELETE"));
    }

    private void disable(String userId) {
        Map<String, Object> user = get("/api/datasets/" + SecurityEntities.USER_DATASET + "/entities/" + userId, admin())
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        post("/api/datasets/" + SecurityEntities.USER_DATASET + "/commit", admin(), Map.of("changes", List.of(Map.of(
            "action", "UPDATE", "id", userId, "version", user.get("version"), "attributes", Map.of("enabled", false)))))
            .expectStatus().isOk();
    }
}
