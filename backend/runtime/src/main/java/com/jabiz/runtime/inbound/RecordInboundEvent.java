package com.jabiz.runtime.inbound;

import com.jabiz.context.RequestContext;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.process.entity.EntityIdGenerator;
import com.jabiz.runtime.process.steps.CheckedStep;
import com.jabiz.runtime.process.steps.EventPublisher;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The step of {@code INBOUND_RECEIVE} (decision D37 item 2): inserts the verified event into the append-only
 * {@code sys_inbound_event}, its body as received with its SHA-256 computed here, and publishes the outbox event
 * {@code jabiz.inbound.<provider>.<type>} that refers to it, both in the process's transaction. The row is written
 * directly, not through the change set: it is a log of what arrived, like the mail attempts, so it has no audit record
 * (docs/design/21-audit-retention.md section 1). A second arrival of the same event fails on the unique key of
 * (provider, external event id), and the receiver answers it as a duplicate.
 *
 * <p>Only the platform's receiver runs it, as the system: anyone else, an administrator holding every permission
 * included, is refused, so that no caller can make the platform record an event no provider signed.
 */
@Component
class RecordInboundEvent implements StepHandler<RecordInboundEvent.Metadata, ProcessContext>,
    CheckedStep<RecordInboundEvent.Metadata> {

    /**
     * @param inputKey  context key of the {@link InboundProcesses.ReceiveInput}
     * @param outputKey context key receiving the id of the stored event
     */
    record Metadata(String inputKey, String outputKey) {
        Metadata {
            Objects.requireNonNull(inputKey, "inputKey must not be null");
            Objects.requireNonNull(outputKey, "outputKey must not be null");
        }
    }

    static StepSpec<Metadata, ProcessContext> of(String inputKey, String outputKey) {
        return StepSpec.of(RecordInboundEvent.class, new Metadata(inputKey, outputKey));
    }

    private final StorageAdapterRegistry storages;
    private final String poolRef;
    private final EntityIdGenerator ids;
    private final ObjectProvider<EventPublisher> publisher;

    RecordInboundEvent(StorageAdapterRegistry storages, @Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
        EntityIdGenerator ids, ObjectProvider<EventPublisher> publisher) {
        this.storages = storages;
        this.poolRef = poolRef;
        this.ids = ids;
        this.publisher = publisher;
    }

    @Override
    public Mono<Void> execute(Metadata metadata, ProcessContext ctx) {
        return Mono.defer(() -> {
            if (!RequestContext.SYSTEM_ACTOR.equals(ctx.request().actorId())) {
                return Mono.error(new PermissionDeniedException(InboundPermissions.RECEIVE,
                    "Inbound events are recorded by the platform's receiver only"));
            }
            EventPublisher events = publisher.getIfUnique();
            if (events == null) {
                return Mono.error(new IllegalStateException("No EventPublisher is configured"));
            }
            InboundProcesses.ReceiveInput input = ctx.get(metadata.inputKey(), InboundProcesses.ReceiveInput.class);
            String id = String.valueOf(ids.next(InboundEntities.EVENT_ENTITY));
            var received = ctx.opTime().truncatedTo(ChronoUnit.MICROS);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("inbound_event_id", id);
            row.put("provider", input.provider());
            row.put("external_event_id", input.externalEventId());
            row.put("event_type", input.eventType());
            row.put("livemode", input.livemode());
            row.put("signed_time", input.signedTime());
            row.put("received_time", received);
            row.put("content_type", input.contentType());
            row.put("body", input.rawBody());
            row.put("body_sha256", sha256(input.rawBody()));
            row.put("process_seq_id", ctx.processSeqId());
            // Only a reference: the body is not copied into the outbox, which inbound.read does not guard and whose
            // events outbound webhooks may forward.
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("inboundEventId", id);
            payload.put("provider", input.provider());
            payload.put("externalEventId", input.externalEventId());
            payload.put("eventType", input.eventType());
            payload.put("receivedTime", received.toString());
            return storages.getEngine(poolRef).insert(InboundEntities.TABLE, row)
                .then(events.publish(InboundEvents.type(input.provider(), input.eventType()), payload,
                    ctx.processSeqId()))
                .then(Mono.fromRunnable(() -> ctx.put(metadata.outputKey(), id)));
        });
    }

    @Override
    public List<String> problems(Metadata metadata) {
        return publisher.getIfUnique() == null ? List.of("records inbound events but no EventPublisher is configured")
            : List.of();
    }

    /** The SHA-256 of the text's UTF-8 bytes: of the body exactly as received, since it was strict UTF-8. */
    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
