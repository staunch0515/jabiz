package com.jabiz.runtime.process.steps;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;

/**
 * Loads an entity by primary key through a dataset (so its scope applies) and puts the {@link EntityInstance} into
 * the context (docs/design/06-process.md section 2.1). The key is read from the context.
 */
@Component
public class LoadEntity<C extends ProcessContext> implements StepHandler<LoadEntity.Metadata, C>,
    CheckedStep<LoadEntity.Metadata> {

    /**
     * @param idKey     context key holding the primary key
     * @param targetKey context key receiving the instance
     * @param required  whether a missing entity fails the process (404); otherwise the target key stays empty
     */
    public record Metadata(String datasetId, String idKey, String targetKey, boolean required) {
        public Metadata {
            Objects.requireNonNull(datasetId, "datasetId must not be null");
            Objects.requireNonNull(idKey, "idKey must not be null");
            Objects.requireNonNull(targetKey, "targetKey must not be null");
        }
    }

    /** Loads a required entity. */
    public static <C extends ProcessContext> StepSpec<Metadata, C> by(String datasetId, String idKey,
        String targetKey) {
        return StepSpec.of(LoadEntity.class, new Metadata(datasetId, idKey, targetKey, true));
    }

    /** Loads an entity that may be missing. */
    public static <C extends ProcessContext> StepSpec<Metadata, C> optional(String datasetId, String idKey,
        String targetKey) {
        return StepSpec.of(LoadEntity.class, new Metadata(datasetId, idKey, targetKey, false));
    }

    private final DatasetRegistry datasets;
    private final EntityDefinitionRegistry entities;
    private final DatasetEntityManager entityManager;

    public LoadEntity(DatasetRegistry datasets, EntityDefinitionRegistry entities, DatasetEntityManager entityManager) {
        this.datasets = datasets;
        this.entities = entities;
        this.entityManager = entityManager;
    }

    @Override
    public Mono<Void> execute(Metadata metadata, C ctx) {
        return Mono.defer(() -> {
            DatasetDefinition dataset = datasets.findById(metadata.datasetId()).orElseThrow();
            Object id = ctx.get(metadata.idKey());
            if (id == null) {
                throw new IllegalStateException("No key in context under '" + metadata.idKey() + "'");
            }
            return entityManager.findById(dataset, entities.getOrThrow(dataset.targetEntityType()), id)
                .doOnNext(instance -> ctx.put(metadata.targetKey(), instance))
                .switchIfEmpty(Mono.defer(() -> metadata.required()
                    ? Mono.error(new EntityNotFoundException(dataset.targetEntityType() + " " + id + " not found"))
                    : Mono.empty()))
                .then();
        });
    }

    @Override
    public List<String> problems(Metadata metadata) {
        return datasets.findById(metadata.datasetId()).isPresent()
            ? List.of()
            : List.of("unknown dataset " + metadata.datasetId());
    }
}
