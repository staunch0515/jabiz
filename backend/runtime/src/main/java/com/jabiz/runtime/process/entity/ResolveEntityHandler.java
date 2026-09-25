package com.jabiz.runtime.process.entity;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.process.NoMetadata;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Resolves the entity definition and the dataset that serves the requested entity type. */
@Component
public class ResolveEntityHandler implements StepHandler<NoMetadata, EntityChangeContext> {

    private final EntityDefinitionRegistry entities;
    private final DatasetRegistry datasets;

    public ResolveEntityHandler(EntityDefinitionRegistry entities, DatasetRegistry datasets) {
        this.entities = entities;
        this.datasets = datasets;
    }

    @Override
    public Mono<Void> execute(NoMetadata metadata, EntityChangeContext ctx) {
        return Mono.fromRunnable(() -> {
            String type = ctx.entityType();
            EntityDefinition definition = entities.find(type).orElseThrow(
                () -> new EntityNotFoundException("Unregistered entity type: " + type));
            DatasetDefinition dataset = datasets.findForEntity(type).orElseThrow(
                () -> new EntityNotFoundException("No dataset serves entity type: " + type));
            ctx.setDefinition(definition);
            ctx.setDataset(dataset);
        });
    }
}
