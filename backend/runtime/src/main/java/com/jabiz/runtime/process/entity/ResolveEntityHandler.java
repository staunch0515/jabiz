package com.jabiz.runtime.process.entity;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.process.NoMetadata;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.security.Permissions;
import com.jabiz.runtime.entity.ProcessOnlyFields;
import com.jabiz.runtime.security.SensitiveDataMasker;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Resolves the entity definition and the dataset that serves the requested entity type.
 *
 * <p>The generic processes write whatever entity their input names, so their own permission ({@code entity.write})
 * cannot say which data they may change: the caller also needs the write permission of the entity's default dataset,
 * whichever entry point started the process (docs/design/10-security.md section 5).
 */
@Component
public class ResolveEntityHandler implements StepHandler<NoMetadata, EntityChangeContext> {

    private final EntityDefinitionRegistry entities;
    private final DatasetRegistry datasets;
    private final boolean development;

    public ResolveEntityHandler(EntityDefinitionRegistry entities, DatasetRegistry datasets, Environment environment) {
        this.entities = entities;
        this.datasets = datasets;
        this.development = environment.acceptsProfiles(Profiles.of("dev"));
    }

    @Override
    public Mono<Void> execute(NoMetadata metadata, EntityChangeContext ctx) {
        return Mono.fromRunnable(() -> {
            String type = ctx.entityType();
            EntityDefinition definition = entities.find(type).orElseThrow(
                () -> new EntityNotFoundException("Unregistered entity type: " + type));
            DatasetDefinition dataset = datasets.findForEntity(type).orElseThrow(
                () -> new EntityNotFoundException("No dataset serves entity type: " + type));
            Permissions.requireDeclared(ctx.request(), dataset.permissions().write(), development,
                "Writing " + type + " through dataset " + dataset.resourceId());
            DatasetEntityManager.rejectDirectWrites(dataset);
            // Sensitive fields are written by their own processes only (docs/design/10-security.md section 6).
            SensitiveDataMasker.rejectWrites(definition, ctx.attributes());
            // So are the fields that only processes change (docs/design/16-content-authoring.md section 5).
            ProcessOnlyFields.rejectWrites(definition, ctx.attributes());
            ctx.setDefinition(definition);
            ctx.setDataset(dataset);
        });
    }
}
