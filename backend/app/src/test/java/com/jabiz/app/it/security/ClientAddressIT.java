package com.jabiz.app.it.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where sign-ins come from (docs/design/10-security.md section 15; decision D36 item 7): forwarded headers count only
 * when the connection comes from a trusted proxy, and the public API's rate limit counts the same address
 * (docs/design/15-public-access.md section 6). The mock server has no connections, so a test filter sets the
 * connection's address from a header of the test's own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.security.trusted-proxies=10.0.0.0/8,192.168.0.1",
    "jabiz.public.enabled=true", "jabiz.public.rate-limit.per-minute=2"})
class ClientAddressIT extends SecurityItSupport {

    private static final String CONNECTION = "X-It-Connection";
    private static final String FORWARDED = "X-Forwarded-For";
    private static final String PASSWORD = "correct horse battery";

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

    private void login(String name, String connection, String forwardedFor, String userAgent) {
        WebTestClient.RequestBodySpec request = client.post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON).header(CONNECTION, connection);
        if (forwardedFor != null) {
            request = request.header(FORWARDED, forwardedFor);
        }
        if (userAgent != null) {
            request = request.header(HttpHeaders.USER_AGENT, userAgent);
        }
        request.bodyValue(Map.of("userName", name, "password", PASSWORD)).exchange().expectStatus().isOk();
    }

    private static Map<String, Object> lastRecord(String userId) {
        List<Map<String, Object>> records = query("SELECT client_ip, user_agent, entry FROM sec_login_record_version "
            + "WHERE user_id = ?::uuid ORDER BY attempt_no", userId);
        return records.getLast();
    }

    @Test
    void forwardedHeadersCountOnlyFromTrustedProxies() {
        String name = unique("pat");
        String userId = userWith(name, PASSWORD, "p");

        // Straight from a client: whatever it claims to have come through.
        login(name, "203.0.113.5", "198.51.100.1", null);
        assertThat(lastRecord(userId)).containsEntry("client_ip", "203.0.113.5").containsEntry("entry", "admin");

        // Through two proxies: the first address from the right that is not a proxy; what lies left of it is the
        // client's own claim and does not count.
        login(name, "192.168.0.1", "1.1.1.1, 198.51.100.7, 10.0.0.3", null);
        assertThat(lastRecord(userId)).containsEntry("client_ip", "198.51.100.7");

        // A malformed header from a proxy: the proxy's address.
        login(name, "10.0.0.2", "198.51.100.7, not-an-address", null);
        assertThat(lastRecord(userId)).containsEntry("client_ip", "10.0.0.2");
    }

    @Test
    void userAgentsAreCleanedAndCut() {
        String name = unique("quinn");
        String userId = userWith(name, PASSWORD, "p");

        login(name, "203.0.113.6", null, "Agent‮/" + "x".repeat(400));
        String agent = (String) lastRecord(userId).get("user_agent");
        assertThat(agent).hasSize(256).startsWith("Agent/x").doesNotContain("‮");
        // Control characters are refused by the firewall: the sign-in goes on without a user agent.
        login(name, "203.0.113.6", "198.51.100.1\u0007", "Agent\u0007");
        assertThat(lastRecord(userId)).containsEntry("user_agent", null).containsEntry("client_ip", "203.0.113.6");
    }

    @Test
    void thePublicRateLimitCountsTheSameAddress() {
        String catalog = "/api/public/queries/commerce.public.catalog";
        for (int i = 0; i < 2; i++) {
            client.get().uri(catalog).header(CONNECTION, "10.0.0.2").header(FORWARDED, "198.51.100.9").exchange()
                .expectStatus().isOk();
        }
        // The same client through another proxy: still the same budget.
        client.get().uri(catalog).header(CONNECTION, "192.168.0.1").header(FORWARDED, "198.51.100.9").exchange()
            .expectStatus().isEqualTo(429);
        // Another client behind the same proxy has its own.
        client.get().uri(catalog).header(CONNECTION, "10.0.0.2").header(FORWARDED, "198.51.100.10").exchange()
            .expectStatus().isOk();
        // A client cannot escape its budget by claiming another address.
        for (int i = 0; i < 2; i++) {
            client.get().uri(catalog).header(CONNECTION, "203.0.113.7").header(FORWARDED, "198.51.100." + i)
                .exchange().expectStatus().isOk();
        }
        client.get().uri(catalog).header(CONNECTION, "203.0.113.7").header(FORWARDED, "198.51.100.99").exchange()
            .expectStatus().isEqualTo(429);
    }
}
