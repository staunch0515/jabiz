package com.jabiz.app.it.security;

import com.jabiz.app.it.fixture.SqlStatementLog;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Carrier, the entity of ROADMAP phase 10 that has nothing but its declaration (docs/design/12-frontend.md): the
 * dataset API the generated pages call works for it, its rules answer with their codes, its history is complete,
 * and its table is only ever inserted into (CLAUDE.md section 5).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "it.sql-log.enabled=true")
class CarrierIT extends SecurityItSupport {

    private static final String CARRIERS = "urn:jabiz:dataset:default:Carrier";

    private Map<String, Object> attributes(String code) {
        return Map.of("carrierCode", code, "carrierName", "Kilo Lines", "countryCode", "JP", "creditLimit", 1000,
            "active", true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theGeneratedPagesWorkThroughTheDatasetApiAndTheTableIsOnlyInsertedInto() {
        String code = unique("K").replace("-", "").toUpperCase(Locale.ROOT);
        SqlStatementLog.STATEMENTS.clear();

        Map<String, Object> created = insert(CARRIERS, attributes(code), null);
        String id = String.valueOf(created.get("id"));
        List<Map<String, Object>> updated = post("/api/datasets/" + CARRIERS + "/commit", admin(), Map.of("changes",
            List.of(Map.of("action", "UPDATE", "id", id, "version", 1, "attributes", Map.of("carrierName", "Kilo Two")))))
            .expectStatus().isOk().expectBody(LIST).returnResult().getResponseBody();
        assertThat((Map<String, Object>) updated.getFirst().get("attributes")).containsEntry("carrierName", "Kilo Two");
        post("/api/datasets/" + CARRIERS + "/commit", admin(), Map.of("changes",
            List.of(Map.of("action", "DELETE", "id", id, "version", 2)))).expectStatus().isOk();

        List<Map<String, Object>> history = get("/api/datasets/" + CARRIERS + "/entities/" + id + "/history", admin())
            .expectStatus().isOk().expectBody(LIST).returnResult().getResponseBody();
        assertThat(history).extracting(v -> v.get("action")).containsExactly("INSERT", "UPDATE", "DELETE");

        List<String> writes = SqlStatementLog.STATEMENTS.stream()
            .map(s -> s.strip().toUpperCase(Locale.ROOT))
            .filter(s -> s.contains("CARRIER_VERSION"))
            .filter(s -> s.startsWith("UPDATE") || s.startsWith("DELETE") || s.startsWith("INSERT"))
            .toList();
        assertThat(writes).hasSize(3).allSatisfy(s -> assertThat(s).startsWith("INSERT"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rulesAnswerWithTheirCodesAndTheCodeIsUnique() {
        Map<String, Object> bad = new java.util.HashMap<>(attributes("x"));
        bad.put("creditLimit", "-1");
        bad.put("contactEmail", "nobody");
        bad.put("countryCode", "ZZ");
        Map<String, Object> problem = post("/api/datasets/" + CARRIERS + "/commit", admin(),
            Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", bad))))
            .expectStatus().isBadRequest().expectBody(MAP).returnResult().getResponseBody();
        assertThat((List<Map<String, Object>>) problem.get("violations")).extracting(v -> v.get("ruleCode"))
            .containsExactlyInAnyOrder("CARRIER_CODE_FORMAT", "NOT_IN_DICTIONARY", "CREDIT_LIMIT_RANGE",
                "CONTACT_EMAIL_FORMAT");

        String code = unique("U").replace("-", "").toUpperCase(Locale.ROOT);
        insert(CARRIERS, attributes(code), null);
        Map<String, Object> duplicate = post("/api/datasets/" + CARRIERS + "/commit", admin(),
            Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", attributes(code)))))
            .expectStatus().isBadRequest().expectBody(MAP).returnResult().getResponseBody();
        assertThat((List<Map<String, Object>>) duplicate.get("violations")).extracting(v -> v.get("ruleCode"))
            .containsExactly("UNIQUE_VIOLATION");
    }
}
