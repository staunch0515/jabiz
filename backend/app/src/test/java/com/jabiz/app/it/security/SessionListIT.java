package com.jabiz.app.it.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A signed-in user's list of their live sessions (docs/design/10-security.md section 15.9; ROADMAP phase 16g), on the
 * tests' fixed clock: one per sign-in, with where it was last used, the caller's own marked; only those of the
 * caller's entry, never anybody else's; ended ones (signed out, expired, idle) are gone.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {SessionItSupport.APP_ROLES,
    SessionItSupport.APP_TTL, SessionItSupport.APP_IDLE, SessionItSupport.SHOP_ROLES, SessionItSupport.SHOP_TTL,
    SessionItSupport.SHOP_IDLE})
class SessionListIT extends SessionItSupport {

    @Test
    @SuppressWarnings("unchecked")
    void everySignInIsASessionOfItsOwn() {
        String name = unique("ann");
        player(name);
        Map<String, Object> laptop = signInFrom(name, APP, "203.0.113.5", CHROME_WINDOWS);
        clock.advance(Duration.ofMinutes(1));
        Map<String, Object> phone = signInFrom(name, APP, "198.51.100.9", SAFARI_IPHONE);

        Map<String, Object> list = listOf(laptop);
        assertThat(list).containsEntry("truncated", false).containsEntry("effectiveWithinSeconds", 900);
        List<Map<String, Object>> sessions = sessions(list);
        // The most recently used first; the caller's own marked.
        assertThat(sessions).extracting(s -> s.get("id")).containsExactly(sessionIdOf(phone), sessionIdOf(laptop));
        assertThat(sessions.get(0)).containsEntry("current", false).containsEntry("entry", APP)
            .containsEntry("clientIp", "198.51.100.9").containsEntry("userAgent", SAFARI_IPHONE)
            .containsEntry("device", Map.of("browser", "SAFARI", "os", "IOS", "deviceType", "MOBILE"))
            .containsEntry("mfa", false).containsEntry("oidc", false)
            .containsEntry("createdAt", START.plus(Duration.ofMinutes(1)).toString())
            .containsEntry("lastUsedAt", START.plus(Duration.ofMinutes(1)).toString());
        assertThat(sessions.get(1)).containsEntry("current", true).containsEntry("clientIp", "203.0.113.5")
            .containsEntry("device", Map.of("browser", "CHROME", "os", "WINDOWS", "deviceType", "DESKTOP"))
            .containsEntry("createdAt", START.toString())
            .containsEntry("expiresAt", START.plus(Duration.ofDays(30)).toString());
        // Seen from the phone, the phone is the caller's.
        assertThat(sessions(listOf(phone))).filteredOn(s -> Boolean.TRUE.equals(s.get("current")))
            .extracting(s -> s.get("id")).containsExactly(sessionIdOf(phone));
        // The session ids are those of the refresh token families, as the access token and /me name them.
        Map<String, Object> me = get("/api/auth/me", bearerOf(laptop)).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(me).containsEntry("sessionId", sessionIdOf(laptop));
        assertThat(query("SELECT DISTINCT family_id::text AS id FROM sec_refresh_token WHERE user_id = "
            + "(SELECT user_id FROM sec_user_version WHERE user_name = ? LIMIT 1)", name))
            .extracting(row -> row.get("id")).containsExactlyInAnyOrder(sessionIdOf(laptop), sessionIdOf(phone));
    }

    @Test
    void aRefreshIsTheLatestUseAndKeepsTheSession() {
        String name = unique("ben");
        player(name);
        Map<String, Object> session = signInFrom(name, APP, "203.0.113.5", CHROME_WINDOWS);
        String id = sessionIdOf(session);

        clock.advance(Duration.ofMinutes(10));
        Map<String, Object> next = refreshed(session, APP, "192.0.2.44");
        // The new access token names the same session.
        assertThat(sessionIdOf(next)).isEqualTo(id);

        assertThat(sessions(listOf(next))).singleElement().satisfies(s -> assertThat(s)
            .containsEntry("id", id).containsEntry("current", true)
            .containsEntry("createdAt", START.toString())
            .containsEntry("lastUsedAt", START.plus(Duration.ofMinutes(10)).toString())
            .containsEntry("expiresAt", START.plus(Duration.ofMinutes(10)).plus(Duration.ofDays(30)).toString())
            .containsEntry("clientIp", "192.0.2.44")
            // A refresh without a user agent: none known for the latest use.
            .containsEntry("userAgent", null)
            .containsEntry("device", Map.of("browser", "OTHER", "os", "OTHER", "deviceType", "OTHER")));
    }

