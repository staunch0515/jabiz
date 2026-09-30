package com.jabiz.runtime.imports;

import com.jabiz.imports.ImportDefinition;
import com.jabiz.runtime.EntityNotFoundException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The imports the application declares ({@link ImportDefinition} beans), by id. */
@Component
public class ImportRegistry {

    private final Map<String, ImportDefinition<?>> byId = new LinkedHashMap<>();
    private final List<String> duplicates = new ArrayList<>();

    public ImportRegistry(ObjectProvider<ImportDefinition<?>> declared) {
        declared.orderedStream().forEach(definition -> {
            if (byId.putIfAbsent(definition.id(), definition) != null) {
                duplicates.add(definition.id());
            }
        });
    }

    public Optional<ImportDefinition<?>> find(String id) {
        return Optional.ofNullable(id == null ? null : byId.get(id));
    }

    /** The import; 404 when there is none. */
    @SuppressWarnings("unchecked")
    public <P> ImportDefinition<P> require(String id) {
        return (ImportDefinition<P>) find(id).orElseThrow(() -> new EntityNotFoundException("Unknown import: " + id));
    }

    public Collection<ImportDefinition<?>> all() {
        return byId.values();
    }

    /** Ids declared more than once (a startup error). */
    List<String> duplicates() {
        return List.copyOf(duplicates);
    }
}
