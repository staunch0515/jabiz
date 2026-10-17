package com.jabiz.app.it.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-address limit of the sign-in endpoints (docs/design/10-security.md section 4.1; decision D47 item 1), at its
 * defaults: a burst of 10 per address and kind of request, refilled at 20 a minute; beyond it 429
 * {@code RATE_LIMITED} with {@code Retry-After}, before anything is read or recorded. Addresses are independent, the
 * client behind a trusted proxy is the one counted, and the lock of an account after failed passwords is unchanged.
 * The mock server has no connections, so a test filter sets the connection's address from a header of the test's own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.security.sign-in.rate-limit.per-address=20", "jabiz.security.sign-in.rate-limit.burst=10",
    "jabiz.security.trusted-proxies=10.0.0.0/8"})
class SignInRateLimitIT extends SecurityItSupport {

    private static final String CONNECTION = "X-It-Connection";
    private static final String FORWARDED = "X-Forwarded-For";
    private static final String PASSWORD = "correct horse battery";
    private static final int BURST = 10;

    @BeforeEach
    @Override
    protected void client() {
        WebFilter connection = (exchange, chain) -> {
            String address = exchange.getRequest().getHeaders().getFirst(CONNECTION);
            return chain.filter(address == null ? exchange : exchange.mutate().request(request -> request
                .remoteAddress(new InetSocketAddress(address, 40000))).build());
        };
        client = WebTestClient.bindToApplicationContext(context).webFilter(connection).build();
    }

    private WebTestClient.ResponseSpec from(String path, String connection, String forwardedFor, String bearer,
        Object body) {
        WebTestClient.RequestBodySpec request = client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
            .header(CONNECTION, connection);
        if (forwardedFor != null) {
            request = request.header(FORWARDED, forwardedFor);
        }
        if (bearer != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, bearer);
        }
        return request.bodyValue(body).exchange();
    }

    private WebTestClient.ResponseSpec login(String connection, String forwardedFor, String name, String password) {
        return from("/api/auth/login", connection, forwardedFor, null, Map.of("userName", name, "password", password));
    }

    private static void refused(WebTestClient.ResponseSpec response) {
        Map<String, Object> problem = response.expectStatus().isEqualTo(429)
            .expectHeader().value(HttpHeaders.RETRY_AFTER, value -> assertThat(Long.parseLong(value)).isBetween(1L, 3L))
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCode(problem)).isEqualTo("RATE_LIMITED");
    }

    @Test
    void oneAddressIsRefusedBeyondItsBurstWhateverItAsksAbout() {
        String name = unique("rl-burst");
        String userId = userWith(name, PASSWORD, "p");
        String address = "203.0.113.40";
        for (int i = 0; i < BURST; i++) {
            login(address, null, unique("nobody"), "wrong").expectStatus().isUnauthorized();
        }
        int records = loginRecords(userId).size();
        // The right password of an existing account is refused alike, and nothing is read or recorded.
        refused(login(address, null, name, PASSWORD));
        refused(login(address, null, unique("nobody"), "wrong"));
        assertThat(loginRecords(userId)).hasSize(records);
    }

    @Test
    void addressesAreIndependent() {
        String name = unique("rl-other");
        userWith(name, PASSWORD, "p");
        for (int i = 0; i < BURST; i++) {
            login("203.0.113.41", null, unique("nobody"), "wrong").expectStatus().isUnauthorized();
        }
        refused(login("203.0.113.41", null, name, PASSWORD));
        login("203.0.113.42", null, name, PASSWORD).expectStatus().isOk();
        // An IPv6 client counts as its /64: another address of the same network shares the budget.
        for (int i = 0; i < BURST; i++) {
            login("2001:db8:40:1::" + Integer.toHexString(i + 1), null, unique("nobody"), "wrong")
                .expectStatus().isUnauthorized();
        }
        refused(login("2001:db8:40:1:ffff::1", null, name, PASSWORD));
        login("2001:db8:40:2::1", null, name, PASSWORD).expectStatus().isOk();
    }

    @Test
    void theClientBehindATrustedProxyIsCounted() {
        String name = unique("rl-proxy");
        userWith(name, PASSWORD, "p");
        for (int i = 0; i < BURST; i++) {
            login("10.0.0.2", "198.51.100.40", unique("nobody"), "wrong").expectStatus().isUnauthorized();
        }
        // The same client through another proxy: the same budget.
        refused(login("10.0.0.3", "198.51.100.40", name, PASSWORD));
        // Another client behind the same proxy has its own; the proxy's address is not what counts.
        login("10.0.0.2", "198.51.100.41", name, PASSWORD).expectStatus().isOk();
        // A client that is not a proxy cannot escape its budget by claiming other addresses.
        for (int i = 0; i < BURST; i++) {
            login("203.0.113.43", "198.51.100." + (50 + i), unique("nobody"), "wrong").expectStatus().isUnauthorized();
        }
        refused(login("203.0.113.43", "198.51.100.99", name, PASSWORD));
    }

    @Test
    void theLockOfAnAccountIsUnchanged() {
        String name = unique("rl-lock");
        String userId = userWith(name, PASSWORD, "p");
        // Five wrong passwords from five addresses (none near its limit) lock the account as before.
        for (int i = 0; i < 5; i++) {
            login("203.0.113." + (100 + i), null, name, "wrong").expectStatus().isUnauthorized();
        }
        login("203.0.113.110", null, name, PASSWORD).expectStatus().isUnauthorized();
        assertThat(loginRecords(userId)).extracting(r -> r.get("outcome")).containsExactly("BAD_CREDENTIALS",
            "BAD_CREDENTIALS", "BAD_CREDENTIALS", "BAD_CREDENTIALS", "BAD_CREDENTIALS", "LOCKED");
    }

    @Test
    void refreshTheSecondFactorAndTheProviderCallbackAreCountedApart() {
        String address = "203.0.113.44";
        String name = unique("rl-kinds");
        userWith(name, PASSWORD, "p");
        String bearer = bearer("p");
        Map<String, Map<String, Object>> endpoints = new LinkedHashMap<>();
        endpoints.put("/api/auth/refresh", Map.of("refreshToken", "not-a-token"));
        endpoints.put("/api/auth/challenge/verify", Map.of("challenge", "not-a-challenge", "code", "000000"));
        endpoints.put("/api/auth/oidc/callback", Map.of("state", "not-a-state", "code", "x", "binder", "y"));
        endpoints.forEach((path, body) -> {
            for (int i = 0; i < BURST; i++) {
                int status = from(path, address, null, bearer, body).returnResult(Void.class).getStatus().value();
                assertThat(status).as(path).isNotEqualTo(429);
            }
            refused(from(path, address, null, bearer, body));
        });
        // A step-up is a second factor too: the same bucket as the second step of signing in.
        refused(from("/api/auth/step-up", address, null, bearer, Map.of("code", "000000")));
        from("/api/auth/step-up", "203.0.113.45", null, bearer, Map.of("code", "000000")).expectStatus()
            .value(status -> assertThat(status).isNotEqualTo(429));
        // Each kind has a bucket of its own: signing in from the address still works.
        login(address, null, name, PASSWORD).expectStatus().isOk();
    }
}
