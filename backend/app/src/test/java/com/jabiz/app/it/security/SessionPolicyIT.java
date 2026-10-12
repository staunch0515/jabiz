package com.jabiz.app.it.security;

import com.jabiz.app.it.fixture.SqlStatementLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sessions of each sign-in entry (docs/design/10-security.md sections 2, 11 and 15.1; decision D43 item 2; ROADMAP
 * phase 16m), on the tests' fixed clock: {@value #APP} keeps its sessions 30 days on the device and never ends them for
 * idleness, {@value #SHOP} has a two-hour lifetime and an hour's idle timeout, and the administration (no entry named)
 * keeps the global settings (eight hours, fifteen minutes), as before.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {"it.sql-log.enabled=true",
    "jabiz.security.entries.app.accepted-roles=IT_PLAYER,IT_ROOT,IT_ALL",
    "jabiz.security.entries.app.session.refresh-token-ttl=30d",
    "jabiz.security.entries.app.session.idle-timeout=0",
    "jabiz.security.entries.app.session.persistence=device",
    "jabiz.security.entries.shop.accepted-roles=IT_PLAYER,IT_ROOT,IT_ALL",
    "jabiz.security.entries.shop.session.refresh-token-ttl=PT2H",
    "jabiz.security.entries.shop.session.idle-timeout=PT1H"})
class SessionPolicyIT extends SecurityItSupport {

    private static final String PASSWORD = "correct horse battery";
    private static final String PLAYER = "IT_PLAYER";
    private static final String APP = "app";
    private static final String SHOP = "shop";
    /** Roles granting the platform's administration: never counted in a long session. */
    private static final String ROOT = "IT_ROOT";
    private static final String ALL = "IT_ALL";

    private String name;

    @BeforeEach
    void player() {
        if (query("SELECT 1 FROM sec_role_version WHERE role_code = ?", PLAYER).isEmpty()) {
            createRole(PLAYER, "p");
            createRole(ROOT, "security.user.write", "q");
            createRole(ALL, "*");
        }
        name = unique("pat");
        String userId = createUser(name, PASSWORD);
        String roleId = String.valueOf(query("SELECT role_id::text AS id FROM sec_role_version WHERE role_code = ?",
            PLAYER).getFirst().get("id"));
        assign(userId, roleId, null);
    }

