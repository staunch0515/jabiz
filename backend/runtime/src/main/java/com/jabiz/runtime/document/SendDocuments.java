package com.jabiz.runtime.document;

import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.task.MailMessage;
import com.jabiz.runtime.task.NotificationSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Sends the deliveries {@code DOCUMENT_SEND} recorded, after its commit (docs/design/22-documents.md section 5), each
 * with the document's kept PDF attached - checked against its hash first, so an altered copy is never sent. Every
 * attempt is recorded; a delivery that was sent is not sent again when the step is retried, one that failed makes the
 * step fail, so the platform retries it by its policy (as task notifications, 18 section 5.4).
 */
@Component
class SendDocuments implements StepHandler<NoMetadata, ProcessContext> {

    private static final Logger log = LoggerFactory.getLogger(SendDocuments.class);
    private static final int MAX_ERROR = 2000;

    private final DocumentDeliveries deliveries;
    private final NotificationSender sender;
    private final Clock clock;

    SendDocuments(DocumentDeliveries deliveries, NotificationSender sender, Clock clock) {
        this.deliveries = deliveries;
        this.sender = sender;
        this.clock = clock;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Mono<Void> execute(NoMetadata metadata, ProcessContext ctx) {
        return Mono.defer(() -> {
            List<String> ids = ctx.contains(DocumentProcesses.DELIVERIES)
                ? (List<String>) ctx.get(DocumentProcesses.DELIVERIES) : List.of();
            return Flux.fromIterable(ids)
                .concatMap(id -> send(UUID.fromString(id)))
                .filter(sent -> !sent)
                .count()
                .flatMap(failed -> failed == 0 ? Mono.<Void>empty()
                    : Mono.error(new IllegalStateException(failed + " document delivery(ies) could not be sent")));
        });
    }

    /** Sends one delivery unless an earlier attempt did; whether it is sent now. */
    private Mono<Boolean> send(UUID id) {
        return deliveries.outgoing(id)
            .flatMap(out -> {
                if (out.sent()) {
                    return Mono.just(true);
                }
                int attempt = out.attempts() + 1;
                return Mono.fromCallable(() -> {
                        if (!Documents.sha256(out.pdf()).equals(out.pdfHash())) {
                            return "The kept PDF of document " + out.runId() + " does not match its hash";
                        }
                        sender.send(new MailMessage(out.address(), out.subject(), out.body(), List.of(
                            new MailMessage.Attachment(DocumentController.fileName(out.documentNo(), out.layoutId(),
                                out.runId()), "application/pdf", out.pdf()))));
                        // An empty error text is a message the server accepted.
                        return "";
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorResume(error -> Mono.just(String.valueOf(error)))
                    .flatMap(error -> {
                        if (!error.isEmpty()) {
                            log.warn("Document delivery {} could not be sent (attempt {}): {}", id, attempt, error);
                        }
                        String recorded = error.isEmpty() ? null
                            : error.length() > MAX_ERROR ? error.substring(0, MAX_ERROR) : error;
                        return deliveries.attempt(id, attempt, recorded, clock.instant()).thenReturn(error.isEmpty());
                    });
            })
            .defaultIfEmpty(false);
    }
}
