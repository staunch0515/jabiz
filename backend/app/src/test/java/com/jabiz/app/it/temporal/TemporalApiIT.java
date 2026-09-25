package com.jabiz.app.it.temporal;

import com.jabiz.app.it.fixture.ItTemporalFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The temporal parts of the dataset API (docs/design/03-dataset.md section 3) and the operation API
 * (docs/design/06-process.md section 8) over HTTP; actors and permissions come from the dev headers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("dev")
class TemporalApiIT extends TemporalItSupport {

    private static final String PRICES = ItTemporalFixtures.PRICE_DATASET;
    private static final String ALL_PERMISSIONS = "temporal.backdate,temporal.revert,operation.read";
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final Class<Map<String, Object>> MAP = (Class) Map.class;

    @Autowired
    ApplicationContext context;

    private WebTestClient client;

    @BeforeEach
    void client() {
        client = WebTestClient.bindToApplicationContext(context).build();
    }

    private WebTestClient.ResponseSpec commit(String permissions, String reason, Map<String, Object> change) {
        Map<String, Object> body = new HashMap<>();
        body.put("changes", List.of(change));
        body.put("reason", reason);
        return client.post().uri("/api/datasets/{id}/commit", PRICES)
            .header("X-Jabiz-Actor", "alice").header("X-Jabiz-Permissions", permissions)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private static Map<String, Object> change(String action, Object id, long version, Map<String, Object> attributes,
        Instant effectiveTime) {
        Map<String, Object> change = new HashMap<>();
        change.put("action", action);
        change.put("id", id);
        change.put("version", version);
        change.put("attributes", attributes);
        change.put("effectiveTime", effectiveTime == null ? null : effectiveTime.toString());
        return change;
    }

    private Map<String, Object> created(String sku) {
        List<Map<String, Object>> result = commit("", null, change("INSERT", null, 0,
            Map.of("sku", sku, "region", "JP", "amount", 100), null))
            .expectStatus().isOk().expectBodyList(MAP).returnResult().getResponseBody();
        return result.getFirst();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributes(Map<String, Object> instance) {
        return (Map<String, Object>) instance.get("attributes");
    }

    @Test
    void readsAtPointsInTimeAndListsTheHistory() {
        Map<String, Object> price = created(sku());
        String id = (String) price.get("id");
        Instant created = now();
        Instant tomorrow = created.plus(Duration.ofDays(1));
        commit("", null, change("UPDATE", id, 1, Map.of("amount", 150), tomorrow)).expectStatus().isOk();

        Map<String, Object> current = client.get().uri("/api/datasets/{d}/entities/{id}", PRICES, id).exchange()
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(attributes(current).get("amount")).isEqualTo(100);
        Map<String, Object> later = client.get()
            .uri("/api/datasets/{d}/entities/{id}?asOf={t}", PRICES, id, tomorrow.toString()).exchange()
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(attributes(later).get("amount")).isEqualTo(150);
        assertThat(later.get("version")).isEqualTo(2);

        Map<String, Object> page = client.post().uri("/api/datasets/{d}/query", PRICES)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("filters", List.of(Map.of("field", "sku", "op", "eq", "value", attributes(price).get("sku"))),
                "asOf", tomorrow.toString()))
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(page.get("total")).isEqualTo(1);

        List<Map<String, Object>> history = client.get().uri("/api/datasets/{d}/entities/{id}/history", PRICES, id)
            .exchange().expectStatus().isOk().expectBodyList(MAP).returnResult().getResponseBody();
        assertThat(history).extracting(h -> h.get("versionNo"), h -> h.get("action"), h -> h.get("actorId"))
            .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "INSERT", "alice"),
                org.assertj.core.groups.Tuple.tuple(2, "UPDATE", "alice"));
        assertThat(history.get(1)).containsEntry("effectStartTime", tomorrow.toString())
            .containsEntry("processName", "jabiz.dataset.commit").containsEntry("changedFields", List.of("amount"));

