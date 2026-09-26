package com.jabiz.app.it.observability;

import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.test.TestTokens;
import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The platform's own observations (docs/design/13-observability-ops.md): processes, sub-processes, dataset reads and
 * commits, and SQL templates each become a timer and a span nested in the enclosing unit, tagged with names and the
 * outcome only, never with keys, actors or values.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ObservabilityIT extends PostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    /** One stopped observation as a handler saw it. */
    record Seen(String name, String contextualName, Map<String, String> tags, String parent) {}

    static final List<Seen> SEEN = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class Recording {
        @Bean
        ObservationHandler<Observation.Context> recordingHandler() {
            return new ObservationHandler<>() {
                @Override
                public void onStop(Observation.Context context) {
                    Map<String, String> tags = new java.util.TreeMap<>();
                    for (KeyValue kv : context.getLowCardinalityKeyValues()) {
                        tags.put(kv.getKey(), kv.getValue());
                    }
                    ObservationView parent = context.getParentObservation();
                    SEEN.add(new Seen(context.getName(), context.getContextualName(), tags,
                        parent == null ? null : parent.getContextView().getName()));
                }

                @Override
                public boolean supportsContext(Observation.Context context) {
                    return true;
                }
            };
        }
    }

    @Autowired
    ApplicationContext context;

    @Autowired
    JwtService tokens;

    @Autowired
    MeterRegistry meters;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
    }

    private WebTestClient.ResponseSpec post(String path, Object body) {
        return client.post().uri(path).header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(tokens, "it-observer", "*"))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private List<Seen> seen(String name) {
        return SEEN.stream().filter(s -> s.name().equals(name)).toList();
    }

    @Test
    void processesDatasetsAndTemplatesAreObservedWithNamesAndOutcomesOnly() {
        String code = "O" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        post("/api/datasets/" + CommerceEntities.WAREHOUSE_DATASET + "/commit", Map.of("changes", List.of(Map.of(
            "action", "INSERT", "attributes", Map.of("warehouseCode", code, "warehouseName", "W", "active", true)))))
            .expectStatus().isOk();
        post("/api/datasets/" + CommerceEntities.PRODUCT_DATASET + "/commit", Map.of("changes", List.of(Map.of(
            "action", "INSERT", "attributes", Map.of("sku", code, "productName", "P", "unitPrice", 10,
                "active", true))))).expectStatus().isOk();
        post("/api/processes/STOCK_RECEIVE/latest", Map.of("warehouseCode", code, "sku", code, "quantity", 1))
            .expectStatus().isOk();
        SEEN.clear();

        Map<String, Object> placed = post("/api/processes/ORDER_PLACE/latest", Map.of("orderNo", code,
            "customerCode", code, "warehouseCode", code, "lines", List.of(Map.of("sku", code, "quantity", 1))))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        post("/api/processes/ORDER_PLACE/latest", Map.of("orderNo", code + "-2", "customerCode", code,
            "warehouseCode", code, "lines", List.of(Map.of("sku", code, "quantity", 1))))
            .expectStatus().isEqualTo(422);
        post("/api/queries/commerce.stock_availability", Map.of("params", Map.of("warehouseCode", code)))
            .expectStatus().isOk();

        // The process, and the reads and the commit inside it, nested.
        assertThat(seen(PlatformObservations.PROCESS)).extracting(Seen::tags).contains(
            Map.of("process", "ORDER_PLACE", "version", "1", "outcome", "success", "status", "none"),
            Map.of("process", "ORDER_PLACE", "version", "1", "outcome", "rejected", "status", "422"));
        assertThat(seen(PlatformObservations.DATASET_QUERY)).isNotEmpty()
            .allSatisfy(s -> assertThat(s.parent()).isEqualTo(PlatformObservations.PROCESS));
        // The registered changes are committed through each entity's dataset.
        assertThat(seen(PlatformObservations.DATASET_COMMIT))
            .allSatisfy(s -> assertThat(s.parent()).isEqualTo(PlatformObservations.PROCESS))
            .extracting(s -> s.tags().get("entity") + ":" + s.tags().get("outcome"))
            .containsExactlyInAnyOrder(CommerceEntities.ORDER + ":success", CommerceEntities.ORDER_LINE + ":success",
                CommerceEntities.STOCK_LEVEL + ":success");
        assertThat(seen(PlatformObservations.TEMPLATE)).singleElement().satisfies(s -> assertThat(s.tags())
            .containsEntry("template", "commerce.stock_availability").containsEntry("outcome", "success"));

        // No key, actor or value in any tag of the platform's observations.
        String orderId = String.valueOf(((Map<?, ?>) placed.get("output")).get("orderId"));
        assertThat(SEEN).filteredOn(s -> s.name().startsWith("jabiz."))
            .flatExtracting(s -> List.copyOf(s.tags().values()))
            .doesNotContain(orderId, code, code + "-2", "it-observer");

        // Each is also a timer.
        Timer rejected = meters.find(PlatformObservations.PROCESS)
            .tags("process", "ORDER_PLACE", "outcome", "rejected", "status", "422").timer();
        assertThat(rejected).isNotNull();
        assertThat(rejected.count()).isPositive();
    }

    @Test
    void aSubProcessIsObservedInsideItsParent() {
        String code = "S" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        // The sale posting of ORDER_SHIP needs these accounts; this class's schema is its own.
        for (String[] account : List.of(new String[] {"1130", "ASSET"}, new String[] {"4120", "REVENUE"})) {
            post("/api/processes/LEDGER_ACCOUNT_OPEN/latest", Map.of("accountCode", account[0],
                "accountName", account[0], "accountType", account[1])).expectStatus().value(status ->
                assertThat(status).isIn(200, 400));
        }
        post("/api/datasets/" + CommerceEntities.WAREHOUSE_DATASET + "/commit", Map.of("changes", List.of(Map.of(
            "action", "INSERT", "attributes", Map.of("warehouseCode", code, "warehouseName", "W", "active", true)))))
            .expectStatus().isOk();
        post("/api/datasets/" + CommerceEntities.PRODUCT_DATASET + "/commit", Map.of("changes", List.of(Map.of(
            "action", "INSERT", "attributes", Map.of("sku", code, "productName", "P", "unitPrice", 10,
                "active", true))))).expectStatus().isOk();
        post("/api/processes/STOCK_RECEIVE/latest", Map.of("warehouseCode", code, "sku", code, "quantity", 1))
            .expectStatus().isOk();
        Map<String, Object> placed = post("/api/processes/ORDER_PLACE/latest", Map.of("orderNo", code,
            "customerCode", code, "warehouseCode", code, "lines", List.of(Map.of("sku", code, "quantity", 1))))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        SEEN.clear();

        post("/api/processes/ORDER_SHIP/latest", Map.of("orderId",
            ((Map<?, ?>) placed.get("output")).get("orderId"))).expectStatus().isOk();

        assertThat(seen(PlatformObservations.PROCESS)).filteredOn(s -> "LEDGER_POST".equals(s.tags().get("process")))
            .singleElement().satisfies(s -> {
                assertThat(s.contextualName()).isEqualTo("sub-process LEDGER_POST");
                assertThat(s.parent()).isEqualTo(PlatformObservations.PROCESS);
            });
        assertThat(seen(PlatformObservations.DATASET_COMMIT)).isNotEmpty()
            .allSatisfy(s -> assertThat(s.parent()).isEqualTo(PlatformObservations.PROCESS));
    }
}