    @Test
    void endedSessionsAreNotListed() {
        String name = unique("cat");
        player(name);

        // Idle: an hour after its access token expired, the shop's session has ended.
        Map<String, Object> idle = signInFrom(name, SHOP, "203.0.113.1", null);
        Map<String, Object> busy = signInFrom(name, SHOP, "203.0.113.2", null);
        clock.advance(Duration.ofMinutes(70));
        busy = refreshed(busy, SHOP, null);
        assertThat(sessions(listOf(busy))).extracting(s -> s.get("id"))
            .containsExactlyInAnyOrder(sessionIdOf(idle), sessionIdOf(busy));
        clock.advance(Duration.ofMinutes(10));
        assertThat(sessions(listOf(busy))).extracting(s -> s.get("id")).containsExactly(sessionIdOf(busy));

        // Signed out: gone at once.
        Map<String, Object> mine = signInFrom(name, APP, "203.0.113.3", null);
        Map<String, Object> gone = signInFrom(name, APP, "203.0.113.4", null);
        Map<String, Object> left = signInFrom(name, APP, "203.0.113.5", null);
        post("/api/auth/logout", null, Map.of("refreshToken", gone.get("refreshToken"))).expectStatus().isNoContent();
        assertThat(sessions(listOf(mine))).extracting(s -> s.get("id"))
            .containsExactlyInAnyOrder(sessionIdOf(mine), sessionIdOf(left));

        // An entry that never ends sessions for idleness: only their lifetime counts, from their latest use.
        clock.advance(Duration.ofDays(29));
        mine = refreshed(mine, APP, null);
        assertThat(sessions(listOf(mine))).extracting(s -> s.get("id"))
            .containsExactlyInAnyOrder(sessionIdOf(mine), sessionIdOf(left));
        clock.advance(Duration.ofDays(2));
        mine = refreshed(mine, APP, null);
        assertThat(sessions(listOf(mine))).extracting(s -> s.get("id")).containsExactly(sessionIdOf(mine));
    }

    @Test
    void onlyTheCallersOwnSessionsAtTheCallersEntry() {
        String name = unique("dan");
        player(name);
        String other = unique("eve");
        player(other);
        Map<String, Object> app = signInFrom(name, APP, "203.0.113.1", null);
        Map<String, Object> shop = signInFrom(name, SHOP, "203.0.113.2", null);
        Map<String, Object> admin = signInFrom(name, null, "203.0.113.3", null);
        signInFrom(other, APP, "203.0.113.4", null);

        assertThat(sessions(listOf(app))).extracting(s -> s.get("id")).containsExactly(sessionIdOf(app));
        assertThat(sessions(listOf(shop))).extracting(s -> s.get("id")).containsExactly(sessionIdOf(shop));
        assertThat(sessions(listOf(admin))).singleElement().satisfies(s -> assertThat(s)
            .containsEntry("id", sessionIdOf(admin)).containsEntry("entry", ADMIN)
            .containsEntry("expiresAt", START.plus(Duration.ofHours(8)).toString()));

        // Actors that are no users of the platform have no sessions here; nobody without a token at all.
        get(ACCOUNT_SESSIONS, admin()).expectStatus().isForbidden();
        get(ACCOUNT_SESSIONS, null).expectStatus().isUnauthorized();
    }

    @Test
    void atMostFiftyAreListed() {
        String name = unique("fay");
        player(name);
        Map<String, Object> last = null;
        for (int i = 0; i < 51; i++) {
            clock.advance(Duration.ofSeconds(1));
            last = signInFrom(name, APP, "203.0.113.9", null);
        }
        Map<String, Object> list = listOf(last);
        assertThat(list).containsEntry("truncated", true);
        assertThat(sessions(list)).hasSize(50);
        // The most recently used are those listed.
        assertThat(sessions(list).getFirst()).containsEntry("current", true);
        assertThat(sessions(list).getLast()).containsEntry("createdAt", START.plusSeconds(2).toString());
        assertThat(Instant.parse(String.valueOf(sessions(list).getFirst().get("lastUsedAt"))))
            .isEqualTo(START.plusSeconds(51));
    }
}
