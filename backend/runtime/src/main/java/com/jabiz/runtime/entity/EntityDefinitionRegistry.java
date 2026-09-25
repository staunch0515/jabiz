package com.jabiz.runtime.entity;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.ReferenceDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of all entity definitions in the application. It is populated once at startup
 * from every {@link EntityDefinition} bean and is immutable afterwards.
 */
@Component
public final class EntityDefinitionRegistry {

    /** A reference declared by {@code source}, seen from the entity it points to. */
    public record IncomingReference(EntityDefinition source, ReferenceDefinition reference) {}

    private final Map<String, EntityDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, List<IncomingReference>> incoming = new LinkedHashMap<>();

    public EntityDefinitionRegistry(ObjectProvider<EntityDefinition> beans) {
        beans.orderedStream().forEach(def -> {
            if (definitions.putIfAbsent(def.name, def) != null) {
                throw new IllegalStateException("Duplicate entity definition: " + def.name);
            }
        });
        for (EntityDefinition def : definitions.values()) {
            for (ReferenceDefinition reference : def.references) {
                incoming.computeIfAbsent(reference.targetEntity(), key -> new ArrayList<>())
                    .add(new IncomingReference(def, reference));
            }
        }
    }

    public Optional<EntityDefinition> find(String entityType) {
        return Optional.ofNullable(definitions.get(entityType));
    }

    public EntityDefinition getOrThrow(String entityType) {
        return find(entityType).orElseThrow(
            () -> new IllegalArgumentException("Unrecognized EntityDefinition: " + entityType));
    }

    /** References that point at the given entity type, declared by any registered entity. */
    public List<IncomingReference> referencesTo(String entityType) {
        return List.copyOf(incoming.getOrDefault(entityType, List.of()));
    }

    public boolean contains(String entityType) {
        return definitions.containsKey(entityType);
    }

    public Collection<EntityDefinition> all() {
        return java.util.Collections.unmodifiableCollection(definitions.values());
    }
}

