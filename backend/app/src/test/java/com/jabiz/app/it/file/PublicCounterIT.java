package com.jabiz.app.it.file;

import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dataset.PublicCounter;
import com.jabiz.runtime.publicread.PublicCounterEntities;
import com.jabiz.runtime.publicread.PublicCounters;
import com.jabiz.runtime.test.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Public counters (ROADMAP phase 17z; docs/design/15-public-access.md section 10; decision D65): anonymous visitors
 * count views of products on sale, once per visitor and day in the counter's zone, and nothing else is counted; the
 * counts are written added up, read through the platform's dataset with its permission, and no operation is recorded.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {"jabiz.public.enabled=true",
    "jabiz.public.file-decision-ttl=0s"})
class PublicCounterIT extends FileItSupport {

    private static final String BY_SKU = "it.product-view-by-sku";
    private static final String MINE = "urn:jabiz:dataset:it:mine:PublicCounterDay";

    /**
     * A counter whose keys' owner is the product's code (decision D65 item 7), and a dataset giving each reader the
     * counts of the keys they own, as an application gives a sponsor theirs.
     */
    @TestConfiguration
    static class OwnedCounts {

        @Bean
        PublicCounter itViewsBySku() {
            return PublicCounter.define(BY_SKU, c -> c.keys(CommerceEntities.PUBLIC_PRODUCT_DATASET, "productId")
                .owner("sku"));
        }

        @Bean
        DatasetDefinition itMyCounts(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define(MINE, d -> d
                .targetEntityType(PublicCounterEntities.ENTITY)
                .scope(s -> s.fixed("counterName", BY_SKU).fromContext("ownerKey", RequestContext::actorId))
                .permissions("it.counts.read", "it.counts.write")
                .policy(p -> p.readOnly(true).maxQueryBatchSize(100))
                .storage(s -> s.connectionPoolRef(pool)));
        }
    }

    private static final String VIEWS = "/api/public/counters/" + CommerceEntities.PRODUCT_VIEWS + "/";

    @Autowired
    PublicCounters counters;

    @Autowired
    MutableClock clock;

    private String admin() {
        return bearer("it-admin", "*");
    }

    private String product(boolean active) {
        return product(active, "CNT" + UUID.randomUUID().toString().replace("-", "").substring(0, 8)
            .toUpperCase(Locale.ROOT));
    }

    private String product(boolean active, String sku) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("sku", sku);
        attributes.put("productName", "Product " + sku);
        attributes.put("unitPrice", 100);
        attributes.put("active", active);
        return (String) post("/api/datasets/" + CommerceEntities.PRODUCT_DATASET + "/commit", admin(),
            Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", attributes))))
            .expectStatus().isOk().expectBody(LIST).returnResult().getResponseBody().getFirst().get("id");
    }

    private WebTestClient.ResponseSpec view(String key, String browser) {
        return client.post().uri(VIEWS + key).header(HttpHeaders.USER_AGENT, browser).exchange();
    }

    private void viewed(String key, String browser) {
        view(key, browser).expectStatus().isNoContent().expectBody().isEmpty();
    }

    /** The stored counts of the key, by day. */
    private static Map<LocalDate, Long> counted(String key) {
        Map<LocalDate, Long> days = new LinkedHashMap<>();
        query("SELECT day, hits FROM sys_public_counter_day WHERE counter_name = '" + CommerceEntities.PRODUCT_VIEWS
            + "' AND counter_key = '" + key + "' ORDER BY day").forEach(row ->
            days.put(((java.sql.Date) row.get("day")).toLocalDate(), ((Number) row.get("hits")).longValue()));
        return days;
    }

    private static long operations() {
        return ((Number) query("SELECT count(*) AS n FROM op_process").getFirst().get("n")).longValue();
    }

    @Test
    void countsEachVisitorOnceADayInTheCountersZone() {
        String product = product(true);
        long operations = operations();
        // 23:59 in Tokyo on 1 March.
        clock.set(Instant.parse("2026-03-01T14:59:00Z"));
        viewed(product, "Browser A");
        viewed(product, "Browser A");
        viewed(product.toUpperCase(Locale.ROOT), "Browser A");
        viewed(product, "Browser B");
        counters.flush().block();
        assertThat(counted(product)).containsExactly(Map.entry(LocalDate.parse("2026-03-01"), 2L));

        // Two minutes later it is 2 March in Tokyo: the same visitor counts again, for the new day.
        clock.set(Instant.parse("2026-03-01T15:01:00Z"));
        viewed(product, "Browser A");
        counters.flush().block();
        // A later write adds to the day's row.
        clock.set(Instant.parse("2026-03-01T15:05:00Z"));
        viewed(product, "Browser C");
        counters.flush().block();
        assertThat(counted(product)).containsExactly(Map.entry(LocalDate.parse("2026-03-01"), 2L),
            Map.entry(LocalDate.parse("2026-03-02"), 2L));

        // Counting is not an operation; nothing is written about the visitor.
        assertThat(operations()).isEqualTo(operations);
        assertThat(query("SELECT * FROM sys_public_counter_day WHERE counter_key = '" + product + "'").getFirst())
            .containsOnlyKeys("row_id", "counter_name", "counter_key", "day", "hits", "owner_key")
            .containsEntry("owner_key", null);
    }

