package com.jabiz.process;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * State container passed between the steps of one process execution. Concrete processes extend
 * it with typed accessors while the underlying map remains available for step-to-step values
 * addressed by key. It is thread-safe because reactive steps may complete on different threads.
 */
public class ProcessContext {

    private final long processSeqId;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    public ProcessContext(long processSeqId) {
        this.processSeqId = processSeqId;
    }

    /** Identifier of this execution, used to trace every change made by the process. */
    public long processSeqId() {
        return processSeqId;
    }

    /** Stores a value; a null value removes the key. */
    public void put(String key, Object value) {
        Objects.requireNonNull(key, "key must not be null");
        if (value == null) {
            attributes.remove(key);
        } else {
            attributes.put(key, value);
        }
    }

    public Object get(String key) {
        return attributes.get(key);
    }

    public <T> T get(String key, Class<T> type) {
        Object value = attributes.get(key);
        if (value == null) {
            return null;
        }
        if (!type.isInstance(value)) {
            throw new IllegalStateException("Context key '" + key + "' holds " + value.getClass().getName()
                + " but " + type.getName() + " was expected");
        }
        return type.cast(value);
    }

    public boolean contains(String key) {
        return attributes.containsKey(key);
    }
}
