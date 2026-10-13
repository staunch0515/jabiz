package com.jabiz.app.it.security;

import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An administrator lists a user's sessions at every entry and ends one or all of them (docs/design/10-security.md
 * section 15.9; ROADMAP phase 16g): reading needs {@code security.user.session.read}; ending runs
 * {@code SEC_USER_SESSIONS_REVOKE}, an action on users that needs {@code security.user.session.revoke}, a recent second
 * factor and a reason. Ending every session of a user (a new password too) concerns live sessions only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {SessionItSupport.APP_ROLES,
    SessionItSupport.APP_TTL, SessionItSupport.APP_IDLE, SessionItSupport.SHOP_ROLES, SessionItSupport.SHOP_TTL,
    SessionItSupport.SHOP_IDLE})
class AdminSessionIT extends SessionItSupport {

    private static final String READ = "security.user.session.read";
    private static final String REVOKE = "security.user.session.revoke";
    private static final String PROCESS = "/api/processes/SEC_USER_SESSIONS_REVOKE/latest";

    private Map<String, Object> listFor(String bearer, String userId) {
        return get("/api/security/sessions?userId=" + userId, bearer).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
    }

    private static Map<String, Object> input(String userId, String sessionId, String reason) {
        Map<String, Object> input = new HashMap<>();
        input.put("userId", userId);
        if (sessionId != null) {
            input.put("sessionId", sessionId);
        }
        if (reason != null) {
            input.put("reason", reason);
        }
        return input;
    }

    @Test
    void anAdministratorSeesTheSessionsOfEveryEntry() {
        String name = unique("ann");
        String userId = player(name);
        Map<String, Object> app = signInFrom(name, APP, "203.0.113.1", CHROME_WINDOWS);
        Map<String, Object> shop = signInFrom(name, SHOP, "203.0.113.2", null);
        Map<String, Object> admin = signInFrom(name, null, "203.0.113.3", null);

        Map<String, Object> list = listFor(bearer(READ), userId);
        assertThat(sessions(list)).extracting(s -> s.get("id")).containsExactlyInAnyOrder(sessionIdOf(app),
            sessionIdOf(shop), sessionIdOf(admin));
        assertThat(sessions(list)).allSatisfy(s -> assertThat(s).containsEntry("current", false));
        assertThat(sessions(list)).filteredOn(s -> APP.equals(s.get("entry"))).singleElement()
            .satisfies(s -> assertThat(s).containsEntry("clientIp", "203.0.113.1")
                .containsEntry("userAgent", CHROME_WINDOWS));

        // Without the permission: refused, also for the user's own sessions' holder of other rights.
        get("/api/security/sessions?userId=" + userId, bearer("security.user.read")).expectStatus().isForbidden();
        get("/api/security/sessions?userId=" + userId, bearerOf(app)).expectStatus().isForbidden();
        get("/api/security/sessions?userId=not-a-uuid", bearer(READ)).expectStatus().isBadRequest();
    }

    @Test
    void anAdministratorEndsOneSessionOrAll() {
        String name = unique("bo");
        String userId = player(name);
        Map<String, Object> app = signInFrom(name, APP, "203.0.113.1", null);
        Map<String, Object> shop = signInFrom(name, SHOP, "203.0.113.2", null);
        Map<String, Object> admin = signInFrom(name, null, "203.0.113.3", null);

        Map<String, Object> one = post(PROCESS, bearer(REVOKE), input(userId, sessionIdOf(shop), "phone lost"))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(one.get("output")).isEqualTo(Map.of("revoked", 1));
        refresh(shop, SHOP, null).expectStatus().isUnauthorized();
        app = refreshed(app, APP, null);
        admin = refreshed(admin, null, null);
        // A session that is not the user's live one: 404, nothing ended.
        assertThat(problemCode(post(PROCESS, bearer(REVOKE), input(userId, sessionIdOf(shop), "again")), 404))
            .isEqualTo("SESSION_NOT_FOUND");

        Map<String, Object> all = post(PROCESS, bearer(REVOKE), input(userId, null, "account compromised"))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(all.get("output")).isEqualTo(Map.of("revoked", 2));
        refresh(app, APP, null).expectStatus().isUnauthorized();
        refresh(admin, null, null).expectStatus().isUnauthorized();
        assertThat(sessions(listFor(bearer(READ), userId))).isEmpty();

        // The revocations name the operation, which keeps the reason.
        assertThat(query("SELECT r.reason, p.process_name, p.input_summary::text AS summary "
            + "FROM sec_refresh_family_revocation r JOIN op_process p ON p.process_seq_id = r.process_seq_id "
            + "WHERE r.family_id = ?::uuid", sessionIdOf(app))).singleElement().satisfies(row -> {
                assertThat(row).containsEntry("reason", "ADMIN_REVOKE")
                    .containsEntry("process_name", "SEC_USER_SESSIONS_REVOKE");
                assertThat(String.valueOf(row.get("summary"))).contains("account compromised")
                    .doesNotContain(String.valueOf(shop.get("refreshToken")));
            });
    }

