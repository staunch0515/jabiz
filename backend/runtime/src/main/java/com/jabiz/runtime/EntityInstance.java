package com.jabiz.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generic carrier of entity instances throughout the system.
 *
 * @param id         primary key value
 * @param entityType name of the EntityDefinition
 * @param version    optimistic-lock version (0 for entities without a version field)
 * @param state      current lifecycle state, if the entity has one
 * @param attributes values by logical field name
 */
public record EntityInstance(
    Object id,
    String entityType,
    long version,
    String state,
    Map<String, Object> attributes
) {
    public EntityInstance {
        attributes = attributes == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /** Identifies the instance without its values, which may be personal or secret: safe in logs. */
    @Override
    public String toString() {
        return "EntityInstance[entityType=" + entityType + ", id=" + id + ", version=" + version + ", state=" + state
            + ", fields=" + attributes.keySet() + "]";
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String logicalFieldName) {
        return (T) attributes.get(logicalFieldName);
    }

    @SuppressWarnings("unchecked")
    public <T> T getOrDefault(String logicalFieldName, T defaultValue) {
        Object val = attributes.get(logicalFieldName);
        return val != null ? (T) val : defaultValue;
    }
}
