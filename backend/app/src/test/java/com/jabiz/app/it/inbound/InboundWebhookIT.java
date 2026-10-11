package com.jabiz.app.it.inbound;

import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.app.it.fixture.ItTaskFixtures;
import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.app.it.security.SecurityItSupport;
import com.jabiz.event.EventSubscription;
import com.jabiz.inbound.HmacSha256Signature;
import com.jabiz.inbound.StripeSignature;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.event.OutboxDeliverer;
import com.jabiz.runtime.inbound.InboundEntities;
import com.jabiz.runtime.inbound.InboundEvents;
import com.jabiz.runtime.inbound.LoadInboundEvent;
import com.jabiz.runtime.inbound.ReceivedEvent;
import com.jabiz.webhook.WebhookSignature;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Inbound webhooks (docs/design/11-ledger-events-jobs.md section 2.5, decision D37) end to end on PostgreSQL: the
 * endpoint verifies, stores the body exactly as received and publishes a reference through the outbox; the sample's
 * consumer records the payment on its order once; what is not signed, too old, too new, too large, not UTF-8 or not
 * JSON is refused with one and the same answer and leaves nothing behind. The server listens on a real port so that the
 * platform's own outbound webhook can be received by its inbound endpoint (the formats of D33 and D37 agree).
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
    "it.sql-log.enabled=true",
    "jabiz.events.delivery.initial-backoff=0s",
    "jabiz.inbound-webhooks.providers.demo-pay.rate-limit.per-minute=10000",
    "jabiz.inbound-webhooks.providers.demo-pay.secrets[0]=whsec_test_only_demo_pay_0123456789abcdef",
    "jabiz.inbound-webhooks.providers.demo-pay.secrets[1]=whsec_test_only_demo_pay_rotated_secret_00",
    "jabiz.inbound-webhooks.providers.it-limited.scheme=stripe",
    "jabiz.inbound-webhooks.providers.it-limited.secrets[0]=whsec_test_only_limited_0123456789abcdef",
    "jabiz.inbound-webhooks.providers.it-limited.event-types[0]=payment_intent.succeeded",
    "jabiz.inbound-webhooks.providers.it-limited.rate-limit.per-minute=3",
    "jabiz.inbound-webhooks.providers.it-peer.scheme=hmac-sha256",
    "jabiz.inbound-webhooks.providers.it-peer.secrets[0]=it-peer-webhook-secret-0123456789abcdef",
    "jabiz.inbound-webhooks.providers.it-peer.event-types[0]=jabiz.task.created",
    "jabiz.inbound-webhooks.providers.it-peer.rate-limit.per-minute=10000",
    "jabiz.webhooks.allowed-hosts=localhost"})
class InboundWebhookIT extends SecurityItSupport {

    static final String SECRET = "whsec_test_only_demo_pay_0123456789abcdef";
    static final String ROTATED = "whsec_test_only_demo_pay_rotated_secret_00";
    static final String PATH = "/api/inbound/demo-pay";
    static final int PORT = freePort();

    @DynamicPropertySource
    static void server(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        // One source for the whole list: Spring binds a list from one property source only.
        registry.add("jabiz.webhooks.subscriptions[0].name", () -> "it-self");
        registry.add("jabiz.webhooks.subscriptions[0].event-type", () -> "jabiz.task.created");
        registry.add("jabiz.webhooks.subscriptions[0].secret", () -> "it-peer-webhook-secret-0123456789abcdef");
        registry.add("jabiz.webhooks.subscriptions[0].url", () -> "http://localhost:" + PORT + "/api/inbound/it-peer");
    }

