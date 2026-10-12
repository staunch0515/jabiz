package com.jabiz.quizbuks.it;

import com.jabiz.inbound.StripeSignature;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.external.ExternalJobExecutor;
import com.jabiz.runtime.test.StubHttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Top-up integration tests (docs/quizbuks/plans/Q6-payments.md, Q6a): real PostgreSQL, no real network. Stripe's API
 * is a {@link StubHttpServer} on this machine that creates checkouts from the form it receives (so the tests see what
 * QuizBuks sent) and answers scripted reads; Stripe's events are signed with the test secret and posted to
 * {@code /api/inbound/stripe} as Stripe would; the jobs are run by {@link #runJobs()} and the events delivered by
 * {@link #deliverEvents()}. Each class installs QuizBuks once (the publishing tests' way).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "quizbuks.links.sponsor=https://sponsor.quizbuks.test/sponsor/"})
abstract class TopUpItSupport extends PlayItSupport {

    static final String SECRET = "whsec_test_only_quizbuks_stripe_0123456789";
    static final String INBOUND = "/api/inbound/stripe";
    static final String SESSIONS = "/v1/checkout/sessions";

    static final AtomicInteger SEQUENCE = new AtomicInteger();
    /**
     * One stub for every top-up class: they share a cached application context (and so its address), so the stub
     * lives as long as the test JVM rather than a class.
     */
    static final StubHttpServer STRIPE = StubHttpServer.start(TopUpItSupport::stripe);

    @DynamicPropertySource
    static void stripeApi(DynamicPropertyRegistry registry) {
        registry.add("jabiz.external-services.stripe.base-url", () -> STRIPE.url(""));
        registry.add("jabiz.external-services.stripe.allowed-hosts", () -> "127.0.0.1");
    }

    @Autowired
    ExternalJobExecutor jobExecutor;

    @Autowired
    JsonMapper json;

    @BeforeEach
    void resetStripe() {
        STRIPE.reset();
    }

    /**
     * The stub's own answers: a checkout made of the form received (the session's id from a sequence, the address on
     * Stripe's host, the record's references as sent), and 404 for anything not scripted.
     */
    static StubHttpServer.Answer stripe(StubHttpServer.Received request) {
        if ("POST".equals(request.method()) && SESSIONS.equals(request.path())) {
            Map<String, String> form = form(request.body());
            String id = "cs_test_stub" + SEQUENCE.incrementAndGet();
            Map<String, Object> session = new LinkedHashMap<>();
            session.put("id", id);
            session.put("object", "checkout.session");
            session.put("status", "open");
            session.put("payment_status", "unpaid");
            session.put("mode", form.get("mode"));
            session.put("currency", form.get("line_items[0][price_data][currency]"));
            session.put("amount_total", Long.parseLong(form.get("line_items[0][price_data][unit_amount]")));
            session.put("amount_subtotal", Long.parseLong(form.get("line_items[0][price_data][unit_amount]")));
            session.put("client_reference_id", form.get("client_reference_id"));
            session.put("metadata", Map.of("topUpId", form.get("metadata[topUpId]"), "sponsorId",
                form.get("metadata[sponsorId]")));
            session.put("payment_intent", null);
            session.put("livemode", false);
            session.put("url", "https://checkout.stripe.com/c/pay/" + id);
            session.put("expires_at", Long.parseLong(form.get("expires_at")));
            return StubHttpServer.Answer.json(200, JsonMapper.builder().build().writeValueAsString(session));
        }
        return StubHttpServer.Answer.json(404, "{\"error\":{\"type\":\"invalid_request_error\","
            + "\"code\":\"resource_missing\"}}");
    }

    static Map<String, String> form(String body) {
        Map<String, String> form = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int equals = pair.indexOf('=');
            form.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
        }
        return form;
    }

    // ---- the API -----------------------------------------------------------------------------------------------

    WebTestClient.ResponseSpec callStart(String token, Object amount) {
        return call("QB_TOPUP_START", token, body("amount", amount));
    }

    /** Starts a top-up as the sponsor; returns its id. */
    String startTopUp(String sponsorId, long amount) {
        return (String) run("QB_TOPUP_START", sponsorToken(sponsorId), body("amount", amount)).get("topUpId");
    }

    /** Starts a top-up and opens its checkout (the job against the stub); returns its id. */
    String openTopUp(String sponsorId, long amount) {
        String topUpId = startTopUp(sponsorId, amount);
        runJobs();
        assertThat(topUp(topUpId)).containsEntry("status", "OPEN");
        return topUpId;
    }

    /** Runs the due jobs and delivers the events their results cause, until nothing is left. */
    void runJobs() {
        for (int round = 0; round < 10; round++) {
            int ran = jobExecutor.runPending().block();
            deliverEvents();
            if (ran == 0) {
                return;
            }
        }
    }

    /** The system's token for the top-up processes (which are internal: refused anyway). */
    String walletSystem() {
        return com.jabiz.runtime.test.TestTokens.bearer(tokens, "system-test", QbPermissions.WALLET_SYSTEM);
    }

    Map<String, Object> topUp(String topUpId) {
        return query("SELECT * FROM qb_topup WHERE topup_id = ?", topUpId).getFirst();
    }

    // ---- Stripe's events -----------------------------------------------------------------------------------------

    /** An event of Stripe, created now. */
    Map<String, Object> event(String id, String type, Map<String, Object> object) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", id);
        event.put("object", "event");
        event.put("api_version", "2025-03-31.basil");
        event.put("created", clock.instant().getEpochSecond());
        event.put("livemode", false);
        event.put("type", type);
        event.put("data", Map.of("object", object));
        return event;
    }

    /** The completed checkout of a top-up as Stripe reports it, with changes. */
    Map<String, Object> completed(String topUpId, Consumer<Map<String, Object>> change) {
        Map<String, Object> top = topUp(topUpId);
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("id", top.get("checkout_session_id"));
        session.put("object", "checkout.session");
        session.put("status", "complete");
        session.put("payment_status", "paid");
        session.put("mode", "payment");
        session.put("currency", "jpy");
        session.put("amount_total", top.get("amount"));
        session.put("amount_subtotal", top.get("amount"));
        session.put("client_reference_id", topUpId);
        session.put("metadata", Map.of("topUpId", topUpId, "sponsorId", top.get("sponsor_id")));
        session.put("payment_intent", "pi_" + topUpId.replace("-", ""));
        session.put("livemode", false);
        change.accept(session);
        return session;
    }

    Map<String, Object> completed(String topUpId) {
        return completed(topUpId, s -> {});
    }

    static String paymentOf(String topUpId) {
        return "pi_" + topUpId.replace("-", "");
    }

    /** Posts a signed event to the inbound endpoint; expects 200. */
    void send(Map<String, Object> event) {
        sendRaw(json.writeValueAsBytes(event), clock.instant().getEpochSecond(), SECRET).expectStatus().isOk();
    }

    WebTestClient.ResponseSpec sendRaw(byte[] body, long signedAt, String secret) {
        String signature = new StripeSignature().sign(secret, signedAt, body).get(StripeSignature.HEADER);
        return client.post().uri(INBOUND).contentType(MediaType.APPLICATION_JSON)
            .header(StripeSignature.HEADER, signature).bodyValue(body).exchange();
    }

    /** Posts a signed event and delivers it (and what it causes). */
    void deliver(Map<String, Object> event) {
        send(event);
        deliverEvents();
    }

    static String eventId() {
        return "evt_test_" + SEQUENCE.incrementAndGet() + "_" + System.nanoTime();
    }

    // ---- the books -------------------------------------------------------------------------------------------------

    /** The lines of the transactions whose source is the top-up, in order. */
    static List<Map<String, Object>> postingsOf(String topUpId) {
        return query("SELECT t.transaction_id, t.booking_time, t.description, t.reference, t.source_entity, "
            + "a.account_code, e.direction, e.amount, e.dimension_1 FROM ledger_transaction_version t "
            + "JOIN ledger_entry_version e ON e.transaction_id = t.transaction_id "
            + "JOIN ledger_account_version a ON a.account_id = e.account_id "
            + "WHERE t.source_entity = 'QbTopUp' AND t.source_id = ? ORDER BY t.process_seq_id, e.line_no", topUpId);
    }

    /** The open tasks of a type for the finance administrators, by title. */
    static List<Map<String, Object>> tasksAbout(String source) {
        return query("SELECT task_type, title_key, title_params, subject_entity, subject_id, source_key FROM "
            + "(SELECT DISTINCT ON (task_id) * FROM sys_task_version ORDER BY task_id, version_no DESC) t "
            + "WHERE assignee_permission = 'qb.reconcile' AND status = 'OPEN' AND NOT is_deleted "
            + "AND source_key LIKE ?", source + "%");
    }

    static long outboxEvents(String type, String key, String value) {
        return ((Number) query("SELECT count(*) AS n FROM sys_outbox_event WHERE event_type = ? "
            + "AND payload::jsonb ->> ? = ?", type, key, value).getFirst().get("n")).longValue();
    }

    static Instant instant(Object value) {
        if (value instanceof java.sql.Timestamp time) {
            return time.toInstant();
        }
        return value instanceof java.time.OffsetDateTime time ? time.toInstant() : (Instant) value;
    }

    static BigDecimal money(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    void advance(Duration duration) {
        clock.set(clock.instant().plus(duration));
    }

    Instant now() {
        return clock.instant();
    }
}
