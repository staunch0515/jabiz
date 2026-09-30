package com.jabiz.runtime.approval;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.function.Function;

/**
 * Loads the current state of the rule, limit or SoD rule a controlled change targets, when it names one; the
 * entity and so the dataset are only known from the change ({@link ControlChanges}).
 */
@Component
class LoadControlTarget<C extends ProcessContext> implements StepHandler<LoadControlTarget.Metadata<C>, C> {

    /** @param target the entity name and id, or null when the change creates a new instance */
    record Metadata<C>(Function<C, Target> target, String targetKey) {
        Metadata {
            Objects.requireNonNull(target, "target must not be null");
            Objects.requireNonNull(targetKey, "targetKey must not be null");
        }
    }

    record Target(String entity, Object id) {}

    static <C extends ProcessContext> StepSpec<Metadata<C>, C> of(Function<C, Target> target, String targetKey) {
        return StepSpec.of(LoadControlTarget.class, new Metadata<>(target, targetKey));
    }

    private final DatasetRegistry datasets;
    private final EntityDefinitionRegistry entities;
    private final DatasetEntityManager entityManager;

    LoadControlTarget(DatasetRegistry datasets, EntityDefinitionRegistry entities, DatasetEntityManager entityManager) {
        this.datasets = datasets;
        this.entities = entities;
        this.entityManager = entityManager;
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        return Mono.defer(() -> {
            Target target = metadata.target().apply(ctx);
            if (target == null || target.id() == null || ControlChanges.datasetOf(target.entity()) == null) {
                return Mono.empty();
            }
            DatasetDefinition dataset = datasets.findById(ControlChanges.datasetOf(target.entity())).orElseThrow();
            return entityManager.findById(dataset, entities.getOrThrow(target.entity()), target.id())
                .switchIfEmpty(Mono.error(() -> new EntityNotFoundException(target.entity() + " " + target.id()
                    + " not found")))
                .doOnNext(found -> ctx.put(metadata.targetKey(), found))
                .then();
        });
    }
}
