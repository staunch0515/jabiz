package com.jabiz.runtime.storage;

import java.util.Objects;

/**
 * Associates a storage engine with the connection pool reference used by datasets.
 * Declare one bean of this type per engine to make it known to the {@link StorageAdapterRegistry}.
 */
public record StorageEngineBinding(String connectionPoolRef, StorageEngine engine) {
    public StorageEngineBinding {
        Objects.requireNonNull(connectionPoolRef, "connectionPoolRef must not be null");
        Objects.requireNonNull(engine, "engine must not be null");
    }
}
