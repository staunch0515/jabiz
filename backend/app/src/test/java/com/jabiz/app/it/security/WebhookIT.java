package com.jabiz.app.it.security;

import com.jabiz.app.it.fixture.ItTaskFixtures;
import com.jabiz.runtime.event.OutboxDeliverer;
import com.jabiz.webhook.WebhookSignature;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Webhooks (docs/design/11-ledger-events-jobs.md section 2.4, decision D33) against a local receiver: each event of
 * the subscribed type is POSTed once, signed; a failed answer is recorded and the event sent again later; each
 * subscription is delivered on its own, so one failing receiver does not make another receive an event twice.
 */
@SpringBootTest(properties = {"jabiz.events.delivery.initial-backoff=0s"})
class WebhookIT extends SecurityItSupport {

    static final String SECRET = "it-webhook-secret-0123456789abcdef";
    static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    /** Answers to give the "flaky" receiver before it answers 204. */
    static final AtomicInteger FAILURES_LEFT = new AtomicInteger();
    static final HttpServer SERVER = server();

    record Received(String path, Map<String, String> headers, String body) {}

    static HttpServer server() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, String> headers = new HashMap<>();
                exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), values.getFirst()));
                String path = exchange.getRequestURI().getPath();
                boolean fail = path.equals("/flaky") && FAILURES_LEFT.getAndUpdate(n -> Math.max(0, n - 1)) > 0;
                if (!fail) {
                    RECEIVED.add(new Received(path, headers, body));
                }
                exchange.sendResponseHeaders(fail ? 503 : 204, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void webhooks(DynamicPropertyRegistry registry) {
        String base = "http://localhost:" + SERVER.getAddress().getPort();
        registry.add("jabiz.webhooks.allowed-hosts", () -> "localhost");
        registry.add("jabiz.webhooks.subscriptions[0].name", () -> "it-steady");
        registry.add("jabiz.webhooks.subscriptions[0].event-type", () -> "jabiz.task.created");
        registry.add("jabiz.webhooks.subscriptions[0].url", () -> base + "/steady");
        registry.add("jabiz.webhooks.subscriptions[0].secret", () -> SECRET);
        registry.add("jabiz.webhooks.subscriptions[1].name", () -> "it-flaky");
        registry.add("jabiz.webhooks.subscriptions[1].event-type", () -> "jabiz.task.created");
        registry.add("jabiz.webhooks.subscriptions[1].url", () -> base + "/flaky");
        registry.add("jabiz.webhooks.subscriptions[1].secret", () -> SECRET);
    }

    @AfterAll
    static void stop() {
        SERVER.stop(0);
    }

    @Autowired
    OutboxDeliverer deliverer;

    @Test
    void anEventIsPostedSignedOncePerSubscriptionAndRetriedWhereItFailed() {
        // Earlier events (other tests') delivered first, so that the one failure falls on this test's event.
        deliverer.deliverPending().block();
        FAILURES_LEFT.set(1);
        String key = unique("WH");
        Map<String, Object> input = new HashMap<>(Map.of("key", key, "item", key));
        input.put("permission", ItTaskFixtures.PERMISSION);
        post("/api/processes/IT_TASK_OPEN/latest", bearer(ItTaskFixtures.PERMISSION), input).expectStatus().isOk();

        deliverer.deliverPending().block();
        List<Received> first = received(key);
        assertThat(first).extracting(Received::path).containsExactly("/steady");
        // The flaky receiver's failure is recorded for its subscription alone.
        String eventId = first.getFirst().headers().get("x-jabiz-event-id");
        assertThat(query("SELECT consumer FROM sys_outbox_attempt WHERE event_id = ?::uuid", eventId))
            .extracting(row -> row.get("consumer")).containsExactly("jabiz.webhook.it-flaky");

        deliverer.deliverPending().block();
        deliverer.deliverPending().block();
        List<Received> all = received(key);
        assertThat(all).extracting(Received::path).containsExactlyInAnyOrder("/steady", "/flaky");

        for (Received r : all) {
            assertThat(r.headers()).containsEntry("x-jabiz-event-type", "jabiz.task.created")
                .containsEntry("x-jabiz-event-id", eventId).containsEntry("content-type", "application/json");
            long timestamp = Long.parseLong(r.headers().get("x-jabiz-timestamp"));
            assertThat(WebhookSignature.matches(SECRET, timestamp, r.body().getBytes(StandardCharsets.UTF_8),
                r.headers().get("x-jabiz-signature"))).isTrue();
            assertThat(r.body()).contains("\"eventId\":\"" + eventId + "\"", "\"eventType\":\"jabiz.task.created\"",
                "\"payload\":{");
        }
        assertThat(query("SELECT consumer FROM sys_event_consumption WHERE event_id = ?::uuid ORDER BY consumer",
            eventId)).extracting(row -> row.get("consumer"))
            .containsExactly("jabiz.webhook.it-flaky", "jabiz.webhook.it-steady");
    }

    @Test
    void noCallerCanHaveThePlatformSendAnEvent() {
        String key = unique("WX");
        Map<String, Object> input = new HashMap<>(Map.of("key", key, "item", key));
        input.put("permission", ItTaskFixtures.PERMISSION);
        post("/api/processes/IT_TASK_OPEN/latest", bearer(ItTaskFixtures.PERMISSION), input).expectStatus().isOk();
        String eventId = (String) query("SELECT e.event_id::text AS id FROM sys_outbox_event e"
            + " JOIN sys_task_version t ON t.task_id::text = e.payload->>'taskId'"
            + " WHERE t.source_key = ? AND t.version_no = 1", key).getFirst().get("id");
        int before = RECEIVED.size();

        // Even an administrator (every permission) cannot run the delivery: the event is read from the outbox and the
        // step runs for the platform's delivery only.
        post("/api/processes/WEBHOOK_DELIVER/latest", admin(), Map.of("subscription", "it-steady", "eventId", eventId))
            .expectStatus().isForbidden();
        assertThat(RECEIVED).hasSize(before);
    }

    /** What the receivers took for the task opened with {@code key}. */
    static List<Received> received(String key) {
        List<String> ids = query("SELECT e.event_id::text AS id FROM sys_outbox_event e"
            + " JOIN sys_task_version t ON t.task_id::text = e.payload->>'taskId'"
            + " WHERE e.event_type = 'jabiz.task.created' AND t.source_key = ? AND t.version_no = 1", key)
            .stream().map(row -> (String) row.get("id")).toList();
        return RECEIVED.stream().filter(r -> ids.contains(r.headers().get("x-jabiz-event-id"))).toList();
    }
}
