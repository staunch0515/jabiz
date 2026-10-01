package com.jabiz.app.it.temporal;

import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Temporal reads and checks stay proportional to what they look at, not to the table (ROADMAP phase 14i, decision
 * D29). About 200,000 product versions, 100,000 stock level versions and 100,000 write-once receipts are loaded;
 * then a product is inserted (uniqueness check), deleted (current and scheduled reference checks), and products and
 * receipts are queried by immutable fields. Every statement the application sent to those tables is explained as a
 * generic plan, and no plan node may expect more than a sliver of a table: the old whole-table
 * {@code DISTINCT ON} read is shown to fail the same test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "it.sql-log.enabled=true")
class TemporalScaleIT extends PostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private static final Set<String> TABLES = Set.of("product_version", "stock_level_version", "stock_receipt_version");
    private static final int PRODUCTS = 50_000;
    /** A plan node of a statement on one instance or one value expects at most this many rows. */
    private static final double MAX_ROWS = 1_000;

    @Autowired
    ApplicationContext context;

    @Autowired
    JwtService tokens;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void checksAndImmutableReadsTouchOnlyTheRowsTheyNeed() throws Exception {
        load();
        WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
        SqlStatementLog.STATEMENTS.clear();

        post(client, "/api/datasets/" + CommerceEntities.PRODUCT_DATASET + "/commit", change("INSERT", null, 0,
            Map.of("sku", "SCALE-NEW", "productName", "New", "unitPrice", 100, "active", true))).expectStatus().isOk();
        Map<String, Object> product = items(post(client, "/api/datasets/" + CommerceEntities.PRODUCT_DATASET + "/query",
            filter("sku", "SCALE-NEW"))).getFirst();
        post(client, "/api/datasets/" + CommerceEntities.PRODUCT_DATASET + "/commit", change("DELETE", product.get("id"),
            ((Number) product.get("version")).longValue(), Map.of())).expectStatus().isOk();
        assertThat(items(post(client, "/api/datasets/" + CommerceEntities.PRODUCT_DATASET + "/query",
            filter("sku", "BULK-777")))).hasSize(1);
        String stockLevelId = (String) query("SELECT stock_level_id::text AS id FROM stock_level_version"
            + " ORDER BY row_id LIMIT 1").getFirst().get("id");
        assertThat(items(post(client, "/api/datasets/" + CommerceEntities.STOCK_RECEIPT_DATASET + "/query",
            filter("stockLevelId", stockLevelId)))).hasSize(2);

        List<String> statements = SqlStatementLog.STATEMENTS.stream()
            .filter(sql -> sql.stripLeading().regionMatches(true, 0, "SELECT", 0, 6)
                || sql.stripLeading().regionMatches(true, 0, "WITH", 0, 4))
            .filter(sql -> TABLES.stream().anyMatch(sql::contains))
            .distinct().toList();
        // The uniqueness check, the reference checks and the reads above.
        assertThat(statements).anyMatch(sql -> sql.contains("WITH cand AS") && sql.contains("product_version"))
            .anyMatch(sql -> sql.contains("WITH cand AS") && sql.contains("stock_level_version"))
            .anyMatch(sql -> sql.contains("stock_receipt_version"));
        for (String sql : statements) {
            assertThat(wideNodes(sql)).as(sql).isEmpty();
        }

        // The control: the version read as it was before decision D29 reads the whole table.
        assertThat(wideNodes("SELECT * FROM (SELECT DISTINCT ON (product_id) * FROM product_version"
            + " WHERE effect_start_time <= $1 ORDER BY product_id, effect_start_time DESC, version_no DESC) v"
            + " WHERE NOT v.is_deleted AND v.sku = $2")).isNotEmpty();
    }

    /** The plan nodes of a statement that expect more than {@link #MAX_ROWS} rows. */
    private List<String> wideNodes(String sql) throws Exception {
        // The simple query protocol leaves the $n placeholders unbound, as GENERIC_PLAN wants them.
        Properties properties = new Properties();
        properties.setProperty("user", DB.username());
        properties.setProperty("password", DB.password());
        properties.setProperty("preferQueryMode", "simple");
        properties.setProperty("currentSchema", schema());
        String plan;
        try (Connection connection = DriverManager.getConnection(DB.jdbcUrl(), properties);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("EXPLAIN (GENERIC_PLAN, FORMAT JSON) " + sql)) {
            rs.next();
            plan = rs.getString(1);
        }
        List<String> wide = new ArrayList<>();
        collect(json.readTree(plan).get(0).get("Plan"), wide);
        return wide;
    }

    private static void collect(JsonNode node, List<String> wide) {
        if (node.path("Plan Rows").asDouble() > MAX_ROWS) {
            wide.add(node.path("Node Type").asText() + " " + node.path("Relation Name").asText("")
                + " rows=" + node.path("Plan Rows").asText());
        }
        for (JsonNode child : node.path("Plans")) {
            collect(child, wide);
        }
    }

    /**
     * Bulk versions straight into the tables: products with four versions each (one of them scheduled), a stock
     * level per product with two versions, and two receipts per stock level.
     */
    private void load() {
        long seq = ((Number) query("INSERT INTO op_process (process_seq_id, process_name, process_version, actor_id,"
            + " op_time) VALUES (nextval('op_process_seq'), 'it.bulk', 1, 'it-bulk', '2025-01-01T00:00:00Z')"
            + " RETURNING process_seq_id").getFirst().get("process_seq_id")).longValue();
        execute("CREATE TABLE it_bulk (n integer PRIMARY KEY, product_id uuid, stock_level_id uuid, receipt_a uuid,"
            + " receipt_b uuid)");
        execute("INSERT INTO it_bulk SELECT n, gen_random_uuid(), gen_random_uuid(), gen_random_uuid(),"
            + " gen_random_uuid() FROM generate_series(1, ?) n", PRODUCTS);
        String warehouse = "INSERT INTO entity_registry SELECT gen_random_uuid(), 'Warehouse', ? RETURNING entity_id";
        Object warehouseId = query(warehouse, seq).getFirst().get("entity_id");
        execute("INSERT INTO warehouse_version (warehouse_id, version_no, effect_start_time, created_time,"
            + " process_seq_id, warehouse_code, warehouse_name, active) VALUES (?, 1, '2025-01-01T00:00:00Z',"
            + " '2025-01-01T00:00:00Z', ?, 'BULK', 'Bulk', true)", warehouseId, seq);
        execute("INSERT INTO entity_registry SELECT product_id, 'Product', ? FROM it_bulk"
            + " UNION ALL SELECT stock_level_id, 'StockLevel', ? FROM it_bulk"
            + " UNION ALL SELECT receipt_a, 'StockReceipt', ? FROM it_bulk"
            + " UNION ALL SELECT receipt_b, 'StockReceipt', ? FROM it_bulk", seq, seq, seq, seq);
        // Version 4 takes effect after the test clock: a scheduled version.
        execute("INSERT INTO product_version (product_id, version_no, effect_start_time, created_time, process_seq_id,"
            + " sku, product_name, unit_price, active) SELECT product_id, v,"
            + " timestamptz '2025-01-01T00:00:00Z' + v * interval '120 days', timestamptz '2025-01-01T00:00:00Z',"
            + " ?, 'BULK-' || n, 'Bulk ' || n, 100 + v, true FROM it_bulk, generate_series(1, 4) v", seq);
        execute("INSERT INTO stock_level_version (stock_level_id, version_no, effect_start_time, created_time,"
            + " process_seq_id, warehouse_id, product_id, on_hand, reserved) SELECT stock_level_id, v,"
            + " timestamptz '2025-01-01T00:00:00Z' + v * interval '1 day', timestamptz '2025-01-01T00:00:00Z', ?, ?,"
            + " product_id, 10 * v, 0 FROM it_bulk, generate_series(1, 2) v", seq, warehouseId);
        execute("INSERT INTO stock_receipt_version (receipt_id, version_no, effect_start_time, created_time,"
            + " process_seq_id, stock_level_id, quantity) SELECT r, 1, timestamptz '2025-01-02T00:00:00Z',"
            + " timestamptz '2025-01-02T00:00:00Z', ?, stock_level_id, 10 FROM it_bulk,"
            + " LATERAL (VALUES (receipt_a), (receipt_b)) x(r)", seq);
        for (String table : List.of("entity_registry", "product_version", "stock_level_version",
            "stock_receipt_version", "warehouse_version")) {
            execute("ANALYZE " + table);
        }
    }

    private WebTestClient.ResponseSpec post(WebTestClient client, String path, Object body) {
        return client.post().uri(path).header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(tokens, "it-admin", "*"))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private static Map<String, Object> change(String action, Object id, long version, Map<String, Object> attributes) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("action", action);
        if (id != null) {
            change.put("id", id);
            change.put("version", version);
        }
        change.put("attributes", attributes);
        return Map.of("changes", List.of(change));
    }

    private static Map<String, Object> filter(String field, Object value) {
        return Map.of("filters", List.of(Map.of("field", field, "op", "eq", "value", value)));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(WebTestClient.ResponseSpec response) {
        return (List<Map<String, Object>>) response.expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody().get("items");
    }
}
