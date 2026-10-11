package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.security.SecurityEntities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sign-in records (M-02, U-07; docs/quizbuks/plans/Q2-accounts.md, D-Q2-1 and D-Q2-5): the three entries sign in
 * separately (a user of another entry's role only is refused and recorded); every attempt is recorded by the platform
 * with its entry, the client's address (from {@code X-Forwarded-For} only through a trusted proxy) and its user agent.
 * A person reads their own records ({@code qb.me.sign-ins}), an administrator everybody's (the platform's dataset).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "jabiz.security.trusted-proxies=198.51.100.0/24")
class SignInAuditIT extends AccountItSupport {

    /** The proxy every request of this class comes through. */
    private static final String PROXY = "198.51.100.7";

    /** A client whose connections come from the trusted proxy (the mock server has no connections of its own). */
    private WebTestClient proxied;

    @BeforeEach
    void proxied() {
        WebFilter connection = (exchange, chain) -> chain.filter(exchange.mutate().request(request -> request
            .remoteAddress(new InetSocketAddress(PROXY, 40000))).build());
        proxied = WebTestClient.bindToApplicationContext(context).webFilter(connection).configureClient()
            .responseTimeout(Duration.ofSeconds(60)).defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "en").build();
    }

    @AfterEach
    void onlyInserted() {
        assertAccountsOnlyInserted();
    }

    private WebTestClient.ResponseSpec loginFrom(String userName, String entry, String forwardedFor,
        String userAgent) {
        return proxied.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .header("X-Forwarded-For", forwardedFor).header(HttpHeaders.USER_AGENT, userAgent)
            .bodyValue(Map.of("userName", userName, "password", PASSWORD, "entry", entry)).exchange();
    }

    /** A minute later: the records' order is their time's. */
    private void tick() {
        clock.set(clock.instant().plusSeconds(60));
    }

    @Test
    void entriesAreSeparateAndEveryAttemptIsRecordedWithItsSource() {
        String tomName = unique("tom");
        String tom = userWithRole(tomName, QbRoles.TAKER);
        String bossName = unique("boss");
        String boss = userWithRole(bossName, QbRoles.ADMIN_SUPER);

        Map<String, Object> session = loginFrom(tomName, "app", "203.0.113.9", "QuizBuks/1.0 (iPhone)")
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        // A taker's token holds the app's permissions only.
        Map<String, Object> me = client.get().uri("/api/auth/me").header(HttpHeaders.AUTHORIZATION,
            bearerOf(session)).exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(me).containsEntry("entry", "app");
        assertThat(me.toString()).contains("qb.play").doesNotContain("qb.sponsor.me", "qb.admin");
        // Neither the sponsor console nor the administration takes a taker: refused like a wrong password, recorded.
        tick();
        loginFrom(tomName, "sponsor", "203.0.113.9", "QuizBuks/1.0 (iPhone)").expectStatus().isUnauthorized();
        tick();
        loginFrom(tomName, "admin", "203.0.113.9", "Mozilla/5.0").expectStatus().isUnauthorized();
        tick();
        // Wrong password, recorded too.
        proxied.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("userName", tomName, "password", "wrong horse battery", "entry", "app")).exchange()
            .expectStatus().isUnauthorized();

        List<Map<String, Object>> records = query("SELECT outcome, entry, client_ip, user_agent "
            + "FROM sec_login_record_version WHERE user_id = ?::uuid ORDER BY attempt_no", tom);
        assertThat(records).extracting(row -> row.get("outcome"), row -> row.get("entry")).containsExactly(
            org.assertj.core.groups.Tuple.tuple("SUCCESS", "app"),
            org.assertj.core.groups.Tuple.tuple("NO_ROLE", "sponsor"),
            org.assertj.core.groups.Tuple.tuple("NO_ROLE", "admin"),
            org.assertj.core.groups.Tuple.tuple("BAD_CREDENTIALS", "app"));
        // Through the trusted proxy, the client's own address; and its device.
        assertThat(records.getFirst()).containsEntry("client_ip", "203.0.113.9")
            .containsEntry("user_agent", "QuizBuks/1.0 (iPhone)");

        // The administrator signs in to the administration only.
        loginFrom(bossName, "app", "203.0.113.10", "Mozilla/5.0").expectStatus().isUnauthorized();
        loginFrom(bossName, "admin", "203.0.113.10", "Mozilla/5.0").expectStatus().isOk();

        // Each reads their own records only.
        List<Map<String, Object>> own = items("qb.me.sign-ins", bearerOf(session), Map.of());
        assertThat(own).hasSize(4).allSatisfy(row -> assertThat(row).containsKeys("attemptTime", "outcome",
            "entry", "clientIp", "userAgent"));
        assertThat(own.getFirst()).containsEntry("outcome", "BAD_CREDENTIALS");
        // The wrong password came through the proxy without a forwarded address: the proxy's own is recorded.
        assertThat(own).extracting(row -> row.get("clientIp")).containsExactly(PROXY, "203.0.113.9",
            "203.0.113.9", "203.0.113.9");
        String annName = unique("ann");
        String ann = userWithRole(annName, QbRoles.TAKER);
        Map<String, Object> annSession = loginFrom(annName, "app", "203.0.113.11", "Android").expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat(items("qb.me.sign-ins", bearerOf(annSession), Map.of())).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("clientIp", "203.0.113.11"));
        assertThat(rows(QbAccounts.MY_SIGN_INS_DATASET, bearerOf(annSession))).singleElement()
            .satisfies(row -> assertThat(String.valueOf(row.get("userId"))).isEqualTo(ann));
        // Administrators hold no qb.account.read: they read everybody's through the platform's dataset (below).
        client.post().uri("/api/queries/qb.me.sign-ins").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, tokenOf(boss, QbRoles.ADMIN_SUPER)).bodyValue(Map.of()).exchange()
            .expectStatus().isForbidden();
        assertThat(rows(QbAccounts.MY_SIGN_INS_DATASET, bearerOf(session))).hasSize(4)
            .allSatisfy(row -> assertThat(String.valueOf(row.get("userId"))).isEqualTo(tom));
        assertThat(items("qb.me.sign-ins", bearerOf(session), Map.of("filters", List.of(Map.of("field", "entry",
            "op", "eq", "value", "admin"))))).hasSize(1);
        // A taker does not read the platform's records of everybody.
        queryDataset(SecurityEntities.LOGIN_RECORD_DATASET, bearerOf(session)).expectStatus().isForbidden();
        // The super administrator does.
        assertThat(rows(SecurityEntities.LOGIN_RECORD_DATASET, tokenOf(boss, QbRoles.ADMIN_SUPER)))
            .extracting(row -> String.valueOf(row.get("userId"))).contains(tom, boss);
    }

    @Test
    void anUntrustedClientCannotForgeItsAddress() {
        String name = unique("ann");
        String ann = userWithRole(name, QbRoles.TAKER);
        // From outside the trusted range, the header is not believed.
        WebTestClient.bindToApplicationContext(context).webFilter((WebFilter) (exchange, chain) -> chain.filter(
                exchange.mutate().request(request -> request.remoteAddress(new InetSocketAddress("192.0.2.44", 40000)))
                    .build())).build()
            .post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .header("X-Forwarded-For", "203.0.113.200")
            .bodyValue(Map.of("userName", name, "password", PASSWORD, "entry", "app")).exchange()
            .expectStatus().isOk();
        assertThat(query("SELECT client_ip FROM sec_login_record_version WHERE user_id = ?::uuid", ann))
            .singleElement().satisfies(row -> assertThat(row.get("client_ip")).isNotEqualTo("203.0.113.200"));
    }
}
