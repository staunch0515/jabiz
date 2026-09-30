package com.jabiz.app.it;

import com.jabiz.app.it.fixture.ItProcessFixtures;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Running SQL templates at a point in time (docs/design/19-reports.md section 2, ROADMAP 14d-1): the temporal
 * entities of a template are read at the requested effective and recorded times, or at those its {@code timeSlice}
 * parameters give; the two sources exclude each other, and datasets without time travel refuse both. The templates
 * are in {@code src/test/resources/queries/it}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("dev")
class TemplateTimeSliceIT extends PostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private static final String PRICES = "urn:jabiz:dataset:it:ItPrice";

    @Autowired
    ApplicationContext context;

    @Autowired
    ProcessExecutor processes;

    private WebTestClient client;
    private String sku;
    private Instant created;
    private Instant changed;

    /**
     * One price of 100, changed to 150 an hour later, and a change to 999 scheduled for tomorrow: three points in time
     * with three different answers.
     */
    @BeforeEach
    void prices() {
        client = WebTestClient.bindToApplicationContext(context).build();
        sku = "T-" + UUID.randomUUID().toString().substring(0, 8);
        created = clock.instant();
        Map<String, Object> price = insertPrice(sku, 100);
        clock.advance(Duration.ofHours(1));
        changed = clock.instant();
        update(price, 1, Map.of("amount", 150), null);
        update(price, 2, Map.of("amount", 999), changed.plus(Duration.ofDays(1)).toString());
        clock.advance(Duration.ofMinutes(5));
    }

    @Test
    void temporalEntitiesAreReadAtTheRequestedPointInTime() {
        assertThat(amounts("it.jp_prices", Map.of())).containsExactly(150);
        assertThat(amounts("it.jp_prices", Map.of("knownAt", created.toString()))).containsExactly(100);
        assertThat(amounts("it.jp_prices", Map.of("asOf", created.toString()))).containsExactly(100);
        assertThat(amounts("it.jp_prices", Map.of("asOf", changed.plus(Duration.ofDays(2)).toString())))
            .containsExactly(999);
        // Tomorrow as known an hour ago: the change to 999 had not been scheduled yet.
        assertThat(amounts("it.jp_prices", Map.of("asOf", changed.plus(Duration.ofDays(2)).toString(),
            "knownAt", changed.minusSeconds(1).toString()))).containsExactly(100);
        // Before the price existed.
        assertThat(amounts("it.jp_prices", Map.of("knownAt", created.minusSeconds(1).toString()))).isEmpty();
    }

    @Test
    void aTemplateMayTakeItsPointInTimeFromItsParameters() {
        assertThat(amounts("it.prices_at", Map.of())).containsExactly(150);
        assertThat(amounts("it.prices_at", Map.of("params", Map.of("seen", created.toString()))))
            .containsExactly(100);
        assertThat(amounts("it.prices_at", Map.of("params", Map.of("at", changed.plus(Duration.ofDays(2)).toString()))))
            .containsExactly(999);

        Map<String, Object> refused = post("it.prices_at", Map.of("asOf", created.toString(),
            "knownAt", created.toString())).expectStatus().isBadRequest().expectBody(MAP).returnResult()
            .getResponseBody();
        assertThat(refused.toString()).contains("INVALID_VALUE", "asOf", "knownAt");
    }

    @Test
    void datasetsWithoutTimeTravelRefuseAPointInTime() {
        assertThat(post("it.current_prices", Map.of()).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody()).isNotNull();

        post("it.current_prices", Map.of("knownAt", created.toString())).expectStatus().isBadRequest()
            .expectBody(MAP).value(body -> assertThat(body.toString()).contains("TIME_TRAVEL_NOT_ALLOWED", "knownAt"));
        post("it.current_prices", Map.of("asOf", created.toString())).expectStatus().isBadRequest()
            .expectBody(MAP).value(body -> assertThat(body.toString()).contains("TIME_TRAVEL_NOT_ALLOWED", "asOf"));
    }

    @Test
    void aProcessRunsATemplateAtAPointInTime() {
        List<Object> then = asTestRequest(processes.execute(ItProcessFixtures.PRICES_AT,
            new ItProcessFixtures.PricesAtInput(sku, created))).block().amounts();
        List<Object> now = asTestRequest(processes.execute(ItProcessFixtures.PRICES_AT,
            new ItProcessFixtures.PricesAtInput(sku, null))).block().amounts();

        assertThat(then).extracting(a -> ((Number) a).intValue()).containsExactly(100);
        assertThat(now).extracting(a -> ((Number) a).intValue()).containsExactly(150);
    }

    private List<Object> amounts(String template, Map<String, Object> request) {
        Map<String, Object> body = new LinkedHashMap<>(request);
        body.put("filters", List.of(Map.of("field", "sku", "op", "eq", "value", sku)));
        Map<String, Object> page = post(template, body).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        return items.stream().map(row -> (Object) ((Number) row.get("amount")).intValue()).toList();
    }

    private WebTestClient.ResponseSpec post(String queryId, Map<String, Object> body) {
        return client.post().uri("/api/queries/{id}", queryId)
            .header("X-Jabiz-Actor", "alice")
            .header("X-Jabiz-Permissions", "it.query")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private Map<String, Object> insertPrice(String sku, int amount) {
        Map<String, Object> attributes = Map.of("sku", sku, "region", "JP", "amount", amount, "status", "DRAFT");
        return commit(Map.of("action", "INSERT", "attributes", attributes)).expectStatus().isOk()
            .expectBodyList(MAP).returnResult().getResponseBody().getFirst();
    }

    private void update(Map<String, Object> price, long version, Map<String, Object> attributes, String effectiveTime) {
        Map<String, Object> change = new HashMap<>(Map.of("action", "UPDATE", "id", price.get("id"),
            "version", version, "attributes", attributes));
        if (effectiveTime != null) {
            change.put("effectiveTime", effectiveTime);
        }
        commit(change).expectStatus().isOk();
    }

    private WebTestClient.ResponseSpec commit(Map<String, Object> change) {
        return client.post().uri("/api/datasets/{id}/commit", PRICES)
            .header("X-Jabiz-Actor", "admin").header("X-Jabiz-Permissions", "*")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("changes", List.of(change)))
            .exchange();
    }
}
