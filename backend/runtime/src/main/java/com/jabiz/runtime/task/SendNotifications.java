package com.jabiz.runtime.task;

import com.jabiz.i18n.MessageCatalog;
import com.jabiz.mail.MailRenderer;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.mail.MailLayouts;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Sends the notifications a process recorded, after its commit (docs/design/18-numbering-approvals-tasks.md
 * section 5.4). Every attempt is recorded ({@code sys_notification_attempt}); a notification that was sent is not sent
 * again when the step is retried, one that failed makes the step fail, so the platform retries it by its policy.
 */
@Component
class SendNotifications<C extends ProcessContext> implements StepHandler<SendNotifications.Metadata, C> {

    private static final Logger log = LoggerFactory.getLogger(SendNotifications.class);
    private static final int MAX_ERROR = 2000;

    /** @param idsKey context key of the ids of the notifications to send */
    record Metadata(String idsKey) {
        Metadata {
            Objects.requireNonNull(idsKey, "idsKey must not be null");
        }
    }

    static <C extends ProcessContext> StepSpec<Metadata, C> of(String idsKey) {
        return StepSpec.of(SendNotifications.class, new Metadata(idsKey));
    }

    private final NotificationSender sender;
    private final StorageAdapterRegistry storages;
    private final Clock clock;
    private final String poolRef;
    private final MailLayouts layouts;
    private final MessageCatalog messages;

    SendNotifications(NotificationSender sender, StorageAdapterRegistry storages, Clock clock,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef, MailLayouts layouts,
        MessageCatalog messages) {
        this.layouts = layouts;
        this.messages = messages;
        this.sender = sender;
        this.storages = storages;
        this.clock = clock;
        this.poolRef = poolRef;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Mono<Void> execute(Metadata metadata, C ctx) {
        return Mono.defer(() -> {
            List<String> ids = ctx.contains(metadata.idsKey()) ? (List<String>) ctx.get(metadata.idsKey()) : List.of();
            StorageEngine engine = storages.getEngine(poolRef);
            // The notifications of one task share the layout version recorded with them: looked up once.
            Map<Integer, Mono<MailLayouts.Chosen>> chosen = new java.util.concurrent.ConcurrentHashMap<>();
            return Flux.fromIterable(ids)
                .concatMap(id -> send(engine, id, chosen))
                .filter(sent -> !sent)
                .count()
                .flatMap(failed -> failed == 0 ? Mono.<Void>empty()
                    : Mono.error(new IllegalStateException(failed + " notification(s) could not be sent")));
        });
    }

    /** Sends one notification unless an earlier attempt did; whether it is sent now. */
    private Mono<Boolean> send(StorageEngine engine, String id, Map<Integer, Mono<MailLayouts.Chosen>> chosen) {
        return engine.select("SELECT n.address, n.subject, n.body, n.layout_version_no,"
                    + " (SELECT count(*) FROM sys_notification_attempt a WHERE a.notification_id = n.notification_id)"
                    + " AS attempts,"
                    + " EXISTS (SELECT 1 FROM sys_notification_attempt a WHERE a.notification_id = n.notification_id"
                    + " AND a.outcome = 'SENT') AS sent"
                    + " FROM sys_notification n WHERE n.notification_id = :id", Map.of("id", BoundValue.of(id)))
            .next()
            .flatMap(row -> {
                if (Boolean.TRUE.equals(row.get("sent"))) {
                    return Mono.just(true);
                }
                int attempt = ((Number) row.get("attempts")).intValue() + 1;
                Object layoutVersion = row.get("layout_version_no");
                // On the layout recorded with the notification (decision D72 item 4), in the language it was
                // rendered in; an empty error text is a message the server accepted.
                Integer version = layoutVersion == null ? null : ((Number) layoutVersion).intValue();
                return chosen.computeIfAbsent(version == null ? -1 : version,
                        key -> layouts.recorded(messages.defaultLocale(), version).cache())
                    .map(layout -> layouts.wrap(MailRenderer.plain((String) row.get("subject"),
                        (String) row.get("body")), layout.layout()))
                    .flatMap(mail -> Mono.fromCallable(() -> {
                        sender.send(new MailMessage((String) row.get("address"), mail.subject(), mail.text(),
                            mail.html(), List.of()));
                        return "";
                    })
                    .subscribeOn(Schedulers.boundedElastic()))
                    .onErrorResume(error -> {
                        log.warn("Notification {} could not be sent (attempt {}): {}", id, attempt, error.toString());
                        String text = String.valueOf(error);
                        return Mono.just(text.length() > MAX_ERROR ? text.substring(0, MAX_ERROR) : text);
                    })
                    .flatMap(error -> {
                        Map<String, Object> attemptRow = new LinkedHashMap<>();
                        attemptRow.put("notification_id", id);
                        attemptRow.put("attempt_no", attempt);
                        attemptRow.put("outcome", error.isEmpty() ? "SENT" : "FAILED");
                        attemptRow.put("error", error.isEmpty() ? null : error);
                        attemptRow.put("attempted_time", clock.instant());
                        return engine.insert("sys_notification_attempt", attemptRow).thenReturn(error.isEmpty());
                    });
            })
            .defaultIfEmpty(false);
    }
}