    @Test
    void countsOnlyKeysOfRowsVisibleInThePublicDataset() {
        String offSale = product(false);
        String unknown = UUID.randomUUID().toString();
        viewed(offSale, "Browser A");
        viewed(unknown, "Browser A");
        viewed("not-a-product", "Browser A");
        viewed("1-2-3-4-5", "Browser A");
        counters.flush().block();
        assertThat(counted(offSale)).isEmpty();
        assertThat(counted(unknown)).isEmpty();
        assertThat(query("SELECT count(*) AS n FROM sys_public_counter_day WHERE counter_key IN ('not-a-product',"
            + " '1-2-3-4-5')").getFirst().get("n")).isEqualTo(0L);

        // A product taken off sale stops counting (here at once: the decision is not remembered).
        String product = product(true);
        viewed(product, "Browser A");
        post("/api/datasets/" + CommerceEntities.PRODUCT_DATASET + "/commit", admin(), Map.of("changes",
            List.of(Map.of("action", "UPDATE", "id", product, "version", 1, "attributes", Map.of("active", false)))))
            .expectStatus().isOk();
        viewed(product, "Browser B");
        counters.flush().block();
        assertThat(counted(product).values()).containsExactly(1L);
    }

    @Test
    void onlyDeclaredCountersArePostedToAndTheRestStaysReadOnly() {
        String product = product(true);
        client.post().uri("/api/public/counters/commerce.no-such-counter/" + product).exchange()
            .expectStatus().isNotFound();
        client.put().uri(VIEWS + product).exchange().expectStatus().isEqualTo(405)
            .expectHeader().value(HttpHeaders.ALLOW, allow -> assertThat(allow).contains("GET").contains("HEAD")
                .doesNotContain("POST"));
        client.post().uri("/api/public/queries/commerce.public.catalog").exchange().expectStatus().isEqualTo(405);
        client.post().uri("/api/public/counters/" + CommerceEntities.PRODUCT_VIEWS).exchange()
            .expectStatus().isEqualTo(405);
        // A stale token sent along does not matter.
        client.post().uri(VIEWS + product).header(HttpHeaders.AUTHORIZATION, "Bearer expired.token.value").exchange()
            .expectStatus().isNoContent();
    }

    @Test
    void theCountsAreReadWithThePlatformsPermission() {
        String product = product(true);
        viewed(product, "Browser A");
        counters.flush().block();
        Map<String, Object> query = Map.of("filters", List.of(
            Map.of("field", "counterName", "op", "eq", "value", CommerceEntities.PRODUCT_VIEWS),
            Map.of("field", "counterKey", "op", "eq", "value", product)));
        String path = "/api/datasets/" + PublicCounterEntities.DATASET + "/query";
        post(path, bearer("it-reader", "commerce.product.read"), query).expectStatus().isForbidden();
        Map<String, Object> page = post(path, bearer("it-analyst", PublicCounterEntities.READ), query)
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(page.get("items")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .singleElement().satisfies(item -> assertThat(item).asString().contains("hits=1"));
    }

    @Test
    void eachOwnerReadsTheCountsOfTheirKeysOnly() {
        String mine = "OWN" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        String theirs = "OWN" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        String myProduct = product(true, mine);
        String theirProduct = product(true, theirs);
        for (String product : List.of(myProduct, theirProduct)) {
            client.post().uri("/api/public/counters/" + BY_SKU + "/" + product).header(HttpHeaders.USER_AGENT, "A")
                .exchange().expectStatus().isNoContent();
        }
        counters.flush().block();
        // The owner is stored with the counts of an owned counter, and not with those of the others.
        assertThat(query("SELECT owner_key FROM sys_public_counter_day WHERE counter_name = '" + BY_SKU
            + "' AND counter_key = '" + myProduct + "'")).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("owner_key", mine));

        String path = "/api/datasets/" + MINE + "/query";
        Map<String, Object> page = post(path, bearer(mine, "it.counts.read"), Map.of())
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(page.get("items")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .singleElement().satisfies(item -> assertThat(item).asString().contains(myProduct)
                .doesNotContain(theirProduct));
        // Asking for the other's key through a filter finds nothing.
        Map<String, Object> other = post(path, bearer(mine, "it.counts.read"), Map.of("filters", List.of(
            Map.of("field", "counterKey", "op", "eq", "value", theirProduct))))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(other.get("items")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).isEmpty();
    }
}
