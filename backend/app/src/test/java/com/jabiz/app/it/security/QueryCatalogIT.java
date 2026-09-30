package com.jabiz.app.it.security;

import com.jabiz.runtime.query.SqlTemplateRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The template catalog {@code GET /api/meta/queries} (docs/design/19-reports.md section 3.2, ROADMAP 14d-1): only
 * templates the caller may run, public ones left out, titles and column names in the language of the request,
 * parameters as the JSON Schema the process forms use, and the version, point in time and report settings the
 * template declares.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class QueryCatalogIT extends SecurityItSupport {

    private static final String TRIAL_BALANCE = "jabiz.ledger.account_balances";
    private static final String STOCK = "commerce.stock_availability";

    @Autowired
    SqlTemplateRegistry templates;

    private List<Map<String, Object>> catalog(String authorization, String language) {
        return client.get().uri("/api/meta/queries")
            .header(HttpHeaders.AUTHORIZATION, authorization).header("Accept-Language", language)
            .exchange().expectStatus().isOk().expectBody(LIST).returnResult().getResponseBody();
    }

    /** A map that may hold nulls: the catalog writes absent settings as null. */
    private static Map<String, Object> withNulls(Object... keysAndValues) {
        Map<String, Object> map = new java.util.HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private static Optional<Map<String, Object>> byId(List<Map<String, Object>> entries, String id) {
        return entries.stream().filter(e -> id.equals(e.get("id"))).findFirst();
    }

    @Test
    void theCatalogNeedsAnAuthenticatedCallerAndListsWhatTheCallerMayRun() {
        get("/api/meta/queries", null).expectStatus().isUnauthorized();

        List<Map<String, Object>> ledger = catalog(bearer("ledger.read"), "en");
        assertThat(ledger).extracting(e -> e.get("id")).contains(TRIAL_BALANCE, "jabiz.ledger.account_activity")
            .doesNotContain(STOCK);
        assertThat(ledger).extracting(e -> (String) e.get("id")).isSorted();
        assertThat(catalog(bearer(), "en")).isEmpty();
        // Public templates have their own contract, even for a caller who may do everything.
        assertThat(catalog(admin(), "en")).extracting(e -> e.get("id")).contains(STOCK, TRIAL_BALANCE)
            .doesNotContain("commerce.public_catalog");
    }

    @Test
    @SuppressWarnings("unchecked")
    void anEntryDescribesTheTemplateInTheLanguageOfTheRequest() {
        Map<String, Object> balances = byId(catalog(bearer("ledger.read"), "zh"), TRIAL_BALANCE).orElseThrow();

        assertThat(balances).containsEntry("title", "试算表")
            .containsEntry("version", templates.find(TRIAL_BALANCE).orElseThrow().version())
            .containsEntry("timeSlice", withNulls("asOf", null, "knownAt", "knownAt"))
            .containsEntry("timeTravel", false)
            .containsEntry("report", Map.of("period", Map.of("from", "from", "to", "asOf"), "landscape", false))
            .containsEntry("defaultSort", Map.of("field", "accountCode", "asc", true));
        assertThat((String) balances.get("version")).hasSize(64);
        assertThat((List<String>) balances.get("filters")).contains("accountCode", "level");

        Map<String, Object> params = (Map<String, Object>) balances.get("params");
        assertThat(params).containsEntry("required", List.of("asOf")).containsEntry("additionalProperties", false);
        assertThat((Map<String, Object>) ((Map<String, Object>) params.get("properties")).get("asOf"))
            .containsEntry("type", "string").containsEntry("format", "date-time");

        List<Map<String, Object>> results = (List<Map<String, Object>>) balances.get("results");
        assertThat(results).extracting(r -> r.get("name")).startsWith("accountCode", "accountName");
        assertThat(results.getFirst()).containsEntry("label", "科目");
        assertThat((Map<String, Object>) results.getLast().get("kind")).containsEntry("type", "monetary");
    }

    @Test
    @SuppressWarnings("unchecked")
    void columnsWithoutTextOfTheirOwnShowTheirFieldsDisplayName() {
        Map<String, Object> stock = byId(catalog(bearer("commerce.stock.read"), "en"), STOCK).orElseThrow();

        assertThat(stock).containsEntry("title", "Stock availability").containsEntry("timeSlice", null)
            .containsEntry("report", withNulls("period", null, "landscape", false));
        Map<String, Object> labels = new java.util.LinkedHashMap<>();
        ((List<Map<String, Object>>) stock.get("results")).forEach(r -> labels.put((String) r.get("name"),
            r.get("label")));
        assertThat(labels).containsEntry("available", "Available").containsEntry("onHand", "On hand");
        Map<String, Object> warehouse = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>)
            stock.get("params")).get("properties")).get("warehouseCode");
        assertThat(warehouse).containsEntry("type", "string");
    }
}
