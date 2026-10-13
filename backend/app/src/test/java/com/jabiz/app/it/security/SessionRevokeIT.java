package com.jabiz.app.it.security;

import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.runtime.context.Actor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A signed-in user ends sessions of their own from the session list (docs/design/10-security.md section 15.9;
 * ROADMAP phase 16g): one (their own too, which signs them out), or all but their own, at their entry. An ended
 * session's next refresh is refused at once; anything that is not one of the caller's live sessions there is the same
 * 404. Every revocation names its operation; the tables only grow.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {"it.sql-log.enabled=true",
    SessionItSupport.APP_ROLES, SessionItSupport.APP_TTL, SessionItSupport.APP_IDLE, SessionItSupport.SHOP_ROLES,
    SessionItSupport.SHOP_TTL, SessionItSupport.SHOP_IDLE})
class SessionRevokeIT extends SessionItSupport {

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec revoke(
        Map<String, Object> caller, String id) {
        return post(ACCOUNT_SESSIONS + "/" + id + "/revoke", bearerOf(caller), Map.of());
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec revokeOthers(String bearer) {
        return post(ACCOUNT_SESSIONS + "/revoke-others", bearer, Map.of());
    }

    private static List<Map<String, Object>> revocations(String familyId) {
        return query("SELECT r.reason, p.process_name FROM sec_refresh_family_revocation r "
            + "LEFT JOIN op_process p ON p.process_seq_id = r.process_seq_id WHERE r.family_id = ?::uuid", familyId);
    }

    @Test
    void anEndedSessionCannotBeRefreshed() {
        String name = unique("ada");
        player(name);
        Map<String, Object> mine = signInFrom(name, APP, "203.0.113.1", null);
        Map<String, Object> other = signInFrom(name, APP, "203.0.113.2", null);

        revoke(mine, sessionIdOf(other)).expectStatus().isNoContent();

        // Its next refresh is refused (not after its access token expired: right away); the caller's lives on.
        assertThat(problemCode(refresh(other, APP, null), 401)).isEqualTo("INVALID_REFRESH_TOKEN");
        mine = refreshed(mine, APP, null);
        assertThat(sessions(listOf(mine))).extracting(s -> s.get("id")).containsExactly(sessionIdOf(mine));
        assertThat(revocations(sessionIdOf(other))).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("reason", "SELF_REVOKE").containsEntry("process_name", "SEC_ACCOUNT_SESSION_REVOKE"));
        // Ended already: there is nothing left to end.
        assertThat(problemCode(revoke(mine, sessionIdOf(other)), 404)).isEqualTo("SESSION_NOT_FOUND");
    }

    @Test
    void endingTheCallersOwnSessionSignsItOut() {
        String name = unique("bea");
        player(name);
        Map<String, Object> mine = signInFrom(name, APP, "203.0.113.1", null);

        revoke(mine, sessionIdOf(mine)).expectStatus().isNoContent();
        refresh(mine, APP, null).expectStatus().isUnauthorized();
    }

    @Test
    void endingAllOthersKeepsTheCallersAndTheOtherEntries() {
        String name = unique("cy");
        player(name);
        Map<String, Object> mine = signInFrom(name, APP, "203.0.113.1", null);
        Map<String, Object> second = signInFrom(name, APP, "203.0.113.2", null);
        Map<String, Object> third = signInFrom(name, APP, "203.0.113.3", null);
        Map<String, Object> shop = signInFrom(name, SHOP, "203.0.113.4", null);

        Map<String, Object> result = revokeOthers(bearerOf(mine)).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(result).containsEntry("revoked", 2).containsEntry("effectiveWithinSeconds", 900);

        refresh(second, APP, null).expectStatus().isUnauthorized();
        refresh(third, APP, null).expectStatus().isUnauthorized();
        mine = refreshed(mine, APP, null);
        // Another entry's sessions are not the caller's to end from here (decision D36).
        refreshed(shop, SHOP, null);
        assertThat(sessions(listOf(mine))).extracting(s -> s.get("id")).containsExactly(sessionIdOf(mine));
        assertThat(revocations(sessionIdOf(second))).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("reason", "SELF_REVOKE")
            .containsEntry("process_name", "SEC_ACCOUNT_SESSIONS_REVOKE_OTHERS"));

