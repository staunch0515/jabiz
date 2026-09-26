package com.jabiz.app.it.security;

import com.jabiz.runtime.ledger.LedgerEntities;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The catalogs clients build their pages from (docs/design/12-frontend.md section 2, decision D15): they need an
 * authenticated caller, list only what the caller may use, leave internal processes out, and the entity export is
 * localized. The application runs without the dev profile, so undeclared permissions count for nobody.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class MetaCatalogIT extends SecurityItSupport {

    private static final String CARRIERS = "urn:jabiz:dataset:default:Carrier";

    private List<Map<String, Object>> datasets(String authorization) {
        return get("/api/meta/datasets", authorization).expectStatus().isOk().expectBody(LIST).returnResult()
            .getResponseBody();
    }

    private List<Map<String, Object>> processes(String authorization) {
        return get("/api/meta/processes", authorization).expectStatus().isOk().expectBody(LIST).returnResult()
            .getResponseBody();
    }

    private static Optional<Map<String, Object>> byId(List<Map<String, Object>> datasets, String id) {
        return datasets.stream().filter(d -> id.equals(d.get("id"))).findFirst();
    }

    private static Optional<Map<String, Object>> byName(List<Map<String, Object>> processes, String name) {
        return processes.stream().filter(p -> name.equals(p.get("name"))).findFirst();
    }

    @Test
    void catalogsAndTheOpenApiDocumentNeedAnAuthenticatedCaller() {
        for (String path : List.of("/api/meta/datasets", "/api/meta/processes", "/api/meta/openapi")) {
            get(path, null).expectStatus().isUnauthorized();
        }
        // springdoc's own default path is not served (it would be public, outside /api).
        get("/v3/api-docs", null).expectStatus().isNotFound();
    }

    @Test
    void datasetsListOnlyWhatTheCallerMayReadAndSayWhetherItMayWrite() {
        List<Map<String, Object>> readOnly = datasets(bearer("logistics.carrier.read"));
        assertThat(readOnly).extracting(d -> d.get("id")).containsExactly(CARRIERS);
        assertThat(readOnly.getFirst()).containsEntry("entity", "Carrier").containsEntry("isDefault", true)
            .containsEntry("temporal", true).containsEntry("allowScheduled", true)
            .containsEntry("allowTimeTravel", true).containsEntry("readOnly", false)
            .containsEntry("listView", "default").containsEntry("canWrite", false);

        Map<String, Object> writable = byId(datasets(bearer("logistics.carrier.read", "logistics.carrier.write")),
            CARRIERS).orElseThrow();
        assertThat(writable).containsEntry("canWrite", true);

        assertThat(datasets(bearer())).isEmpty();
        assertThat(datasets(bearer("logistics.carrier.write"))).isEmpty();
    }

    @Test
    void datasetsWrittenByProcessesOnlyAreNeverWritableThroughTheCatalog() {
        List<Map<String, Object>> all = datasets(admin());
        assertThat(byId(all, LedgerEntities.TRANSACTION_DATASET).orElseThrow())
            .containsEntry("processOnlyWrites", true).containsEntry("canWrite", false);
        assertThat(byId(all, CARRIERS).orElseThrow()).containsEntry("canWrite", true);
    }

    @Test
    void labelsFollowTheLanguageOfTheRequest() {
        List<Map<String, Object>> ja = client.get().uri("/api/meta/datasets")
            .header(HttpHeaders.AUTHORIZATION, bearer("logistics.carrier.read")).header("Accept-Language", "ja")
            .exchange().expectStatus().isOk().expectBody(LIST).returnResult().getResponseBody();
        assertThat(ja.getFirst()).containsEntry("label", "運送会社");
    }

    @Test
    @SuppressWarnings("unchecked")
    void processesListWhatTheCallerMayRunWithTheSchemaOfTheirInput() {
        assertThat(processes(bearer())).isEmpty();
        List<Map<String, Object>> priceAdjusters = processes(bearer("price.adjust"));
        assertThat(priceAdjusters).extracting(p -> p.get("name")).containsExactly("PRICE_ADJUST");
        Map<String, Object> adjust = priceAdjusters.getFirst();
        assertThat(adjust).containsEntry("latest", true).containsEntry("deprecated", false);
        Map<String, Object> input = (Map<String, Object>) adjust.get("input");
        assertThat(input).containsEntry("type", "object")
            .containsEntry("required", List.of("priceId", "version", "percent"));
        assertThat((Map<String, Object>) ((Map<String, Object>) input.get("properties")).get("percent"))
            .containsEntry("format", "decimal");

        List<Map<String, Object>> all = processes(admin());
        assertThat(all).extracting(p -> p.get("name"))
            .contains("LEDGER_POST", "SEC_USER_SET_PASSWORD", "PARAM_SET")
            .doesNotContain("SPONSOR_SIGN_IN", "SEC_BOOTSTRAP_ADMIN", "ADD_ENTITY", "UPDATE_ENTITY", "DELETE_ENTITY");

        Map<String, Object> password = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>)
            byName(all, "SEC_USER_SET_PASSWORD").orElseThrow().get("input")).get("properties")).get("password");
        assertThat(password).containsEntry("writeOnly", true).containsEntry("format", "password");

        Map<String, Object> entries = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>)
            byName(all, "LEDGER_POST").orElseThrow().get("input")).get("properties")).get("entries");
        assertThat(entries).containsEntry("type", "array").containsEntry("minItems", 1);
        Map<String, Object> line = (Map<String, Object>) entries.get("items");
        assertThat(line).containsEntry("type", "object")
            .containsEntry("required", List.of("accountCode", "direction", "amount"));
        assertThat((Map<String, Object>) ((Map<String, Object>) line.get("properties")).get("direction"))
            .containsEntry("enum", List.of("DEBIT", "CREDIT"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theEntityExportCarriesLabelsAndMessagesInTheLanguageOfTheRequest() {
        Map<String, Object> carrier = client.get().uri("/api/meta/entities/Carrier")
            .header(HttpHeaders.AUTHORIZATION, bearer()).header("Accept-Language", "zh")
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(carrier).containsEntry("label", "承运商").containsEntry("temporal", true);
        Map<String, Object> code = ((List<Map<String, Object>>) carrier.get("fields")).stream()
            .filter(f -> "carrierCode".equals(f.get("name"))).findFirst().orElseThrow();
        assertThat(code).containsEntry("label", "代码");
        assertThat((List<Map<String, Object>>) code.get("rules")).singleElement()
            .satisfies(rule -> assertThat(rule).containsEntry("kind", "PATTERN")
                .containsEntry("params", Map.of("regex", "[A-Z0-9]{2,10}")));
        assertThat((Map<String, Object>) carrier.get("messages"))
            .containsEntry("CARRIER_CODE_FORMAT", "承运商代码须为 2～10 位大写字母或数字。")
            .containsKeys("REQUIRED", "TOO_LONG", "NOT_IN_DICTIONARY", "CREDIT_LIMIT_RANGE");
    }
}
