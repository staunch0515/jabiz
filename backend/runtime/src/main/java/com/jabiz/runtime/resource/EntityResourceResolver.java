package com.jabiz.resource;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dataset.DatasetRegistry;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.EntityDefinitionRegistry;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.entity.FieldValueCoercer;
import com.jabiz.runtime.DatasetEntityManager;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Resolves {@code kind = "entity"} identifiers ({@code urn:<ns>:entity:<EntityType>:<primaryKey>}).
 *
 * The lookup goes through the dataset that serves the entity type, so partition filters and
 * soft-delete exclusion apply exactly as they do for every other read.
 */
@Component
public class EntityResourceResolver implements ResourceResolver {

    private final EntityDefinitionRegistry entities;
    private final DatasetRegistry datasets;
    private final DatasetEntityManager entityManager;

    public EntityResourceResolver(
        EntityDefinitionRegistry entities,
        DatasetRegistry datasets,
        DatasetEntityManager entityManager
    ) {
        this.entities = entities;
        this.datasets = datasets;
        this.entityManager = entityManager;
    }

    @Override
    public String kind() {
        return "entity";
    }

    @Override
    public Mono<Resource> resolve(ResourceId id) {
        return Mono.defer(() -> {
            EntityDefinition def = entities.find(id.type()).orElseThrow(() -> new ResourceNotFoundException(
                "Unregistered entity type: " + id.type() + " (resource " + id + ")"));
            DatasetDefinition dataset = datasets.findForEntity(def.name).orElseThrow(() -> new ResourceNotFoundException(
                "No dataset serves entity type: " + def.name + " (resource " + id + ")"));
            Object key = parseKey(def, id);

            return entityManager.findById(dataset, def, key)
                .map(instance -> (Resource) new GenericResource(id, instance.attributes()))
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Resource does not exist: " + id)));
        });
    }

    private static Object parseKey(EntityDefinition def, ResourceId id) {
        FieldDefinition primaryKeyField = def.field(def.primaryKey);
        try {
            return FieldValueCoercer.coerce(primaryKeyField, id.id(), false);
        } catch (IllegalArgumentException e) {
            throw new ResourceNotFoundException(
                "Identifier is not a valid key of entity " + def.name + ": " + id);
        }
    }
}
