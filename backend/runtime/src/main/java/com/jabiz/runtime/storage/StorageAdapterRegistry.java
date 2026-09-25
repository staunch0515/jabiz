package com.jabiz.runtime.storage;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of storage engines, keyed by connection pool reference.
 * Engines declared as {@link StorageEngineBinding} beans are registered at startup.
 */
@Component
public final class StorageAdapterRegistry {

    private final Map<String, StorageEngine> engineRegistry = new ConcurrentHashMap<>();

    public StorageAdapterRegistry(ObjectProvider<StorageEngineBinding> bindings) {
        bindings.orderedStream().forEach(b -> registerEngine(b.connectionPoolRef(), b.engine()));
    }

    /**
     * Registers a storage engine.
     *
     * @param connectionPoolRef pool identifier, for example "pool:logistics:tokyo_dc"
     * @param engine            the engine instance
     */
    public void registerEngine(String connectionPoolRef, StorageEngine engine) {
        if (connectionPoolRef == null || engine == null) {
            throw new IllegalArgumentException("Connection reference and StorageEngine cannot be null");
        }
        engineRegistry.put(connectionPoolRef, engine);
    }

    public StorageEngine getEngine(String connectionPoolRef) {
        StorageEngine engine = engineRegistry.get(connectionPoolRef);
        if (engine == null) {
            throw new IllegalStateException("No StorageEngine registered for reference: " + connectionPoolRef);
        }
        return engine;
    }

    /** Removes an engine (for hot reload or pool decommissioning). */
    public StorageEngine unregisterEngine(String connectionPoolRef) {
        return engineRegistry.remove(connectionPoolRef);
    }

    public boolean hasEngine(String connectionPoolRef) {
        return engineRegistry.containsKey(connectionPoolRef);
    }
}