        client.get().uri("/api/datasets/{d}/entities/{id}/history", ItTemporalFixtures.PRICE_CURRENT_DATASET, id)
            .exchange().expectStatus().isBadRequest();
        client.get().uri("/api/datasets/{d}/entities/{id}/history", PRICES, java.util.UUID.randomUUID())
            .exchange().expectStatus().isNotFound();
    }

    @Test
    void operationsCanBeReadAndReverted() {
        Map<String, Object> price = created(sku());
        String id = (String) price.get("id");
        advance(Duration.ofMinutes(1));
        commit("", null, change("UPDATE", id, 1, Map.of("amount", 130), null)).expectStatus().isOk();
        long seq = lastOperation(id);

        client.get().uri("/api/processes/executions/{seq}", seq).header("X-Jabiz-Actor", "alice").exchange()
            .expectStatus().isForbidden()
            .expectBody(MAP).value(problem -> assertThat(problem.toString()).contains("PERMISSION_DENIED"));
        Map<String, Object> detail = client.get().uri("/api/processes/executions/{seq}", seq)
            .header("X-Jabiz-Actor", "alice").header("X-Jabiz-Permissions", "operation.read").exchange()
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(detail).containsEntry("processSeqId", (int) seq).containsEntry("actorId", "alice");
        assertThat((List<?>) detail.get("items")).hasSize(1);

        client.post().uri("/api/processes/executions/{seq}/revert", seq).header("X-Jabiz-Actor", "alice")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("reason", "x")).exchange()
            .expectStatus().isForbidden();
        client.post().uri("/api/processes/executions/{seq}/revert", seq).header("X-Jabiz-Actor", "alice")
            .header("X-Jabiz-Permissions", ALL_PERMISSIONS)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of()).exchange()
            .expectStatus().isBadRequest();
        Map<String, Object> revert = client.post().uri("/api/processes/executions/{seq}/revert", seq)
            .header("X-Jabiz-Actor", "alice").header("X-Jabiz-Permissions", ALL_PERMISSIONS)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("reason", "wrong price")).exchange()
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(revert).containsEntry("revertsSeqId", (int) seq).containsEntry("reason", "wrong price")
            .containsEntry("processName", "jabiz.revert");

        // Reverted already: a second revert conflicts and names the revert.
        Map<String, Object> conflict = client.post().uri("/api/processes/executions/{seq}/revert", seq)
            .header("X-Jabiz-Actor", "alice").header("X-Jabiz-Permissions", ALL_PERMISSIONS)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("reason", "again")).exchange()
            .expectStatus().isEqualTo(HttpStatus.CONFLICT).expectBody(MAP).returnResult().getResponseBody();
        assertThat((List<?>) conflict.get("blockingOperations")).singleElement()
            .satisfies(b -> assertThat(asMap(b)).containsEntry("processSeqId", revert.get("processSeqId")));
    }

    @Test
    void conflictsAndCorrectionsAnswerWithProblems() {
        Map<String, Object> price = created(sku());
        String id = (String) price.get("id");
        Instant created = now();
        commit("", null, change("UPDATE", id, 1, Map.of("amount", 150), created.plus(Duration.ofDays(1))))
            .expectStatus().isOk();

        Map<String, Object> rebase = commit("", null, change("UPDATE", id, 1, Map.of("amount", 120), null))
            .expectStatus().isEqualTo(HttpStatus.CONFLICT).expectBody(MAP).returnResult().getResponseBody();
        assertThat((List<?>) rebase.get("conflicts")).singleElement()
            .satisfies(c -> assertThat(asMap(c)).containsEntry("versionNo", 2)
                .containsEntry("fields", List.of("amount")));

        advance(Duration.ofHours(1));
        commit("", "typo", change("UPDATE", id, 1, Map.of("note", "x"), created)).expectStatus().isForbidden();
        commit(ALL_PERMISSIONS, null, change("UPDATE", id, 1, Map.of("note", "x"), created))
            .expectStatus().isBadRequest();
        commit(ALL_PERMISSIONS, "typo", change("UPDATE", id, 1, Map.of("note", "x"), created)).expectStatus().isOk();
    }
}