    /** A port free just now; binding is tried again a few times, since the system may refuse one at random. */
    static int freePort() {
        IOException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try (ServerSocket socket = new ServerSocket(0)) {
                socket.setReuseAddress(true);
                return socket.getLocalPort();
            } catch (IOException e) {
                last = e;
            }
        }
        throw new UncheckedIOException(last);
    }

    /** A consumer of the peer's events that fails as often as asked before it handles one. */
    @TestConfiguration
    static class PeerConsumer {

        static final AtomicInteger FAILURES_LEFT = new AtomicInteger();
        static final List<String> HANDLED = new CopyOnWriteArrayList<>();

        record PeerInput(@NotBlank String inboundEventId) {}

        static final ProcessDefinition<PeerInput, PeerInput, ProcessContext> PROCESS = ProcessDefinition.define(
            "IT_PEER_EVENT", 1, PeerInput.class, PeerInput.class, ProcessContext.class, pb -> pb
                .permissions("it.peer")
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("input", input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get("input", PeerInput.class))
                .step("Load the event", LoadInboundEvent.of("it-peer", "jabiz.task.created",
                    ctx -> ctx.get("input", PeerInput.class).inboundEventId(), "event"))
                .compute("Handle it", (metadata, ctx) -> {
                    if (FAILURES_LEFT.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                        throw new IllegalStateException("the first attempt fails");
                    }
                    HANDLED.add(ctx.get("event", ReceivedEvent.class).externalEventId());
                }));

        @Bean
        ProcessDefinition<PeerInput, PeerInput, ProcessContext> peerProcess() {
            return PROCESS;
        }

        @Bean
        EventSubscription<PeerInput> peerSubscription() {
            return EventSubscription.of("it.peer-received", InboundEvents.type("it-peer", "jabiz.task.created"),
                PROCESS, event -> new PeerInput(InboundEvents.ref(event).inboundEventId()));
        }
    }

    @Autowired
    OutboxDeliverer deliverer;

    @Autowired
    com.jabiz.runtime.process.ProcessExecutor executor;

    @BeforeEach
    void catalog() {
        if (query("SELECT 1 FROM warehouse_version WHERE warehouse_code = 'PAY'").isEmpty()) {
            insert(CommerceEntities.WAREHOUSE_DATASET, Map.of("warehouseCode", "PAY", "warehouseName", "Payments",
                "active", true), null);
            insert(CommerceEntities.PRODUCT_DATASET, Map.of("sku", "PAY-1", "productName", "Paid tea",
                "unitPrice", 500, "active", true), null);
            post("/api/processes/STOCK_RECEIVE/latest", admin(), Map.of("warehouseCode", "PAY", "sku", "PAY-1",
                "quantity", 1000)).expectStatus().isOk();
        }
    }

    // ---- requests -------------------------------------------------------------------------------------------------

    static String payment(String eventId, String orderNo, long amount, String paymentId) {
        return "{\"id\":\"" + eventId + "\",\"object\":\"event\",\"type\":\"payment_intent.succeeded\","
            + "\"livemode\":false,\"data\":{\"object\":{\"id\":\"" + paymentId + "\",\"object\":\"payment_intent\","
            + "\"amount_received\":" + amount + ",\"currency\":\"jpy\",\"metadata\":{\"orderNo\":\"" + orderNo
            + "\"}}}}";
    }

    /** The Stripe-Signature of the body, signed now (the application's clock) with the secret. */
    String signature(String secret, byte[] body) {
        return signature(secret, clock.instant().getEpochSecond(), body);
    }

    static String signature(String secret, long timestamp, byte[] body) {
        return new StripeSignature().sign(secret, timestamp, body).get(StripeSignature.HEADER);
    }

    WebTestClient.ResponseSpec send(String path, byte[] body, Map<String, String> headers) {
        WebTestClient.RequestBodySpec request = client.post().uri(path).contentType(MediaType.APPLICATION_JSON);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            request = request.header(header.getKey(), header.getValue());
        }
        return request.bodyValue(body).exchange();
    }

    WebTestClient.ResponseSpec send(byte[] body, String signature) {
        return send(PATH, body, signature == null ? Map.of() : Map.of(StripeSignature.HEADER, signature));
    }

    WebTestClient.ResponseSpec send(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return send(bytes, signature(SECRET, bytes));
    }

    void accepted(String body) {
        send(body).expectStatus().isOk().expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
            .expectBody().json("{\"received\":true}");
    }

    /** The response of a rejected request; every rejection must answer the very same. */
    byte[] rejected(WebTestClient.ResponseSpec response) {
        return response.expectStatus().isBadRequest().expectBody().returnResult().getResponseBody();
    }

    String placeOrder(String orderNo, int quantity) {
        post("/api/processes/ORDER_PLACE/latest", admin(), Map.of("orderNo", orderNo, "customerCode", "PAY-CUST",
            "warehouseCode", "PAY", "lines", List.of(Map.of("sku", "PAY-1", "quantity", quantity))))
            .expectStatus().isOk();
        return orderNo;
    }

    /** The stored event with its body, which is kept in a table of its own. */
    static List<Map<String, Object>> stored(String eventId) {
        return query("SELECT e.*, b.body FROM sys_inbound_event e LEFT JOIN sys_inbound_event_body b"
            + " ON b.inbound_event_id = e.inbound_event_id WHERE e.external_event_id = ?", eventId);
    }

    static List<Map<String, Object>> outbox(String eventId) {
        return query("SELECT e.event_type, e.payload::text AS payload, e.process_seq_id FROM sys_outbox_event e"
            + " JOIN sys_inbound_event i ON i.inbound_event_id = e.payload->>'inboundEventId'"
            + " WHERE i.external_event_id = ?", eventId);
    }

    static long count(String sql) {
        return ((Number) query(sql).getFirst().get("n")).longValue();
    }

    static Map<String, Object> order(String orderNo) {
        return query("SELECT paid_time, payment_ref FROM sales_order_version WHERE order_no = ?"
            + " ORDER BY version_no DESC LIMIT 1", orderNo).getFirst();
    }

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** Delivers until nothing is due (bounded: failures wait for their backoff, which is 0 s here, up to the limit). */
    void drain() {
        for (int round = 0; round < 50; round++) {
            Integer delivered = deliverer.deliverPending().block();
            if (delivered == null || delivered == 0) {
                return;
            }
        }
        throw new AssertionError("events kept coming");
    }

    // ---- tests ----------------------------------------------------------------------------------------------------

    @Test
    void aSignedPaymentIsStoredAsReceivedAndRecordedOnItsOrderOnce(CapturedOutput output) throws Exception {
        String orderNo = placeOrder(unique("SO").toUpperCase(), 2);
        String eventId = unique("evt");
        String marker = unique("customer-name-in-body");
        String body = payment(eventId, orderNo, 1000, "pi_" + eventId).replace("\"currency\"",
            "\"description\":\"" + marker + " 東京\",\"currency\"");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        accepted(body);

        List<Map<String, Object>> rows = stored(eventId);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("provider", "demo-pay").containsEntry("event_type",
                "payment_intent.succeeded").containsEntry("livemode", false).containsEntry("body", body)
                .containsEntry("content_type", "application/json");
            assertThat(row.get("body_sha256")).isEqualTo(uncheckedSha256(bytes));
        });
        // The outbox carries a reference only.
        List<Map<String, Object>> events = outbox(eventId);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event).containsEntry("event_type", "jabiz.inbound.demo-pay.payment_intent.succeeded");
            assertThat((String) event.get("payload")).contains("inboundEventId", eventId).doesNotContain(marker,
                "amount_received", orderNo);
        });
        // The operation record keeps the body masked.
        Object seq = rows.getFirst().get("process_seq_id");
        Map<String, Object> operation = query("SELECT process_name, actor_id, input_summary::text AS summary"
            + " FROM op_process WHERE process_seq_id = ?", seq).getFirst();
        assertThat(operation).containsEntry("process_name", "INBOUND_RECEIVE").containsEntry("actor_id", "system");
        assertThat((String) operation.get("summary")).contains("\"rawBody\": \"***\"").doesNotContain(marker);
        // No business process ran in the request.
        assertThat(order(orderNo)).containsEntry("payment_ref", null);

        deliverer.deliverPending().block();
        Map<String, Object> paid = order(orderNo);
        assertThat(paid.get("payment_ref")).isEqualTo("pi_" + eventId);
        assertThat(paid.get("paid_time")).isNotNull();
        assertThat(query("SELECT count(*) AS n FROM op_process WHERE process_name = 'ORDER_RECORD_PAYMENT'"
            + " AND input_summary->>'inboundEventId' = ?", rows.getFirst().get("inbound_event_id")))
            .singleElement().satisfies(row -> assertThat(((Number) row.get("n")).intValue()).isEqualTo(1));
        deliverer.deliverPending().block();
        assertThat(query("SELECT count(*) AS n FROM sales_order_version WHERE order_no = ?", orderNo))
            .singleElement().satisfies(row -> assertThat(((Number) row.get("n")).intValue()).isEqualTo(2));

        // Nothing of the body was logged.
        assertThat(output.getAll()).doesNotContain(marker);
    }

    private static String uncheckedSha256(byte[] bytes) {
        try {
            return sha256(bytes);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void theSameEventAgainIsAnsweredButKeptOnce() {
        String eventId = unique("evt");
        String body = payment(eventId, "SO-NONE", 1, "pi_dup");
        accepted(body);
        accepted(body);
        // Another body under the same id (the provider's retry after an edit, or a forgery with a valid signature):
        // still the first one.
        accepted(payment(eventId, "SO-OTHER", 2, "pi_dup2"));
        assertThat(stored(eventId)).singleElement().satisfies(row -> assertThat((String) row.get("body"))
            .isEqualTo(body));
        assertThat(outbox(eventId)).hasSize(1);
    }

    @Test
    void theDuplicateKeyIsTheConstraintTheReceiverKnows() {
        assertThat(query("SELECT conname FROM pg_constraint WHERE conrelid = 'sys_inbound_event'::regclass"
            + " AND contype = 'u'")).extracting(row -> row.get("conname"))
            .containsExactly(InboundEntities.EXTERNAL_KEY);
        // Neither the list nor any read of the events carries the body.
        assertThat(query("SELECT column_name FROM information_schema.columns WHERE table_schema = current_schema()"
            + " AND table_name = 'sys_inbound_event'")).extracting(row -> row.get("column_name"))
            .doesNotContain("body");
    }

    @Test
    void concurrentDeliveriesOfOneEventStoreOneRow() throws Exception {
        String eventId = unique("evt");
        byte[] body = payment(eventId, "SO-NONE", 1, "pi_race").getBytes(StandardCharsets.UTF_8);
        String signature = signature(SECRET, body);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(() -> send(body, signature).expectBody().returnResult().getStatus().value()));
            }
            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(stored(eventId)).hasSize(1);
        assertThat(outbox(eventId)).hasSize(1);
    }

    @Test
    void whatIsNotSignedInTimeOrNotAnEventIsRefusedAlikeAndLeavesNothing() {
        long inbound = count("SELECT count(*) AS n FROM sys_inbound_event");
        long events = count("SELECT count(*) AS n FROM sys_outbox_event WHERE event_type LIKE 'jabiz.inbound.%'");
        long operations = count("SELECT count(*) AS n FROM op_process WHERE process_name = 'INBOUND_RECEIVE'");
        String eventId = unique("evt");
        byte[] body = payment(eventId, "SO-NONE", 1, "pi_bad").getBytes(StandardCharsets.UTF_8);
        long now = clock.instant().getEpochSecond();
        List<byte[]> answers = new ArrayList<>();
        // Wrong signature, altered body, wrong secret.
        answers.add(rejected(send(body, "t=" + now + ",v1=" + "0".repeat(64))));
        byte[] altered = body.clone();
        altered[10] ^= 1;
        answers.add(rejected(send(altered, signature(SECRET, body))));
        answers.add(rejected(send(body, signature("whsec_not_the_configured_secret_000000", body))));
        // Outside the five minutes, either way.
        answers.add(rejected(send(body, signature(SECRET, now - 301, body))));
        answers.add(rejected(send(body, signature(SECRET, now + 301, body))));
        // No header, a malformed one.
        answers.add(rejected(send(body, null)));
        answers.add(rejected(send(body, "t=soon,v1=" + "0".repeat(64))));
        // Signed, but not UTF-8, not JSON, no event id.
        byte[] latin1 = payment(eventId, "SO-É", 1, "pi_bad").getBytes(StandardCharsets.ISO_8859_1);
        answers.add(rejected(send(latin1, signature(SECRET, latin1))));
        byte[] notJson = "id=evt&type=payment_intent.succeeded".getBytes(StandardCharsets.UTF_8);
        answers.add(rejected(send(notJson, signature(SECRET, notJson))));
        byte[] noId = "{\"type\":\"payment_intent.succeeded\"}".getBytes(StandardCharsets.UTF_8);
        answers.add(rejected(send(noId, signature(SECRET, noId))));

        for (byte[] answer : answers) {
            assertThat(new String(answer, StandardCharsets.UTF_8)).isEqualTo(new String(answers.getFirst(),
                StandardCharsets.UTF_8)).contains("INBOUND_REJECTED").doesNotContain("no_match", "too_old");
        }
        assertThat(count("SELECT count(*) AS n FROM sys_inbound_event")).isEqualTo(inbound);
        assertThat(count("SELECT count(*) AS n FROM sys_outbox_event WHERE event_type LIKE 'jabiz.inbound.%'"))
            .isEqualTo(events);
        assertThat(count("SELECT count(*) AS n FROM op_process WHERE process_name = 'INBOUND_RECEIVE'"))
            .isEqualTo(operations);
    }

    @Test
    void theToleranceBoundaryIsInclusive() {
        String eventId = unique("evt");
        byte[] body = payment(eventId, "SO-NONE", 1, "pi_edge").getBytes(StandardCharsets.UTF_8);
        send(body, signature(SECRET, clock.instant().getEpochSecond() - 300, body)).expectStatus().isOk();
        assertThat(stored(eventId)).hasSize(1);
    }

    @Test
    void bodiesOverOneMegabyteAreRefusedUnread(CapturedOutput output) {
        long inbound = count("SELECT count(*) AS n FROM sys_inbound_event");
        String eventId = unique("evt");
        String padding = "x".repeat(1024 * 1024);
        byte[] large = payment(eventId, "SO-NONE", 1, "pi_large").replace("\"currency\"",
            "\"pad\":\"" + padding + "\",\"currency\"").getBytes(StandardCharsets.UTF_8);
        // Declared too large: refused before the body is read.
        rejected(send(large, signature(SECRET, large)));
        // Chunked, without a length: reading stops at the limit.
        Flux<DataBuffer> chunks = Flux.range(0, large.length / 8192 + 1).map(i -> DefaultDataBufferFactory
            .sharedInstance.wrap(java.util.Arrays.copyOfRange(large, i * 8192, Math.min(large.length, (i + 1) * 8192))));
        rejected(client.post().uri(PATH).contentType(MediaType.APPLICATION_JSON)
            .header(StripeSignature.HEADER, signature(SECRET, large))
            .body(chunks, DataBuffer.class).exchange());
        // The same over the network, where Netty reads the body: declared too large, and chunked.
        WebTestClient network = network();
        rejected(network.post().uri(PATH).contentType(MediaType.APPLICATION_JSON)
            .header(StripeSignature.HEADER, signature(SECRET, large)).bodyValue(large).exchange());
        Flux<DataBuffer> overNetwork = Flux.range(0, large.length / 8192 + 1).map(i -> DefaultDataBufferFactory
            .sharedInstance.wrap(java.util.Arrays.copyOfRange(large, i * 8192, Math.min(large.length, (i + 1) * 8192))));
        rejected(network.post().uri(PATH).contentType(MediaType.APPLICATION_JSON)
            .header(StripeSignature.HEADER, signature(SECRET, large))
            .body(overNetwork, DataBuffer.class).exchange());
        // And a body just within the limit is read and accepted.
        String fitsId = unique("evt");
        String fitting = payment(fitsId, "SO-NONE", 1, "pi_fits");
        byte[] fits = fitting.replace("\"currency\"", "\"pad\":\"" + "y".repeat(1024 * 1024 - fitting.length() - 9)
            + "\",\"currency\"").getBytes(StandardCharsets.UTF_8);
        assertThat(fits.length).isEqualTo(1024 * 1024);
        network.post().uri(PATH).contentType(MediaType.APPLICATION_JSON)
            .header(StripeSignature.HEADER, signature(SECRET, fits)).bodyValue(fits).exchange()
            .expectStatus().isOk();
        assertThat(stored(fitsId)).hasSize(1);
        leaksReported(output);
        assertThat(count("SELECT count(*) AS n FROM sys_inbound_event")).isEqualTo(inbound + 1); // the one that fits
    }

    @Test
    void typesNotListedAreAnsweredButNotKept() {
        String eventId = unique("evt");
        String body = payment(eventId, "SO-NONE", 1, "pi_x").replace("payment_intent.succeeded",
            "payment_intent.created");
        accepted(body);
        assertThat(stored(eventId)).isEmpty();
        assertThat(query("SELECT 1 FROM sys_outbox_event WHERE payload->>'externalEventId' = ?", eventId)).isEmpty();
    }

    @Test
    void onlyPostToAServedProviderIsAnswered() {
        byte[] body = payment(unique("evt"), "SO-NONE", 1, "pi_x").getBytes(StandardCharsets.UTF_8);
        send("/api/inbound/no-such-provider", body, Map.of()).expectStatus().isNotFound();
        send("/api/inbound/demo-pay/more", body, Map.of()).expectStatus().isNotFound();
        send("/api/inbound", body, Map.of()).expectStatus().isNotFound();
        client.get().uri(PATH).exchange().expectStatus().isEqualTo(405).expectHeader().valueEquals("Allow", "POST");
        client.put().uri(PATH).bodyValue(body).exchange().expectStatus().isEqualTo(405);
        // A stray or expired token does not turn a delivery into a 401: credentials are not looked at.
        String eventId = unique("evt");
        byte[] signed = payment(eventId, "SO-NONE", 1, "pi_y").getBytes(StandardCharsets.UTF_8);
        send(PATH, signed, Map.of(StripeSignature.HEADER, signature(SECRET, signed),
            HttpHeaders.AUTHORIZATION, "Bearer not-a-token")).expectStatus().isOk();
        send(PATH, signed, Map.of(StripeSignature.HEADER, signature(SECRET, signed),
            HttpHeaders.AUTHORIZATION, "Basic Zm9vOmJhcg==")).expectStatus().isOk();
        assertThat(stored(eventId)).hasSize(1);
    }

    @Test
    void eachClientIsLimitedPerProviderAndForwardedHeadersDoNotHelp(CapturedOutput output) {
        // Over the network: the connection comes from 127.0.0.1, which is no trusted proxy, so the forwarded
        // addresses it claims are ignored and every request counts against it.
        WebTestClient network = network();
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 3; i++) {
            network.post().uri("/api/inbound/it-limited").contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", "203.0.113." + i).bodyValue(body).exchange()
                .expectStatus().isBadRequest();
        }
        network.post().uri("/api/inbound/it-limited").contentType(MediaType.APPLICATION_JSON)
            .header("X-Forwarded-For", "198.51.100.7").bodyValue(body).exchange()
            .expectStatus().isEqualTo(429).expectHeader().exists(HttpHeaders.RETRY_AFTER)
            .expectBody().jsonPath("$.violations[0].ruleCode").isEqualTo("RATE_LIMITED");
        // Another provider has a limit of its own.
        network.post().uri("/api/inbound/demo-pay").contentType(MediaType.APPLICATION_JSON).bodyValue(body)
            .exchange().expectStatus().isBadRequest();
        leaksReported(output);
    }

    /** A client of the running server, over the network (not bound to the application context). */
    WebTestClient network() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + PORT)
            .responseTimeout(Duration.ofSeconds(30)).build();
    }

    /**
     * Netty reports a buffer that was never released when the garbage collector finds it; with paranoid leak detection
     * on for this class, every buffer is tracked. Collects and allocates a little more so that reports come out, then
     * looks for reports of buffers the server read (requests): those of the test's own HTTP client do not count.
     */
    static void leaksReported(CapturedOutput output) {
        for (int i = 0; i < 3; i++) {
            System.gc();
            io.netty.buffer.ByteBufAllocator.DEFAULT.buffer(1024).release();
        }
        String[] reports = output.getAll().split("LEAK: ");
        for (int i = 1; i < reports.length; i++) {
            String trace = reports[i].split("\n20\\d\\d-")[0];
            assertThat(trace).doesNotContain("HttpServerCodec", "HttpRequestDecoder", "reactor.netty.http.server");
        }
    }

    static io.netty.util.ResourceLeakDetector.Level leakLevel;

    @org.junit.jupiter.api.BeforeAll
    static void paranoidLeakDetection() {
        leakLevel = io.netty.util.ResourceLeakDetector.getLevel();
        io.netty.util.ResourceLeakDetector.setLevel(io.netty.util.ResourceLeakDetector.Level.PARANOID);
    }

    @org.junit.jupiter.api.AfterAll
    static void restoreLeakDetection() {
        io.netty.util.ResourceLeakDetector.setLevel(leakLevel);
    }

    @Test
    void aRotatedSecretIsAcceptedWhileConfigured() {
        String eventId = unique("evt");
        byte[] body = payment(eventId, "SO-NONE", 1, "pi_rot").getBytes(StandardCharsets.UTF_8);
        send(body, signature(ROTATED, body)).expectStatus().isOk();
        assertThat(stored(eventId)).hasSize(1);
    }

    @Test
    void noCallerCanRecordAnEventThroughTheProcessApi() {
        String eventId = unique("evt");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("provider", "demo-pay");
        input.put("externalEventId", eventId);
        input.put("eventType", "payment_intent.succeeded");
        input.put("signedTime", clock.instant().toString());
        input.put("rawBody", payment(eventId, "SO-NONE", 1, "pi_forged"));
        post("/api/processes/INBOUND_RECEIVE/latest", admin(), input).expectStatus().isForbidden();
        assertThat(stored(eventId)).isEmpty();
    }

    @Test
    void readingNeedsInboundReadAndTheBodyIsReadOneAtATime() {
        String eventId = unique("evt");
        String body = payment(eventId, "SO-NONE", 7, "pi_read");
        accepted(body);
        String id = (String) stored(eventId).getFirst().get("inbound_event_id");
        String dataset = "/api/datasets/urn:jabiz:dataset:platform:InboundEvent";

        post(dataset + "/query", bearer("commerce.order.read"), Map.of()).expectStatus().isForbidden();
        get("/api/inbound-events/" + id + "/body", bearer("commerce.order.read")).expectStatus().isForbidden();

        Map<String, Object> page = post(dataset + "/query", bearer("inbound.read"), Map.of("filters",
            List.of(Map.of("field", "externalEventId", "op", "EQ", "value", eventId))))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        assertThat(items).singleElement().satisfies(item -> assertThat(item.toString())
            .contains(eventId, "payment_intent.succeeded").doesNotContain("pi_read", "amount_received"));
        get(dataset + "/entities/" + id, bearer("inbound.read")).expectStatus().isOk().expectBody(String.class)
            .value(text -> assertThat(text).contains(eventId).doesNotContain("pi_read"));

        get("/api/inbound-events/" + id + "/body", bearer("inbound.read")).expectStatus().isOk()
            .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
            .expectBody(MAP).value(read -> assertThat(read).containsEntry("body", body)
                .containsEntry("inboundEventId", id));
        get("/api/inbound-events/no-such-event/body", bearer("inbound.read")).expectStatus().isNotFound();
        // Read-only: nobody writes through the dataset.
        post(dataset + "/commit", admin(), Map.of("changes", List.of(Map.of("action", "INSERT", "attributes",
            Map.of("provider", "demo-pay"))))).expectStatus().value(status -> assertThat(status).isIn(403, 422));
    }

    @Test
    void thePlatformsOwnWebhookIsReceivedByItsInboundEndpointAndRetriedUntilHandled() {
        // Earlier events (other tests') first, so that the one failure falls on this test's event.
        drain();
        PeerConsumer.FAILURES_LEFT.set(1);
        String key = unique("PEER");
        Map<String, Object> input = new LinkedHashMap<>(Map.of("key", key, "item", key));
        input.put("permission", ItTaskFixtures.PERMISSION);
        post("/api/processes/IT_TASK_OPEN/latest", bearer(ItTaskFixtures.PERMISSION), input).expectStatus().isOk();
        String outboundId = (String) query("SELECT e.event_id::text AS id FROM sys_outbox_event e"
            + " JOIN sys_task_version t ON t.task_id::text = e.payload->>'taskId'"
            + " WHERE e.event_type = 'jabiz.task.created' AND t.source_key = ? AND t.version_no = 1", key)
            .getFirst().get("id");

        // The outbound webhook is POSTed over the network to this application's inbound endpoint.
        deliverer.deliverPending().block();
        List<Map<String, Object>> received = query("SELECT * FROM sys_inbound_event WHERE provider = 'it-peer'"
            + " AND external_event_id = ?", outboundId);
        assertThat(received).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("event_type", "jabiz.task.created"));
        // Its consumer failed the first time; the outbox tries again and it is handled once.
        drain();
        assertThat(PeerConsumer.HANDLED).filteredOn(outboundId::equals).hasSize(1);
        assertThat(query("SELECT a.attempt FROM sys_outbox_attempt a JOIN sys_outbox_event e ON e.event_id = a.event_id"
            + " WHERE a.consumer = 'it.peer-received' AND e.payload->>'externalEventId' = ?", outboundId)).hasSize(1);
        assertThat(query("SELECT 1 FROM sys_event_consumption c JOIN sys_outbox_event e ON e.event_id = c.event_id"
            + " WHERE c.consumer = 'it.peer-received' AND e.payload->>'externalEventId' = ?", outboundId)).hasSize(1);
        // The headers of the outbound format may come along, but must agree with the signed body.
        byte[] body = ("{\"eventId\":\"x-1\",\"eventType\":\"jabiz.task.created\",\"payload\":{}}")
            .getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>(new HmacSha256Signature().sign(
            "it-peer-webhook-secret-0123456789abcdef", clock.instant().getEpochSecond(), body));
        headers.put("X-Jabiz-Event-Id", "x-2");
        rejected(send("/api/inbound/it-peer", body, headers));
        assertThat(WebhookSignature.matchesAny(List.of("it-peer-webhook-secret-0123456789abcdef"),
            clock.instant().getEpochSecond(), body, headers.get(HmacSha256Signature.SIGNATURE))).isTrue();
    }

    @Test
    void onlyTheDeliveryRecordsPaymentsAndLoadingNeedsInboundRead() {
        String eventId = unique("evt");
        accepted(payment(eventId, "SO-NONE", 1, "pi_forged"));
        String id = (String) stored(eventId).getFirst().get("inbound_event_id");
        // internal() keeps a process out of the catalog; the process itself refuses anyone but the delivery.
        post("/api/processes/ORDER_RECORD_PAYMENT/latest", admin(), Map.of("inboundEventId", id))
            .expectStatus().isForbidden();
        // A step loading an event: 403 without inbound.read, 404 for an unknown id.
        post("/api/processes/IT_PEER_EVENT/latest", bearer("it.peer"), Map.of("inboundEventId", id))
            .expectStatus().isForbidden();
        post("/api/processes/IT_PEER_EVENT/latest", bearer("it.peer", "inbound.read"),
            Map.of("inboundEventId", "no-such-event")).expectStatus().isNotFound();
        // A consumer of the peer's events does not load a payment of demo-pay.
        assertThatThrownBy(() -> asRequest(com.jabiz.context.RequestContext.system(java.util.Locale.ENGLISH, "it"),
            executor.run(PeerConsumer.PROCESS, new PeerConsumer.PeerInput(id),
                com.jabiz.runtime.process.ExecutionOptions.NONE)).block())
            .hasMessageContaining("is not a jabiz.task.created event of it-peer");
    }

    @Test
    void receivedEventsAreInTheRetentionReport() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> policies = (List<Map<String, Object>>) get("/api/retention",
            bearer("retention.read")).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody()
            .get("policies");
        assertThat(policies).filteredOn(policy -> "InboundEvent".equals(policy.get("entity"))).singleElement()
            .satisfies(policy -> assertThat(policy).containsEntry("keep", "P7Y"));
    }

    @Test
    void theInboundTableIsNeverUpdatedOrDeleted() {
        SqlStatementLog.STATEMENTS.clear();
        String eventId = unique("evt");
        accepted(payment(eventId, "SO-NONE", 1, "pi_append"));
        accepted(payment(eventId, "SO-NONE", 1, "pi_append"));
        deliverer.deliverPending().block();
        assertThat(SqlStatementLog.STATEMENTS).anyMatch(sql -> sql.contains("sys_inbound_event"));
        assertThat(SqlStatementLog.STATEMENTS).noneMatch(sql -> sql.matches(
            "(?is).*(UPDATE|DELETE\\s+FROM|TRUNCATE)\\s+sys_inbound_event(_body)?\\b.*"));
        assertThatThrownBy(() -> execute("UPDATE sys_inbound_event SET event_type = 'x' WHERE external_event_id = ?",
            eventId)).hasMessageContaining("append-only");
        assertThatThrownBy(() -> execute("DELETE FROM sys_inbound_event WHERE external_event_id = ?", eventId))
            .hasMessageContaining("append-only");
        assertThatThrownBy(() -> execute("UPDATE sys_inbound_event_body SET body = '{}'"))
            .hasMessageContaining("append-only");
    }

    @Test
    void aWrongAmountOrAnUnknownOrderBecomesATaskNotAFailure() {
        String orderNo = placeOrder(unique("SO").toUpperCase(), 1);
        String wrongAmount = unique("evt");
        String unknownOrder = unique("evt");
        accepted(payment(wrongAmount, orderNo, 999, "pi_" + wrongAmount));
        accepted(payment(unknownOrder, "SO-DOES-NOT-EXIST", 500, "pi_" + unknownOrder));
        deliverer.deliverPending().block();
        assertThat(order(orderNo)).containsEntry("payment_ref", null);
        for (String eventId : List.of(wrongAmount, unknownOrder)) {
            String id = (String) stored(eventId).getFirst().get("inbound_event_id");
            assertThat(query("SELECT task_type, assignee_permission, subject_entity, title_params FROM sys_task_version"
                + " WHERE source_key = ?", "payment:demo-pay:" + eventId)).singleElement().satisfies(task -> {
                    assertThat(task).containsEntry("task_type", "commerce.payment-check")
                        .containsEntry("assignee_permission", "commerce.order.place")
                        .containsEntry("subject_entity", eventId.equals(wrongAmount) ? "SalesOrder" : "InboundEvent");
                    assertThat((String) task.get("title_params")).contains("\"currency\":\"jpy\"")
                        .contains(eventId.equals(wrongAmount) ? "AMOUNT_MISMATCH" : "NO_ORDER");
                });
            assertThat(query("SELECT 1 FROM sys_outbox_attempt a JOIN sys_outbox_event e ON e.event_id = a.event_id"
                + " WHERE a.consumer = 'commerce.payment-received' AND e.payload->>'inboundEventId' = ?", id))
                .isEmpty();
        }
    }
}