        // Nothing else left: none ended.
        assertThat(revokeOthers(bearerOf(mine)).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody()).containsEntry("revoked", 0);
    }

    @Test
    void anAccessTokenOfNoSessionCannotEndTheOthers() {
        String name = unique("dee");
        String userId = player(name);
        Map<String, Object> other = signInFrom(name, APP, "203.0.113.1", null);
        // As issued before sessions were listed: no session named.
        String bearer = "Bearer " + tokens.issue(new Actor(userId, null, Set.of(PLAYER), Set.of("p"), null, null,
            APP, false)).token();

        assertThat(problemCode(revokeOthers(bearer), 422)).isEqualTo("SESSION_UNKNOWN");
        refreshed(other, APP, null);
        // It still lists the sessions, none of them its own.
        Map<String, Object> list = get(ACCOUNT_SESSIONS, bearer).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
        assertThat(sessions(list)).singleElement().satisfies(s -> assertThat(s).containsEntry("current", false));
    }

    @Test
    void anythingButALiveSessionOfTheCallersAtTheirEntryIsTheSame404() {
        String name = unique("eli");
        player(name);
        String stranger = unique("fox");
        player(stranger);
        Map<String, Object> mine = signInFrom(name, APP, "203.0.113.1", null);
        Map<String, Object> shop = signInFrom(name, SHOP, "203.0.113.2", null);
        Map<String, Object> theirs = signInFrom(stranger, APP, "203.0.113.3", null);

        for (String id : List.of(sessionIdOf(theirs), sessionIdOf(shop), UUID.randomUUID().toString(), "not-an-id")) {
            assertThat(problemCode(revoke(mine, id), 404)).as(id).isEqualTo("SESSION_NOT_FOUND");
        }
        refreshed(theirs, APP, null);
        refreshed(shop, SHOP, null);
    }

    @Test
    void theProcessesRunOnlyThroughTheirEndpointsForTheCallerOnly() {
        String name = unique("gus");
        String userId = player(name);
        Map<String, Object> mine = signInFrom(name, APP, "203.0.113.1", null);
        Map<String, Object> input = Map.of("userId", userId, "sessionId", sessionIdOf(mine));
        assertThat(problemCode(post("/api/processes/SEC_ACCOUNT_SESSION_REVOKE/latest", admin(), input), 403))
            .isEqualTo("PROCESS_INTERNAL");
        assertThat(problemCode(post("/api/processes/SEC_ACCOUNT_SESSIONS_REVOKE_OTHERS/latest", admin(),
            Map.of("userId", userId, "keepSessionId", UUID.randomUUID().toString())), 403))
            .isEqualTo("PROCESS_INTERNAL");
        // Actors that are no users have no sessions to end; nobody without a token.
        revoke(Map.of("accessToken", admin().substring("Bearer ".length())), sessionIdOf(mine))
            .expectStatus().isForbidden();
        post(ACCOUNT_SESSIONS + "/revoke-others", null, Map.of()).expectStatus().isUnauthorized();
        refreshed(mine, APP, null);
    }

    @Test
    void noTokenReachesTheOperationRecord() {
        String name = unique("hal");
        player(name);
        Map<String, Object> mine = signInFrom(name, APP, "203.0.113.1", null);
        Map<String, Object> other = signInFrom(name, APP, "203.0.113.2", null);
        revoke(mine, sessionIdOf(other)).expectStatus().isNoContent();
        revokeOthers(bearerOf(mine)).expectStatus().isOk();

        assertThat(query("SELECT input_summary::text AS summary FROM op_process WHERE process_name IN (?, ?)",
            "SEC_ACCOUNT_SESSION_REVOKE", "SEC_ACCOUNT_SESSIONS_REVOKE_OTHERS"))
            .isNotEmpty().allSatisfy(row -> assertThat(String.valueOf(row.get("summary")))
                .doesNotContain(String.valueOf(mine.get("refreshToken")))
                .doesNotContain(String.valueOf(other.get("refreshToken")))
                .doesNotContain(String.valueOf(mine.get("accessToken"))));
    }

    @Test
    void endingSessionsOnlyAppendsRows() {
        String name = unique("ivy");
        player(name);
        Map<String, Object> mine = signInFrom(name, APP, "203.0.113.1", null);
        Map<String, Object> other = signInFrom(name, APP, "203.0.113.2", null);
        signInFrom(name, APP, "203.0.113.3", null);
        SqlStatementLog.STATEMENTS.clear();
        revoke(mine, sessionIdOf(other)).expectStatus().isNoContent();
        revokeOthers(bearerOf(mine)).expectStatus().isOk();
        refreshed(mine, APP, "203.0.113.9");
        refresh(other, APP, null).expectStatus().isUnauthorized();

        assertThat(SqlStatementLog.STATEMENTS).isNotEmpty()
            .noneSatisfy(sql -> assertThat(sql.trim().toUpperCase(Locale.ROOT)).startsWith("UPDATE"))
            .noneSatisfy(sql -> assertThat(sql.trim().toUpperCase(Locale.ROOT)).startsWith("DELETE"));
    }

    @Test
    void endingSessionsIsLimitedPerUser() {
        String name = unique("jon");
        player(name);
        Map<String, Object> mine = signInFrom(name, APP, "203.0.113.1", null);
        for (int i = 0; i < 10; i++) {
            revokeOthers(bearerOf(mine)).expectStatus().isOk();
        }
        revokeOthers(bearerOf(mine)).expectStatus().isEqualTo(429).expectHeader().exists("Retry-After");
        assertThat(problemCode(revoke(mine, sessionIdOf(mine)), 429)).isEqualTo("RATE_LIMITED");
    }
}
