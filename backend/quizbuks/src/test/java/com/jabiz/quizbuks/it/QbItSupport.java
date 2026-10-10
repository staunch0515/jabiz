package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QuizBuks integration tests call the API as users would, with real access tokens. Each test class has its own schema;
 * the temporal tables cannot be cleaned up, so a class that needs a fresh installation is a class of its own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
abstract class QbItSupport extends PostgresIntegrationTest {

    protected static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    @Autowired
    ApplicationContext context;

    @Autowired
    protected JwtService tokens;

    protected WebTestClient client;

    @BeforeEach
    void client() {
        // A first QB_SETUP writes some 600 rows (every country and its records): more than the default 5 seconds.
        client = WebTestClient.bindToApplicationContext(context).configureClient()
            .responseTimeout(java.time.Duration.ofSeconds(60))
            .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "en").build();
    }

    protected String installer() {
        return TestTokens.bearer(tokens, "installer", QbPermissions.SETUP);
    }

    /** An administrator holding every permission, as the platform's bootstrap administrator does. */
    protected String admin() {
        return TestTokens.bearer(tokens, "admin", "*");
    }

    /** Runs {@code QB_SETUP}, which must succeed; returns its output. */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> setup() {
        var exchange = runSetup(installer()).expectBody(MAP).returnResult();
        assertThat(exchange.getStatus().value()).as("QB_SETUP answered " + exchange.getResponseBody()).isEqualTo(200);
        return (Map<String, Object>) exchange.getResponseBody().get("output");
    }

    /**
     * Publishes, as a second administrator, the controlled parameters a run of {@code QB_SETUP} proposed (platform
     * decision D40: the proposer cannot publish).
     */
    @SuppressWarnings("unchecked")
    protected void publishProposals(Map<String, Object> setupOutput) {
        for (Object changeId : (List<Object>) setupOutput.get("proposals")) {
            client.post().uri("/api/processes/CONTROL_CHANGE_PUBLISH/latest").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(tokens, "publisher", "control.publish"))
                .bodyValue(Map.of("changeId", changeId)).exchange().expectStatus().isOk();
        }
    }

    protected WebTestClient.ResponseSpec runSetup(String authorization) {
        return client.post().uri("/api/processes/QB_SETUP/latest").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, authorization).bodyValue(Map.of()).exchange();
    }

    /** One change through a dataset's generic API, as an administrator; must succeed. */
    protected void commit(String dataset, String action, Object id, Long version, Map<String, Object> attributes,
        Instant effectiveTime) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("action", action);
        if (id != null) {
            change.put("id", id);
        }
        if (version != null) {
            change.put("version", version);
        }
        if (attributes != null) {
            change.put("attributes", attributes);
        }
        if (effectiveTime != null) {
            change.put("effectiveTime", effectiveTime);
        }
        client.post().uri("/api/datasets/" + dataset + "/commit").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, admin()).bodyValue(Map.of("changes", List.of(change))).exchange()
            .expectStatus().isOk();
    }

    /** The latest version of each instance of a temporal table (deleted ones included, flagged). */
    protected static List<Map<String, Object>> latest(String table, String key, String where) {
        return query("SELECT * FROM (SELECT DISTINCT ON (" + key + ") * FROM " + table + " ORDER BY " + key
            + ", effect_start_time DESC, version_no DESC) v WHERE " + where);
    }
}
