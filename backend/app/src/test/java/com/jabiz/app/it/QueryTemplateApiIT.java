package com.jabiz.app.it;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SQL template API {@code POST /api/queries/{id}} (ROADMAP phase 5, requirement 9) and acceptance criteria 3 and
 * 4: a hand-written template cannot read rows outside its datasets' scopes, soft-deleted rows or versions of a
 * temporal entity that are not in effect; outer filters and sorts are limited to the template's whitelist. The
 * templates are in {@code src/test/resources/queries/it}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("dev")
class QueryTemplateApiIT extends PostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private static final String PRICES = "urn:jabiz:dataset:it:ItPrice";

    @Autowired
    ApplicationContext context;

    private WebTestClient client;

    @BeforeEach
    void reset() {
        client = WebTestClient.bindToApplicationContext(context).build();
        execute("DELETE FROM todo");
        execute("DELETE FROM it_soft");
        execute("DELETE FROM it_tenant");
    }

    // ---------------------------------------------------------------- Acceptance 3: scope, deletion, versions

    @Test
    void theMemberScopeAppliesToTemplates() {
        execute("INSERT INTO todo (id, title, done, owner_id) VALUES ('t1', 'alice 1', false, 'alice'), "
            + "('t2', 'alice 2', true, 'alice'), ('t3', 'bob 1', false, 'bob')");

        assertThat(titles(run("it.member_todos", "alice", Map.of()))).containsExactly("alice 1", "alice 2");
        assertThat(titles(run("it.member_todos", "bob", Map.of()))).containsExactly("bob 1");
    }

    @Test
    void aMissingScopeValueRejectsTheQuery() {
        execute("INSERT INTO it_tenant (f_id, f_tenant, f_name) VALUES ('1', 'acme', 'a'), ('2', 'other', 'b')");

        post("it.tenant_names", "alice", null, Map.of()).expectStatus().isForbidden()
            .expectBody(MAP).value(body -> assertThat(body.toString()).contains("SCOPE_UNAVAILABLE"));

        Map<String, Object> page = post("it.tenant_names", "alice", "acme", Map.of())
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(items(page)).extracting(row -> row.get("name")).containsExactly("a");
    }

    @Test
    void softDeletedRowsAreInvisible() {
        execute("INSERT INTO it_soft (f_id, f_name, is_deleted) VALUES ('1', 'kept', false), ('2', 'gone', true)");

        assertThat(items(run("it.soft_names", "alice", Map.of()))).extracting(row -> row.get("name"))
            .containsExactly("kept");
    }

    /** Only the version in effect, never a tombstone, a scheduled version or an entity moved out of the scope. */
    @Test
    void temporalEntitiesShowTheirVersionInEffectOnly() {
        String sku = "Q-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> changed = insertPrice(sku + "-changed", "JP", 100);
        update(changed, 1, Map.of("amount", 150), null);
        Map<String, Object> scheduled = insertPrice(sku + "-scheduled", "JP", 200);
        update(scheduled, 1, Map.of("amount", 999), clock.instant().plus(Duration.ofDays(1)).toString());
        Map<String, Object> moved = insertPrice(sku + "-moved", "JP", 300);
        update(moved, 1, Map.of("region", "US"), null);
        Map<String, Object> deleted = insertPrice(sku + "-deleted", "JP", 400);
        commit(PRICES, Map.of("action", "DELETE", "id", deleted.get("id"), "version", 1)).expectStatus().isOk();

        List<Map<String, Object>> rows = items(run("it.jp_prices", "alice",
            Map.of("filters", List.of(Map.of("field", "sku", "op", "like", "value", sku + "%")))));

        assertThat(rows).extracting(row -> row.get("sku") + "=" + row.get("amount"))
            .containsExactly(sku + "-changed=150", sku + "-scheduled=200");

        clock.advance(Duration.ofDays(2));
        assertThat(items(run("it.jp_prices", "alice",
            Map.of("filters", List.of(Map.of("field", "sku", "op", "like", "value", sku + "%"))))))
            .extracting(row -> row.get("sku") + "=" + row.get("amount"))
            .containsExactly(sku + "-changed=150", sku + "-scheduled=999");
    }

    /** Identifiers of a temporal entity are bound as one {@code uuid[]} (decision D7). */
    @Test
    void listParametersOfTemporalIdentifiersAreUuidArrays() {
        String sku = "U-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> a = insertPrice(sku + "-a", "JP", 10);
        insertPrice(sku + "-b", "JP", 20);

        List<Map<String, Object>> rows = items(run("it.jp_prices", "alice",
            Map.of("params", Map.of("ids", List.of(a.get("id"))))));

        assertThat(rows).extracting(row -> row.get("sku")).containsExactly(sku + "-a");
        assertThat(items(run("it.jp_prices", "alice", Map.of("params", Map.of("ids", List.of()))))).isEmpty();
    }

    // ---------------------------------------------------------------- Acceptance 4: whitelist

    @Test
    void filteringOrSortingOutsideTheWhitelistIsRejected() {
        expectViolation(post("it.jp_prices", "alice", null,
                Map.of("filters", List.of(Map.of("field", "note", "op", "eq", "value", "x")))),
            "FILTER_NOT_ALLOWED");
        expectViolation(post("it.jp_prices", "alice", null,
                Map.of("sorts", List.of(Map.of("field", "note")))),
            "SORT_NOT_ALLOWED");
        expectViolation(post("it.jp_prices", "alice", null,
                Map.of("filters", List.of(Map.of("field", "amount", "op", "like", "value", "1%")))),
            "OPERATOR_NOT_ALLOWED");
        expectViolation(post("it.jp_prices", "alice", null,
                Map.of("filters", List.of(Map.of("field", "priceIdTypo", "op", "eq", "value", "x")))),
            "UNKNOWN_FIELD");
    }

    @Test
    void whitelistedFiltersSortsAndPagingWork() {
        String sku = "P-" + UUID.randomUUID().toString().substring(0, 8);
        for (int i = 1; i <= 5; i++) {
            insertPrice(sku + "-" + i, "JP", i * 10);
        }

        Map<String, Object> page = post("it.jp_prices", "alice", null, Map.of(
                "filters", List.of(Map.of("field", "sku", "op", "like", "value", sku + "%"),
                    Map.of("field", "amount", "op", "gte", "value", 20)),
                "sorts", List.of(Map.of("field", "amount", "asc", false)),
                "offset", 1, "limit", 2))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();

        assertThat(page).containsEntry("total", 4).containsEntry("offset", 1).containsEntry("limit", 2);
        assertThat(items(page)).extracting(row -> row.get("sku")).containsExactly(sku + "-4", sku + "-3");
    }

    // ---------------------------------------------------------------- Access and parameters

    @Test
    void theDeclaredPermissionIsRequired() {
        client.post().uri("/api/queries/{id}", "it.soft_names")
            .header("X-Jabiz-Actor", "alice")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
            .exchange()
            .expectStatus().isForbidden()
            .expectBody(MAP).value(body -> assertThat(body.toString()).contains("PERMISSION_DENIED"));
    }

    @Test
    void unknownQueriesAreNotFound() {
        post("it.nope", "alice", null, Map.of()).expectStatus().isNotFound();
    }

    @Test
    void malformedParametersAreRejected() {
        expectViolation(post("it.jp_prices", "alice", null, Map.of("params", Map.of("minAmount", "lots"))),
            "INVALID_VALUE");
        expectViolation(post("it.jp_prices", "alice", null, Map.of("params", Map.of("ids", List.of("not-a-uuid")))),
            "INVALID_VALUE");
        expectViolation(post("it.jp_prices", "alice", null, Map.of("params", Map.of("other", 1))),
            "UNKNOWN_FIELD");
    }

    // ---------------------------------------------------------------- Helpers

    private Map<String, Object> run(String queryId, String actor, Map<String, Object> body) {
        return post(queryId, actor, null, body).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
    }

    private WebTestClient.ResponseSpec post(String queryId, String actor, String tenant, Map<String, Object> body) {
        WebTestClient.RequestBodySpec request = client.post().uri("/api/queries/{id}", queryId)
            .header("X-Jabiz-Actor", actor)
            .header("X-Jabiz-Permissions", "it.query")
            .header("Accept-Language", "en");
        if (tenant != null) {
            request = request.header("X-Jabiz-Tenant", tenant);
        }
        return request.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private static void expectViolation(WebTestClient.ResponseSpec response, String ruleCode) {
        response.expectStatus().isBadRequest()
            .expectBody(MAP).value(body -> assertThat(body.toString()).contains(ruleCode));
    }

    private Map<String, Object> insertPrice(String sku, String region, int amount) {
        Map<String, Object> attributes = Map.of("sku", sku, "region", region, "amount", amount, "status", "DRAFT");
        List<Map<String, Object>> result = commit(PRICES, Map.of("action", "INSERT", "attributes", attributes))
            .expectStatus().isOk()
            .expectBodyList(MAP).returnResult().getResponseBody();
        return result.getFirst();
    }

    private void update(Map<String, Object> price, long version, Map<String, Object> attributes, String effectiveTime) {
        Map<String, Object> change = new HashMap<>(Map.of("action", "UPDATE", "id", price.get("id"),
            "version", version, "attributes", attributes));
        if (effectiveTime != null) {
            change.put("effectiveTime", effectiveTime);
        }
        commit(PRICES, change).expectStatus().isOk();
    }

    private WebTestClient.ResponseSpec commit(String dataset, Map<String, Object> change) {
        return client.post().uri("/api/datasets/{id}/commit", dataset)
            .header("X-Jabiz-Actor", "admin")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("changes", List.of(change)))
            .exchange();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    private static List<Object> titles(Map<String, Object> page) {
        return items(page).stream().map(row -> row.get("title")).toList();
    }
}
