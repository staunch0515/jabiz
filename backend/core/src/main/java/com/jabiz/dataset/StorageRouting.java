package com.jabiz.dataset;

/**
 * Where and how a dataset is stored.
 *
 * @param driver               logical driver name (informational)
 * @param connectionPoolRef    key of the primary storage engine in the StorageAdapterRegistry
 * @param physicalTableOverride table that replaces the target entity's own table, or null
 * @param readReplicaRef       key of the engine used for reads, or null to read from the primary
 */
public record StorageRouting(
    String driver,
    String connectionPoolRef,
    String physicalTableOverride,
    String readReplicaRef
) {
    public StorageRouting {
        if (connectionPoolRef == null || connectionPoolRef.isBlank()) {
            throw new IllegalArgumentException("connectionPoolRef must not be blank");
        }
    }
}
