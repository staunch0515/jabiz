package com.jabiz.app.it.document;

import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.app.commerce.OrderConfirmations;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.test.TestTokens;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Business documents (docs/design/22-documents.md, ROADMAP phase 14j-1) through the HTTP API, with the order
 * confirmation of the commerce sample: an issued document is printed again from the kept bytes, whatever changes
 * later; it is read as at the order's time; verification compares the kept PDF with its hash and the data at the
 * archived point in time with the content hash; a template that must give one row and gives none refuses the issue;
 * permissions, previews, the catalog and the append-only store. Orders and products are temporal and cannot be
 * cleaned up, so every test works with codes of its own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class DocumentIssueIT extends PostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
        new ParameterizedTypeReference<>() {};

    @Autowired
    ApplicationContext context;

    @Autowired
    JwtService tokens;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
    }

    /** An order of two products in a warehouse of its own; the ids and codes the tests need. */
    private record Order(String code, String orderId, String orderNo, String warehouseId, String productId) {}

    private Order order() {
        String code = "D" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        commit(CommerceEntities.WAREHOUSE_DATASET, "INSERT", null, 0, Map.of("warehouseCode", code,
            "warehouseName", "North " + code, "active", true));
        String apple = code + "-APPLE";
        String pear = code + "-PEAR";
        commit(CommerceEntities.PRODUCT_DATASET, "INSERT", null, 0, Map.of("sku", apple, "productName",
            "Apple crate", "unitPrice", 1200, "active", true));
        commit(CommerceEntities.PRODUCT_DATASET, "INSERT", null, 0, Map.of("sku", pear, "productName",
            "Pear tray", "unitPrice", 450, "active", true));
        process("STOCK_RECEIVE", Map.of("warehouseCode", code, "sku", apple, "quantity", 10), admin());
        process("STOCK_RECEIVE", Map.of("warehouseCode", code, "sku", pear, "quantity", 10), admin());
        clock.advance(Duration.ofMinutes(1));
        Map<String, Object> placed = output(process("ORDER_PLACE", Map.of("orderNo", code + "-1",
            "customerCode", code, "warehouseCode", code, "lines", List.of(Map.of("sku", apple, "quantity", 3),
                Map.of("sku", pear, "quantity", 2))), admin()).expectStatus().isOk());
        clock.advance(Duration.ofMinutes(1));
        return new Order(code, (String) placed.get("orderId"), (String) placed.get("orderNo"),
            String.valueOf(find(CommerceEntities.WAREHOUSE_DATASET, "warehouseCode", code).get("id")),
            String.valueOf(find(CommerceEntities.PRODUCT_DATASET, "sku", apple).get("id")));
    }

    private String clerk() {
        return bearer("commerce.order.confirm", "commerce.order.read");
    }

    private String reader() {
        return bearer("document.archive.read", "commerce.order.read");
    }

    private Map<String, Object> confirm(Order order) {
        Map<String, Object> issued = output(process(OrderConfirmations.ISSUE, Map.of("orderId", order.orderId()),
            clerk()).expectStatus().isOk());
        clock.advance(Duration.ofMinutes(1));
        return issued;
    }

    @Test
    void anIssuedDocumentIsPrintedFromItsKeptBytesWhateverChangesLater() throws IOException {
        Order order = order();
        Map<String, Object> issued = confirm(order);
        assertThat(issued.get("documentNo")).isEqualTo(order.orderNo());

        EntityExchangeResult<byte[]> pdf = original((String) issued.get("runId"), reader());
        byte[] bytes = pdf.getResponseBody();
        assertThat(sha256(bytes)).isEqualTo(issued.get("pdfHash"));
        assertThat(pdf.getResponseHeaders().getFirst("X-Jabiz-Pdf-Hash")).isEqualTo(issued.get("pdfHash"));
        assertThat(pdf.getResponseHeaders().getContentDisposition().getFilename()).isEqualTo(order.orderNo() + ".pdf");
        String text = text(bytes);
        assertThat(text).contains("Order confirmation", order.orderNo(), "Customer", order.code(),
            "North " + order.code(), "Apple crate", "Pear tray", "3,600", "900", "4,500", "Thank you for your order",
            "Page 1 of 1").doesNotContain("PREVIEW");

        // Later the product and the warehouse are renamed: the kept document does not change.
        commit(CommerceEntities.PRODUCT_DATASET, "UPDATE", order.productId(), 1, Map.of("productName", "Red apples"));
        commit(CommerceEntities.WAREHOUSE_DATASET, "UPDATE", order.warehouseId(), 1,
            Map.of("warehouseName", "South " + order.code()));
        clock.advance(Duration.ofMinutes(1));
        assertThat(original((String) issued.get("runId"), reader()).getResponseBody()).isEqualTo(bytes);
        assertThat(verify((String) issued.get("runId"))).containsEntry("verdict", "identical")
            .containsEntry("copyIntact", true).containsEntry("recomputable", true)
            .containsEntry("currentHash", detail((String) issued.get("runId")).get("contentHash"));

        // Issued again, the confirmation is read as at the order's time: the names of then.
        Map<String, Object> again = confirm(order);
        String second = text(original((String) again.get("runId"), reader()).getResponseBody());
        assertThat(second).contains("Apple crate", "North " + order.code()).doesNotContain("Red apples");

        // A preview reads now, says it is one, and is not kept.
        byte[] preview = client.post().uri("/api/documents/{id}/preview", OrderConfirmations.LAYOUT_ID)
            .header(HttpHeaders.AUTHORIZATION, bearer("document.issue", "commerce.order.read"))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("params", Map.of("orderId", order.orderId())))
            .exchange().expectStatus().isOk().expectBody(byte[].class).returnResult().getResponseBody();
        assertThat(text(preview)).contains("Red apples", "South " + order.code(), "PREVIEW");
        assertThat(runs("subject=" + order.orderId(), reader())).extracting(r -> r.get("runId"))
            .containsExactly(again.get("runId"), issued.get("runId"));
    }

    @Test
    void verificationTellsAChangedCopyChangedDataAndAChangedLayoutApart() {
        Order order = order();
        String runId = (String) confirm(order).get("runId");

        bypassingTheGuard("UPDATE sys_document_run SET pdf = pdf || '\\x00'::bytea WHERE run_id = '" + runId + "'");
        assertThat(verify(runId)).containsEntry("copyIntact", false).containsEntry("verdict", "identical");
        client.get().uri("/api/documents/runs/{id}/pdf", runId).header(HttpHeaders.AUTHORIZATION, reader())
            .exchange().expectStatus().is5xxServerError();

        // Data changed behind the platform's back at the archived point in time.
        bypassingTheGuard("UPDATE sales_order_line_version SET quantity = quantity + 1 WHERE order_id = '"
            + order.orderId() + "'");
        assertThat(verify(runId)).containsEntry("verdict", "differs");
        // The order itself gone: issuing would refuse (no row), verifying says the data differs.
        bypassingTheGuard("DELETE FROM sales_order_version WHERE order_id = '" + order.orderId() + "'");
        assertThat(verify(runId)).containsEntry("verdict", "differs").containsEntry("currentHash", null);

        bypassingTheGuard("UPDATE sys_document_run SET template_versions = replace(template_versions, '\"commerce.order_document_lines\":\"', '\"commerce.order_document_lines\":\"0') WHERE run_id = '" + runId + "'");
        assertThat(verify(runId)).containsEntry("verdict", "template_changed");
        bypassingTheGuard("UPDATE sys_document_run SET layout_version = repeat('0', 64) WHERE run_id = '"
            + runId + "'");
        assertThat(verify(runId)).containsEntry("verdict", "layout_changed")
            .containsEntry("currentLayoutVersion", OrderConfirmations.LAYOUT.version());
    }

    @Test
    void aTemplateThatMustGiveOneRowAndGivesNoneRefusesTheIssueAndKeepsNothing() {
        int before = query("SELECT run_id FROM sys_document_run").size();
        Map<String, Object> problem = process("DOCUMENT_ISSUE", Map.of("layoutId", OrderConfirmations.LAYOUT_ID,
            "params", Map.of("orderId", UUID.randomUUID().toString())), bearer("document.issue",
            "commerce.order.read")).expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody();
        assertThat(problem.toString()).contains("DOCUMENT_NOT_SINGLE", OrderConfirmations.HEADER);
        assertThat(query("SELECT run_id FROM sys_document_run")).hasSize(before);

        Map<String, Object> unknown = process("DOCUMENT_ISSUE", Map.of("layoutId", OrderConfirmations.LAYOUT_ID,
            "params", Map.of("orderId", UUID.randomUUID().toString(), "colour", "red")), bearer("document.issue",
            "commerce.order.read")).expectStatus().isBadRequest().expectBody(MAP).returnResult().getResponseBody();
        assertThat(unknown.toString()).contains("colour", "UNKNOWN_FIELD");
        process("DOCUMENT_ISSUE", Map.of("layoutId", "commerce.nothing"), bearer("document.issue"))
            .expectStatus().isNotFound();
        process("DOCUMENT_ISSUE", Map.of("layoutId", OrderConfirmations.LAYOUT_ID, "language", "fr",
            "params", Map.of("orderId", UUID.randomUUID().toString())), bearer("document.issue",
            "commerce.order.read")).expectStatus().isBadRequest();
    }

    @Test
    void issuingAndReadingNeedTheLayoutsPermissionsAndTheStoreIsAppendOnly() {
        Order order = order();
        // The layout's permission is the caller's to have, also through a business process.
        process(OrderConfirmations.ISSUE, Map.of("orderId", order.orderId()), bearer("commerce.order.confirm"))
            .expectStatus().isForbidden();
        process("DOCUMENT_ISSUE", Map.of("layoutId", OrderConfirmations.LAYOUT_ID,
            "params", Map.of("orderId", order.orderId())), bearer("commerce.order.read")).expectStatus().isForbidden();
        String runId = (String) confirm(order).get("runId");

        client.get().uri("/api/documents/runs/{id}", runId).header(HttpHeaders.AUTHORIZATION,
            bearer("commerce.order.read")).exchange().expectStatus().isForbidden();
        // Without the layout's permissions the document does not exist for the caller, in the list neither.
        client.get().uri("/api/documents/runs/{id}/pdf", runId).header(HttpHeaders.AUTHORIZATION,
            bearer("document.archive.read")).exchange().expectStatus().isNotFound();
        assertThat(runs("subject=" + order.orderId(), bearer("document.archive.read"))).isEmpty();
        client.get().uri("/api/documents/runs/{id}", "not-a-run").header(HttpHeaders.AUTHORIZATION, reader())
            .exchange().expectStatus().isNotFound();
        Map<String, Object> detail = detail(runId);
        assertThat(detail).containsEntry("layoutId", OrderConfirmations.LAYOUT_ID)
            .containsEntry("documentNo", order.orderNo()).containsEntry("subjectEntity", CommerceEntities.ORDER)
            .containsEntry("pages", 1).containsEntry("issuedBy", "it-clerk");

        client.post().uri("/api/documents/{id}/preview", OrderConfirmations.LAYOUT_ID)
            .header(HttpHeaders.AUTHORIZATION, bearer("commerce.order.read"))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("params", Map.of("orderId", order.orderId())))
            .exchange().expectStatus().isForbidden();

        assertThatThrownBy(() -> execute("UPDATE sys_document_run SET title = 'x' WHERE run_id = CAST(? AS uuid)",
            runId)).hasMessageContaining("sys_document_run");
        assertThatThrownBy(() -> execute("DELETE FROM sys_document_run WHERE run_id = CAST(? AS uuid)", runId))
            .hasMessageContaining("sys_document_run");
    }

    @Test
    void sendingIsRefusedWhileMailIsOff() {
        Order order = order();
        String runId = (String) confirm(order).get("runId");
        Map<String, Object> refused = process("DOCUMENT_SEND", Map.of("runId", runId),
            bearer("document.send", "commerce.order.read")).expectStatus().isEqualTo(422).expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(refused.toString()).contains("MAIL_DISABLED");
        assertThat(query("SELECT delivery_id FROM sys_document_delivery WHERE run_id = CAST(? AS uuid)", runId))
            .isEmpty();
        // The address the data names was kept with the document when it was issued.
        Map<String, Object> run = client.get().uri("/api/documents/runs/{id}", runId)
            .header(HttpHeaders.AUTHORIZATION, reader()).exchange().expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(run.get("run").toString())
            .contains("recipients=[" + order.code().toLowerCase(Locale.ROOT) + "@customers.example.com]");
    }

    @Test
    void theCatalogListsTheLayoutsTheCallerMayIssueWithTheirParameters() {
        List<Map<String, Object>> layouts = client.get().uri("/api/meta/documents")
            .header(HttpHeaders.AUTHORIZATION, bearer("document.issue", "commerce.order.read")).exchange()
            .expectStatus().isOk().expectBody(LIST).returnResult().getResponseBody();
        assertThat(layouts).extracting(l -> l.get("id")).contains(OrderConfirmations.LAYOUT_ID);
        Map<String, Object> confirmation = layouts.stream()
            .filter(l -> OrderConfirmations.LAYOUT_ID.equals(l.get("id"))).findFirst().orElseThrow();
        assertThat(confirmation).containsEntry("title", "Order confirmation")
            .containsEntry("subjectEntity", CommerceEntities.ORDER).containsEntry("subjectParam", "orderId")
            .containsEntry("version", OrderConfirmations.LAYOUT.version());
        assertThat(confirmation.get("params").toString()).contains("orderId", "required=[orderId]");

        for (String token : List.of(bearer("document.issue"), bearer("commerce.order.read"))) {
            assertThat(client.get().uri("/api/meta/documents").header(HttpHeaders.AUTHORIZATION, token).exchange()
                .expectStatus().isOk().expectBody(LIST).returnResult().getResponseBody()).isEmpty();
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private String bearer(String... permissions) {
        return TestTokens.bearer(tokens, "it-clerk", permissions);
    }

    private String admin() {
        return TestTokens.bearer(tokens, "it-admin", "*");
    }

    private WebTestClient.ResponseSpec process(String name, Object input, String token) {
        return client.post().uri("/api/processes/" + name + "/latest").header(HttpHeaders.AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(input).exchange();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> output(WebTestClient.ResponseSpec response) {
        return (Map<String, Object>) response.expectBody(MAP).returnResult().getResponseBody().get("output");
    }

    private void commit(String dataset, String action, Object id, long version, Map<String, Object> attributes) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("action", action);
        if (id != null) {
            change.put("id", id);
            change.put("version", version);
        }
        change.put("attributes", attributes);
        client.post().uri("/api/datasets/" + dataset + "/commit").header(HttpHeaders.AUTHORIZATION, admin())
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("changes", List.of(change))).exchange()
            .expectStatus().isOk();
    }

    private Map<String, Object> find(String dataset, String field, Object value) {
        Map<String, Object> page = client.post().uri("/api/datasets/" + dataset + "/query")
            .header(HttpHeaders.AUTHORIZATION, admin()).contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("filters", List.of(Map.of("field", field, "op", "eq", "value", value)))).exchange()
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        return items.getFirst();
    }

    private EntityExchangeResult<byte[]> original(String runId, String token) {
        return client.get().uri("/api/documents/runs/{id}/pdf", runId).header(HttpHeaders.AUTHORIZATION, token)
            .exchange().expectStatus().isOk().expectBody(byte[].class).returnResult();
    }

    private Map<String, Object> verify(String runId) {
        return client.post().uri("/api/documents/runs/{id}/verify", runId).header(HttpHeaders.AUTHORIZATION, reader())
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> detail(String runId) {
        Map<String, Object> detail = client.get().uri("/api/documents/runs/{id}", runId)
            .header(HttpHeaders.AUTHORIZATION, reader()).exchange().expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        return (Map<String, Object>) detail.get("run");
    }

    private List<Map<String, Object>> runs(String query, String token) {
        return client.get().uri("/api/documents/runs?" + query).header(HttpHeaders.AUTHORIZATION, token).exchange()
            .expectStatus().isOk().expectBody(LIST).returnResult().getResponseBody();
    }

    private static String text(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Statements run with the append-only guard off, as a database administrator could. */
    private static void bypassingTheGuard(String... statements) {
        try (Connection connection = DB.connect(schema()); Statement statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = replica");
            for (String sql : statements) {
                statement.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
