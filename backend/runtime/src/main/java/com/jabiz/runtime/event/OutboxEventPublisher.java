package com.jabiz.runtime.event;

import com.jabiz.runtime.process.steps.EventPublisher;
import com.jabiz.runtime.security.SensitiveDataMasker;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Objects;

/**
 * The outbox behind {@code PublishEvent} steps (docs/design/06-process.md section 2.1): the event is written in the
 * process's transaction, on the storage of the operation tables where every process runs (decision D11), and
 * delivered after the commit by {@link OutboxDeliverer}. Secrets in the payload are removed (null) first.
 */
@Component
public class OutboxEventPublisher implements EventPublisher {

    private final Outbox outbox;
    private final StorageAdapterRegistry storages;
    private final SensitiveDataMasker masker;
    private final JsonMapper json;
    private final String poolRef;

    public OutboxEventPublisher(Outbox outbox, StorageAdapterRegistry storages, SensitiveDataMasker masker,
        JsonMapper json, @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.outbox = outbox;
        this.storages = storages;
        this.masker = masker;
        this.json = json;
        this.poolRef = Objects.requireNonNull(poolRef, "poolRef must not be null");
    }

    @Override
    public Mono<Void> publish(String eventType, Object payload, long processSeqId) {
        return Mono.defer(() -> {
            String payloadJson = masker.withoutSecrets(payload);
            JsonNode tree = json.readTree(payloadJson);
            if (!tree.isObject()) {
                return Mono.error(new IllegalArgumentException("The payload of event " + eventType
                    + " must be an object (a record or a map), not " + tree.getNodeType()));
            }
            return outbox.append(storages.getEngine(poolRef), eventType, null, null, payloadJson);
        });
    }
}
