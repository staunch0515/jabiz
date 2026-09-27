package com.jabiz.app.it.commerce;

import com.jabiz.app.commerce.SupplierCertifications;
import com.jabiz.app.commerce.SupplierDefinitions;
import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.temporal.TemporalPermissions;
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
 * The content-authoring sample (docs/design/16-content-authoring.md section 9) on a temporal entity: a certification
 * is created through the dataset API with its status filled in, moves only through its processes, cannot be reverted
 * past them, is found by its multilingual title, and its table is only inserted into.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "it.sql-log.enabled=true")
class SupplierCertificationIT extends PostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
        new ParameterizedTypeReference<>() {};
    private static final String CERTIFICATIONS = "/api/datasets/" + SupplierCertifications.DATASET;

    @Autowired
    ApplicationContext context;

    @Autowired
    JwtService tokens;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
    }

    private String editor() {
        return TestTokens.bearer(tokens, "it-editor", "commerce.supplier.read", "commerce.supplier.write",
            "commerce.certification.read", "commerce.certification.write", "commerce.certification.submit",
            "commerce.certification.review", TemporalPermissions.REVERT);
    }

    private WebTestClient.ResponseSpec post(String path, Object body) {
        return client.post().uri(path).header(HttpHeaders.AUTHORIZATION, editor())
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private Map<String, Object> ok(WebTestClient.ResponseSpec response) {
        return response.expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private static List<String> codes(Map<String, Object> problem) {
        return ((List<Map<String, Object>>) problem.get("violations")).stream()
            .map(v -> v.get("field") + ":" + v.get("ruleCode")).toList();
    }

    private Object insert(String dataset, Map<String, Object> attributes) {
        return post("/api/datasets/" + dataset + "/commit", Map.of("changes", List.of(Map.of("action", "INSERT",
            "attributes", attributes)))).expectStatus().isOk().expectBody(LIST).returnResult().getResponseBody()
            .getFirst().get("id");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> certification(Object id) {
        return (Map<String, Object>) client.get().uri(CERTIFICATIONS + "/entities/" + id)
            .header(HttpHeaders.AUTHORIZATION, editor()).exchange().expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody().get("attributes");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCertificationMovesThroughItsProcessesOnly() {
        String code = "C" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT).replace("-", "");
        String name = "Kanto Tea " + code;
        Object supplier = insert(SupplierDefinitions.DATASET, Map.of("supplierCode", code, "supplierName", name,
            "countryCode", "JP", "leadTimeDays", 5, "active", true));
        SqlStatementLog.STATEMENTS.clear();

        Object id = insert(SupplierCertifications.DATASET, Map.of("supplierId", supplier,
            "title", Map.of("ja", "有機認証 " + code, "en", "Organic " + code),
            "body", Map.of("en", "**Certified** by the board.")));
        assertThat(certification(id)).containsEntry("status", "DRAFT")
            .containsEntry("title", Map.of("ja", "有機認証 " + code, "en", "Organic " + code));

        Map<String, Object> refused = post(CERTIFICATIONS + "/commit", Map.of("changes", List.of(Map.of(
            "action", "UPDATE", "id", id, "version", 1, "attributes", Map.of("status", "APPROVED")))))
            .expectStatus().isBadRequest().expectBody(MAP).returnResult().getResponseBody();
        assertThat(codes(refused)).containsExactly("status:PROCESS_ONLY_FIELD");

        ok(post("/api/processes/" + SupplierCertifications.SUBMIT + "/1", Map.of("certificationId", id)));
        Map<String, Object> approved = ok(post("/api/processes/" + SupplierCertifications.APPROVE + "/1",
            Map.of("certificationId", id, "comment", "Fine")));
        assertThat(certification(id)).containsEntry("status", "APPROVED").containsEntry("reviewComment", "Fine");

        // Reverting the approval would put the status back without the lifecycle.
        long approval = ((Number) approved.get("processSeqId")).longValue();
        Map<String, Object> revert = post("/api/processes/executions/" + approval + "/revert",
            Map.of("reason", "undo")).expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody();
        assertThat(codes(revert)).containsExactlyInAnyOrder("status:PROCESS_ONLY_FIELD",
            "reviewComment:PROCESS_ONLY_FIELD");

        // Rejecting an approved certification is not a transition, whatever a client showed.
        Map<String, Object> late = post("/api/processes/" + SupplierCertifications.REJECT + "/1",
            Map.of("certificationId", id, "comment", "Too late")).expectStatus().isEqualTo(422).expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(codes(late)).containsExactly("status:ILLEGAL_TRANSITION");

        // Found by any language of its title; the supplier by its name.
        List<Map<String, Object>> found = client.get().uri(uri -> uri.path(CERTIFICATIONS + "/lookup")
                .queryParam("q", "有機認証 " + code).build())
            .header(HttpHeaders.AUTHORIZATION, editor()).exchange().expectStatus().isOk().expectBody(LIST)
            .returnResult().getResponseBody();
        assertThat(found).singleElement().satisfies(item -> {
            assertThat(item.get("id")).isEqualTo(id);
            assertThat((Map<String, Object>) item.get("label")).containsEntry("en", "Organic " + code);
        });
        Map<String, Object> labels = ok(post("/api/datasets/" + SupplierDefinitions.DATASET + "/labels",
            Map.of("ids", List.of(supplier))));
        assertThat(labels).containsExactly(Map.entry(String.valueOf(supplier), name));

        assertThat(SqlStatementLog.STATEMENTS.stream().map(s -> s.strip().toUpperCase(Locale.ROOT))
            .filter(s -> s.contains("SUPPLIER_CERTIFICATION_VERSION"))
            .filter(s -> s.startsWith("UPDATE") || s.startsWith("DELETE") || s.startsWith("INSERT")))
            .isNotEmpty().allSatisfy(s -> assertThat(s).startsWith("INSERT"));
    }

    @Test
    void theTitleNeedsEnglish() {
        String code = "D" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT).replace("-", "");
        Object supplier = insert(SupplierDefinitions.DATASET, Map.of("supplierCode", code, "supplierName", "Other",
            "countryCode", "JP", "leadTimeDays", 5, "active", true));
        Map<String, Object> problem = post(CERTIFICATIONS + "/commit", Map.of("changes", List.of(Map.of(
            "action", "INSERT", "attributes", Map.of("supplierId", supplier, "title", Map.of("zh", "有机认证"))))))
            .expectStatus().isBadRequest().expectBody(MAP).returnResult().getResponseBody();
        assertThat(codes(problem)).containsExactly("title:TRANSLATION_REQUIRED");
    }
}
