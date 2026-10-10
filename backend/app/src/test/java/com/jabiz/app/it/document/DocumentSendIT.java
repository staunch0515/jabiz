package com.jabiz.app.it.document;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.app.commerce.OrderConfirmations;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.task.MailMessage;
import com.jabiz.runtime.task.NotificationSender;
import com.jabiz.runtime.task.SmtpNotificationSender;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.test.TestTokens;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.InputStream;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Documents sent by e-mail (docs/design/22-documents.md section 5, ROADMAP phase 14j-2) against a local SMTP server:
 * the order confirmation goes to the address its data names, one message per address with the kept PDF attached;
 * other addresses need {@code document.send.any}; only plain addresses are taken; a failed attempt is recorded and
 * retried, a sent one is not sent again; an altered copy is never sent; deliveries are only inserted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {"jabiz.mail.enabled=true",
    "jabiz.mail.from=documents@example.com", "jabiz.mail.base-url=https://jabiz.example.com",
    "spring.mail.host=localhost", "spring.mail.port=3026"})
class DocumentSendIT extends PostgresIntegrationTest {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3026, null, ServerSetup.PROTOCOL_SMTP));

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    /** Fails the first attempt for addresses starting with "flaky", then sends through the platform's SMTP sender. */
    @TestConfiguration
    static class FlakySender {

        static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

        @Bean
        @Primary
        NotificationSender flakyDocumentSender(SmtpNotificationSender smtp) {
            return new NotificationSender() {
                @Override
                public void send(String to, String subject, String body) {
                    smtp.send(to, subject, body);
                }

                @Override
                public void send(MailMessage message) throws Exception {
                    if (message.to().startsWith("flaky") && FAILED.add(message.to())) {
                        throw new IllegalStateException("mail server busy");
                    }
                    smtp.send(message);
                }
            };
        }
    }

    @Autowired
    ApplicationContext context;

    @Autowired
    JwtService tokens;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
    }

    /** An order of its own; the code is its customer, whose made-up contact the confirmation names. */
    private record Order(String code, String orderId, String orderNo, String contact) {}

    private Order order() {
        String code = "S" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        commit(CommerceEntities.WAREHOUSE_DATASET, Map.of("warehouseCode", code, "warehouseName", "North " + code,
            "active", true));
        commit(CommerceEntities.PRODUCT_DATASET, Map.of("sku", code, "productName", "Apple crate", "unitPrice", 1200,
            "active", true));
        process("STOCK_RECEIVE", Map.of("warehouseCode", code, "sku", code, "quantity", 10), admin())
            .expectStatus().isOk();
        clock.advance(Duration.ofMinutes(1));
        Map<String, Object> placed = output(process("ORDER_PLACE", Map.of("orderNo", code + "-1", "customerCode", code,
            "warehouseCode", code, "lines", List.of(Map.of("sku", code, "quantity", 3))), admin())
            .expectStatus().isOk());
        clock.advance(Duration.ofMinutes(1));
        return new Order(code, (String) placed.get("orderId"), (String) placed.get("orderNo"),
            code.toLowerCase(Locale.ROOT) + "@customers.example.com");
    }

    private String issue(Order order) {
        Map<String, Object> issued = output(process(OrderConfirmations.ISSUE, Map.of("orderId", order.orderId()),
            bearer("commerce.order.confirm", "commerce.order.read")).expectStatus().isOk());
        return (String) issued.get("runId");
    }

    private WebTestClient.ResponseSpec send(String runId, List<String> to, String... permissions) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("runId", runId);
        input.put("to", to);
        return process("DOCUMENT_SEND", input, bearer(permissions));
    }

    @Test
    void theConfirmationGoesToTheAddressItsDataNamesWithTheKeptPdfAttached() throws Exception {
        Order order = order();
        Map<String, Object> sent = output(process(OrderConfirmations.SEND, Map.of("orderId", order.orderId()),
            bearer("commerce.order.confirm", "commerce.order.read")).expectStatus().isOk());
        assertThat(sent).containsEntry("documentNo", order.orderNo())
            .containsEntry("addresses", List.of(order.contact()));
        String runId = (String) sent.get("runId");

        assertThat(awaitSent(runId, 1)).extracting(row -> row.get("address") + ":" + row.get("outcome"))
            .containsExactly(order.contact() + ":SENT");
        MimeMessage message = messagesTo(order.contact()).getFirst();
        assertThat(message.getSubject()).isEqualTo("Order confirmation " + order.orderNo());
        assertThat(message.getFrom()[0].toString()).isEqualTo("documents@example.com");
        Map<String, Object> detail = detail(runId);
        Map<String, Object> run = map(detail.get("run"));
        assertThat(run.get("recipients")).isEqualTo(List.of(order.contact()));
        Attached attached = attachment(message);
        assertThat(attached.fileName()).isEqualTo(order.orderNo() + ".pdf");
        assertThat(sha256(attached.bytes())).isEqualTo(run.get("pdfHash"));
        assertThat(text(message)).contains("Order confirmation " + order.orderNo());
        assertThat(list(detail.get("deliveries"))).singleElement().satisfies(delivery -> assertThat(delivery)
            .containsEntry("address", order.contact()).containsEntry("outcome", "SENT")
            .containsEntry("attempts", 1).containsEntry("requestedBy", "it-clerk"));

        // Sent again on request: one more message, the same bytes; the first one is not sent twice.
        output(send(runId, List.of(), "document.send", "commerce.order.read").expectStatus().isOk());
        assertThat(awaitSent(runId, 2)).hasSize(2);
        assertThat(messagesTo(order.contact())).hasSize(2);
        assertThat(sha256(attachment(messagesTo(order.contact()).get(1)).bytes())).isEqualTo(run.get("pdfHash"));
    }

    @Test
    void anotherAddressNeedsThePermissionToSendAnywhere() throws Exception {
        Order order = order();
        String runId = issue(order);
        String other = "buyer-" + order.code().toLowerCase(Locale.ROOT) + "@example.com";

        Map<String, Object> refused = send(runId, List.of(order.contact().toUpperCase(Locale.ROOT), other),
            "document.send", "commerce.order.read").expectStatus().isEqualTo(422)
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat(refused.toString()).contains("DOCUMENT_RECIPIENT_NOT_ALLOWED", other);
        assertThat(deliveries(runId)).isEmpty();

        // The address of the data in another case is the same address.
        output(send(runId, List.of(order.contact().toUpperCase(Locale.ROOT)), "document.send", "commerce.order.read")
            .expectStatus().isOk());
        output(send(runId, List.of(other), "document.send", "document.send.any", "commerce.order.read")
            .expectStatus().isOk());
        assertThat(awaitSent(runId, 2)).extracting(row -> row.get("address"))
            .containsExactlyInAnyOrder(order.contact().toUpperCase(Locale.ROOT), other);
        assertThat(messagesTo(other)).hasSize(1);
    }

    @Test
    void onlyPlainAddressesAreTakenAndAtMostTen() {
        Order order = order();
        String runId = issue(order);
        String[] permissions = {"document.send", "document.send.any", "commerce.order.read"};
        for (String address : List.of("a@example.com\r\nBcc: spy@example.com", "Buyer <buyer@example.com>",
            "not-an-address", "a@b")) {
            Map<String, Object> refused = send(runId, List.of(address), permissions).expectStatus().isBadRequest()
                .expectBody(MAP).returnResult().getResponseBody();
            assertThat(refused.toString()).contains("to[0]");
        }
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            many.add("p" + i + "@example.com");
        }
        assertThat(send(runId, many, permissions).expectStatus().isBadRequest().expectBody(MAP).returnResult()
            .getResponseBody().toString()).contains("to");
        assertThat(deliveries(runId)).isEmpty();
    }

    @Test
    void sendingNeedsItsPermissionAndADocumentTheCallerMaySee() {
        Order order = order();
        String runId = issue(order);
        send(runId, List.of(), "commerce.order.read").expectStatus().isForbidden();
        // Without the document's own permissions it does not exist for the caller.
        send(runId, List.of(), "document.send").expectStatus().isNotFound();
        send(UUID.randomUUID().toString(), List.of(), "document.send", "commerce.order.read").expectStatus()
            .isNotFound();
        send("not-a-run", List.of(), "document.send", "commerce.order.read").expectStatus().isNotFound();
        assertThat(deliveries(runId)).isEmpty();
    }

    @Test
    void aFailedAttemptIsRecordedAndRetriedAndAnAlteredCopyIsNeverSent() throws Exception {
        Order order = order();
        String runId = issue(order);
        String flaky = "flaky-" + order.code().toLowerCase(Locale.ROOT) + "@example.com";
        output(send(runId, List.of(flaky), "document.send", "document.send.any", "commerce.order.read")
            .expectStatus().isOk());
        assertThat(awaitSent(runId, 1)).extracting(row -> row.get("attempt_no") + ":" + row.get("outcome"))
            .containsExactly("1:FAILED", "2:SENT");
        assertThat(messagesTo(flaky)).hasSize(1);
        assertThat(list(detail(runId).get("deliveries"))).singleElement()
            .satisfies(delivery -> assertThat(delivery).containsEntry("outcome", "SENT").containsEntry("attempts", 2));

        Order other = order();
        String altered = issue(other);
        bypassingTheGuard("UPDATE sys_document_run SET pdf = pdf || '\\x00'::bytea WHERE run_id = '" + altered + "'");
        output(send(altered, List.of(), "document.send", "commerce.order.read").expectStatus().isOk());
        List<Map<String, Object>> attempts = awaitAttempt(altered);
        assertThat(attempts.getFirst()).containsEntry("outcome", "FAILED");
        assertThat((String) attempts.getFirst().get("error")).contains("does not match its hash");
        assertThat(messagesTo(other.contact())).isEmpty();
    }

    @Test
    void deliveriesAndTheirAttemptsAreOnlyInserted() throws Exception {
        Order order = order();
        String runId = issue(order);
        output(send(runId, List.of(), "document.send", "commerce.order.read").expectStatus().isOk());
        awaitSent(runId, 1);
        for (String table : List.of("sys_document_delivery", "sys_document_delivery_attempt")) {
            String key = table.equals("sys_document_delivery") ? "run_id" : "delivery_id";
            String id = table.equals("sys_document_delivery") ? runId
                : String.valueOf(deliveries(runId).getFirst().get("delivery_id"));
            assertThatThrownBy(() -> execute("UPDATE " + table + " SET " + key + " = " + key + " WHERE " + key
                + " = CAST(? AS uuid)", id)).hasMessageContaining(table);
            assertThatThrownBy(() -> execute("DELETE FROM " + table + " WHERE " + key + " = CAST(? AS uuid)", id))
                .hasMessageContaining(table);
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private record Attached(String fileName, byte[] bytes) {}

    private static List<Map<String, Object>> deliveries(String runId) {
        return query("SELECT delivery_id, address FROM sys_document_delivery WHERE run_id = CAST(? AS uuid)", runId);
    }

    private static List<Map<String, Object>> attempts(String runId) {
        return query("SELECT d.address, a.attempt_no, a.outcome, a.error FROM sys_document_delivery d"
            + " JOIN sys_document_delivery_attempt a ON a.delivery_id = d.delivery_id"
            + " WHERE d.run_id = CAST(? AS uuid) ORDER BY d.created_time, d.address, a.attempt_no", runId);
    }

    private static List<Map<String, Object>> awaitSent(String runId, int count) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            List<Map<String, Object>> rows = attempts(runId);
            if (rows.stream().filter(row -> "SENT".equals(row.get("outcome"))).count() >= count) {
                return rows;
            }
            Thread.sleep(100);
        }
        return attempts(runId);
    }

    private static List<Map<String, Object>> awaitAttempt(String runId) throws InterruptedException {
        for (int i = 0; i < 100 && attempts(runId).isEmpty(); i++) {
            Thread.sleep(100);
        }
        return attempts(runId);
    }

    private static List<MimeMessage> messagesTo(String address) {
        return Arrays.stream(MAIL.getReceivedMessages()).filter(message -> {
            try {
                return message.getAllRecipients()[0].toString().equalsIgnoreCase(address);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

    private static Attached attachment(MimeMessage message) throws Exception {
        Multipart parts = (Multipart) message.getContent();
        for (int i = 0; i < parts.getCount(); i++) {
            Part part = parts.getBodyPart(i);
            if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
                try (InputStream in = part.getInputStream()) {
                    return new Attached(part.getFileName(), in.readAllBytes());
                }
            }
        }
        throw new AssertionError("no attachment");
    }

    private static String text(MimeMessage message) throws Exception {
        Multipart parts = (Multipart) message.getContent();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < parts.getCount(); i++) {
            Part part = parts.getBodyPart(i);
            if (!Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
                Object content = part.getContent();
                text.append(content instanceof Multipart nested ? nested.getBodyPart(0).getContent() : content);
            }
        }
        return text.toString();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private String bearer(String... permissions) {
        return TestTokens.bearer(tokens, "it-clerk", permissions);
    }

    private String admin() {
        return TestTokens.bearer(tokens, "it-admin", "*");
    }

    private WebTestClient.ResponseSpec process(String name, Object input, String token) {
        return client.post().uri("/api/processes/" + name + "/latest").header(HttpHeaders.AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(input).exchange();
    }

    private static Map<String, Object> output(WebTestClient.ResponseSpec response) {
        return map(response.expectBody(MAP).returnResult().getResponseBody().get("output"));
    }

    private void commit(String dataset, Map<String, Object> attributes) {
        client.post().uri("/api/datasets/" + dataset + "/commit").header(HttpHeaders.AUTHORIZATION, admin())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", attributes)))).exchange()
            .expectStatus().isOk();
    }

    private Map<String, Object> detail(String runId) {
        return client.get().uri("/api/documents/runs/{id}", runId)
            .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(tokens, "it-reader", "document.archive.read",
                "commerce.order.read"))
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }

    /** Statements run with the append-only guard off, as a database administrator could. */
    private static void bypassingTheGuard(String... statements) {
        try (Connection connection = DB.connect(schema()); Statement statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = replica");
            for (String sql : statements) {
                statement.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
