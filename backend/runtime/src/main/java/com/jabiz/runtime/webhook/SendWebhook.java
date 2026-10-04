package com.jabiz.runtime.webhook;

import com.jabiz.context.RequestContext;
import com.jabiz.process.ProcessContext;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.process.StepSpec;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.webhook.WebhookSignature;
import io.micrometer.common.KeyValues;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * POSTs one outbox event to one subscription's receiver (decision D33). Only the platform's delivery (the system
 * actor) may run it, and the event is read from the outbox here: its type must be the subscription's, its payload is
 * the stored one, so no caller can have the platform sign something else. The body is {@code eventId},
 * {@code eventType}, {@code createdTime} and {@code payload} (the stored JSON as it is), signed with
 * {@link WebhookSignature}; headers {@code X-Jabiz-Event-Id}, {@code X-Jabiz-Event-Type}, {@code X-Jabiz-Timestamp},
 * {@code X-Jabiz-Signature}. A 2xx answer within the timeout (the whole exchange) delivers it; anything else fails the
 * step, so the outbox records the attempt and tries again later. Redirects are not followed; no thread is blocked.
 */
@Component
class SendWebhook<C extends ProcessContext> implements StepHandler<SendWebhook.Metadata, C> {

    /** @param inputKey context key of the {@link WebhookProcesses.DeliverInput} */
    record Metadata(String inputKey) {
        Metadata {
            Objects.requireNonNull(inputKey, "inputKey must not be null");
        }
    }

    static <C extends ProcessContext> StepSpec<Metadata, C> of(String inputKey) {
        return StepSpec.of(SendWebhook.class, new Metadata(inputKey));
    }

    private final WebhookProperties properties;
    private final WebhookChecks checks;
    private final StorageAdapterRegistry storages;
    private final String poolRef;
    private final JsonMapper json;
    private final Clock clock;
    private final PlatformObservations observations;
    private final HttpClient http;

    SendWebhook(WebhookProperties properties, WebhookChecks checks, StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef, JsonMapper json, Clock clock,
        PlatformObservations observations) {
        this.properties = properties;
        this.checks = checks;
        this.storages = storages;
        this.poolRef = poolRef;
        this.json = json;
        this.clock = clock;
        this.observations = observations;
        this.http = HttpClient.newBuilder().connectTimeout(properties.timeout())
            .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @PreDestroy
    void close() {
        http.close();
    }

    @Override
    public Mono<Void> execute(Metadata metadata, C ctx) {
        WebhookProcesses.DeliverInput input = ctx.get(metadata.inputKey(), WebhookProcesses.DeliverInput.class);
        return RequestContexts.current()
            .filter(context -> RequestContext.SYSTEM_ACTOR.equals(context.actorId()))
            .switchIfEmpty(Mono.error(new PermissionDeniedException(WebhookProcesses.PERMISSION,
                "Webhooks are sent by the platform's event delivery only")))
            .flatMap(context -> storages.getEngine(poolRef).select("SELECT event_type, payload, created_time"
                    + " FROM sys_outbox_event WHERE event_id = :id", Map.of("id", BoundValue.of(input.eventId())))
                .next()
                .switchIfEmpty(Mono.error(new IllegalStateException("no outbox event " + input.eventId()))))
            .flatMap(event -> send(input, event));
    }

    private Mono<Void> send(WebhookProcesses.DeliverInput input, Map<String, Object> event) {
        WebhookProperties.Subscription subscription = properties.find(input.subscription())
            .orElseThrow(() -> new IllegalStateException("no webhook subscription " + input.subscription()));
        String type = Rows.string(event.get("event_type"));
        if (!type.equals(subscription.eventType())) {
            return Mono.error(new IllegalStateException("event " + input.eventId() + " is not of the type of webhook "
                + subscription.name()));
        }
        String refused = checks.refusal(subscription.url());
        if (refused != null) {
            return Mono.error(new IllegalStateException("webhook " + subscription.name() + ": " + refused));
        }
        Instant created = Rows.instant(event.get("created_time"));
        // The stored payload as it is: decimals keep the form the database gives them.
        String body = "{\"eventId\":" + json.writeValueAsString(input.eventId().toString())
            + ",\"eventType\":" + json.writeValueAsString(type)
            + ",\"createdTime\":" + json.writeValueAsString(created == null ? null : created.toString())
            + ",\"payload\":" + Rows.string(event.get("payload")) + "}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        long timestamp = clock.instant().getEpochSecond();
        HttpRequest request = HttpRequest.newBuilder(subscription.url())
            .timeout(properties.timeout())
            .header("Content-Type", "application/json")
            .header("User-Agent", "jabiz-webhook")
            .header("X-Jabiz-Event-Id", input.eventId().toString())
            .header("X-Jabiz-Event-Type", type)
            .header("X-Jabiz-Timestamp", Long.toString(timestamp))
            .header("X-Jabiz-Signature", WebhookSignature.sign(subscription.secret(), timestamp, bytes))
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
            .build();
        Mono<Void> exchange = Mono.fromFuture(() -> http.sendAsync(request, HttpResponse.BodyHandlers.discarding()))
            // The request's own timeout ends at the response's headers; this one bounds the body too.
            .timeout(properties.timeout())
            // Off the client's own threads, which the reactive checks do not watch.
            .publishOn(Schedulers.parallel())
            .flatMap(response -> response.statusCode() / 100 == 2 ? Mono.<Void>empty()
                : Mono.error(new IllegalStateException("webhook " + subscription.name() + " answered "
                    + response.statusCode())));
        return observations.mono(PlatformObservations.WEBHOOK, "webhook " + subscription.name(),
            KeyValues.of("subscription", subscription.name()), exchange);
    }
}
