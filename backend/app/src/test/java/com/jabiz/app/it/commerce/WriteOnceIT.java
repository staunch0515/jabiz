package com.jabiz.app.it.commerce;

import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.entity.MetaModelConsistencyChecker;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Write-once temporal entities (docs/design/04-temporal-append-only.md section 5.4, decision D29), through the demo
 * stock receipts: a receipt is inserted once and never updated, deleted or reverted, whichever path asks; it reads
 * like any other entity; and the startup check insists on the unique index that makes a version read cheap.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class WriteOnceIT extends PostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
        new ParameterizedTypeReference<>() {};

    @Autowired
    ApplicationContext context;

    @Autowired
    JwtService tokens;

    @Autowired
    DatabaseClient databaseClient;

    @Autowired
    EntityDefinitionRegistry registry;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
    }

    @Test
    void aReceiptIsWrittenOnceAndNeverChangedDeletedOrReverted() {
        Object stockLevelId = receiveStock(5);
        List<Map<String, Object>> receipts = receipts("stockLevelId", stockLevelId);
        assertThat(receipts).hasSize(1);
        Map<String, Object> receipt = receipts.getFirst();
        assertThat(attributes(receipt)).containsEntry("quantity", 5);
        Object receiptId = receipt.get("id");

        assertThat(ruleCodes(commit("UPDATE", receiptId, 1, Map.of("note", "changed"))
            .expectStatus().isEqualTo(422))).containsExactly("WRITE_ONCE");
        assertThat(ruleCodes(commit("DELETE", receiptId, 1, Map.of())
            .expectStatus().isEqualTo(422))).containsExactly("WRITE_ONCE");

        // An insert is allowed; taking it back would change the receipt, so the revert is refused too.
        commit("INSERT", null, 0, Map.of("stockLevelId", stockLevelId, "quantity", 2, "note", "manual"))
            .expectStatus().isOk();
        List<Map<String, Object>> both = receipts("stockLevelId", stockLevelId);
        assertThat(both).hasSize(2);
        Object manualId = both.stream().filter(r -> "manual".equals(attributes(r).get("note"))).findFirst().orElseThrow().get("id");
        long seq = ((Number) query("SELECT process_seq_id FROM stock_receipt_version WHERE receipt_id = ?",
            UUID.fromString(manualId.toString())).getFirst().get("process_seq_id")).longValue();
        assertThat(ruleCodes(post("/api/processes/executions/" + seq + "/revert", Map.of("reason", "typo"))
            .expectStatus().isEqualTo(422))).containsExactly("WRITE_ONCE");

        // One version each, whatever was attempted, and the history shows just that one.
        assertThat(query("SELECT count(*) AS n FROM stock_receipt_version WHERE stock_level_id = ?",
            UUID.fromString(stockLevelId.toString())).getFirst().get("n")).isEqualTo(2L);
        List<Map<String, Object>> history = client.get()
            .uri("/api/datasets/{dataset}/entities/{id}/history", CommerceEntities.STOCK_RECEIPT_DATASET, receiptId)
            .header(HttpHeaders.AUTHORIZATION, admin()).exchange()
            .expectStatus().isOk().expectBody(LIST).returnResult().getResponseBody();
        assertThat(history).hasSize(1);
    }

    @Test
    void theStartupCheckWantsAUniqueIndexOnTheKeyAlone() {
        MetaModelConsistencyChecker checker = new MetaModelConsistencyChecker(databaseClient, registry);
        assertThat(errors(checker)).isEmpty();

        execute("DROP INDEX stock_receipt_version_once_uk");
        try {
            assertThat(errors(checker)).singleElement().satisfies(problem -> assertThat(problem.location() + " " + problem.message())
                .contains("StockReceipt").contains("write-once").contains("receipt_id"));
        } finally {
            execute("CREATE UNIQUE INDEX stock_receipt_version_once_uk ON stock_receipt_version (receipt_id)");
        }
        assertThat(errors(checker)).isEmpty();
    }

    private static List<CheckProblem> errors(MetaModelConsistencyChecker checker) {
        return checker.check().stream().filter(p -> p.severity() == CheckProblem.Severity.ERROR).toList();
    }

    /** Receives stock of a product and warehouse of their own and returns the stock level. */
    private Object receiveStock(int quantity) {
        String code = "W" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        post("/api/datasets/" + CommerceEntities.WAREHOUSE_DATASET + "/commit", changes("INSERT", null, 0,
            Map.of("warehouseCode", code, "warehouseName", "Warehouse " + code, "active", true))).expectStatus().isOk();
        post("/api/datasets/" + CommerceEntities.PRODUCT_DATASET + "/commit", changes("INSERT", null, 0,
            Map.of("sku", code, "productName", "Product " + code, "unitPrice", 100, "active", true)))
            .expectStatus().isOk();
        Map<String, Object> body = post("/api/processes/STOCK_RECEIVE/latest", Map.of("warehouseCode", code, "sku",
            code, "quantity", quantity)).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) body.get("output");
        return output.get("stockLevelId");
    }

    private String admin() {
        return TestTokens.bearer(tokens, "it-admin", "*");
    }

    private WebTestClient.ResponseSpec post(String path, Object body) {
        return client.post().uri(path).header(HttpHeaders.AUTHORIZATION, admin())
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private WebTestClient.ResponseSpec commit(String action, Object id, long version, Map<String, Object> attributes) {
        return post("/api/datasets/" + CommerceEntities.STOCK_RECEIPT_DATASET + "/commit",
            changes(action, id, version, attributes));
    }

    private static Map<String, Object> changes(String action, Object id, long version, Map<String, Object> attributes) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("action", action);
        if (id != null) {
            change.put("id", id);
            change.put("version", version);
        }
        change.put("attributes", attributes);
        return Map.of("changes", List.of(change));
    }

    private List<Map<String, Object>> receipts(String field, Object value) {
        Map<String, Object> page = post("/api/datasets/" + CommerceEntities.STOCK_RECEIPT_DATASET + "/query",
            Map.of("filters", List.of(Map.of("field", field, "op", "eq", "value", value)))).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        return items;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributes(Map<String, Object> item) {
        return (Map<String, Object>) item.get("attributes");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> ruleCodes(WebTestClient.ResponseSpec response) {
        Map<String, Object> problem = response.expectBody(MAP).returnResult().getResponseBody();
        return ((List<Map<String, Object>>) problem.get("violations")).stream().map(v -> v.get("ruleCode")).toList();
    }
}
