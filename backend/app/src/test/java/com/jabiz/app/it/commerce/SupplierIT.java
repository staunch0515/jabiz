package com.jabiz.app.it.commerce;

import com.jabiz.app.commerce.SupplierDefinitions;
import com.jabiz.app.it.fixture.SqlStatementLog;
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

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tutorial's Supplier (docs/guide/new-business-object.md, step 6): the dataset API writes and reads it, its rules
 * answer with their codes, its history is kept, it needs its permissions, and its table is only inserted into.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "it.sql-log.enabled=true")
class SupplierIT extends PostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
        new ParameterizedTypeReference<>() {};
    private static final String COMMIT = "/api/datasets/" + SupplierDefinitions.DATASET + "/commit";

    @Autowired
    ApplicationContext context;

    @Autowired
    JwtService tokens;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
    }

    private WebTestClient.ResponseSpec post(String path, String bearer, Object body) {
        return client.post().uri(path).header(HttpHeaders.AUTHORIZATION, bearer)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private String writer() {
        return TestTokens.bearer(tokens, "it-buyer", "commerce.supplier.read", "commerce.supplier.write");
    }

    @Test
    @SuppressWarnings("unchecked")
    void suppliersAreKeptWithTheirHistoryAndOnlyInserted() {
        String code = "S" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT).replace("-", "");
        SqlStatementLog.STATEMENTS.clear();
        List<Map<String, Object>> created = post(COMMIT, writer(), Map.of("changes", List.of(Map.of("action",
            "INSERT", "attributes", Map.of("supplierCode", code, "supplierName", "Acme", "countryCode", "JP",
                "leadTimeDays", 7, "active", true))))).expectStatus().isOk().expectBody(LIST).returnResult()
            .getResponseBody();
        Object id = created.getFirst().get("id");
        post(COMMIT, writer(), Map.of("changes", List.of(Map.of("action", "UPDATE", "id", id, "version", 1,
            "attributes", Map.of("leadTimeDays", 10))))).expectStatus().isOk();

        List<Map<String, Object>> history = client.get()
            .uri("/api/datasets/" + SupplierDefinitions.DATASET + "/entities/" + id + "/history")
            .header(HttpHeaders.AUTHORIZATION, writer()).exchange().expectStatus().isOk().expectBody(LIST)
            .returnResult().getResponseBody();
        assertThat(history).extracting(v -> v.get("action")).containsExactly("INSERT", "UPDATE");

        Map<String, Object> problem = post(COMMIT, writer(), Map.of("changes", List.of(Map.of("action", "INSERT",
            "attributes", Map.of("supplierCode", "x", "supplierName", " ", "countryCode", "JP", "leadTimeDays", 0,
                "active", true))))).expectStatus().isBadRequest().expectBody(MAP).returnResult().getResponseBody();
        assertThat((List<Map<String, Object>>) problem.get("violations")).extracting(v -> v.get("ruleCode"))
            .containsExactlyInAnyOrder("SUPPLIER_CODE_FORMAT", "SUPPLIER_NAME_BLANK", "SUPPLIER_LEAD_TIME_RANGE");

        post(COMMIT, TestTokens.bearer(tokens, "it-reader", "commerce.supplier.read"), Map.of("changes", List.of()))
            .expectStatus().isForbidden();

        assertThat(SqlStatementLog.STATEMENTS.stream().map(s -> s.strip().toUpperCase(Locale.ROOT))
            .filter(s -> s.contains("SUPPLIER_VERSION"))
            .filter(s -> s.startsWith("UPDATE") || s.startsWith("DELETE") || s.startsWith("INSERT")))
            .isNotEmpty().allSatisfy(s -> assertThat(s).startsWith("INSERT"));
    }
}
