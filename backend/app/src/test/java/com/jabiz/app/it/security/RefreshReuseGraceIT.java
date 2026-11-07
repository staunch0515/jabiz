package com.jabiz.app.it.security;

import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.runtime.security.RefreshTokenStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reuse grace of refresh tokens (decision D73; docs/design/10-security.md section 3): a consumed token presented
 * again within the grace, at its own entry, while its successor is unused, gets one sibling of the successor, and the
 * session goes on (a page reloaded during its refresh, a duplicated tab). Otherwise reuse ends the session, as before.
 * The tables only grow.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {"it.sql-log.enabled=true",
    SessionItSupport.APP_ROLES, SessionItSupport.APP_TTL, SessionItSupport.APP_IDLE, SessionItSupport.SHOP_ROLES,
    SessionItSupport.SHOP_TTL, SessionItSupport.SHOP_IDLE})
class RefreshReuseGraceIT extends SessionItSupport {

    @Autowired
    RefreshTokenStore refreshTokens;

    @Test
    void theGraceIsThirtySecondsByDefault() {
        assertThat(refreshTokens.reuseGrace()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void aTokenBackWithinTheGraceBeforeItsSuccessorIsUsedGetsASiblingOnce() {
        String name = unique("ada");
        player(name);
        Map<String, Object> signedIn = signInFrom(name, APP, "203.0.113.1", null);
        Map<String, Object> successor = refreshed(signedIn, APP, null);

        // The page reloaded before it stored the answer: it presents the old token again.
        clock.advance(Duration.ofSeconds(20));
        Map<String, Object> sibling = refreshed(signedIn, APP, null);
        assertThat(sibling.get("refreshToken")).isNotEqualTo(successor.get("refreshToken"));
        assertThat(sessionIdOf(sibling)).isEqualTo(sessionIdOf(signedIn));
        assertThat(revocations(name)).isEmpty();
        assertThat(query("SELECT family_id::text AS family FROM sec_refresh_token_regrant WHERE family_id = ?::uuid",
            sessionIdOf(signedIn))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("family", sessionIdOf(signedIn)));
        // Still one session in the list.
        assertThat(sessions(listOf(sibling))).extracting(s -> s.get("id")).containsExactly(sessionIdOf(sibling));

        // A second time: reuse, and the whole session ends (the successor and the sibling with it).
        assertThat(problemCode(refresh(signedIn, APP, null), 401)).isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(revocations(name)).containsExactly("REUSE");
        refresh(successor, APP, null).expectStatus().isUnauthorized();
        refresh(sibling, APP, null).expectStatus().isUnauthorized();
    }

    @Test
    void aDuplicatedTabRenewsOnItsOwnAndSoDoesTheOriginal() {
        String name = unique("bea");
        player(name);
        Map<String, Object> signedIn = signInFrom(name, APP, "203.0.113.1", null);
        // Both tabs hold the same token; the first refreshes, the second a moment later.
        Map<String, Object> first = refreshed(signedIn, APP, null);
        clock.advance(Duration.ofSeconds(1));
        Map<String, Object> second = refreshed(signedIn, APP, null);

        // From now on each renews its own token, again and again.
        for (int round = 0; round < 3; round++) {
            clock.advance(Duration.ofMinutes(10));
            first = refreshed(first, APP, null);
            second = refreshed(second, APP, null);
        }
        assertThat(revocations(name)).isEmpty();
    }

    @Test
    void aTokenBackAfterItsSuccessorWasUsedEndsTheSession() {
        String name = unique("cy");
        player(name);
        Map<String, Object> signedIn = signInFrom(name, APP, "203.0.113.1", null);
        Map<String, Object> successor = refreshed(signedIn, APP, null);
        Map<String, Object> next = refreshed(successor, APP, null);

        assertThat(problemCode(refresh(signedIn, APP, null), 401)).isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(revocations(name)).containsExactly("REUSE");
        refresh(next, APP, null).expectStatus().isUnauthorized();
    }

    @Test
    void aTokenBackAfterTheGraceEndsTheSession() {
        String name = unique("dot");
        player(name);
        Map<String, Object> signedIn = signInFrom(name, APP, "203.0.113.1", null);
        Map<String, Object> successor = refreshed(signedIn, APP, null);

        clock.advance(Duration.ofSeconds(31));
        assertThat(problemCode(refresh(signedIn, APP, null), 401)).isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(revocations(name)).containsExactly("REUSE");
        refresh(successor, APP, null).expectStatus().isUnauthorized();
    }

    @Test
    void aTokenBackAtAnotherEntryEndsTheSession() {
        String name = unique("eve");
        player(name);
        Map<String, Object> signedIn = signInFrom(name, APP, "203.0.113.1", null);
        Map<String, Object> successor = refreshed(signedIn, APP, null);

        assertThat(problemCode(refresh(signedIn, SHOP, null), 401)).isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(revocations(name)).containsExactly("REUSE");
        refresh(successor, APP, null).expectStatus().isUnauthorized();
    }

    @Test
    void twoRefreshesAtTheSameMomentAreAUseAndARegrant() {
        String name = unique("fay");
        player(name);
        String token = (String) signInFrom(name, APP, "203.0.113.1", null).get("refreshToken");

        List<RefreshTokenStore.Rotated<Integer>> both = Mono.zip(
                refreshTokens.rotate(token, APP, grant -> Mono.just(1)).subscribeOn(Schedulers.parallel()),
                refreshTokens.rotate(token, APP, grant -> Mono.just(2)).subscribeOn(Schedulers.parallel()))
            .map(pair -> List.of(pair.getT1(), pair.getT2()))
            .block();

        assertThat(both).extracting(RefreshTokenStore.Rotated::regranted).containsExactlyInAnyOrder(false, true);
        assertThat(revocations(name)).isEmpty();
    }

    @Test
    void withoutAGraceEveryReuseEndsTheSession() {
        String name = unique("gil");
        player(name);
        RefreshTokenStore strict = new RefreshTokenStore(() -> storages().getEngine("default"),
            entry -> new RefreshTokenStore.Policy(Duration.ofHours(2), null), clock);
        String token = (String) signInFrom(name, APP, "203.0.113.1", null).get("refreshToken");
        strict.rotate(token, APP, grant -> Mono.just(1)).block();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> strict.rotate(token, APP, grant -> Mono.just(1))
                .block())
            .isInstanceOfSatisfying(RefreshTokenStore.InvalidRefreshTokenException.class,
                e -> assertThat(e.reused()).isTrue());
        assertThat(revocations(name)).containsExactly("REUSE");
    }

    @Test
    void regrantingOnlyAppendsRows() {
        String name = unique("hal");
        player(name);
        Map<String, Object> signedIn = signInFrom(name, APP, "203.0.113.1", null);
        SqlStatementLog.STATEMENTS.clear();
        refreshed(signedIn, APP, null);
        refreshed(signedIn, APP, null);
        refresh(signedIn, APP, null).expectStatus().isUnauthorized();

        assertThat(SqlStatementLog.STATEMENTS).isNotEmpty()
            .noneSatisfy(sql -> assertThat(SqlStatementLog.mutatedTables(sql)).isNotEmpty());
        for (String sql : List.of("UPDATE sec_refresh_token_regrant SET regranted_at = regranted_at",
            "DELETE FROM sec_refresh_token_regrant")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> execute(sql)).hasMessageContaining("append-only");
        }
    }

    @Autowired
    com.jabiz.runtime.storage.StorageAdapterRegistry storageRegistry;

    private com.jabiz.runtime.storage.StorageAdapterRegistry storages() {
        return storageRegistry;
    }

    /** The reasons the user's sessions were revoked for. */
    private static List<Object> revocations(String userName) {
        return query("SELECT r.reason FROM sec_refresh_family_revocation r JOIN sec_refresh_token t "
            + "ON t.family_id = r.family_id JOIN sec_user_version u ON u.user_id = t.user_id WHERE u.user_name = ? "
            + "GROUP BY r.family_id, r.reason", userName).stream().map(row -> row.get("reason")).toList();
    }
}
