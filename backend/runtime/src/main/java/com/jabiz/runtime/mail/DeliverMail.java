package com.jabiz.runtime.mail;

import com.jabiz.i18n.MessageCatalog;
import com.jabiz.mail.MailRenderer;
import com.jabiz.mail.MailTemplate;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.process.entity.EntityIdGenerator;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.security.secret.SingleUseSecrets;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.runtime.storage.UniqueKeyViolationException;
import com.jabiz.runtime.task.MailMessage;
import com.jabiz.runtime.task.NotificationSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Sends one recorded message: the only step of {@code MAIL_SEND} (docs/design/18-numbering-approvals-tasks.md
 * section 5.6; decision D35). A message with a {@code SENT} or {@code SKIPPED} attempt is done and left alone. With
 * mail off, or a notification the recipient turned off, the attempt is {@code SKIPPED}. Otherwise the template's
 * one-time tokens are drawn (only their SHA-256 is stored, in the delivery's transaction), the mail is rendered in
 * the message's language and handed to the {@link NotificationSender}; the attempt is {@code SENT}. A failure is
 * recorded as {@code FAILED} in a transaction of its own and fails the delivery, which rolls back (its tokens with it)
 * and is retried by the outbox.
 */
@Component
class DeliverMail implements StepHandler<DeliverMail.Metadata, ProcessContext> {

    private static final Logger log = LoggerFactory.getLogger(DeliverMail.class);
    private static final int TOKEN_BYTES = 32;
    private static final int MAX_DETAIL = 2000;
    private static final TypeReference<Map<String, Object>> PARAMS = new TypeReference<>() {};

    /**
     * @param messageKey context key of the message id
     * @param outcomeKey context key receiving what happened ({@code SENT}, {@code SKIPPED}, or {@code DONE} for a
     *                   message that was done already)
     */
    record Metadata(String messageKey, String outcomeKey) {}

    static StepSpec<Metadata, ProcessContext> of(String messageKey, String outcomeKey) {
        return StepSpec.of(DeliverMail.class, new Metadata(messageKey, outcomeKey));
    }

    /** What a message is to the delivery. */
    private record Pending(String messageId, MailTemplate template, String templateName, UUID userId, String address,
        Locale locale, Map<String, Object> params, int attempt, boolean done, boolean unsubscribed) {}

    private final MailTemplates templates;
    private final MessageCatalog messages;
    private final NotificationSender sender;
    private final JwtService tokens;
    private final StorageAdapterRegistry storages;
    private final EntityIdGenerator ids;
    private final JsonMapper json;
    private final Clock clock;
    private final String poolRef;
    private final SecureRandom random = new SecureRandom();

    DeliverMail(MailTemplates templates, MessageCatalog messages, NotificationSender sender, JwtService tokens,
        StorageAdapterRegistry storages, EntityIdGenerator ids, JsonMapper json, Clock clock,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.templates = templates;
        this.messages = messages;
        this.sender = sender;
        this.tokens = tokens;
        this.storages = storages;
        this.ids = ids;
        this.json = json;
        this.clock = clock;
        this.poolRef = poolRef;
    }

    @Override
    public Mono<Void> execute(Metadata metadata, ProcessContext ctx) {
        return Mono.defer(() -> {
            String messageId = String.valueOf(ctx.get(metadata.messageKey()));
            return load(messageId).flatMap(pending -> {
                if (pending.done()) {
                    ctx.put(metadata.outcomeKey(), "DONE");
                    return Mono.<Void>empty();
                }
                if (!templates.enabled()) {
                    return skip(ctx, metadata, pending, "MAIL_DISABLED");
                }
                if (pending.unsubscribed()) {
                    return skip(ctx, metadata, pending, "UNSUBSCRIBED");
                }
                return send(pending)
                    .then(attempt(pending, MailEntities.SENT, null, now()))
                    .doOnSuccess(sent -> ctx.put(metadata.outcomeKey(), MailEntities.SENT))
                    .onErrorResume(error -> recordFailure(pending, error).then(Mono.error(error)));
            });
        });
    }

    private Mono<Pending> load(String messageId) {
        return engine().select("""
                SELECT m.template, m.user_id, m.address, m.locale, m.params,
                       (SELECT count(*) FROM sys_mail_attempt a WHERE a.message_id = m.message_id) AS attempts,
                       EXISTS (SELECT 1 FROM sys_mail_attempt a WHERE a.message_id = m.message_id
                               AND a.outcome IN ('SENT', 'SKIPPED')) AS done,
                       m.category = 'NOTIFICATION' AND COALESCE(
                           (SELECT NOT p.subscribed AND NOT p.is_deleted FROM sec_user_mail_preference_version p
                             WHERE p.user_id = m.user_id AND p.template = m.template
                             ORDER BY p.row_id DESC LIMIT 1), false) AS unsubscribed
                FROM sys_mail_message m WHERE m.message_id = :id""", Map.of("id", BoundValue.of(messageId)))
            .next()
            .switchIfEmpty(Mono.error(() -> new IllegalStateException("No mail message " + messageId)))
            .map(row -> {
                String name = Rows.string(row.get("template"));
                return new Pending(messageId, templates.find(name).orElse(null), name, Rows.uuid(row.get("user_id")),
                    Rows.string(row.get("address")), Locale.of(Rows.string(row.get("locale"))),
                    json.readValue(Rows.string(row.get("params")), PARAMS),
                    Math.toIntExact(Rows.longValue(row.get("attempts"))) + 1,
                    Boolean.TRUE.equals(row.get("done")), Boolean.TRUE.equals(row.get("unsubscribed")));
            });
    }

    private Mono<Void> skip(ProcessContext ctx, Metadata metadata, Pending pending, String reason) {
        return attempt(pending, MailEntities.SKIPPED, reason, now())
            .doOnSuccess(skipped -> ctx.put(metadata.outcomeKey(), MailEntities.SKIPPED));
    }

    /** Draws the tokens, renders and sends; the tokens are written in the delivery's transaction. */
    private Mono<Void> send(Pending pending) {
        MailTemplate template = pending.template();
        if (template == null) {
            return Mono.error(new IllegalStateException("Mail template " + pending.templateName()
                + " is not declared (any more)"));
        }
        List<String> purposes = List.copyOf(template.tokens().keySet());
        Mono<byte[]> drawn = purposes.isEmpty() ? Mono.just(new byte[0])
            : SingleUseSecrets.randomBytes(random, TOKEN_BYTES * purposes.size());
        return drawn.flatMap(bytes -> {
            Instant issued = now();
            Map<String, String> values = new LinkedHashMap<>();
            pending.params().forEach((name, value) -> values.put(name, value == null ? "" : String.valueOf(value)));
            values.put(MailTemplate.BASE_URL, templates.baseUrl());
            if (template.unsubscribable() && pending.userId() != null) {
                values.put(MailTemplate.UNSUBSCRIBE_URL, templates.unsubscribeUrl(
                    tokens.issueUnsubscribe(pending.userId().toString(), template.name())));
            }
            Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
            Flux<Void> stored = Flux.empty();
            for (int i = 0; i < purposes.size(); i++) {
                String purpose = purposes.get(i);
                String token = base64.encodeToString(Arrays.copyOfRange(bytes, i * TOKEN_BYTES,
                    (i + 1) * TOKEN_BYTES));
                values.put(purpose, token);
                stored = stored.concatWith(storeToken(pending, purpose, token, issued,
                    template.tokens().get(purpose)));
            }
            Arrays.fill(bytes, (byte) 0);
            MailRenderer.Rendered mail = render(template, pending.locale(), values);
            MailMessage message = new MailMessage(pending.address(), mail.subject(), mail.text(), mail.html(),
                List.of());
            return stored.then(Mono.fromCallable(() -> {
                    sender.send(message);
                    return true;
                })
                .subscribeOn(Schedulers.boundedElastic()))
                .then();
        });
    }

    private MailRenderer.Rendered render(MailTemplate template, Locale locale, Map<String, String> values) {
        String subject = messages.find(template.subjectKey(), locale).orElseThrow(() -> new IllegalStateException(
            "No message " + template.subjectKey() + " [" + locale + "]"));
        String body = messages.find(template.bodyKey(), locale).orElseThrow(() -> new IllegalStateException(
            "No message " + template.bodyKey() + " [" + locale + "]"));
        return MailRenderer.render(subject, body, values, template.urlSafePlaceholders());
    }

    private Mono<Void> storeToken(Pending pending, String purpose, String token, Instant issued, Duration validity) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("token_hash", SingleUseSecrets.sha256Hex(token));
        row.put("message_id", pending.messageId());
        row.put("attempt_no", pending.attempt());
        row.put("purpose", purpose);
        row.put("user_id", pending.userId());
        row.put("address", pending.address());
        row.put("issued_time", issued);
        row.put("expires_time", issued.plus(validity));
        return engine().insert("sys_mail_token", row);
    }

    private Mono<Void> attempt(Pending pending, String outcome, String detail, Instant time) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("message_id", pending.messageId());
        row.put("attempt_no", pending.attempt());
        row.put("attempt_id", ids.next(MailEntities.ATTEMPT_ENTITY));
        row.put("outcome", outcome);
        row.put("detail", detail);
        row.put("attempted_time", time);
        return engine().insert("sys_mail_attempt", row);
    }

    /** In a transaction of its own: the delivery's rolls back. */
    private Mono<Void> recordFailure(Pending pending, Throwable error) {
        log.warn("Mail message {} could not be sent (attempt {}): {}", pending.messageId(), pending.attempt(),
            error.toString());
        String text = error.getClass().getName() + (error.getMessage() == null ? "" : ": " + error.getMessage());
        StorageEngine engine = engine();
        return engine.inNewTransaction(attempt(pending, MailEntities.FAILED,
                text.length() > MAX_DETAIL ? text.substring(0, MAX_DETAIL) : text, now()))
            // A concurrent delivery recorded this attempt number already: one row is enough.
            .onErrorResume(UniqueKeyViolationException.class, duplicate -> Mono.empty());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private StorageEngine engine() {
        return storages.getEngine(poolRef);
    }
}
