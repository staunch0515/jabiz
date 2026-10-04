package com.jabiz.finance.it;

import com.jabiz.finance.ar.InvoiceProcesses;
import com.jabiz.webhook.WebhookSignature;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIN-DI-007 (ROADMAP F11e D3): with a subscription to "invoice posted", posting INV-1004 delivers one notification
 * with its number and total. Posting publishes {@value InvoiceProcesses#POSTED_EVENT} once per invoice (a credit memo
 * publishes nothing); the platform's webhook (decision D33) POSTs it, signed, to the subscriber, here a local receiver.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class FinDi007IT extends JanuaryBooks {

    static final String SECRET = "fin-di-007-webhook-secret-0123456789";
    static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    static final HttpServer SERVER = server();

    record Received(String timestamp, String signature, String body) {}

    static HttpServer server() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/erp/invoices", exchange -> {
                RECEIVED.add(new Received(exchange.getRequestHeaders().getFirst("X-Jabiz-Timestamp"),
                    exchange.getRequestHeaders().getFirst("X-Jabiz-Signature"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void subscription(DynamicPropertyRegistry registry) {
        registry.add("jabiz.webhooks.allowed-hosts", () -> "localhost");
        registry.add("jabiz.webhooks.subscriptions[0].name", () -> "erp-invoices");
        registry.add("jabiz.webhooks.subscriptions[0].event-type", () -> InvoiceProcesses.POSTED_EVENT);
        registry.add("jabiz.webhooks.subscriptions[0].url",
            () -> "http://localhost:" + SERVER.getAddress().getPort() + "/erp/invoices");
        registry.add("jabiz.webhooks.subscriptions[0].secret", () -> SECRET);
    }

    @AfterAll
    static void stop() {
        SERVER.stop(0);
    }

    @Test
    void aPostedInvoiceIsAnnouncedOnceWithItsNumberAndTotal() {
        people();
        books();
        receivables();

        List<Map<String, Object>> events = query("SELECT payload->>'invoiceNo' AS no, payload->>'total' AS total,"
            + " payload->>'taxTotal' AS tax, payload->>'customerCode' AS customer, payload->>'currency' AS currency"
            + " FROM sys_outbox_event WHERE event_type = ? ORDER BY event_seq", InvoiceProcesses.POSTED_EVENT);
        Map<String, Object> inv1004 = events.stream().filter(e -> "INV-1004".equals(e.get("no"))).findFirst()
            .orElseThrow();
        assertThat(new BigDecimal((String) inv1004.get("total"))).isEqualByComparingTo("53300.00");
        assertThat(new BigDecimal((String) inv1004.get("tax"))).isEqualByComparingTo("3300.00");
        assertThat(inv1004).containsEntry("customer", "C100").containsEntry("currency", "USD");
        // Every posted invoice once; the credit memo not at all.
        assertThat(events).extracting(e -> e.get("no"))
            .containsExactlyInAnyOrder("INV-1004", "INV-1005", "INV-1006", "INV-1007");

        // The subscriber hears of INV-1004 once, signed, with its number and total; delivering again sends nothing.
        deliverer.deliverPending().block();
        deliverer.deliverPending().block();
        List<Received> about1004 = RECEIVED.stream().filter(r -> r.body().contains("\"invoiceNo\": \"INV-1004\"")
            || r.body().contains("\"invoiceNo\":\"INV-1004\"")).toList();
        assertThat(about1004).hasSize(1);
        Received notice = about1004.getFirst();
        assertThat(WebhookSignature.matches(SECRET, Long.parseLong(notice.timestamp()),
            notice.body().getBytes(StandardCharsets.UTF_8), notice.signature())).isTrue();
        assertThat(notice.body()).contains("\"eventType\":\"" + InvoiceProcesses.POSTED_EVENT + "\"")
            .containsPattern("\"total\": ?53300\\.00");
        // One notification per posted invoice, whatever else the books post.
        assertThat(RECEIVED).hasSameSizeAs(events);
    }
}
