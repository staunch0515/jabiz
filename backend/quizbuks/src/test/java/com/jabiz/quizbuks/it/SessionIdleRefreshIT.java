package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.security.SecurityEntities;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 24 hours without activity (the requester, 2026-11-21) as a signed-in person meets them: a session left alone for
 * 24 hours is still refreshed (the access token's 15 minutes plus the idle timeout), one left for 24 hours and 16
 * minutes is refused. In the sponsor console and in the administration alike. The administration's own roles want a
 * second factor, so here it also accepts a role without one ({@code IT_IDLE}); its sessions are configured as always.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "jabiz.security.entries.admin.accepted-roles=ADMIN,QB_ADMIN_SUPER,QB_ADMIN_CONTENT,QB_ADMIN_FINANCE,IT_IDLE")
class SessionIdleRefreshIT extends AccountItSupport {

    private WebTestClient.ResponseSpec refresh(Object refreshToken, String entry) {
        return client.post().uri("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("refreshToken", refreshToken, "entry", entry)).exchange();
    }

    private void idleRefreshes(String role, String entry) {
        String name = unique("idle");
        userWithRole(name, role);
        Map<String, Object> first = session(name, entry);

        clock.set(clock.instant().plus(Duration.ofHours(24)));
        Map<String, Object> next = refresh(first.get("refreshToken"), entry).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(next).containsKey("accessToken");

        clock.set(clock.instant().plus(Duration.ofHours(24)).plus(Duration.ofMinutes(16)));
        Map<String, Object> refused = refresh(next.get("refreshToken"), entry).expectStatus().isUnauthorized()
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat(refusalCode(refused)).isEqualTo("INVALID_REFRESH_TOKEN");
    }

    @Test
    void aSponsorConsoleSessionIdleForADayIsStillRefreshedAndNotAfter() {
        idleRefreshes(QbRoles.SPONSOR, "sponsor");
    }

    @Test
    void anAdministrationSessionIdleForADayIsStillRefreshedAndNotAfter() {
        commit(SecurityEntities.ROLE_DATASET, "INSERT", null, null, Map.of("roleCode", "IT_IDLE", "labels",
            Map.of("en", "Idle test"), "enabled", true), null);
        idleRefreshes("IT_IDLE", "admin");
    }
}
