package com.jabiz.app.it.security;

import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Users, roles and sign-ins set up through the HTTP API as an administrator would (docs/design/10-security.md).
 * Every test works with names of its own: the security tables are temporal and cannot be cleaned up.
 */
abstract class SecurityItSupport extends PostgresIntegrationTest {

    static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {};

    @Autowired
    ApplicationContext context;

    @Autowired
    JwtService tokens;

    WebTestClient client;

    @BeforeEach
    void client() {
        client = WebTestClient.bindToApplicationContext(context).build();
    }

    /** A name no other test uses. */
    static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    String admin() {
        return TestTokens.bearer(tokens, "it-admin", "*");
    }

    String bearer(String... permissions) {
        return TestTokens.bearer(tokens, "it-caller", permissions);
    }

    WebTestClient.ResponseSpec post(String path, String authorization, Object body) {
        WebTestClient.RequestBodySpec request = client.post().uri(path).contentType(MediaType.APPLICATION_JSON);
        if (authorization != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return request.bodyValue(body).exchange();
    }

    WebTestClient.ResponseSpec get(String path, String authorization) {
        WebTestClient.RequestHeadersSpec<?> request = client.get().uri(path);
        if (authorization != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return request.exchange();
    }

    /** Creates a user through SEC_USER_CREATE; returns the user id. */
    @SuppressWarnings("unchecked")
    String createUser(String userName, String password) {
        Map<String, Object> result = post("/api/processes/SEC_USER_CREATE/latest", admin(),
            Map.of("userName", userName, "displayName", userName, "password", password))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) result.get("output")).get("userId");
    }

    /** Inserts one entity through its platform dataset; returns the stored snapshot. */
    Map<String, Object> insert(String dataset, Map<String, Object> attributes, Instant effectiveTime) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("action", "INSERT");
        change.put("attributes", attributes);
        if (effectiveTime != null) {
            change.put("effectiveTime", effectiveTime.toString());
        }
        List<Map<String, Object>> saved = post("/api/datasets/" + dataset + "/commit", admin(),
            Map.of("changes", List.of(change)))
            .expectStatus().isOk().expectBody(LIST).returnResult().getResponseBody();
        return saved.getFirst();
    }

    /** A role with the permissions; returns its id. */
    String createRole(String roleCode, String... permissions) {
        String roleId = String.valueOf(insert(SecurityEntities.ROLE_DATASET,
            Map.of("roleCode", roleCode, "labels", Map.of("en", roleCode), "enabled", true), null).get("id"));
        for (String permission : permissions) {
            insert(SecurityEntities.ROLE_PERMISSION_DATASET, Map.of("roleId", roleId, "permission", permission), null);
        }
        return roleId;
    }

    void assign(String userId, String roleId, Instant effectiveTime) {
        insert(SecurityEntities.USER_ROLE_DATASET, Map.of("userId", userId, "roleId", roleId), effectiveTime);
    }

    /** A user holding one role with the given permissions; returns the user id. */
    String userWith(String userName, String password, String... permissions) {
        String userId = createUser(userName, password);
        assign(userId, createRole(unique("ROLE"), permissions), null);
        return userId;
    }

    WebTestClient.ResponseSpec login(String userName, String password) {
        return post("/api/auth/login", null, Map.of("userName", userName, "password", password));
    }

    Map<String, Object> signIn(String userName, String password) {
        return login(userName, password).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
    }

    static String bearerOf(Map<String, Object> session) {
        return "Bearer " + session.get("accessToken");
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> violations(Map<String, Object> problem) {
        return (List<Map<String, Object>>) problem.get("violations");
    }

    static String ruleCode(Map<String, Object> problem) {
        return (String) violations(problem).getFirst().get("ruleCode");
    }

    /** The login records of a user, oldest first, from the table itself. */
    static List<Map<String, Object>> loginRecords(String userId) {
        return query("SELECT attempt_no, outcome, failure_count, locked_until FROM sec_login_record_version "
            + "WHERE user_id = ?::uuid ORDER BY attempt_no", userId);
    }
}
