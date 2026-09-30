package com.jabiz.app.it.commerce;

import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.app.commerce.CommerceProcesses;
import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.context.RequestContext;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.process.ProcessExecutor;
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
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Orders and inventory (ROADMAP phase 11) through the same HTTP API the generated pages and other clients use:
 * stock is received, reserved by orders, released by cancels and taken out by shipments, which post the sale to the
 * ledger; every rule problem of an order comes back at once; stock and orders cannot be written around the
 * processes; racing orders never oversell; and the commerce tables are only ever inserted into (CLAUDE.md section 5).
 * The tables are temporal and cannot be cleaned up, so every test works with codes of its own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "it.sql-log.enabled=true")
class CommerceIT extends PostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
        new ParameterizedTypeReference<>() {};
    private static final RequestContext CLERK = new RequestContext("it-clerk", null, Locale.ENGLISH, "it-commerce",
        Set.of(), Set.of("*"));
    private static final List<String> TABLES = List.of("PRODUCT_VERSION", "WAREHOUSE_VERSION", "STOCK_LEVEL_VERSION",
        "SALES_ORDER_VERSION", "SALES_ORDER_LINE_VERSION");

    @Autowired
    ApplicationContext context;

    @Autowired
    JwtService tokens;

    @Autowired
    ProcessExecutor executor;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
        // The sale postings need these accounts; the schema is this class's own, so they are opened once.
        for (String[] account : List.of(new String[] {"1130", "ASSET"}, new String[] {"4120", "REVENUE"})) {
            List<Map<String, Object>> found = query("urn:jabiz:dataset:platform:LedgerAccount", "accountCode",
                account[0]);
            if (found.isEmpty()) {
                process("LEDGER_ACCOUNT_OPEN", Map.of("accountCode", account[0], "accountName", "Account " + account[0],
                    "accountType", account[1])).expectStatus().isOk();
            }
        }
    }

    @Test
    void anOrderReservesStockACancelReleasesItAndAShipmentTakesItOutAndPostsTheSale() {
        String code = code();
        SqlStatementLog.STATEMENTS.clear();
        String warehouse = warehouse(code);
        String apple = product(code + "-A", 120);
        String pear = product(code + "-P", 300);
        receive(warehouse, apple, 10);
        receive(warehouse, pear, 4);
        assertThat(amount(receive(warehouse, apple, 5).get("onHand"))).isEqualByComparingTo("15");

        Map<String, Object> placed = output(process("ORDER_PLACE", order(code + "-1", warehouse,
            line(apple, 3), line(pear, 2))).expectStatus().isOk());
        assertThat(amount(placed.get("totalAmount"))).isEqualByComparingTo("960");
        assertThat(availability(warehouse)).containsEntry(apple, List.of(15, 3, 12)).containsEntry(pear,
            List.of(4, 2, 2));

        output(process("ORDER_CANCEL", Map.of("orderId", placed.get("orderId"))).expectStatus().isOk());
        assertThat(availability(warehouse)).containsEntry(apple, List.of(15, 0, 15)).containsEntry(pear,
            List.of(4, 0, 4));

        Map<String, Object> second = output(process("ORDER_PLACE", order(code + "-2", warehouse, line(apple, 4)))
            .expectStatus().isOk());
        Map<String, Object> shipped = output(process("ORDER_SHIP", Map.of("orderId", second.get("orderId")))
            .expectStatus().isOk());
        assertThat(shipped).containsEntry("status", "SHIPPED");
        assertThat(shipped.get("transactionId")).isNotNull();
        assertThat(availability(warehouse)).containsEntry(apple, List.of(11, 0, 11));

        // Neither a cancelled nor a shipped order changes again.
        for (Object orderId : List.of(placed.get("orderId"), second.get("orderId"))) {
            assertThat(ruleCodes(process("ORDER_SHIP", Map.of("orderId", orderId)).expectStatus()
                .isEqualTo(422))).containsExactly(CommerceProcesses.ORDER_NOT_PLACED);
        }

        // The order summary template sees both orders with their lines.
        List<Map<String, Object>> summary = template("commerce.order_summary", Map.of("customerCode", code));
        assertThat(summary).extracting(r -> r.get("orderno") + "/" + r.get("status") + "/" + r.get("linecount"))
            .containsExactlyInAnyOrder(code + "-1/CANCELLED/2", code + "-2/SHIPPED/1");

        List<String> writes = SqlStatementLog.STATEMENTS.stream()
            .map(s -> s.strip().toUpperCase(Locale.ROOT))
            .filter(s -> TABLES.stream().anyMatch(s::contains))
            .filter(s -> s.startsWith("UPDATE") || s.startsWith("DELETE") || s.startsWith("INSERT")
                || s.startsWith("TRUNCATE"))
            .toList();
        assertThat(writes).isNotEmpty().allSatisfy(s -> assertThat(s).startsWith("INSERT"));
    }

    @Test
    void anOrderPlacedWithoutANumberGetsTheNextOfTheYearAndARefusedOrderNone() {
        String code = code();
        String warehouse = warehouse(code);
        String apple = product(code + "-A", 100);
        receive(warehouse, apple, 3);
        Map<String, Object> unnumbered = Map.of("customerCode", code, "warehouseCode", warehouse,
            "lines", List.of(line(apple, 1)));

        String first = (String) output(process("ORDER_PLACE", unnumbered).expectStatus().isOk()).get("orderNo");
        // Refused for lack of stock: it draws no number, so the next placed order gets the following one.
        process("ORDER_PLACE", Map.of("customerCode", code, "warehouseCode", warehouse,
            "lines", List.of(line(apple, 50)))).expectStatus().isEqualTo(422);
        String second = (String) output(process("ORDER_PLACE", unnumbered).expectStatus().isOk()).get("orderNo");

        // The test clock starts in 2026 (PostgresIntegrationTest.START).
        assertThat(first).matches("SO-2026-\\d{6}");
        assertThat(Integer.parseInt(second.substring(8))).isEqualTo(Integer.parseInt(first.substring(8)) + 1);
        // A given number is kept as it is.
        assertThat(output(process("ORDER_PLACE", order(code + "-X", warehouse, line(apple, 1)))
            .expectStatus().isOk())).containsEntry("orderNo", code + "-X");
    }

    @Test
    void everyProblemOfAnOrderIsReportedAtOnceAndNothingIsWritten() {
        String code = code();
        String warehouse = warehouse(code);
        String apple = product(code + "-A", 100);
        String retired = product(code + "-R", 100);
        commit(CommerceEntities.PRODUCT_DATASET, "UPDATE", productId(retired), 1, Map.of("active", false))
            .expectStatus().isOk();
        receive(warehouse, apple, 2);

        Map<String, Object> problem = process("ORDER_PLACE", order(code + "-1", warehouse, line(apple, 3),
            line(code + "-NONE", 1), line(retired, 1), line(apple, 1))).expectStatus().isEqualTo(422)
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat(violations(problem)).extracting(v -> v.get("field") + ":" + v.get("ruleCode")).containsExactly(
            "lines[0].quantity:" + CommerceProcesses.STOCK_INSUFFICIENT,
            "lines[1].sku:" + CommerceProcesses.PRODUCT_NOT_FOUND,
            "lines[2].sku:" + CommerceProcesses.PRODUCT_INACTIVE,
            "lines[3].sku:" + CommerceProcesses.DUPLICATE_SKU);
        assertThat(availability(warehouse)).containsEntry(apple, List.of(2, 0, 2));
        assertThat(query(CommerceEntities.ORDER_DATASET, "orderNo", code + "-1")).isEmpty();

        assertThat(ruleCodes(process("ORDER_PLACE", order(code + "-2", "NOWHERE", line(apple, 1)))
            .expectStatus().isEqualTo(422))).containsExactly(CommerceProcesses.WAREHOUSE_NOT_FOUND);
    }

    @Test
    void stockAndOrdersAreWrittenByTheProcessesOnly() {
        String code = code();
        String warehouse = warehouse(code);
        String apple = product(code + "-A", 100);
        Map<String, Object> stock = receive(warehouse, apple, 1);

        assertThat(ruleCodes(commit(CommerceEntities.STOCK_LEVEL_DATASET, "UPDATE", stock.get("stockLevelId"), 1,
            Map.of("onHand", 1000)).expectStatus().isEqualTo(422))).containsExactly("PROCESS_ONLY_DATASET");
        assertThat(ruleCodes(commit(CommerceEntities.ORDER_DATASET, "INSERT", null, 0, Map.of("orderNo", code))
            .expectStatus().isEqualTo(422))).containsExactly("PROCESS_ONLY_DATASET");
    }

    @Test
    void theProcessesAndTemplatesNeedTheirPermissions() {
        String reader = TestTokens.bearer(tokens, "it-reader", "commerce.product.read");
        client.post().uri("/api/processes/ORDER_PLACE/latest").header(HttpHeaders.AUTHORIZATION, reader)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(order("X-1", "W", line("A", 1)))
            .exchange().expectStatus().isForbidden();
        client.post().uri("/api/queries/commerce.stock_availability").header(HttpHeaders.AUTHORIZATION, reader)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
            .exchange().expectStatus().isForbidden();
        client.post().uri("/api/processes/ORDER_PLACE/latest").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(order("X-1", "W", line("A", 1))).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void ordersRacingForTheLastUnitsNeverOversell() throws Exception {
        String code = code();
        String warehouse = warehouse(code);
        String apple = product(code + "-A", 100);
        receive(warehouse, apple, 3);
        ProcessDefinition<CommerceProcesses.PlaceInput, CommerceProcesses.PlaceOutput, ProcessContext> place =
            CommerceProcesses.PLACE_PROCESS;

        int racers = 6;
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Boolean>> outcomes = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            CommerceProcesses.PlaceInput input = new CommerceProcesses.PlaceInput(code + "-" + i, code, warehouse,
                List.of(new CommerceProcesses.LineInput(apple, 1)));
            outcomes.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                    asRequest(CLERK, executor.execute(place, input)).block();
                    return true;
                } catch (Exception e) {
                    // 409 (the stock row moved on) or 422 (nothing left): either way, nothing was reserved.
                    return false;
                }
            }));
        }
        start.countDown();
        long succeeded = 0;
        for (CompletableFuture<Boolean> outcome : outcomes) {
            succeeded += outcome.get(60, TimeUnit.SECONDS) ? 1 : 0;
        }

        List<Integer> stock = availability(warehouse).get(apple);
        assertThat(succeeded).isBetween(1L, 3L);
        assertThat(stock).containsExactly(3, (int) succeeded, 3 - (int) succeeded);
        assertThat(query(CommerceEntities.ORDER_DATASET, "customerCode", code)).hasSize((int) succeeded);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static String code() {
        return "C" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
    }

    private String admin() {
        return TestTokens.bearer(tokens, "it-admin", "*");
    }

    private WebTestClient.ResponseSpec post(String path, Object body) {
        return client.post().uri(path).header(HttpHeaders.AUTHORIZATION, admin())
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private WebTestClient.ResponseSpec process(String name, Object input) {
        return post("/api/processes/" + name + "/latest", input);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> output(WebTestClient.ResponseSpec response) {
        return (Map<String, Object>) response.expectBody(MAP).returnResult().getResponseBody().get("output");
    }

    private WebTestClient.ResponseSpec commit(String dataset, String action, Object id, long version,
        Map<String, Object> attributes) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("action", action);
        if (id != null) {
            change.put("id", id);
            change.put("version", version);
        }
        change.put("attributes", attributes);
        return post("/api/datasets/" + dataset + "/commit", Map.of("changes", List.of(change)));
    }

    private List<Map<String, Object>> query(String dataset, String field, Object value) {
        Map<String, Object> page = post("/api/datasets/" + dataset + "/query", Map.of("filters",
            List.of(Map.of("field", field, "op", "eq", "value", value)))).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        return items;
    }

    private String warehouse(String code) {
        commit(CommerceEntities.WAREHOUSE_DATASET, "INSERT", null, 0, Map.of("warehouseCode", code,
            "warehouseName", "Warehouse " + code, "active", true)).expectStatus().isOk();
        return code;
    }

    private String product(String sku, int unitPrice) {
        commit(CommerceEntities.PRODUCT_DATASET, "INSERT", null, 0, Map.of("sku", sku, "productName",
            "Product " + sku, "unitPrice", unitPrice, "active", true)).expectStatus().isOk();
        return sku;
    }

    private Object productId(String sku) {
        return query(CommerceEntities.PRODUCT_DATASET, "sku", sku).getFirst().get("id");
    }

    private Map<String, Object> receive(String warehouse, String sku, int quantity) {
        return output(process("STOCK_RECEIVE", Map.of("warehouseCode", warehouse, "sku", sku, "quantity", quantity))
            .expectStatus().isOk());
    }

    private static Map<String, Object> line(String sku, int quantity) {
        return Map.of("sku", sku, "quantity", quantity);
    }

    @SafeVarargs
    private static Map<String, Object> order(String orderNo, String warehouse, Map<String, Object>... lines) {
        return Map.of("orderNo", orderNo, "customerCode", orderNo.split("-")[0], "warehouseCode", warehouse,
            "lines", List.of(lines));
    }

    /** Rows of a template, keys in lower case (unquoted result names fold to lower case, docs/design/05 2.1). */
    private List<Map<String, Object>> template(String id, Map<String, Object> params) {
        Map<String, Object> page = post("/api/queries/" + id, Map.of("params", params, "limit", 500))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            item.forEach((key, value) -> row.put(key.toLowerCase(Locale.ROOT), value));
            rows.add(row);
        }
        return rows;
    }

    /** On hand, reserved and available by SKU, as the stock availability template reports them. */
    private Map<String, List<Integer>> availability(String warehouse) {
        Map<String, List<Integer>> stock = new LinkedHashMap<>();
        for (Map<String, Object> row : template("commerce.stock_availability", Map.of("warehouseCode", warehouse))) {
            stock.put((String) row.get("sku"), List.of(amount(row.get("onhand")).intValueExact(),
                amount(row.get("reserved")).intValueExact(), amount(row.get("available")).intValueExact()));
        }
        return stock;
    }

    private static BigDecimal amount(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> violations(Map<String, Object> problem) {
        return (List<Map<String, Object>>) problem.get("violations");
    }

    private static List<Object> ruleCodes(WebTestClient.ResponseSpec response) {
        return violations(response.expectBody(MAP).returnResult().getResponseBody()).stream()
            .map(v -> v.get("ruleCode")).toList();
    }
}
