package com.jabiz.runtime.process;

import com.jabiz.process.ProcessDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Registry of all process definitions, populated once at startup from every
 * {@link ProcessDefinition} bean. Several versions of the same process can coexist; a
 * (name, version) pair must be unique.
 */
@Component
public final class ProcessRegistry {

    private final Map<String, TreeMap<Integer, ProcessDefinition<?, ?, ?>>> byName = new LinkedHashMap<>();

    public ProcessRegistry(ObjectProvider<ProcessDefinition<?, ?, ?>> beans) {
        beans.orderedStream().forEach(def -> {
            ProcessDefinition<?, ?, ?> previous = byName
                .computeIfAbsent(def.name(), n -> new TreeMap<>())
                .putIfAbsent(def.version(), def);
            if (previous != null) {
                throw new IllegalStateException(
                    "Duplicate process definition: " + def.name() + " version " + def.version());
            }
        });
    }

    public Optional<ProcessDefinition<?, ?, ?>> find(String name, int version) {
        TreeMap<Integer, ProcessDefinition<?, ?, ?>> versions = byName.get(name);
        return versions == null ? Optional.empty() : Optional.ofNullable(versions.get(version));
    }

    public Optional<ProcessDefinition<?, ?, ?>> findLatest(String name) {
        TreeMap<Integer, ProcessDefinition<?, ?, ?>> versions = byName.get(name);
        return versions == null || versions.isEmpty()
            ? Optional.empty()
            : Optional.of(versions.lastEntry().getValue());
    }

    public Collection<ProcessDefinition<?, ?, ?>> all() {
        List<ProcessDefinition<?, ?, ?>> all = new ArrayList<>();
        byName.values().forEach(versions -> all.addAll(versions.values()));
        return Collections.unmodifiableList(all);
    }
}
