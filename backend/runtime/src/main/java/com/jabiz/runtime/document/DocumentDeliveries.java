package com.jabiz.runtime.document;

import com.jabiz.query.BoundValue;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Documents sent by e-mail (docs/design/22-documents.md section 5): {@code sys_document_delivery}, one row per address
 * and send, and {@code sys_document_delivery_attempt}, every attempt to send it; both append-only (decision D5).
 * Written by {@code DOCUMENT_SEND} only.
 */
@Component
public class DocumentDeliveries {

    static final String DELIVERIES = "sys_document_delivery";
    static final String ATTEMPTS = "sys_document_delivery_attempt";

    /**
     * A delivery and how it went.
     *
     * @param outcome      {@code PENDING} (no attempt yet), {@code SENT} or {@code FAILED} (the last attempt failed)
     * @param attempts     the attempts so far
     * @param lastAttempt  when the last attempt was made, or null
     * @param lastError    why the last attempt failed, or null
     */
    public record Delivery(UUID deliveryId, UUID runId, String address, String subject, String requestedBy,
        Instant createdTime, String outcome, int attempts, Instant lastAttempt, String lastError) {}

    /** What sending one delivery needs: the message as recorded and the document's kept PDF. */
    record Outgoing(UUID deliveryId, String address, String subject, String body, byte[] pdf, String pdfHash,
        String documentNo, String layoutId, UUID runId, int attempts, boolean sent) {}

    private final StorageAdapterRegistry storages;
    private final String poolRef;

    public DocumentDeliveries(StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.storages = storages;
        this.poolRef = poolRef;
    }

    StorageEngine engine() {
        return storages.getEngine(poolRef);
    }

    Mono<Void> insert(UUID deliveryId, UUID runId, String address, String subject, String body, String requestedBy,
        Instant created, long processSeqId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("delivery_id", deliveryId);
        row.put("run_id", runId);
        row.put("address", address);
        row.put("subject", subject);
        row.put("body", body);
        row.put("requested_by", requestedBy);
        row.put("created_time", created);
        row.put("process_seq_id", processSeqId);
        row.put("version", 1L);
        return engine().insert(DELIVERIES, row);
    }

    Mono<Void> attempt(UUID deliveryId, int attemptNo, String error, Instant time) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("delivery_id", deliveryId);
        row.put("attempt_no", attemptNo);
        row.put("outcome", error == null ? "SENT" : "FAILED");
        row.put("error", error);
        row.put("attempted_time", time);
        return engine().insert(ATTEMPTS, row);
    }

    /** The deliveries of a document, oldest first, with how each went. */
    public Flux<Delivery> of(UUID runId) {
        return engine().select("SELECT d.delivery_id, d.run_id, d.address, d.subject, d.requested_by, d.created_time,"
                + " (SELECT count(*) FROM " + ATTEMPTS + " a WHERE a.delivery_id = d.delivery_id) AS attempts,"
                + " EXISTS (SELECT 1 FROM " + ATTEMPTS + " a WHERE a.delivery_id = d.delivery_id"
                + " AND a.outcome = 'SENT') AS sent,"
                + " (SELECT a.attempted_time FROM " + ATTEMPTS + " a WHERE a.delivery_id = d.delivery_id"
                + " ORDER BY a.attempt_no DESC LIMIT 1) AS last_attempt,"
                + " (SELECT a.error FROM " + ATTEMPTS + " a WHERE a.delivery_id = d.delivery_id"
                + " ORDER BY a.attempt_no DESC LIMIT 1) AS last_error"
                + " FROM " + DELIVERIES + " d WHERE d.run_id = :run ORDER BY d.created_time, d.address",
                Map.of("run", BoundValue.of(runId)))
            .map(row -> {
                int attempts = ((Number) row.get("attempts")).intValue();
                boolean sent = Boolean.TRUE.equals(row.get("sent"));
                return new Delivery((UUID) row.get("delivery_id"), (UUID) row.get("run_id"),
                    (String) row.get("address"), (String) row.get("subject"), (String) row.get("requested_by"),
                    instant(row.get("created_time")), sent ? "SENT" : attempts == 0 ? "PENDING" : "FAILED", attempts,
                    instant(row.get("last_attempt")), sent ? null : (String) row.get("last_error"));
            });
    }

    /** One delivery with the document's PDF, for sending it. */
    Mono<Outgoing> outgoing(UUID deliveryId) {
        return engine().select("SELECT d.delivery_id, d.address, d.subject, d.body, r.pdf, r.pdf_hash, r.document_no,"
                + " r.layout_id, r.run_id,"
                + " (SELECT count(*) FROM " + ATTEMPTS + " a WHERE a.delivery_id = d.delivery_id) AS attempts,"
                + " EXISTS (SELECT 1 FROM " + ATTEMPTS + " a WHERE a.delivery_id = d.delivery_id"
                + " AND a.outcome = 'SENT') AS sent"
                + " FROM " + DELIVERIES + " d JOIN " + DocumentRuns.RUNS + " r ON r.run_id = d.run_id"
                + " WHERE d.delivery_id = :id", Map.of("id", BoundValue.of(deliveryId)))
            .next()
            .map(row -> new Outgoing((UUID) row.get("delivery_id"), (String) row.get("address"),
                (String) row.get("subject"), (String) row.get("body"), bytes(row.get("pdf")),
                ((String) row.get("pdf_hash")).trim(), (String) row.get("document_no"), (String) row.get("layout_id"),
                (UUID) row.get("run_id"), ((Number) row.get("attempts")).intValue(),
                Boolean.TRUE.equals(row.get("sent"))));
    }

    private static byte[] bytes(Object value) {
        return switch (value) {
            case byte[] b -> b;
            case ByteBuffer buffer -> {
                byte[] copy = new byte[buffer.remaining()];
                buffer.duplicate().get(copy);
                yield copy;
            }
            default -> throw new IllegalStateException("Unexpected bytes " + value);
        };
    }

    private static Instant instant(Object value) {
        return switch (value) {
            case null -> null;
            case Instant i -> i;
            case OffsetDateTime t -> t.toInstant();
            default -> throw new IllegalStateException("Unexpected time " + value.getClass());
        };
    }
}
