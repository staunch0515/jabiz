package com.jabiz.app.it.inbound;

import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.app.it.security.SecurityItSupport;
import com.jabiz.event.EventSubscription;
import com.jabiz.inbound.BodyHmacSignature;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.event.OutboxDeliverer;
import com.jabiz.runtime.inbound.InboundEntities;
import com.jabiz.runtime.inbound.InboundEvents;
import com.jabiz.runtime.inbound.LoadInboundEvent;
import com.jabiz.runtime.inbound.ReceivedEvent;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Inbound webhooks of a sender that signs the body only (decision D57; the provider {@code demo-shop} of the test
 * configuration, with Shopify's headers): the event's id, topic and shop come in unsigned headers. On PostgreSQL: the
 * event is kept without a signed time, with its configured metadata, and published under a type whose {@code /} became
 * a {@code .}; the same body of the same topic under another id is a duplicate; one id under two topics is two events;
 * a failed signature is answered 401 and anything else wrong 400, leaving nothing behind.
 */
@SpringBootTest(properties = {
    "it.sql-log.enabled=true",
    "jabiz.inbound-webhooks.providers.demo-shop.rate-limit.per-minute=10000"})
class BodyHmacInboundIT extends SecurityItSupport {

    static final String SECRET = "test_only_demo_shop_secret_0123456789ab";
    static final String PATH = "/api/inbound/demo-shop";
    static final String SHOP = "demo-shop.myshopify.com";

    /** A consumer of paid orders that keeps what it was given, by the event's id. */
    @TestConfiguration
    static class ShopConsumer {

        static final Map<String, ReceivedEvent> HANDLED = new ConcurrentHashMap<>();

        record ShopInput(@NotBlank String inboundEventId) {}

        static final ProcessDefinition<ShopInput, ShopInput, ProcessContext> PROCESS = ProcessDefinition.define(
            "IT_SHOP_ORDER_PAID", 1, ShopInput.class, ShopInput.class, ProcessContext.class, pb -> pb
                .permissions("it.shop")
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("input", input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get("input", ShopInput.class))
                .step("Load the event", LoadInboundEvent.of("demo-shop", "orders/paid",
                    ctx -> ctx.get("input", ShopInput.class).inboundEventId(), "event"))
                // The spelling of the subscription's event type names the same type.
                .step("Load it again", LoadInboundEvent.of("demo-shop", "orders.paid",
                    ctx -> ctx.get("input", ShopInput.class).inboundEventId(), "again"))
                .compute("Keep it", (metadata, ctx) -> {
                    ReceivedEvent event = ctx.get("event", ReceivedEvent.class);
                    if (!event.equals(ctx.get("again", ReceivedEvent.class))) {
                        throw new IllegalStateException("loaded differently");
                    }
                    HANDLED.put(event.externalEventId(), event);
                }));

        @Bean
        ProcessDefinition<ShopInput, ShopInput, ProcessContext> shopProcess() {
            return PROCESS;
        }

        @Bean
        EventSubscription<ShopInput> shopSubscription() {
            return EventSubscription.of("it.shop-order-paid", InboundEvents.type("demo-shop", "orders/paid"), PROCESS,
                event -> new ShopInput(InboundEvents.ref(event).inboundEventId()));
        }
    }

    @Autowired
    OutboxDeliverer deliverer;

    // ---- requests -------------------------------------------------------------------------------------------------

    static String order(String name) {
        return "{\"id\":820982911946154508,\"name\":\"" + name + "\",\"financial_status\":\"paid\","
            + "\"total_price\":\"1600.00\",\"currency\":\"JPY\"}";
    }

    static Map<String, String> headers(String body, String eventId, String topic) {
        Map<String, String> headers = new LinkedHashMap<>(BodyHmacSignature.DEFAULT.sign(SECRET, 0,
            body.getBytes(StandardCharsets.UTF_8)));
        headers.put("X-Shopify-Event-Id", eventId);
        headers.put("X-Shopify-Topic", topic);
        headers.put("X-Shopify-Shop-Domain", SHOP);
        headers.put("X-Shopify-Webhook-Id", "delivery-" + eventId);
        headers.put("X-Shopify-Triggered-At", "2026-10-26T00:00:00Z");
        return headers;
    }

    WebTestClient.ResponseSpec send(String body, Map<String, String> headers) {
        WebTestClient.RequestBodySpec request = client.post().uri(PATH).contentType(MediaType.APPLICATION_JSON);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            request = request.header(header.getKey(), header.getValue());
        }
        return request.bodyValue(body.getBytes(StandardCharsets.UTF_8)).exchange();
    }

    void accepted(String body, Map<String, String> headers) {
        send(body, headers).expectStatus().isOk().expectBody().json("{\"received\":true}");
    }

    static List<Map<String, Object>> stored(String eventId) {
        return query("SELECT e.inbound_event_id, e.event_type, e.signed_time, e.livemode, e.replay_guard,"
            + " e.metadata::text AS metadata, b.body FROM sys_inbound_event e JOIN sys_inbound_event_body b"
            + " ON b.inbound_event_id = e.inbound_event_id WHERE e.provider = 'demo-shop' AND e.external_event_id = ?"
            + " ORDER BY e.event_type", eventId);
    }

    static List<Map<String, Object>> outbox(String eventId) {
        return query("SELECT e.event_type, e.payload::text AS payload FROM sys_outbox_event e"
            + " JOIN sys_inbound_event i ON i.inbound_event_id = e.payload->>'inboundEventId'"
            + " WHERE i.provider = 'demo-shop' AND i.external_event_id = ?", eventId);
    }

    static long count(String sql) {
        return ((Number) query(sql).getFirst().get("n")).longValue();
    }

    // ---- tests ----------------------------------------------------------------------------------------------------

    @Test
    void aSignedBodyIsKeptWithItsShopAndNoSignedTimeAndReachesItsConsumer() {
        String eventId = unique("ev");
        String body = order(unique("#"));
        accepted(body, headers(body, eventId, "orders/paid"));

        assertThat(stored(eventId)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("event_type", "orders/paid").containsEntry("signed_time", null)
                .containsEntry("livemode", null).containsEntry("replay_guard", true).containsEntry("body", body);
            // Only the configured header: not the delivery id, the time or the signature.
            assertThat((String) row.get("metadata")).isEqualTo("{\"X-Shopify-Shop-Domain\": \"" + SHOP + "\"}");
        });
        assertThat(outbox(eventId)).singleElement().satisfies(event -> {
            assertThat(event).containsEntry("event_type", "jabiz.inbound.demo-shop.orders.paid");
            assertThat((String) event.get("payload")).contains("\"eventType\": \"orders/paid\"", SHOP)
                .doesNotContain("financial_status");
        });

        deliverer.deliverPending().block();
        assertThat(ShopConsumer.HANDLED.get(eventId)).satisfies(event -> {
            assertThat(event.signedTime()).isNull();
            assertThat(event.receivedTime()).isNotNull();
            assertThat(event.eventType()).isEqualTo("orders/paid");
            assertThat(event.metadata()).containsExactlyEntriesOf(Map.of("X-Shopify-Shop-Domain", SHOP));
            // By name in any case, as HTTP has it.
            assertThat(event.metadata()).containsEntry("x-shopify-shop-domain", SHOP);
            assertThat(event.at("financial_status")).isEqualTo("paid");
        });
    }

    @Test
    void theSameBodyOfATopicUnderAnotherIdIsADuplicate() {
        String eventId = unique("ev");
        String body = order(unique("#"));
        accepted(body, headers(body, eventId, "orders/paid"));
        // The sender's retry: the same id.
        accepted(body, headers(body, eventId, "orders/paid"));
        // A replay of the captured request with an id of the replayer's choosing.
        String replayed = unique("ev");
        accepted(body, headers(body, replayed, "orders/paid"));
        assertThat(stored(eventId)).hasSize(1);
        assertThat(stored(replayed)).isEmpty();
        assertThat(outbox(eventId)).hasSize(1);
    }

    @Test
    void oneIdUnderTwoTopicsIsTwoEvents() {
        // One action of the sender gives its topics the same event id.
        String eventId = unique("ev");
        String body = order(unique("#"));
        accepted(body, headers(body, eventId, "orders/paid"));
        accepted(body, headers(body, eventId, "app/uninstalled"));
        assertThat(stored(eventId)).extracting(row -> row.get("event_type"))
            .containsExactly("app/uninstalled", "orders/paid");
        assertThat(outbox(eventId)).extracting(row -> row.get("event_type")).containsExactlyInAnyOrder(
            "jabiz.inbound.demo-shop.orders.paid", "jabiz.inbound.demo-shop.app.uninstalled");
    }

    @Test
    void aFailedSignatureIs401AndAnythingElseWrong400AndNothingIsKept() {
        long inbound = count("SELECT count(*) AS n FROM sys_inbound_event WHERE provider = 'demo-shop'");
        long operations = count("SELECT count(*) AS n FROM op_process WHERE process_name = 'INBOUND_RECEIVE'");
        String eventId = unique("ev");
        String body = order(unique("#"));
        Map<String, String> headers = headers(body, eventId, "orders/paid");

        // Another body, no signature, a signature that is not Base64.
        byte[] answer = send(order("#altered"), headers).expectStatus().isUnauthorized().expectBody().returnResult()
            .getResponseBody();
        assertThat(new String(answer, StandardCharsets.UTF_8)).contains("INBOUND_REJECTED")
            .doesNotContain("no_match");
        Map<String, String> unsigned = new LinkedHashMap<>(headers);
        unsigned.remove(BodyHmacSignature.DEFAULT_HEADER);
        send(body, unsigned).expectStatus().isUnauthorized();
        Map<String, String> malformed = new LinkedHashMap<>(headers);
        malformed.put(BodyHmacSignature.DEFAULT_HEADER, "not base64!");
        send(body, malformed).expectStatus().isUnauthorized();
        // Signed, but no event id, no topic, two shops, not JSON.
        Map<String, String> noId = new LinkedHashMap<>(headers);
        noId.remove("X-Shopify-Event-Id");
        send(body, noId).expectStatus().isBadRequest();
        Map<String, String> noTopic = new LinkedHashMap<>(headers);
        noTopic.remove("X-Shopify-Topic");
        send(body, noTopic).expectStatus().isBadRequest();
        client.post().uri(PATH).contentType(MediaType.APPLICATION_JSON)
            .headers(h -> {
                headers.forEach(h::add);
                h.add("X-Shopify-Shop-Domain", "other.myshopify.com");
            })
            .bodyValue(body.getBytes(StandardCharsets.UTF_8)).exchange().expectStatus().isBadRequest();
        String notJson = "name=#1";
        send(notJson, headers(notJson, eventId, "orders/paid")).expectStatus().isBadRequest();

        assertThat(count("SELECT count(*) AS n FROM sys_inbound_event WHERE provider = 'demo-shop'"))
            .isEqualTo(inbound);
        assertThat(count("SELECT count(*) AS n FROM op_process WHERE process_name = 'INBOUND_RECEIVE'"))
            .isEqualTo(operations);
    }

    @Test
    void topicsNotListedAreAnsweredButNotKept() {
        String eventId = unique("ev");
        String body = order(unique("#"));
        accepted(body, headers(body, eventId, "products/update"));
        // Whatever its metadata headers: the topic is not wanted, so the sender must not retry it.
        client.post().uri(PATH).contentType(MediaType.APPLICATION_JSON)
            .headers(h -> {
                headers(body, eventId, "products/update").forEach(h::add);
                h.add("X-Shopify-Shop-Domain", "other.myshopify.com");
            })
            .bodyValue(body.getBytes(StandardCharsets.UTF_8)).exchange().expectStatus().isOk();
        assertThat(stored(eventId)).isEmpty();
    }

    @Test
    void theKeysThatMakeDuplicatesAreTheOnesTheReceiverKnows() {
        assertThat(query("SELECT pg_get_constraintdef(oid) AS def FROM pg_constraint WHERE conname = ?",
            InboundEntities.EXTERNAL_KEY)).singleElement().satisfies(row -> assertThat(row.get("def"))
            .isEqualTo("UNIQUE (provider, event_type, external_event_id)"));
        assertThat(query("SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema() AND indexname = ?",
            InboundEntities.BODY_KEY)).singleElement().satisfies(row -> assertThat((String) row.get("indexdef"))
            .contains("UNIQUE INDEX", "(provider, event_type, body_sha256)", "WHERE replay_guard"));
    }

    @Test
    void theInboundTablesAreNeverUpdatedOrDeleted() {
        SqlStatementLog.STATEMENTS.clear();
        String eventId = unique("ev");
        String body = order(unique("#"));
        accepted(body, headers(body, eventId, "orders/paid"));
        accepted(body, headers(body, unique("ev"), "orders/paid"));
        deliverer.deliverPending().block();
        assertThat(SqlStatementLog.STATEMENTS).anyMatch(sql -> sql.contains("sys_inbound_event"));
        assertThat(SqlStatementLog.STATEMENTS).noneMatch(sql -> sql.matches(
            "(?is).*(UPDATE|DELETE\\s+FROM|TRUNCATE)\\s+sys_inbound_event(_body)?\\b.*"));
        assertThatThrownBy(() -> execute("UPDATE sys_inbound_event SET metadata = NULL WHERE external_event_id = ?",
            eventId)).hasMessageContaining("append-only");
    }
}
