package com.jabiz.app.it.security;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sessions signed in from clients of the test's choosing (docs/design/10-security.md section 15.9): the mock server
 * has no connections, so a test filter sets the client's address from a header of the test's own. The entries:
 * {@value #APP} keeps sessions 30 days and never ends them for idleness, {@value #SHOP} ends them after an hour idle;
 * the administration keeps the global settings (eight hours, fifteen minutes idle).
 */
abstract class SessionItSupport extends SecurityItSupport {

    /** The entries' settings, for {@code @SpringBootTest}. */
    static final String APP_ROLES = "jabiz.security.entries.app.accepted-roles=IT_SESSION_PLAYER";
    static final String APP_TTL = "jabiz.security.entries.app.session.refresh-token-ttl=30d";
    static final String APP_IDLE = "jabiz.security.entries.app.session.idle-timeout=0";
    static final String SHOP_ROLES = "jabiz.security.entries.shop.accepted-roles=IT_SESSION_PLAYER";
    static final String SHOP_TTL = "jabiz.security.entries.shop.session.refresh-token-ttl=PT2H";
    static final String SHOP_IDLE = "jabiz.security.entries.shop.session.idle-timeout=PT1H";

    static final String PASSWORD = "correct horse battery";
    static final String PLAYER = "IT_SESSION_PLAYER";
    static final String APP = "app";
    static final String SHOP = "shop";
    static final String ADMIN = "admin";
    static final String CONNECTION = "X-It-Connection";
    static final String ACCOUNT_SESSIONS = "/api/auth/account/sessions";

    static final String CHROME_WINDOWS = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like "
        + "Gecko) Chrome/126.0.0.0 Safari/537.36";
    static final String SAFARI_IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 "
        + "(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";

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

    @BeforeEach
    void playerRole() {
        if (query("SELECT 1 FROM sec_role_version WHERE role_code = ?", PLAYER).isEmpty()) {
            createRole(PLAYER, "p");
        }
    }

    /** A user holding the player role; returns the user id. */
    String player(String name) {
        String userId = createUser(name, PASSWORD);
        assign(userId, String.valueOf(query("SELECT role_id::text AS id FROM sec_role_version WHERE role_code = ?",
            PLAYER).getFirst().get("id")), null);
        return userId;
    }

    /** Signs in at the entry (null: the administration) from the client address with the user agent. */
    Map<String, Object> signInFrom(String name, String entry, String address, String userAgent) {
        Map<String, Object> body = new HashMap<>(Map.of("userName", name, "password", PASSWORD));
        if (entry != null) {
            body.put("entry", entry);
        }
        WebTestClient.RequestBodySpec request = client.post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON).header(CONNECTION, address);
        if (userAgent != null) {
            request = request.header(HttpHeaders.USER_AGENT, userAgent);
        }
        return request.bodyValue(body).exchange().expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
    }

    WebTestClient.ResponseSpec refresh(Map<String, Object> session, String entry, String address) {
        Map<String, Object> body = new HashMap<>(Map.of("refreshToken", session.get("refreshToken")));
        if (entry != null) {
            body.put("entry", entry);
        }
        WebTestClient.RequestBodySpec request = client.post().uri("/api/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON);
        if (address != null) {
            request = request.header(CONNECTION, address);
        }
        return request.bodyValue(body).exchange();
    }

    Map<String, Object> refreshed(Map<String, Object> session, String entry, String address) {
        return refresh(session, entry, address).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
    }

    /** The session list of the signed-in session. */
    Map<String, Object> listOf(Map<String, Object> session) {
        return get(ACCOUNT_SESSIONS, bearerOf(session)).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> sessions(Map<String, Object> list) {
        return (List<Map<String, Object>>) list.get("sessions");
    }

    /** The session id the access token of a sign-in names (read whether or not the token has expired by now). */
    static String sessionIdOf(Map<String, Object> session) {
        String payload = ((String) session.get("accessToken")).split("\\.")[1];
        String claims = new String(java.util.Base64.getUrlDecoder().decode(payload),
            java.nio.charset.StandardCharsets.UTF_8);
        java.util.regex.Matcher sid = java.util.regex.Pattern.compile("\"sid\":\"([0-9a-f-]{36})\"").matcher(claims);
        return sid.find() ? sid.group(1) : null;
    }

    String problemCode(WebTestClient.ResponseSpec response, int status) {
        return ruleCode(response.expectStatus().isEqualTo(status).expectBody(MAP).returnResult().getResponseBody());
    }
}
