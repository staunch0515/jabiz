package com.jabiz.process.entity;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.runtime.EntityInstance;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Context shared by the add, update and delete processes. The request is fixed at creation
 * (the attributes can only be extended, for example by a generated identity); the steps hand
 * over the resolved definition, the dataset and the resulting instance through typed accessors.
 */
public class EntityChangeContext extends ProcessContext {

    public static final String KEY_DEFINITION = "entity_definition";
    public static final String KEY_DATASET = "dataset_definition";
    public static final String KEY_RESULT = "result_instance";

    private final String entityType;
    private final Object id;
    private final long version;
    private volatile Map<String, Object> attributes;

    public EntityChangeContext(
        long processSeqId, String entityType, Object id, long version, Map<String, Object> attributes
    ) {
        super(processSeqId);
        this.entityType = Objects.requireNonNull(entityType, "entityType must not be null");
        this.id = id;
        this.version = version;
        this.attributes = immutableCopy(attributes);
    }

    public String entityType() {
        return entityType;
    }

    /** Primary key of the addressed instance; null for an insert. */
    public Object id() {
        return id;
    }

    public long version() {
        return version;
    }

    public Map<String, Object> attributes() {
        return attributes;
    }

    public void putAttribute(String name, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(attributes);
        copy.put(name, value);
        attributes = Collections.unmodifiableMap(copy);
    }

    public void setDefinition(EntityDefinition definition) {
        put(KEY_DEFINITION, definition);
    }

    public EntityDefinition definition() {
        return required(KEY_DEFINITION, EntityDefinition.class, "entity resolution");
    }

    public void setDataset(DatasetDefinition dataset) {
        put(KEY_DATASET, dataset);
    }

    public DatasetDefinition dataset() {
        return required(KEY_DATASET, DatasetDefinition.class, "entity resolution");
    }

    public void setResult(EntityInstance result) {
        put(KEY_RESULT, result);
    }

    public EntityInstance result() {
        return required(KEY_RESULT, EntityInstance.class, "commit");
    }

    private <T> T required(String key, Class<T> type, String producer) {
        T value = get(key, type);
        if (value == null) {
            throw new IllegalStateException("The " + producer + " step has not run: no " + key + " in context");
        }
        return value;
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> source) {
        return source == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
