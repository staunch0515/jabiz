package com.jabiz.dataset;

import com.jabiz.entity.EntityDefinitionRegistry;
import com.jabiz.storage.StorageAdapterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of all dataset definitions, populated once at startup from every
 * {@link DatasetDefinition} bean.
 *
 * At most one dataset may target a given entity type: generic lookups by entity type (for
 * example URN resolution) must be unambiguous. Every dataset must target a registered entity
 * type and refer to a registered storage engine, so misconfiguration fails at startup.
 */
@Component
public final class DatasetRegistry {

    private final Map<String, DatasetDefinition> byResourceId = new LinkedHashMap<>();
    private final Map<String, DatasetDefinition> byEntityType = new LinkedHashMap<>();

    public DatasetRegistry(
        ObjectProvider<DatasetDefinition> beans,
        EntityDefinitionRegistry entities,
        StorageAdapterRegistry storage
    ) {
        beans.orderedStream().forEach(dataset -> {
            if (!entities.contains(dataset.targetEntityType())) {
                throw new IllegalStateException("Dataset " + dataset.resourceId()
                    + " targets unregistered entity type: " + dataset.targetEntityType());
            }
            requireEngine(storage, dataset, dataset.storage().connectionPoolRef());
            if (dataset.storage().readReplicaRef() != null && !dataset.storage().readReplicaRef().isBlank()) {
                requireEngine(storage, dataset, dataset.storage().readReplicaRef());
            }
            if (byResourceId.putIfAbsent(dataset.resourceId(), dataset) != null) {
                throw new IllegalStateException("Duplicate dataset resourceId: " + dataset.resourceId());
            }
            DatasetDefinition other = byEntityType.putIfAbsent(dataset.targetEntityType(), dataset);
            if (other != null) {
                throw new IllegalStateException("Entity type " + dataset.targetEntityType()
                    + " is targeted by more than one dataset: " + other.resourceId()
                    + " and " + dataset.resourceId());
            }
        });
    }

    public Optional<DatasetDefinition> findById(String resourceId) {
        return Optional.ofNullable(byResourceId.get(resourceId));
    }

    public Optional<DatasetDefinition> findForEntity(String entityType) {
        return Optional.ofNullable(byEntityType.get(entityType));
    }

    public Collection<DatasetDefinition> all() {
        return Collections.unmodifiableCollection(byResourceId.values());
    }

    private static void requireEngine(StorageAdapterRegistry storage, DatasetDefinition dataset, String poolRef) {
        if (!storage.hasEngine(poolRef)) {
            throw new IllegalStateException("Dataset " + dataset.resourceId()
                + " refers to unregistered storage engine: " + poolRef);
        }
    }
}