    private Map<String, Object> signInAt(String entry) {
        Map<String, Object> body = new HashMap<>(Map.of("userName", name, "password", PASSWORD));
        if (entry != null) {
            body.put("entry", entry);
        }
        return post("/api/auth/login", null, body).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec refresh(
        Map<String, Object> session, String entry) {
        Map<String, Object> body = new HashMap<>(Map.of("refreshToken", session.get("refreshToken")));
        if (entry != null) {
            body.put("entry", entry);
        }
        return post("/api/auth/refresh", null, body);
    }

    private Map<String, Object> refreshed(Map<String, Object> session, String entry) {
        return refresh(session, entry).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
    }

    private static Instant expiry(Map<String, Object> session) {
        return Instant.parse(String.valueOf(session.get("refreshTokenExpiresAt")));
    }

    private String roleId(String code) {
        return String.valueOf(query("SELECT role_id::text AS id FROM sec_role_version WHERE role_code = ?", code)
            .getFirst().get("id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rolesGrantingTheAdministrationDoNotCountInLongSessions() {
        String userId = String.valueOf(query("SELECT user_id::text AS id FROM sec_user_version WHERE user_name = ?",
            name).getFirst().get("id"));
        assign(userId, roleId(ROOT), null);
        assign(userId, roleId(ALL), null);

        Map<String, Object> app = signInAt(APP);
        assertThat((List<String>) app.get("roles")).containsExactly(PLAYER);
        assertThat((List<String>) app.get("permissions")).containsExactly("p");
        // Not after a refresh either.
        Map<String, Object> renewed = refreshed(app, APP);
        assertThat((List<String>) renewed.get("permissions")).containsExactly("p");
        assertThat(tokens.verify((String) renewed.get("accessToken")).permissions()).containsExactly("p");

        // In a session that ends when idle, they count as before.
        assertThat((List<String>) signInAt(SHOP).get("permissions")).contains("*", "security.user.write", "q", "p");

        // A user with such roles only has none that counts there.
        String root = unique("root");
        assign(createUser(root, PASSWORD), roleId(ROOT), null);
        post("/api/auth/login", null, Map.of("userName", root, "password", PASSWORD, "entry", APP))
            .expectStatus().isUnauthorized();
        post("/api/auth/login", null, Map.of("userName", root, "password", PASSWORD, "entry", SHOP))
            .expectStatus().isOk();
    }

    @Test
    void anEntryWithoutIdleEndKeepsItsSessionForItsLifetimeFromEachRefresh() {
        Map<String, Object> session = signInAt(APP);
        assertThat(expiry(session)).isEqualTo(START.plus(Duration.ofDays(30)));

        // Ten days without a request: no idle end, only the lifetime counts.
        clock.advance(Duration.ofDays(10));
        Map<String, Object> next = refreshed(session, APP);
        // Sliding: each refresh starts the lifetime afresh.
        assertThat(expiry(next)).isEqualTo(START.plus(Duration.ofDays(40)));

        clock.advance(Duration.ofDays(30));
        refresh(next, APP).expectStatus().isUnauthorized();
    }

    @Test
    void anEntryOfItsOwnIdleTimeoutAndLifetime() {
        Map<String, Object> session = signInAt(SHOP);
        assertThat(expiry(session)).isEqualTo(START.plus(Duration.ofHours(2)));

        // Within the access token (15 minutes) plus the entry's hour.
        clock.advance(Duration.ofMinutes(74));
        Map<String, Object> next = refreshed(session, SHOP);
        assertThat(expiry(next)).isEqualTo(START.plus(Duration.ofMinutes(74)).plus(Duration.ofHours(2)));

        // An hour and a quarter later it is idle: refused, and not consumed (it is still not reuse).
        clock.advance(Duration.ofMinutes(75));
        refresh(next, SHOP).expectStatus().isUnauthorized();
        assertThat(query("SELECT 1 FROM sec_refresh_family_revocation r JOIN sec_refresh_token t "
            + "ON t.family_id = r.family_id WHERE t.user_id = (SELECT user_id FROM sec_user_version WHERE user_name = ? "
            + "LIMIT 1)", name)).isEmpty();
    }

    @Test
    void theAdministrationKeepsTheGlobalSettings() {
        Map<String, Object> session = signInAt(null);
        assertThat(expiry(session)).isEqualTo(START.plus(Duration.ofHours(8)));
        clock.advance(Duration.ofMinutes(29));
        Map<String, Object> next = refreshed(session, null);
        clock.advance(Duration.ofMinutes(30));
        refresh(next, null).expectStatus().isUnauthorized();
    }

    @Test
    void meTellsTheIdleTimeoutOfTheSessionsEntry() {
        assertThat(get("/api/auth/me", bearerOf(signInAt(APP))).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody()).containsEntry("idleTimeoutSeconds", 0).containsEntry("entry", APP);
        assertThat(get("/api/auth/me", bearerOf(signInAt(SHOP))).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody()).containsEntry("idleTimeoutSeconds", 3600);
        assertThat(get("/api/auth/me", bearerOf(signInAt(null))).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody()).containsEntry("idleTimeoutSeconds", 900);
    }

    @Test
    void theEntryTellsWhereItsSessionsAreKept() {
        assertThat(get("/api/auth/entries/app", null).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody()).containsEntry("sessionPersistence", "device");
        assertThat(get("/api/auth/entries/shop", null).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody()).containsEntry("sessionPersistence", "tab");
        assertThat(get("/api/auth/entries/admin", null).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody()).containsEntry("sessionPersistence", "tab");
    }

    @Test
    void sessionsOnlyAppendRows() {
        SqlStatementLog.STATEMENTS.clear();
        Map<String, Object> session = signInAt(APP);
        clock.advance(Duration.ofDays(1));
        Map<String, Object> next = refreshed(session, APP);
        refresh(session, APP).expectStatus().isUnauthorized();
        refresh(next, APP).expectStatus().isUnauthorized();

        assertThat(SqlStatementLog.STATEMENTS).isNotEmpty()
            .noneSatisfy(sql -> assertThat(sql.trim().toUpperCase(Locale.ROOT)).startsWith("UPDATE"))
            .noneSatisfy(sql -> assertThat(sql.trim().toUpperCase(Locale.ROOT)).startsWith("DELETE"));
    }
}
