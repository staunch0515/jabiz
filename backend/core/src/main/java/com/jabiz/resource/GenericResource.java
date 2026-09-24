package com.jabiz.resource;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Default resource carrier without typed fields: data is held as logical field name -> value.
 *
 * Intended for cross-type scenarios such as audit display and dependency tracing. Business code
 * that knows the entity type should keep using typed access instead.
 */
public record GenericResource(ResourceId resourceId, Map<String, Object> data) implements Resource {
    public GenericResource {
        data = data == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }
}