    @Test
    void endingSessionsNeedsThePermissionARecentSecondFactorAndAReason() {
        String name = unique("cy");
        String userId = player(name);
        Map<String, Object> app = signInFrom(name, APP, "203.0.113.1", null);

        post(PROCESS, bearer(READ), input(userId, null, "why")).expectStatus().isForbidden();
        assertThat(problemCode(post(PROCESS, TestTokens.withoutMfa(tokens, "it-admin", REVOKE),
            input(userId, null, "why")), 403)).isEqualTo("MFA_REQUIRED");
        post(PROCESS, bearer(REVOKE), input(userId, null, null)).expectStatus().isBadRequest();
        post(PROCESS, bearer(REVOKE), input(userId, null, "x".repeat(501))).expectStatus().isBadRequest();
        // An unknown user: nothing to act on.
        post(PROCESS, bearer(REVOKE), input(java.util.UUID.randomUUID().toString(), null, "why"))
            .expectStatus().isNotFound();
        refreshed(app, APP, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void itIsAnActionOnUsers() {
        List<Map<String, Object>> processes = get("/api/meta/processes", bearer(REVOKE)).expectStatus().isOk()
            .expectBody(LIST).returnResult().getResponseBody();
        assertThat(processes).filteredOn(p -> "SEC_USER_SESSIONS_REVOKE".equals(p.get("name"))).singleElement()
            .satisfies(p -> assertThat((Map<String, Object>) p.get("actsOn"))
                .containsEntry("entity", "SecUser").containsEntry("input", "userId"));
        // The user's own processes are not in the catalogue.
        assertThat(get("/api/meta/processes", admin()).expectStatus().isOk().expectBody(LIST).returnResult()
            .getResponseBody()).extracting(p -> p.get("name"))
            .doesNotContain("SEC_ACCOUNT_SESSION_REVOKE", "SEC_ACCOUNT_SESSIONS_REVOKE_OTHERS");
    }

    @Test
    void aNewPasswordEndsOnlySessionsThatAreStillLive() {
        String name = unique("dot");
        String userId = player(name);
        Map<String, Object> expired = signInFrom(name, SHOP, "203.0.113.1", null);
        clock.advance(Duration.ofHours(3));
        Map<String, Object> live = signInFrom(name, SHOP, "203.0.113.2", null);

        post("/api/processes/SEC_USER_SET_PASSWORD/latest", admin(), Map.of("userId", userId,
            "password", "brand new horse battery")).expectStatus().isOk();

        refresh(live, SHOP, null).expectStatus().isUnauthorized();
        refresh(expired, SHOP, null).expectStatus().isUnauthorized();
        // The family that had expired already got no revocation; the live one did, by the operation.
        assertThat(query("SELECT 1 FROM sec_refresh_family_revocation WHERE family_id = ?::uuid",
            sessionIdOf(expired))).isEmpty();
        assertThat(query("SELECT r.reason, p.process_name FROM sec_refresh_family_revocation r "
            + "JOIN op_process p ON p.process_seq_id = r.process_seq_id WHERE r.family_id = ?::uuid",
            sessionIdOf(live))).singleElement().satisfies(row -> assertThat(row).containsEntry("reason", "PASSWORD")
                .containsEntry("process_name", "SEC_USER_SET_PASSWORD"));
    }
}
