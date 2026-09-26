package com.jabiz.runtime.process;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.process.ChangeSet;
import com.jabiz.process.IdAssigner;
import com.jabiz.process.ProcessContext;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityAction;
import com.jabiz.runtime.EntityChange;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.process.entity.EntityIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Commits the changes a process registered (docs/design/06-process.md section 3.1): consecutive changes through the
 * same dataset form one {@link DatasetEntityManager#commitBatch} call, all within the process's transaction and
 * operation, in registration order. Every dataset must live in the storage of the operation tables, since one
 * process is one transaction (decision D11).
 */
@Component
public class ChangeSetCommitter {

    private final DatasetEntityManager entityManager;
    private final DatasetRegistry datasets;
    private final EntityDefinitionRegistry entities;
    private final EntityIdGenerator idGenerator;
    private final String poolRef;

    public ChangeSetCommitter(DatasetEntityManager entityManager, DatasetRegistry datasets,
        EntityDefinitionRegistry entities, EntityIdGenerator idGenerator,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.entityManager = entityManager;
        this.datasets = datasets;
        this.entities = entities;
        this.idGenerator = idGenerator;
        this.poolRef = Objects.requireNonNull(poolRef, "poolRef must not be null");
    }

    /**
     * Keys of registered inserts: the key given in the attributes, else a new one (UUIDv7 by default) when the
     * entity's key field is generated.
     */
    public IdAssigner idAssigner() {
        return (entityType, attributes) -> {
            EntityDefinition definition = entities.find(entityType)
                .orElseThrow(() -> new IllegalArgumentException("Unregistered entity type: " + entityType));
            Object given = attributes.get(definition.primaryKey);
            if (given != null) {
                return given;
            }
            FieldDefinition key = definition.field(definition.primaryKey);
            if (!key.generated()) {
                return null;
            }
            Object generated = idGenerator.next(definition);
            attributes.put(definition.primaryKey, generated);
            return generated;
        };
    }

    /** Commits and clears the pending changes of the context, recording what was stored in its change set. */
    public Mono<Void> commit(ProcessContext ctx) {
        return Mono.defer(() -> {
            List<ChangeSet.Change> pending = ctx.changes().drain();
            if (pending.isEmpty()) {
                return Mono.empty();
            }
            return Flux.fromIterable(batches(pending))
                .concatMap(batch -> entityManager.commitBatch(batch.dataset(), batch.changes()))
                .doOnNext(saved -> ctx.changes().recordSaved(saved.stream().map(ChangeSetCommitter::toSaved).toList()))
                .then();
        });
    }

    private record Batch(DatasetDefinition dataset, List<EntityChange> changes) {}

    private List<Batch> batches(List<ChangeSet.Change> pending) {
        List<Batch> batches = new ArrayList<>();
        for (ChangeSet.Change change : pending) {
            DatasetDefinition dataset = datasetFor(change);
            Batch last = batches.isEmpty() ? null : batches.getLast();
            if (last == null || !last.dataset().resourceId().equals(dataset.resourceId())) {
                last = new Batch(dataset, new ArrayList<>());
                batches.add(last);
            }
            last.changes().add(toEntityChange(change));
        }
        return batches;
    }

    private DatasetDefinition datasetFor(ChangeSet.Change change) {
        DatasetDefinition dataset = change.datasetId() == null
            ? datasets.findForEntity(change.entityType()).orElseThrow(() -> new IllegalStateException(
                "No dataset serves entity type " + change.entityType()))
            : datasets.findById(change.datasetId()).orElseThrow(() -> new IllegalStateException(
                "Unknown dataset " + change.datasetId()));
        // Writing another entity through a dataset would bypass that entity's own scope.
        if (!dataset.targetEntityType().equals(change.entityType())) {
            throw new IllegalStateException("Dataset " + dataset.resourceId() + " serves "
                + dataset.targetEntityType() + ", not " + change.entityType());
        }
        if (!poolRef.equals(dataset.storage().connectionPoolRef())) {
            throw new IllegalStateException("Dataset " + dataset.resourceId() + " lives in storage '"
                + dataset.storage().connectionPoolRef() + "'; a process writes only to '" + poolRef
                + "', the storage of its transaction");
        }
        return dataset;
    }

    private static EntityChange toEntityChange(ChangeSet.Change change) {
        EntityAction action = switch (change.action()) {
            case INSERT -> EntityAction.INSERT;
            case UPDATE -> EntityAction.UPDATE;
            case DELETE -> EntityAction.DELETE;
            case CANCEL_SCHEDULED -> EntityAction.CANCEL_SCHEDULED;
        };
        EntityInstance instance = new EntityInstance(change.id(), change.entityType(), change.version(), null,
            new LinkedHashMap<>(change.attributes()));
        return new EntityChange(action, instance, change.effectiveTime());
    }

    private static ChangeSet.Saved toSaved(EntityInstance instance) {
        Map<String, Object> attributes = instance.attributes();
        return new ChangeSet.Saved(instance.entityType(), instance.id(), instance.version(), attributes);
    }
}
